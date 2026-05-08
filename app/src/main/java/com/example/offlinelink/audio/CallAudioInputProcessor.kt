package com.example.offlinelink.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.PI

data class CallAudioInputProcessorProfile(
  val lowPassCutoffHz: Double,
  val fullVoiceAmplitude: Int,
  val minNoiseGain: Double,
  val backgroundMaxGain: Double,
  val targetVoiceAmplitude: Double,
  val maxAutomaticGain: Double,
  val sampleMinGain: Double,
  val deHissSmoothing: Double,
  val speechLowBandGain: Double,
  val speechHighBandGain: Double,
  val backgroundHighBandGain: Double,
  val compressorThreshold: Double,
  val compressorRatio: Double,
)

object CallAudioInputProcessorProfiles {
  val Natural =
    CallAudioInputProcessorProfile(
      lowPassCutoffHz = 2_600.0,
      fullVoiceAmplitude = 500,
      minNoiseGain = 0.28,
      backgroundMaxGain = 0.68,
      targetVoiceAmplitude = 760.0,
      maxAutomaticGain = 2.6,
      sampleMinGain = 0.2,
      deHissSmoothing = 0.38,
      speechLowBandGain = 1.05,
      speechHighBandGain = 0.28,
      backgroundHighBandGain = 0.18,
      compressorThreshold = 6_400.0,
      compressorRatio = 3.2,
    )

  val Balanced =
    CallAudioInputProcessorProfile(
      lowPassCutoffHz = 1_900.0,
      fullVoiceAmplitude = 520,
      minNoiseGain = 0.18,
      backgroundMaxGain = 0.55,
      targetVoiceAmplitude = 840.0,
      maxAutomaticGain = 3.4,
      sampleMinGain = 0.12,
      deHissSmoothing = 0.28,
      speechLowBandGain = 1.12,
      speechHighBandGain = 0.06,
      backgroundHighBandGain = 0.12,
      compressorThreshold = 5_600.0,
      compressorRatio = 4.0,
    )

  val Strong =
    CallAudioInputProcessorProfile(
      lowPassCutoffHz = 1_650.0,
      fullVoiceAmplitude = 540,
      minNoiseGain = 0.14,
      backgroundMaxGain = 0.42,
      targetVoiceAmplitude = 900.0,
      maxAutomaticGain = 3.2,
      sampleMinGain = 0.09,
      deHissSmoothing = 0.24,
      speechLowBandGain = 1.12,
      speechHighBandGain = 0.04,
      backgroundHighBandGain = 0.08,
      compressorThreshold = 5_400.0,
      compressorRatio = 4.5,
    )
}

