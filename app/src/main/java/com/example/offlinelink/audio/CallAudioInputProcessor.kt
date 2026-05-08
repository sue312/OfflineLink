package com.example.offlinelink.audio

import kotlin.math.exp
import kotlin.math.PI

class CallAudioInputProcessor(sampleRateHz: Int) {
  private val highPassAlpha = exp(-2.0 * PI * HIGH_PASS_CUTOFF_HZ / sampleRateHz)
  private val lowPassAlpha = 1.0 - exp(-2.0 * PI * LOW_PASS_CUTOFF_HZ / sampleRateHz)
  private var previousInput = 0.0
  private var previousHighPassOutput = 0.0
  private var previousLowPassOutput = 0.0

  fun process(bytes: ByteArray, length: Int) {
    val usableLength = length.coerceAtMost(bytes.size).coerceAtLeast(0)
    var index = 0
    while (index + 1 < usableLength) {
      val input = readPcm16(bytes, index).toDouble()
      val highPassed = input - previousInput + highPassAlpha * previousHighPassOutput
      previousInput = input
      previousHighPassOutput = highPassed

      val lowPassed = previousLowPassOutput + lowPassAlpha * (highPassed - previousLowPassOutput)
      previousLowPassOutput = lowPassed

      writePcm16(bytes, index, lowPassed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
  }

  fun reset() {
    previousInput = 0.0
    previousHighPassOutput = 0.0
    previousLowPassOutput = 0.0
  }

  private fun readPcm16(bytes: ByteArray, index: Int): Short {
    val lo = bytes[index].toInt() and 0xff
    val hi = bytes[index + 1].toInt()
    return ((hi shl 8) or lo).toShort()
  }

  private fun writePcm16(bytes: ByteArray, index: Int, sample: Short) {
    val value = sample.toInt()
    bytes[index] = (value and 0xff).toByte()
    bytes[index + 1] = ((value shr 8) and 0xff).toByte()
  }

  private companion object {
    const val HIGH_PASS_CUTOFF_HZ = 150.0
    const val LOW_PASS_CUTOFF_HZ = 3_000.0
  }
}
