package com.example.offlinelink.audio

import kotlin.math.abs

class CallAudioNoiseGate(
  private val threshold: Int = DEFAULT_THRESHOLD,
  private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
) {
  private var remainingHangoverFrames = 0

  init {
    require(threshold >= 0) { "threshold must be non-negative" }
    require(hangoverFrames >= 0) { "hangoverFrames must be non-negative" }
  }

  fun shouldTransmit(
    bytes: ByteArray,
    length: Int,
    speechHint: Boolean? = null,
  ): Boolean {
    val amplitude = averageAbsolutePcm16Amplitude(bytes, length)
    val shouldOpen =
      when (speechHint) {
        true -> amplitude >= threshold
        false -> amplitude >= LOUD_UNCLASSIFIED_THRESHOLD
        null -> amplitude >= threshold
      }
    if (shouldOpen) {
      remainingHangoverFrames = hangoverFrames
      return true
    }
    if (remainingHangoverFrames > 0) {
      remainingHangoverFrames--
      return true
    }
    return false
  }

  fun reset() {
    remainingHangoverFrames = 0
  }

  private companion object {
    const val DEFAULT_THRESHOLD = 48
    const val DEFAULT_HANGOVER_FRAMES = 8
    const val LOUD_UNCLASSIFIED_THRESHOLD = 2_400
  }
}

fun averageAbsolutePcm16Amplitude(bytes: ByteArray, length: Int): Int {
  val usableLength = length.coerceAtMost(bytes.size).coerceAtLeast(0)
  val sampleCount = usableLength / CALL_AUDIO_BYTES_PER_SAMPLE
  if (sampleCount == 0) return 0

  var sum = 0L
  var index = 0
  repeat(sampleCount) {
    val lo = bytes[index].toInt() and 0xff
    val hi = bytes[index + 1].toInt()
    val sample = (hi shl 8) or lo
    sum += abs(sample).toLong()
    index += CALL_AUDIO_BYTES_PER_SAMPLE
  }
  return (sum / sampleCount).toInt()
}