class CallAudioInputProcessor(
  sampleRateHz: Int,
  private val profile: CallAudioInputProcessorProfile = CallAudioInputProcessorProfiles.Balanced,
) {
  private val highPassAlpha = exp(-2.0 * PI * HIGH_PASS_CUTOFF_HZ / sampleRateHz)
  private val lowPassAlpha = 1.0 - exp(-2.0 * PI * profile.lowPassCutoffHz / sampleRateHz)
  private var previousInput = 0.0
  private var previousHighPassOutput = 0.0
  private var previousLowPassOutput = 0.0
  private var previousDeHissOutput = 0.0
  private var noiseSuppressionGain = 1.0
  private var noiseFloorAmplitude = INITIAL_NOISE_FLOOR_AMPLITUDE
  private var automaticGain = 1.0

  fun process(bytes: ByteArray, length: Int) {
    val usableLength = length.coerceAtMost(bytes.size).coerceAtLeast(0)
    val metrics = frameMetrics(bytes, usableLength)
    val speech = isSpeechFrame(metrics)
    updateNoiseFloor(metrics, speech)
    val frameGain = nextNoiseSuppressionGain(metrics.averageAmplitude, speech)
    val voiceGain = nextAutomaticGain(metrics, speech)
    var index = 0
    while (index + 1 < usableLength) {
      val input = readPcm16(bytes, index).toDouble()
      val highPassed = input - previousInput + highPassAlpha * previousHighPassOutput
      previousInput = input
      previousHighPassOutput = highPassed

      val lowPassed = previousLowPassOutput + lowPassAlpha * (highPassed - previousLowPassOutput)
      previousLowPassOutput = lowPassed

      val processed = applyCompressor(applySoftNoiseGate(lowPassed * voiceGain) * frameGain)
      val output = applyHissSuppressor(processed, speech)
      writePcm16(bytes, index, output.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
  }

  fun reset() {
    previousInput = 0.0
    previousHighPassOutput = 0.0
    previousLowPassOutput = 0.0
    previousDeHissOutput = 0.0
    noiseSuppressionGain = 1.0
    noiseFloorAmplitude = INITIAL_NOISE_FLOOR_AMPLITUDE
    automaticGain = 1.0
  }

  private fun nextNoiseSuppressionGain(
    frameAmplitude: Int,
    speech: Boolean,
  ): Double {
    val targetGain = targetNoiseSuppressionGain(frameAmplitude, speech)
    noiseSuppressionGain =
      when {
        targetGain >= 1.0 -> 1.0
        targetGain < noiseSuppressionGain ->
          noiseSuppressionGain + (targetGain - noiseSuppressionGain) * NOISE_ATTACK
        else ->
          noiseSuppressionGain + (targetGain - noiseSuppressionGain) * NOISE_RELEASE
      }
    return noiseSuppressionGain
  }

  private fun targetNoiseSuppressionGain(
    frameAmplitude: Int,
    speech: Boolean,
  ): Double {
    if (speech) return 1.0
    val quietAmplitude = max(QUIET_NOISE_AMPLITUDE.toDouble(), noiseFloorAmplitude * NOISE_SUPPRESSION_FLOOR_MULTIPLIER)
    val voiceAmplitude = max(profile.fullVoiceAmplitude.toDouble(), quietAmplitude + VOICE_RANGE_ABOVE_NOISE)
    return when {
      frameAmplitude <= quietAmplitude -> profile.minNoiseGain
      frameAmplitude >= voiceAmplitude -> if (speech) 1.0 else profile.backgroundMaxGain
      else -> {
        val progress = (frameAmplitude - quietAmplitude) / (voiceAmplitude - quietAmplitude)
        val maxGain = if (speech) 1.0 else profile.backgroundMaxGain
        profile.minNoiseGain + progress * (maxGain - profile.minNoiseGain)
      }
    }
  }

  private fun nextAutomaticGain(
    metrics: FrameMetrics,
    speech: Boolean,
  ): Double {
    val targetGain =
      if (speech) {
        (profile.targetVoiceAmplitude / metrics.averageAmplitude.coerceAtLeast(1).toDouble())
          .coerceIn(1.0, profile.maxAutomaticGain)
      } else {
        1.0
      }
    automaticGain =
      if (targetGain > automaticGain) {
        automaticGain + (targetGain - automaticGain) * AGC_ATTACK
      } else {
        automaticGain + (targetGain - automaticGain) * AGC_RELEASE
      }
    return automaticGain
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

  private fun applySoftNoiseGate(sample: Double): Double {
    val magnitude = abs(sample)
    val adaptiveNoiseFloor = max(SAMPLE_NOISE_FLOOR, noiseFloorAmplitude * SAMPLE_NOISE_FLOOR_MULTIPLIER)
    val adaptiveVoiceFloor = max(SAMPLE_VOICE_FLOOR, adaptiveNoiseFloor + SAMPLE_VOICE_RANGE_ABOVE_NOISE)
    val gain =
      when {
        magnitude <= adaptiveNoiseFloor -> profile.sampleMinGain
        magnitude >= adaptiveVoiceFloor -> 1.0
        else -> {
          val progress = (magnitude - adaptiveNoiseFloor) / (adaptiveVoiceFloor - adaptiveNoiseFloor)
          profile.sampleMinGain + progress * progress * (1.0 - profile.sampleMinGain)
        }
      }
    return sample * gain
  }

  private fun applyHissSuppressor(
    sample: Double,
    speech: Boolean,
  ): Double {
    val smoothed = previousDeHissOutput + profile.deHissSmoothing * (sample - previousDeHissOutput)
    val highBand = sample - smoothed
    val highBandGain = if (speech) profile.speechHighBandGain else profile.backgroundHighBandGain
    val lowBandGain = if (speech) profile.speechLowBandGain else 1.0
    val output = smoothed * lowBandGain + highBand * highBandGain
    previousDeHissOutput = smoothed
    return output
  }

  private fun applyCompressor(sample: Double): Double {
    val magnitude = abs(sample)
    if (magnitude <= profile.compressorThreshold) return sample
    val compressedMagnitude = profile.compressorThreshold + (magnitude - profile.compressorThreshold) / profile.compressorRatio
    return if (sample < 0) -compressedMagnitude else compressedMagnitude
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
    const val QUIET_NOISE_AMPLITUDE = 80
    const val NOISE_ATTACK = 0.75
    const val NOISE_RELEASE = 0.55
    const val INITIAL_NOISE_FLOOR_AMPLITUDE = 72.0
    const val MIN_NOISE_FLOOR_AMPLITUDE = 24.0
    const val MAX_NOISE_FLOOR_AMPLITUDE = 420.0
    const val NOISE_FLOOR_RISE = 0.08
    const val NOISE_FLOOR_FALL = 0.02
    const val NOISE_SUPPRESSION_FLOOR_MULTIPLIER = 1.7
    const val VOICE_RANGE_ABOVE_NOISE = 440.0
    const val VAD_MIN_VOICE_AMPLITUDE = 130
    const val VAD_SIGNAL_TO_NOISE_RATIO = 2.4
    const val VAD_PEAK_MULTIPLIER = 2
    const val VAD_MIN_ZERO_CROSSING_RATE = 0.015
    const val VAD_MAX_ZERO_CROSSING_RATE = 0.42
    const val AGC_ATTACK = 0.45
    const val AGC_RELEASE = 0.35
    const val SAMPLE_NOISE_FLOOR = 140.0
    const val SAMPLE_VOICE_FLOOR = 760.0
    const val SAMPLE_NOISE_FLOOR_MULTIPLIER = 2.1
    const val SAMPLE_VOICE_RANGE_ABOVE_NOISE = 620.0
  }

  private data class FrameMetrics(
    val averageAmplitude: Int,
    val peakAmplitude: Int,
    val zeroCrossingRate: Double,
  )
}
