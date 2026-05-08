package com.example.offlinelink.transport

import com.example.offlinelink.model.NearbyEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestPayloadSenderTest {

  @Test
  fun sendsFirstPayloadImmediatelyAndKeepsOnlyLatestPendingPayload() {
    val transport = RecordingChatTransport()
    val sender = LatestPayloadSender(transport)
    val results = mutableListOf<Pair<String, Throwable?>>()

    sender.send(endpointId = ENDPOINT_A, bytes = bytes("one")) { results += "one" to it.exceptionOrNull() }
    sender.send(endpointId = ENDPOINT_A, bytes = bytes("two")) { results += "two" to it.exceptionOrNull() }
    sender.send(endpointId = ENDPOINT_A, bytes = bytes("three")) { results += "three" to it.exceptionOrNull() }

    assertEquals(listOf("one"), transport.sentPayloadLabels())
    assertEquals(listOf("two"), results.map { it.first })
    assertTrue(results.single().second is LatestPayloadSender.StalePayloadDroppedException)

    transport.completeNextSuccess()

    assertEquals(listOf("one", "three"), transport.sentPayloadLabels())

    transport.completeNextSuccess()

    assertEquals(listOf("two", "one", "three"), results.map { it.first })
    assertTrue(results.first { it.first == "one" }.second == null)
    assertTrue(results.first { it.first == "three" }.second == null)
  }

  @Test
  fun sendsDifferentEndpointsConcurrently() {
    val transport = RecordingChatTransport()
    val sender = LatestPayloadSender(transport)

    sender.send(endpointId = ENDPOINT_A, bytes = bytes("a-one"))
    sender.send(endpointId = ENDPOINT_B, bytes = bytes("b-one"))

    assertEquals(listOf(ENDPOINT_A, ENDPOINT_B), transport.sentEndpointIds())
    assertEquals(listOf("a-one", "b-one"), transport.sentPayloadLabels())
  }

  @Test
  fun clearEndpointDropsPendingPayloadAndStopsFollowUpSend() {
    val transport = RecordingChatTransport()
    val sender = LatestPayloadSender(transport)
    val results = mutableListOf<Pair<String, Throwable?>>()

    sender.send(endpointId = ENDPOINT_A, bytes = bytes("one")) { results += "one" to it.exceptionOrNull() }
    sender.send(endpointId = ENDPOINT_A, bytes = bytes("two")) { results += "two" to it.exceptionOrNull() }

    sender.clearEndpoint(ENDPOINT_A)

    assertEquals(listOf("two"), results.map { it.first })
    assertTrue(results.single().second is LatestPayloadSender.EndpointClearedException)

    transport.completeNextSuccess()

    assertEquals(listOf("one"), transport.sentPayloadLabels())
    assertEquals(listOf("two", "one"), results.map { it.first })
  }

  private class RecordingChatTransport : ChatTransport {
    data class SentPayload(
      val endpointId: String,
      val bytes: ByteArray,
      val onResult: (Result<Unit>) -> Unit,
    )

    val sentPayloads = mutableListOf<SentPayload>()
    private var nextCallbackIndex = 0

    override val events: Flow<TransportEvent> = emptyFlow()

    override fun startAdvertising(displayName: String, deviceId: String) = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun requestConnection(endpoint: NearbyEndpoint, displayName: String) = Unit
    override fun acceptConnection(endpointId: String) = Unit
    override fun rejectConnection(endpointId: String) = Unit

    override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
      sentPayloads += SentPayload(endpointId, bytes, onResult)
    }

    override fun stopAll() = Unit

    fun completeNextSuccess() {
      sentPayloads[nextCallbackIndex++].onResult(Result.success(Unit))
    }
  }

  private fun RecordingChatTransport.sentPayloadLabels(): List<String> =
    sentPayloads.map { String(it.bytes) }

  private fun RecordingChatTransport.sentEndpointIds(): List<String> =
    sentPayloads.map { it.endpointId }

  private fun bytes(value: String): ByteArray = value.toByteArray()

  private companion object {
    const val ENDPOINT_A = "endpoint-a"
    const val ENDPOINT_B = "endpoint-b"
  }
}
