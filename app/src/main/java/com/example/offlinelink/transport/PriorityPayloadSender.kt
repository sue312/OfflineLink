package com.example.offlinelink.transport

import java.util.PriorityQueue

class PriorityPayloadSender(
  private val transport: ChatTransport,
  private val maxPendingPerEndpoint: Int = DEFAULT_MAX_PENDING_PER_ENDPOINT,
) {
  private val lock = Any()
  private val endpoints = mutableMapOf<String, EndpointQueue>()
  private var nextSequence = 0L

  init {
    require(maxPendingPerEndpoint > 0) { "maxPendingPerEndpoint must be greater than 0" }
  }

  fun enqueue(
    endpointId: String,
    bytes: ByteArray,
    priority: PayloadPriority,
    onResult: (Result<Unit>) -> Unit = {},
  ): EnqueueResult {
    val item =
      QueuedPayload(
        endpointId = endpointId,
        bytes = bytes,
        priority = priority,
        sequence = nextSequence(),
        onResult = onResult,
      )

    var dropped: QueuedPayload? = null
    val shouldStart: Boolean
    val result: EnqueueResult
    synchronized(lock) {
      val endpointQueue = endpoints.getOrPut(endpointId) { EndpointQueue() }
      if (endpointQueue.pending.size < maxPendingPerEndpoint) {
        endpointQueue.pending.add(item)
        shouldStart = !endpointQueue.isSending
        result = EnqueueResult.Enqueued
      } else {
        val lowestPriorityItem = endpointQueue.pending.maxWithOrNull(QUEUE_ORDER)
        if (lowestPriorityItem != null && item.hasHigherPriorityThan(lowestPriorityItem)) {
          endpointQueue.pending.remove(lowestPriorityItem)
          endpointQueue.pending.add(item)
          dropped = lowestPriorityItem
          shouldStart = !endpointQueue.isSending
          result = EnqueueResult.EnqueuedAfterDroppingLowerPriority
        } else {
          shouldStart = false
          result = EnqueueResult.RejectedQueueFull
        }
      }
    }

    dropped?.onResult?.invoke(Result.failure(QueueFullException("Dropped lower priority payload for $endpointId")))
    if (result == EnqueueResult.RejectedQueueFull) {
      onResult(Result.failure(QueueFullException("Queue is full for $endpointId")))
      return result
    }
    if (shouldStart) {
      drain(endpointId)
    }
    return result
  }

  fun pendingCount(endpointId: String): Int =
    synchronized(lock) {
      endpoints[endpointId]?.pending?.size ?: 0
    }

  fun isSending(endpointId: String): Boolean =
    synchronized(lock) {
      endpoints[endpointId]?.isSending == true
    }

  fun clearEndpoint(endpointId: String) {
    val dropped =
      synchronized(lock) {
        val endpointQueue = endpoints.remove(endpointId) ?: return
        endpointQueue.pending.toList()
      }
    dropped.forEach { it.onResult(Result.failure(QueueClearedException("Queue cleared for $endpointId"))) }
  }

  private fun drain(endpointId: String) {
    val next =
      synchronized(lock) {
        val endpointQueue = endpoints[endpointId] ?: return
        if (endpointQueue.isSending) return
        val item = endpointQueue.pending.poll() ?: return
        endpointQueue.isSending = true
        item
      }

    transport.send(next.endpointId, next.bytes) { result ->
      next.onResult(result)
      synchronized(lock) {
        val endpointQueue = endpoints[next.endpointId]
        if (endpointQueue != null) {
          endpointQueue.isSending = false
          if (endpointQueue.pending.isEmpty()) {
            endpoints.remove(next.endpointId)
          }
        }
      }
      drain(next.endpointId)
    }
  }

  private fun nextSequence(): Long =
    synchronized(lock) {
      nextSequence++
    }

  private data class EndpointQueue(
    val pending: PriorityQueue<QueuedPayload> = PriorityQueue(QUEUE_ORDER),
    var isSending: Boolean = false,
  )

  private data class QueuedPayload(
    val endpointId: String,
    val bytes: ByteArray,
    val priority: PayloadPriority,
    val sequence: Long,
    val onResult: (Result<Unit>) -> Unit,
  ) {
    fun hasHigherPriorityThan(other: QueuedPayload): Boolean = priority.rank < other.priority.rank
  }

  enum class PayloadPriority(val rank: Int) {
    Control(0),
    CallSignal(0),
    CallAudio(1),
    Text(2),
    Location(2),
    Voice(2),
    Image(3),
  }

  enum class EnqueueResult {
    Enqueued,
    EnqueuedAfterDroppingLowerPriority,
    RejectedQueueFull,
  }

  class QueueFullException(message: String) : IllegalStateException(message)

  class QueueClearedException(message: String) : IllegalStateException(message)

  private companion object {
    const val DEFAULT_MAX_PENDING_PER_ENDPOINT = 64

    val QUEUE_ORDER: Comparator<QueuedPayload> =
      compareBy<QueuedPayload> { it.priority.rank }.thenBy { it.sequence }
  }
}
