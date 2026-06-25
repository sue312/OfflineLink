package com.example.offlinelink.transport

class BluetoothLinkMetrics(
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  private var windowStartedAtMs = clockMs()
  private var windowBytes = 0L
  private var windowWriteBlockedMs = 0L
  private var windowWriteCount = 0L
  private var windowMaxWriteBlockedMs = 0L
  private var writeQueueLength = 0
  private var maxWriteQueueLength = 0
  private var lastReport = TransportLinkStats()

  @Synchronized
  fun recordEnqueued(queueLength: Int): TransportLinkStats? {
    recordQueueLengthLocked(queueLength)
    return rollWindowIfDueLocked()
  }

  @Synchronized
  fun recordQueueLength(queueLength: Int): TransportLinkStats? {
    recordQueueLengthLocked(queueLength)
    return rollWindowIfDueLocked()
  }

  @Synchronized
  fun recordWrite(
    payloadBytes: Int,
    writeBlockedMs: Long,
    queueLength: Int,
  ): TransportLinkStats? {
    recordQueueLengthLocked(queueLength)
    windowBytes += payloadBytes.toLong().coerceAtLeast(0L)
    val blockedMs = writeBlockedMs.coerceAtLeast(0L)
    windowWriteBlockedMs += blockedMs
    windowWriteCount++
    windowMaxWriteBlockedMs = maxOf(windowMaxWriteBlockedMs, blockedMs)
    return rollWindowIfDueLocked()
  }

  @Synchronized
  fun rollWindowIfDue(): TransportLinkStats? = rollWindowIfDueLocked()

  @Synchronized
  fun snapshot(): TransportLinkStats =
    lastReport.copy(
      writeQueueLength = writeQueueLength,
      maxWriteQueueLength = maxOf(lastReport.maxWriteQueueLength, maxWriteQueueLength),
      socketCongested = lastReport.socketCongested || isCongested(lastReport.averageWriteBlockedMs, lastReport.maxWriteBlockedMs),
    )

  private fun rollWindowIfDueLocked(): TransportLinkStats? {
    val now = clockMs()
    val durationMs = now - windowStartedAtMs
    if (durationMs < REPORT_INTERVAL_MS) return null
    val averageWriteBlockedMs =
      if (windowWriteCount == 0L) {
        0L
      } else {
        windowWriteBlockedMs / windowWriteCount
      }
    val report =
      TransportLinkStats(
        sentBytesPerSecond = if (durationMs <= 0L) 0L else windowBytes * 1_000L / durationMs,
        averageWriteBlockedMs = averageWriteBlockedMs,
        maxWriteBlockedMs = windowMaxWriteBlockedMs,
        writeQueueLength = writeQueueLength,
        maxWriteQueueLength = maxWriteQueueLength,
        socketCongested = isCongested(averageWriteBlockedMs, windowMaxWriteBlockedMs),
      )
    lastReport = report
    windowStartedAtMs = now
    windowBytes = 0L
    windowWriteBlockedMs = 0L
    windowWriteCount = 0L
    windowMaxWriteBlockedMs = 0L
    maxWriteQueueLength = writeQueueLength
    return report
  }

  private fun recordQueueLengthLocked(queueLength: Int) {
    writeQueueLength = queueLength.coerceAtLeast(0)
    maxWriteQueueLength = maxOf(maxWriteQueueLength, writeQueueLength)
  }

  private fun isCongested(
    averageWriteBlockedMs: Long,
    maxWriteBlockedMs: Long,
  ): Boolean =
    writeQueueLength >= CONGESTED_QUEUE_LENGTH ||
      maxWriteQueueLength >= CONGESTED_QUEUE_LENGTH ||
      averageWriteBlockedMs >= CONGESTED_AVERAGE_WRITE_BLOCKED_MS ||
      maxWriteBlockedMs >= CONGESTED_MAX_WRITE_BLOCKED_MS

  private companion object {
    const val REPORT_INTERVAL_MS = 1_000L
    const val CONGESTED_QUEUE_LENGTH = 3
    const val CONGESTED_AVERAGE_WRITE_BLOCKED_MS = 80L
    const val CONGESTED_MAX_WRITE_BLOCKED_MS = 150L
  }
}
