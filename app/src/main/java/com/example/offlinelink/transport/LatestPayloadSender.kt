package com.example.offlinelink.transport

class LatestPayloadSender(
  private val transport: ChatTransport,
  private val maxPendingPerEndpoint: Int = DEFAULT_MAX_PENDING_PER_ENDPOINT,
) {
  private val lock = Any()
  private val endpoints = mutableMapOf<String, EndpointState>()
  private val endpointMetrics = mutableMapOf<String, EndpointMetrics>()

  init {
    require(maxPendingPerEndpoint > 0) { "maxPendingPerEndpoint must be greater than 0" }
  }

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
      val metrics = endpointMetrics.getOrPut(endpointId) { EndpointMetrics() }
      if (state.inFlight) {
        if (state.pending.size >= maxPendingPerEndpoint) {
          dropped = state.pending.removeFirst()
          metrics.droppedStalePayloads++
        }
        state.pending.addLast(item)
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
        endpointMetrics.remove(endpointId)
        endpoints.remove(endpointId)?.pending?.toList().orEmpty()
      }
    dropped.forEach {
      it.onResult(Result.failure(EndpointClearedException("Live payload queue cleared for $endpointId")))
    }
  }

  fun stats(endpointId: String): EndpointStats =
    synchronized(lock) {
      val state = endpoints[endpointId]
      val metrics = endpointMetrics[endpointId]
      EndpointStats(
        pendingCount = state?.pending?.size ?: 0,
        inFlight = state?.inFlight == true,
        droppedStalePayloads = metrics?.droppedStalePayloads ?: 0L,
      )
    }

  private fun dispatch(item: PendingPayload, state: EndpointState) {
    transport.send(item.endpointId, item.bytes) { result ->
      item.onResult(result)

      val next =
        synchronized(lock) {
          if (endpoints[item.endpointId] !== state) {
            null
          } else {
            if (state.pending.isNotEmpty()) {
              state.pending.removeFirst()
            } else {
              state.inFlight = false
              endpoints.remove(item.endpointId)
              null
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
    val pending: ArrayDeque<PendingPayload> = ArrayDeque(),
  )

  private data class PendingPayload(
    val endpointId: String,
    val bytes: ByteArray,
    val onResult: (Result<Unit>) -> Unit,
  )

  class StalePayloadDroppedException(message: String) : IllegalStateException(message)

  class EndpointClearedException(message: String) : IllegalStateException(message)

  data class EndpointStats(
    val pendingCount: Int = 0,
    val inFlight: Boolean = false,
    val droppedStalePayloads: Long = 0,
  )

  private class EndpointMetrics(
    var droppedStalePayloads: Long = 0,
  )

  private companion object {
    const val DEFAULT_MAX_PENDING_PER_ENDPOINT = 12
  }
}
