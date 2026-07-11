package com.example.offlinelink.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

class CallAudioPlaybackProcessor(
  sampleRateHz: Int,
) {
  private val highPassAlpha = exp(-2.0 * PI * HIGH_PASS_CUTOFF_HZ / sampleRateHz)
  private val lowPassAlpha = 1.0 - exp(-2.0 * PI * LOW_PASS_CUTOFF_HZ / sampleRateHz)
  private var previousInput = 0.0
  private var previousHighPassOutput = 0.0
  private var previousLowPassOutput = 0.0
  private var noiseSuppressionGain = 1.0
  private var noiseFloorAmplitude = INITIAL_NOISE_FLOOR_AMPLITUDE

  fun process(frame: PcmAudioFrame): PcmAudioFrame {
    val processedBytes = frame.bytes.copyOf()
    process(processedBytes, processedBytes.size)
    return PcmAudioFrame(bytes = processedBytes, sampleRateHz = frame.sampleRateHz)
  }

  fun process(
    bytes: ByteArray,
    length: Int,
  ): Boolean {
    val usableLength = length.coerceAtMost(bytes.size).coerceAtLeast(0)
    val metrics = frameMetrics(bytes, usableLength)
    val speech = isSpeechFrame(metrics)
    updateNoiseFloor(metrics, speech)
    val frameGain = nextNoiseSuppressionGain(metrics.averageAmplitude, speech)
    val highBandGain = if (speech) SPEECH_HIGH_BAND_GAIN else BACKGROUND_HIGH_BAND_GAIN
    var index = 0
    while (index + 1 < usableLength) {
      val input = readPcm16(bytes, index).toDouble()
      val highPassed = input - previousInput + highPassAlpha * previousHighPassOutput
      previousInput = input
      previousHighPassOutput = highPassed

      val lowPassed = previousLowPassOutput + lowPassAlpha * (highPassed - previousLowPassOutput)
      previousLowPassOutput = lowPassed

      val highBand = highPassed - lowPassed
      val output = (lowPassed + highBand * highBandGain) * frameGain
      writePcm16(bytes, index, output.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return speech
  }

  fun reset() {
    previousInput = 0.0
    previousHighPassOutput = 0.0
    previousLowPassOutput = 0.0
    noiseSuppressionGain = 1.0
    noiseFloorAmplitude = INITIAL_NOISE_FLOOR_AMPLITUDE
  }

  private fun nextNoiseSuppressionGain(
    frameAmplitude: Int,
    speech: Boolean,
  ): Double {
    val targetGain = targetNoiseSuppressionGain(frameAmplitude, speech)
    noiseSuppressionGain =
      if (targetGain < noiseSuppressionGain) {
        noiseSuppressionGain + (targetGain - noiseSuppressionGain) * NOISE_ATTACK
      } else {
        noiseSuppressionGain + (targetGain - noiseSuppressionGain) * NOISE_RELEASE
      }
    return noiseSuppressionGain
  }

  private fun targetNoiseSuppressionGain(
    frameAmplitude: Int,
    speech: Boolean,
  ): Double {
    if (speech) return SPEECH_FRAME_GAIN
    val quietAmplitude = max(QUIET_NOISE_AMPLITUDE.toDouble(), noiseFloorAmplitude * NOISE_SUPPRESSION_FLOOR_MULTIPLIER)
    val voiceAmplitude = max(BACKGROUND_VOICE_AMPLITUDE.toDouble(), quietAmplitude + VOICE_RANGE_ABOVE_NOISE)
    return when {
      frameAmplitude <= quietAmplitude -> MIN_BACKGROUND_GAIN
      frameAmplitude >= voiceAmplitude -> BACKGROUND_MAX_GAIN
      else -> {
        val progress = (frameAmplitude - quietAmplitude) / (voiceAmplitude - quietAmplitude)
        MIN_BACKGROUND_GAIN + progress * (BACKGROUND_MAX_GAIN - MIN_BACKGROUND_GAIN)
      }
    }
  }

  private fun isSpeechFrame(metrics: FrameMetrics): Boolean {
    val minimumVoiceAmplitude =
      max(VAD_MIN_VOICE_AMPLITUDE.toDouble(), noiseFloorAmplitude * VAD_SIGNAL_TO_NOISE_RATIO).toInt()
    return metrics.averageAmplitude >= minimumVoiceAmplitude &&
      metrics.peakAmplitude >= minimumVoiceAmplitude * VAD_PEAK_MULTIPLIER &&
      metrics.zeroCrossingRate in VAD_MIN_ZERO_CROSSING_RATE..VAD_MAX_ZERO_CROSSING_RATE
  }

  private fun updateNoiseFloor(
    metrics: FrameMetrics,
    speech: Boolean,
  ) {
    if (metrics.averageAmplitude <= 0) return
    if (speech && metrics.averageAmplitude > noiseFloorAmplitude * VAD_SIGNAL_TO_NOISE_RATIO) return
    val smoothing = if (metrics.averageAmplitude > noiseFloorAmplitude) NOISE_FLOOR_RISE else NOISE_FLOOR_FALL
    noiseFloorAmplitude += (metrics.averageAmplitude - noiseFloorAmplitude) * smoothing
    noiseFloorAmplitude = noiseFloorAmplitude.coerceIn(MIN_NOISE_FLOOR_AMPLITUDE, MAX_NOISE_FLOOR_AMPLITUDE)
  }

  private fun frameMetrics(
    bytes: ByteArray,
    length: Int,
  ): FrameMetrics {
    val sampleCount = length / CALL_AUDIO_BYTES_PER_SAMPLE
    if (sampleCount == 0) return FrameMetrics(0, 0, 0.0)
    var sum = 0L
    var peak = 0
    var crossings = 0
    var previousSign = 0
    var index = 0
    repeat(sampleCount) {
      val sample = readPcm16(bytes, index).toInt()
      val magnitude = abs(sample)
      sum += magnitude.toLong()
      if (magnitude > peak) peak = magnitude
      val sign =
        when {
          sample > 0 -> 1
          sample < 0 -> -1
          else -> 0
        }
      if (sign != 0) {
        if (previousSign != 0 && sign != previousSign) crossings++
        previousSign = sign
      }
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return FrameMetrics(
      averageAmplitude = (sum / sampleCount).toInt(),
      peakAmplitude = peak,
      zeroCrossingRate = crossings.toDouble() / sampleCount.toDouble(),
    )
  }

  private fun readPcm16(
    bytes: ByteArray,
    index: Int,
  ): Short {
    val lo = bytes[index].toInt() and 0xff
    val hi = bytes[index + 1].toInt()
    return ((hi shl 8) or lo).toShort()
  }

  private fun writePcm16(
    bytes: ByteArray,
    index: Int,
    sample: Short,
  ) {
    val value = sample.toInt()
    bytes[index] = (value and 0xff).toByte()
    bytes[index + 1] = ((value shr 8) and 0xff).toByte()
  }

  private companion object {
    const val HIGH_PASS_CUTOFF_HZ = 120.0
    const val LOW_PASS_CUTOFF_HZ = 2_650.0
    const val SPEECH_FRAME_GAIN = 0.96
    const val SPEECH_HIGH_BAND_GAIN = 0.2
    const val BACKGROUND_HIGH_BAND_GAIN = 0.1
    const val QUIET_NOISE_AMPLITUDE = 82
    const val MIN_BACKGROUND_GAIN = 0.24
    const val BACKGROUND_MAX_GAIN = 0.68
    const val BACKGROUND_VOICE_AMPLITUDE = 520
    const val VOICE_RANGE_ABOVE_NOISE = 420.0
    const val NOISE_ATTACK = 0.8
    const val NOISE_RELEASE = 0.45
    const val INITIAL_NOISE_FLOOR_AMPLITUDE = 72.0
    const val MIN_NOISE_FLOOR_AMPLITUDE = 24.0
    const val MAX_NOISE_FLOOR_AMPLITUDE = 420.0
    const val NOISE_FLOOR_RISE = 0.08
    const val NOISE_FLOOR_FALL = 0.02
    const val NOISE_SUPPRESSION_FLOOR_MULTIPLIER = 1.7
    const val VAD_MIN_VOICE_AMPLITUDE = 170
    const val VAD_SIGNAL_TO_NOISE_RATIO = 2.6
    const val VAD_PEAK_MULTIPLIER = 2
    const val VAD_MIN_ZERO_CROSSING_RATE = 0.015
    const val VAD_MAX_ZERO_CROSSING_RATE = 0.45
  }

  private data class FrameMetrics(
    val averageAmplitude: Int,
    val peakAmplitude: Int,
    val zeroCrossingRate: Double,
  )
}
