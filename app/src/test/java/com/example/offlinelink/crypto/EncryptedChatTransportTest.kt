package com.example.offlinelink.crypto

import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedChatTransportTest {
  @Test
  fun keyExchangeCompletesBeforeConnectedAndPayloadsAreEncryptedOnWire() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      val connectedA = backgroundScope.launch { secureA.events.first { it is TransportEvent.Connected } }
      val connectedB = backgroundScope.launch { secureB.events.first { it is TransportEvent.Connected } }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()

      connectedA.join()
      connectedB.join()

      val plaintext = "plain chat message".encodeToByteArray()
      secureA.send("b", plaintext) { it.getOrThrow() }
      val encryptedBytes = rawA.sentPayloads.single().bytes

      assertFalse(encryptedBytes.decodeToString().contains("plain chat message"))

      val receivedPayload = backgroundScope.async {
        secureB.events.first { it is TransportEvent.BytesReceived } as TransportEvent.BytesReceived
      }
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      runCurrent()
      val received = receivedPayload.await()
      assertArrayEquals(plaintext, received.bytes)
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  @Test
  fun stopAllResetsSessionsWithoutMakingTransportDead() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      secureA.stopAll()
      secureB.stopAll()

      val connectedA = backgroundScope.async {
        withTimeout(1_000) { secureA.events.first { it is TransportEvent.Connected } }
      }
      val connectedB = backgroundScope.async {
        withTimeout(1_000) { secureB.events.first { it is TransportEvent.Connected } }
      }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()

      connectedA.await()
      connectedB.await()
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  @Test
  fun compactAckEncryptedPayloadFitsSingleLongRangeGattValue() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      val connectedA = backgroundScope.async {
        withTimeout(1_000) { secureA.events.first { it is TransportEvent.Connected } }
      }
      val connectedB = backgroundScope.async {
        withTimeout(1_000) { secureB.events.first { it is TransportEvent.Connected } }
      }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()
      connectedA.await()
      connectedB.await()

      val ack = ChatProtocol.encodeAck("44444444-4444-4444-4444-444444444444", sentAt = 8765L)
      secureA.send("b", ack) { it.getOrThrow() }

      assertTrue(rawA.sentPayloads.single().bytes.size <= 45)
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  @Test
  fun compactCallAudioUsesShortSecureWireFrameAndRoundTrips() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      val connectedA = backgroundScope.async {
        withTimeout(1_000) { secureA.events.first { it is TransportEvent.Connected } }
      }
      val connectedB = backgroundScope.async {
        withTimeout(1_000) { secureB.events.first { it is TransportEvent.Connected } }
      }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()
      connectedA.await()
      connectedB.await()

      val audioBytes = ByteArray(16) { it.toByte() }
      val callAudio =
        ChatProtocol.encodeCallAudioFrame(
          callId = "call-1",
          frameId = "frame-1",
          senderId = "device-a",
          targetId = "device-b",
          audioBytes = audioBytes,
          durationMs = 20L,
          mimeType = "audio/opus;rate=16000",
          sequenceNumber = 7,
          createdAt = 1234L,
          sentAt = 5678L,
          compact = true,
        )
      val receivedPayload = backgroundScope.async {
        secureB.events.first { it is TransportEvent.BytesReceived } as TransportEvent.BytesReceived
      }

      secureA.send("b", callAudio) { it.getOrThrow() }
      val wireBytes = rawA.sentPayloads.single().bytes

      assertEquals(26, wireBytes.size)

      rawA.drainSentTo(rawB, "a")
      runCurrent()
      val decoded = ChatProtocol.decode(receivedPayload.await().bytes)

      assertTrue(decoded is com.example.offlinelink.protocol.DecodedWireMessage.CallAudioFrame)
      decoded as com.example.offlinelink.protocol.DecodedWireMessage.CallAudioFrame
      assertEquals("audio/opus;rate=16000", decoded.mimeType)
      assertEquals(20L, decoded.durationMs)
      assertEquals(7, decoded.sequenceNumber)
      assertEquals(audioBytes.toList(), decoded.audioBytes.toList())
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  @Test
  fun compactShortTextUsesRawSecureWireFrameAndRoundTrips() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      val connectedA = backgroundScope.async {
        withTimeout(1_000) { secureA.events.first { it is TransportEvent.Connected } }
      }
      val connectedB = backgroundScope.async {
        withTimeout(1_000) { secureB.events.first { it is TransportEvent.Connected } }
      }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()
      connectedA.await()
      connectedB.await()

      val messageId = "44444444-4444-4444-4444-444444444444"
      val conversationId = "55555555-5555-5555-5555-555555555555"
      val senderId = "11111111-1111-1111-1111-111111111111"
      val message =
        ChatProtocol.encodeMessage(
          messageId = messageId,
          conversationId = conversationId,
          senderId = senderId,
          text = "hello nearby",
          createdAt = 1234L,
          sentAt = 5678L,
        )
      val receivedPayload = backgroundScope.async {
        secureB.events.first { it is TransportEvent.BytesReceived } as TransportEvent.BytesReceived
      }

      secureA.send("b", message) { it.getOrThrow() }
      val wireBytes = rawA.sentPayloads.single().bytes

      assertEquals("OLS1", wireBytes.copyOfRange(0, 4).decodeToString())
      assertEquals(5, wireBytes[4].toInt() and 0xff)
      assertTrue(wireBytes.size <= 45)

      rawA.drainSentTo(rawB, "a")
      runCurrent()
      val decoded = ChatProtocol.decode(receivedPayload.await().bytes)

      assertTrue(decoded is DecodedWireMessage.Message)
      decoded as DecodedWireMessage.Message
      assertEquals(ChatProtocol.compactWireId(messageId), decoded.messageId)
      assertEquals(ChatProtocol.compactWireId(conversationId), decoded.conversationId)
      assertEquals(ChatProtocol.compactWireId(senderId), decoded.senderId)
      assertEquals("hello nearby", decoded.text)
      assertEquals(1234L, decoded.createdAt)
      assertEquals(5678L, decoded.sentAt)
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  @Test
  fun compactThirtyByteTextUsesRawButThirtyOneByteTextStaysEncrypted() = runTest {
    val rawA = RecordingTransport()
    val rawB = RecordingTransport()
    val secureA = EncryptedChatTransport(rawA, backgroundScope)
    val secureB = EncryptedChatTransport(rawB, backgroundScope)
    try {
      val connectedA = backgroundScope.async {
        withTimeout(1_000) { secureA.events.first { it is TransportEvent.Connected } }
      }
      val connectedB = backgroundScope.async {
        withTimeout(1_000) { secureB.events.first { it is TransportEvent.Connected } }
      }
      runCurrent()

      rawA.emit(TransportEvent.Connected(NearbyEndpoint("b", "Phone B")))
      rawB.emit(TransportEvent.Connected(NearbyEndpoint("a", "Phone A")))
      runCurrent()
      rawA.drainSentTo(rawB, "a")
      rawB.drainSentTo(rawA, "b")
      runCurrent()
      connectedA.await()
      connectedB.await()

      val thirtyByteText = "123456789012345678901234567890"
      val thirtyOneByteText = "$thirtyByteText!"
      val compactThirtyByteMessage =
        ChatProtocol.encodeMessage(
          messageId = "44444444-4444-4444-4444-444444444444",
          conversationId = "55555555-5555-5555-5555-555555555555",
          senderId = "11111111-1111-1111-1111-111111111111",
          text = thirtyByteText,
          createdAt = 1234L,
          sentAt = 5678L,
        )
      val compactThirtyOneByteMessage =
        ChatProtocol.encodeMessage(
          messageId = "44444444-4444-4444-4444-444444444445",
          conversationId = "55555555-5555-5555-5555-555555555555",
          senderId = "11111111-1111-1111-1111-111111111111",
          text = thirtyOneByteText,
          createdAt = 1234L,
          sentAt = 5678L,
        )

      secureA.send("b", compactThirtyByteMessage) { it.getOrThrow() }
      secureA.send("b", compactThirtyOneByteMessage) { it.getOrThrow() }

      assertEquals(5, rawA.sentPayloads[0].bytes[4].toInt() and 0xff)
      assertEquals(2, rawA.sentPayloads[1].bytes[4].toInt() and 0xff)
    } finally {
      secureA.stopAll()
      secureB.stopAll()
    }
  }

  private data class SentPayload(
    val endpointId: String,
    val bytes: ByteArray,
  )

  private class RecordingTransport : ChatTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    val sentPayloads = mutableListOf<SentPayload>()

    override val events = mutableEvents

    override fun startAdvertising(displayName: String, deviceId: String) = Unit

    override fun startDiscovery() = Unit

    override fun stopAdvertising() = Unit

    override fun stopDiscovery() = Unit

    override fun requestConnection(endpoint: NearbyEndpoint, displayName: String, deviceId: String) = Unit

    override fun acceptConnection(endpointId: String) = Unit

    override fun rejectConnection(endpointId: String) = Unit

    override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
      sentPayloads += SentPayload(endpointId, bytes)
      onResult(Result.success(Unit))
    }

    override fun disconnectEndpoint(endpointId: String) = Unit

    override fun stopAll() = Unit

    fun emit(event: TransportEvent) {
      assertTrue(mutableEvents.tryEmit(event))
    }

    fun drainSentTo(peer: RecordingTransport, fromEndpointId: String) {
      val payloads = sentPayloads.toList()
      sentPayloads.clear()
      payloads.forEach { payload ->
        peer.emit(TransportEvent.BytesReceived(fromEndpointId, payload.bytes))
      }
    }
  }
}
