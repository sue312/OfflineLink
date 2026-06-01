package com.example.offlinelink.audio

class CallAudioJitterBuffer(
  private val targetBufferedMs: Long = DEFAULT_TARGET_BUFFER_MS,
  private val maxBufferedMs: Long = DEFAULT_MAX_BUFFER_MS,
) {
  private val frames = ArrayDeque<PcmAudioFrame>()
  var bufferedDurationMs: Long = 0L
    private set
  private var priming = true

  init {
    require(targetBufferedMs > 0) { "targetBufferedMs must be greater than 0" }
    require(maxBufferedMs >= targetBufferedMs) { "maxBufferedMs must be at least targetBufferedMs" }
  }

  fun enqueue(frame: PcmAudioFrame): Int {
    frames.addLast(frame)
    bufferedDurationMs += frame.durationMs()
    var dropped = 0
    while (bufferedDurationMs > maxBufferedMs && frames.size > 1) {
      bufferedDurationMs -= frames.removeFirst().durationMs()
      dropped++
    }
    return dropped
  }

  fun pollReady(): PcmAudioFrame? {
    if (frames.isEmpty()) {
      priming = true
      return null
    }
    if (priming && bufferedDurationMs < targetBufferedMs) return null
    priming = false
    val frame = frames.removeFirst()
    bufferedDurationMs -= frame.durationMs()
    if (frames.isEmpty()) {
      priming = true
      bufferedDurationMs = 0L
    }
    return frame
  }

  fun reset() {
    frames.clear()
    bufferedDurationMs = 0L
    priming = true
  }

  private fun PcmAudioFrame.durationMs(): Long {
    val bytesPerMillisecond = sampleRateHz * CALL_AUDIO_BYTES_PER_SAMPLE / 1_000
    if (bytesPerMillisecond <= 0) return CALL_AUDIO_FRAME_DURATION_MS.toLong()
    return (bytes.size / bytesPerMillisecond).coerceAtLeast(CALL_AUDIO_FRAME_DURATION_MS).toLong()
  }

  private companion object {
    const val DEFAULT_TARGET_BUFFER_MS = 100L
    const val DEFAULT_MAX_BUFFER_MS = 400L
  }
}
