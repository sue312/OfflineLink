package com.example.offlinelink.transport

class LatestPayloadSender(private val transport: ChatTransport) {
  private val lock = Any()
  private val endpoints = mutableMapOf<String, EndpointState>()

  fun send(
    endpointId: String,
    bytes: ByteArray,
    onResult: (Result<Unit>) -> Unit = {},
  ) {
    val item = PendingPayload(endpointId = endpointId, bytes = bytes, onResult = onResult)
    var dropped: PendingPayload? = null
    var dispatchState: EndpointState? = null

    synchronized(lock) {
      val state = endpoints.getOrPut(endpointId) { EndpointState() }
      if (state.inFlight) {
        dropped = state.latest
        state.latest = item
      } else {
        state.inFlight = true
        dispatchState = state
      }
    }

    dropped?.onResult?.invoke(Result.failure(StalePayloadDroppedException("Dropped stale live payload for $endpointId")))
    dispatchState?.let { state -> dispatch(item, state) }
  }

  fun clearEndpoint(endpointId: String) {
    val dropped =
      synchronized(lock) {
        endpoints.remove(endpointId)?.latest
      }
    dropped?.onResult?.invoke(Result.failure(EndpointClearedException("Live payload queue cleared for $endpointId")))
  }

  private fun dispatch(item: PendingPayload, state: EndpointState) {
    transport.send(item.endpointId, item.bytes) { result ->
      item.onResult(result)

      val next =
        synchronized(lock) {
          if (endpoints[item.endpointId] !== state) {
            null
          } else {
            state.latest.also { pending ->
              state.latest = null
              if (pending == null) {
                state.inFlight = false
                endpoints.remove(item.endpointId)
              }
            }
          }
        }

      if (next != null) {
        dispatch(next, state)
      }
    }
  }

  private class EndpointState(
    var inFlight: Boolean = false,
    var latest: PendingPayload? = null,
  )

  private data class PendingPayload(
    val endpointId: String,
    val bytes: ByteArray,
    val onResult: (Result<Unit>) -> Unit,
  )

  class StalePayloadDroppedException(message: String) : IllegalStateException(message)

  class EndpointClearedException(message: String) : IllegalStateException(message)
}
