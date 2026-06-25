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
    val interval = maxOf(baseInterval, pressureInterval)
    return if (mode == CallAudioProcessingMode.LongRange && hasSevereBackpressure(stats)) {
      maxOf(interval, 3)
    } else {
      interval
    }
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
      stats.liveAudioPendingFrames >= CONGESTED_LIVE_AUDIO_PENDING_FRAMES ||
      stats.averageWriteBlockedMs >= CONGESTED_AVERAGE_WRITE_BLOCKED_MS ||
      stats.maxWriteBlockedMs >= CONGESTED_MAX_WRITE_BLOCKED_MS

  private fun shouldReduceFrameRate(stats: CallAudioTransmitStats): Boolean =
    isWeakSignal(stats) || hasBackpressure(stats)

  private fun hasSevereBackpressure(stats: CallAudioTransmitStats): Boolean =
    stats.socketCongested ||
      stats.remoteRssi?.let { it <= SEVERE_RSSI_DBM } == true ||
      stats.liveAudioPendingFrames >= SEVERE_LIVE_AUDIO_PENDING_FRAMES ||
      stats.writeQueueLength >= SEVERE_WRITE_QUEUE_LENGTH ||
      stats.maxWriteBlockedMs >= SEVERE_MAX_WRITE_BLOCKED_MS

  private const val WEAK_RSSI_DBM = -90
  private const val SEVERE_RSSI_DBM = -96
  private const val CONGESTED_WRITE_QUEUE_LENGTH = 3
  private const val SEVERE_WRITE_QUEUE_LENGTH = 5
  private const val CONGESTED_LIVE_AUDIO_PENDING_FRAMES = 2
  private const val SEVERE_LIVE_AUDIO_PENDING_FRAMES = 3
  private const val CONGESTED_AVERAGE_WRITE_BLOCKED_MS = 80L
  private const val CONGESTED_MAX_WRITE_BLOCKED_MS = 150L
  private const val SEVERE_MAX_WRITE_BLOCKED_MS = 250L
}
