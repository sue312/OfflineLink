package com.example.offlinelink.audio

object CallAudioTransmitPolicy {
  fun shouldPreferReliableEncoding(stats: CallAudioTransmitStats): Boolean =
    stats.forceReliableEncoding ||
      isWeakSignal(stats) ||
      hasBackpressure(stats)

  fun captureFrameInterval(
    mode: CallAudioProcessingMode,
    stats: CallAudioTransmitStats,
  ): Int {
    val baseInterval = mode.baseTransmitFrameInterval.coerceAtLeast(1)
    val pressureInterval = if (shouldReduceFrameRate(stats)) 2 else 1
    return maxOf(baseInterval, pressureInterval)
  }

  fun shouldTransmitCapturedFrame(
    mode: CallAudioProcessingMode,
    stats: CallAudioTransmitStats,
    frameIndex: Long,
  ): Boolean {
    val interval = captureFrameInterval(mode, stats)
    return interval <= 1 || frameIndex % interval == 0L
  }

  private fun isWeakSignal(stats: CallAudioTransmitStats): Boolean =
    stats.remoteRssi?.let { it <= WEAK_RSSI_DBM } == true

  private fun hasBackpressure(stats: CallAudioTransmitStats): Boolean =
    stats.socketCongested ||
      stats.writeQueueLength >= CONGESTED_WRITE_QUEUE_LENGTH ||
      stats.maxWriteQueueLength >= CONGESTED_WRITE_QUEUE_LENGTH ||
      stats.averageWriteBlockedMs >= CONGESTED_AVERAGE_WRITE_BLOCKED_MS ||
      stats.maxWriteBlockedMs >= CONGESTED_MAX_WRITE_BLOCKED_MS

  private fun shouldReduceFrameRate(stats: CallAudioTransmitStats): Boolean =
    isWeakSignal(stats) || hasBackpressure(stats)

  private const val WEAK_RSSI_DBM = -90
  private const val CONGESTED_WRITE_QUEUE_LENGTH = 3
  private const val CONGESTED_AVERAGE_WRITE_BLOCKED_MS = 80L
  private const val CONGESTED_MAX_WRITE_BLOCKED_MS = 150L
}
