package com.example.offlinelink.transport

import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.transport.PriorityPayloadSender.EnqueueResult
import com.example.offlinelink.transport.PriorityPayloadSender.PayloadPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityPayloadSenderTest {
  @Test
  fun sendsPendingPayloadsByPriorityThenFifoWithinPriority() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport)

    sender.enqueue(ENDPOINT, bytes("in-flight"), PayloadPriority.Text)
    sender.enqueue(ENDPOINT, bytes("image"), PayloadPriority.Image)
    sender.enqueue(ENDPOINT, bytes("text-1"), PayloadPriority.Text)
    sender.enqueue(ENDPOINT, bytes("call-signal"), PayloadPriority.CallSignal)
    sender.enqueue(ENDPOINT, bytes("call-audio"), PayloadPriority.CallAudio)
    sender.enqueue(ENDPOINT, bytes("text-2"), PayloadPriority.Location)

    assertEquals(listOf("in-flight"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-signal"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-signal", "call-audio"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-signal", "call-audio", "text-1"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-signal", "call-audio", "text-1", "text-2"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-signal", "call-audio", "text-1", "text-2", "image"), transport.sentPayloadLabels())
  }

  @Test
  fun callSignalIsNotStarvedByQueuedCallAudioFrames() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport)

    sender.enqueue(ENDPOINT, bytes("in-flight-image"), PayloadPriority.Image)
    sender.enqueue(ENDPOINT, bytes("call-accept"), PayloadPriority.CallSignal)
    repeat(5) { index ->
      sender.enqueue(ENDPOINT, bytes("audio-$index"), PayloadPriority.CallAudio)
    }

    transport.completeNextSuccess()

    assertEquals(listOf("in-flight-image", "call-accept"), transport.sentPayloadLabels())
  }

  @Test
  fun serializesSendsForSameEndpointAndPrioritizesPendingPayloadsAfterCallbackCompletes() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport)

    sender.enqueue(ENDPOINT, bytes("first"), PayloadPriority.Text)
    sender.enqueue(ENDPOINT, bytes("second"), PayloadPriority.CallAudio)
    sender.enqueue(ENDPOINT, bytes("third"), PayloadPriority.Control)

    assertEquals(listOf("first"), transport.sentPayloadLabels())
    assertTrue(sender.isSending(ENDPOINT))
    assertEquals(2, sender.pendingCount(ENDPOINT))

    transport.completeNextSuccess()
    assertEquals(listOf("first", "third"), transport.sentPayloadLabels())
    assertEquals(1, sender.pendingCount(ENDPOINT))

    transport.completeNextSuccess()
    assertEquals(listOf("first", "third", "second"), transport.sentPayloadLabels())
    assertEquals(0, sender.pendingCount(ENDPOINT))

    transport.completeNextSuccess()
    assertFalse(sender.isSending(ENDPOINT))
  }

  @Test
  fun allowsDifferentEndpointsToSendConcurrently() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport)

    sender.enqueue("endpoint-a", bytes("a1"), PayloadPriority.Image)
    sender.enqueue("endpoint-b", bytes("b1"), PayloadPriority.Image)

    assertEquals(listOf("a1", "b1"), transport.sentPayloadLabels())
    assertTrue(sender.isSending("endpoint-a"))
    assertTrue(sender.isSending("endpoint-b"))
  }

  @Test
  fun rejectsLowPriorityPayloadWhenPendingQueueIsFull() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport, maxPendingPerEndpoint = 2)
    var rejectedResult: Result<Unit>? = null

    sender.enqueue(ENDPOINT, bytes("in-flight"), PayloadPriority.CallAudio)
    sender.enqueue(ENDPOINT, bytes("text"), PayloadPriority.Text)
    sender.enqueue(ENDPOINT, bytes("voice"), PayloadPriority.Voice)

    val result =
      sender.enqueue(ENDPOINT, bytes("image"), PayloadPriority.Image) {
        rejectedResult = it
      }

    assertEquals(EnqueueResult.RejectedQueueFull, result)
    assertTrue(rejectedResult?.isFailure == true)
    assertEquals(2, sender.pendingCount(ENDPOINT))

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "text"), transport.sentPayloadLabels())
  }

  @Test
  fun dropsQueuedLowerPriorityPayloadWhenHigherPriorityPayloadArrivesAtCapacity() {
    val transport = RecordingChatTransport()
    val sender = PriorityPayloadSender(transport, maxPendingPerEndpoint = 2)
    var droppedResult: Result<Unit>? = null

    sender.enqueue(ENDPOINT, bytes("in-flight"), PayloadPriority.Text)
    sender.enqueue(ENDPOINT, bytes("image"), PayloadPriority.Image) {
      droppedResult = it
    }
    sender.enqueue(ENDPOINT, bytes("voice"), PayloadPriority.Voice)

    val result = sender.enqueue(ENDPOINT, bytes("call-audio"), PayloadPriority.CallAudio)

    assertEquals(EnqueueResult.EnqueuedAfterDroppingLowerPriority, result)
    assertTrue(droppedResult?.isFailure == true)
    assertEquals(2, sender.pendingCount(ENDPOINT))

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-audio"), transport.sentPayloadLabels())

    transport.completeNextSuccess()
    assertEquals(listOf("in-flight", "call-audio", "voice"), transport.sentPayloadLabels())
  }

  private class RecordingChatTransport : ChatTransport {
    data class SendCall(
      val endpointId: String,
      val bytes: ByteArray,
      val onResult: (Result<Unit>) -> Unit,
    )

    private val pendingResults = ArrayDeque<(Result<Unit>) -> Unit>()
    val sendCalls = mutableListOf<SendCall>()

    override val events: Flow<TransportEvent> = emptyFlow()

    override fun startAdvertising(displayName: String) = Unit

    override fun startDiscovery() = Unit

    override fun requestConnection(endpoint: NearbyEndpoint, displayName: String) = Unit

    override fun acceptConnection(endpointId: String) = Unit

    override fun rejectConnection(endpointId: String) = Unit

    override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
      sendCalls.add(SendCall(endpointId, bytes, onResult))
      pendingResults.addLast(onResult)
    }

    override fun stopAll() = Unit

    fun completeNextSuccess() {
      pendingResults.removeFirst().invoke(Result.success(Unit))
    }

    fun sentPayloadLabels(): List<String> = sendCalls.map { String(it.bytes) }
  }

  private companion object {
    const val ENDPOINT = "endpoint-1"

    fun bytes(value: String): ByteArray = value.toByteArray()
  }
}
