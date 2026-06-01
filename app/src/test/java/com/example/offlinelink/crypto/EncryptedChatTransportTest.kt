package com.example.offlinelink.crypto

import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
