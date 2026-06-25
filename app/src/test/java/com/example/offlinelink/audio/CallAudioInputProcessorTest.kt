package com.example.offlinelink.audio

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CallAudioInputProcessorTest {
  @Test
  fun removesDcOffsetBeforeEncoding() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    val frame = repeatedPcmFrame(sample = 1200, sampleCount = 320)

    processor.process(frame, frame.size)

    assertTrue(averageAbsolutePcm16Amplitude(frame, frame.size) < 160)
  }

  @Test
  fun attenuatesHighFrequencyAlternatingNoise() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    val frame = alternatingPcmFrame(sample = 1200, sampleCount = 320)

    processor.process(frame, frame.size)

    assertTrue(averageAbsolutePcm16Amplitude(frame, frame.size) < 700)
  }

  @Test
  fun attenuatesLowLevelBackgroundNoiseWithoutMutingSpeechBand() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    val quietNoise = tonePcmFrame(frequencyHz = 700, amplitude = 120, sampleRateHz = 16_000, sampleCount = 320)

    processor.process(quietNoise, quietNoise.size)

    assertTrue(averageAbsolutePcm16Amplitude(quietNoise, quietNoise.size) < 45)

    processor.reset()
    val speech = tonePcmFrame(frequencyHz = 700, amplitude = 1200, sampleRateHz = 16_000, sampleCount = 320)

    processor.process(speech, speech.size)

    assertTrue(averageAbsolutePcm16Amplitude(speech, speech.size) > 500)
  }

  @Test
  fun suppressesLowLevelNoiseInsideLoudSpeechFrames() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    val frame =
      pcmFrame(
        *ShortArray(320) { index ->
          if (index < 160) {
            (sin(2.0 * PI * 700 * index / 16_000) * 2200).toInt().toShort()
          } else {
            (if (index % 2 == 0) 120 else -120).toShort()
          }
        },
      )

    processor.process(frame, frame.size)

    assertTrue(averageAbsolutePcm16Amplitude(frame, sampleOffset = 40, sampleCount = 80) > 500)
    assertTrue(averageAbsolutePcm16Amplitude(frame, sampleOffset = 240, sampleCount = 80) < 35)
  }

  @Test
  fun agcLiftsSoftSpeechWithoutBoostingLearnedNoise() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    repeat(8) {
      val noise = alternatingPcmFrame(sample = 70, sampleCount = 320)
      processor.process(noise, noise.size)
    }
    val softSpeech = tonePcmFrame(frequencyHz = 700, amplitude = 430, sampleRateHz = 16_000, sampleCount = 320)

    processor.process(softSpeech, softSpeech.size)

    val softSpeechAmplitude = averageAbsolutePcm16Amplitude(softSpeech, softSpeech.size)
    assertTrue("softSpeechAmplitude=$softSpeechAmplitude", softSpeechAmplitude > 300)

    val noiseAfterSpeech = alternatingPcmFrame(sample = 70, sampleCount = 320)

    processor.process(noiseAfterSpeech, noiseAfterSpeech.size)

    val noiseAfterSpeechAmplitude = averageAbsolutePcm16Amplitude(noiseAfterSpeech, noiseAfterSpeech.size)
    assertTrue("noiseAfterSpeechAmplitude=$noiseAfterSpeechAmplitude", noiseAfterSpeechAmplitude < 25)
  }

  @Test
  fun compressorLimitsLoudSpeechPeaks() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    val loudSpeech = tonePcmFrame(frequencyHz = 700, amplitude = 14_000, sampleRateHz = 16_000, sampleCount = 320)

    processor.process(loudSpeech, loudSpeech.size)

    assertTrue(peakAbsolutePcm16Amplitude(loudSpeech) < 9_000)
    assertTrue(averageAbsolutePcm16Amplitude(loudSpeech, loudSpeech.size) > 2_500)
  }

  @Test
  fun agcDoesNotLiftHissMixedWithSpeech() {
    val cleanProcessor = trainedNoiseProcessor()
    val noisyProcessor = trainedNoiseProcessor()
    val cleanSpeech = tonePcmFrame(frequencyHz = 700, amplitude = 900, sampleRateHz = 16_000, sampleCount = 320)
    val noisySpeech =
      mixedTonePcmFrame(
        primaryFrequencyHz = 700,
        primaryAmplitude = 900,
        noiseFrequencyHz = 2_600,
        noiseAmplitude = 220,
        sampleRateHz = 16_000,
        sampleCount = 320,
      )

    cleanProcessor.process(cleanSpeech, cleanSpeech.size)
    noisyProcessor.process(noisySpeech, noisySpeech.size)

    val hissResidual = residualAverageAmplitude(noisySpeech, cleanSpeech)
    assertTrue("hissResidual=$hissResidual", hissResidual < 58)
    val noisySpeechAmplitude = averageAbsolutePcm16Amplitude(noisySpeech, noisySpeech.size)
    assertTrue("noisySpeechAmplitude=$noisySpeechAmplitude", noisySpeechAmplitude > 500)
  }

  @Test
  fun strongProfileSuppressesMoreMixedHissThanNaturalProfile() {
    val naturalResidual = mixedSpeechResidual(CallAudioInputProcessorProfiles.Natural)
    val strongResidual = mixedSpeechResidual(CallAudioInputProcessorProfiles.Strong)

    assertTrue("naturalResidual=$naturalResidual strongResidual=$strongResidual", strongResidual < naturalResidual)
  }

  @Test
  fun processReportsSpeechForVoiceFramesButNotHighAmplitudeNonSpeechNoise() {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    repeat(8) {
      val quietNoise = tonePcmFrame(frequencyHz = 2_600, amplitude = 90, sampleRateHz = 16_000, sampleCount = 320)
      processor.process(quietNoise, quietNoise.size)
    }
    val windLikeNoise = alternatingPcmFrame(sample = 900, sampleCount = 320)
    val speech = tonePcmFrame(frequencyHz = 700, amplitude = 1_000, sampleRateHz = 16_000, sampleCount = 320)

    val noiseDetectedAsSpeech = processor.process(windLikeNoise, windLikeNoise.size)
    val speechDetected = processor.process(speech, speech.size)

    assertFalse(noiseDetectedAsSpeech)
    assertTrue(speechDetected)
  }

  private fun repeatedPcmFrame(sample: Short, sampleCount: Int): ByteArray =
    pcmFrame(*ShortArray(sampleCount) { sample })

  private fun alternatingPcmFrame(sample: Short, sampleCount: Int): ByteArray =
    pcmFrame(*ShortArray(sampleCount) { index -> if (index % 2 == 0) sample else (-sample).toShort() })

  private fun tonePcmFrame(
    frequencyHz: Int,
    amplitude: Int,
    sampleRateHz: Int,
    sampleCount: Int,
  ): ByteArray =
    pcmFrame(
      *ShortArray(sampleCount) { index ->
        (sin(2.0 * PI * frequencyHz * index / sampleRateHz) * amplitude).toInt().toShort()
      },
    )

  private fun mixedTonePcmFrame(
    primaryFrequencyHz: Int,
    primaryAmplitude: Int,
    noiseFrequencyHz: Int,
    noiseAmplitude: Int,
    sampleRateHz: Int,
    sampleCount: Int,
  ): ByteArray =
    pcmFrame(
      *ShortArray(sampleCount) { index ->
        (
          sin(2.0 * PI * primaryFrequencyHz * index / sampleRateHz) * primaryAmplitude +
            sin(2.0 * PI * noiseFrequencyHz * index / sampleRateHz) * noiseAmplitude
        ).toInt().toShort()
      },
    )

  private fun trainedNoiseProcessor(): CallAudioInputProcessor {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000)
    repeat(8) {
      val noise = tonePcmFrame(frequencyHz = 2_600, amplitude = 110, sampleRateHz = 16_000, sampleCount = 320)
      processor.process(noise, noise.size)
    }
    return processor
  }

  private fun trainedNoiseProcessor(profile: CallAudioInputProcessorProfile): CallAudioInputProcessor {
    val processor = CallAudioInputProcessor(sampleRateHz = 16_000, profile = profile)
    repeat(8) {
      val noise = tonePcmFrame(frequencyHz = 2_600, amplitude = 110, sampleRateHz = 16_000, sampleCount = 320)
      processor.process(noise, noise.size)
    }
    return processor
  }

  private fun mixedSpeechResidual(profile: CallAudioInputProcessorProfile): Int {
    val cleanProcessor = trainedNoiseProcessor(profile)
    val noisyProcessor = trainedNoiseProcessor(profile)
    val cleanSpeech = tonePcmFrame(frequencyHz = 700, amplitude = 900, sampleRateHz = 16_000, sampleCount = 320)
    val noisySpeech =
      mixedTonePcmFrame(
        primaryFrequencyHz = 700,
        primaryAmplitude = 900,
        noiseFrequencyHz = 2_600,
        noiseAmplitude = 220,
        sampleRateHz = 16_000,
        sampleCount = 320,
      )

    cleanProcessor.process(cleanSpeech, cleanSpeech.size)
    noisyProcessor.process(noisySpeech, noisySpeech.size)

    return residualAverageAmplitude(noisySpeech, cleanSpeech)
  }

  private fun pcmFrame(vararg samples: Short): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
      bytes[index * 2] = (sample.toInt() and 0xff).toByte()
      bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
  }

  private fun averageAbsolutePcm16Amplitude(
    bytes: ByteArray,
    sampleOffset: Int,
    sampleCount: Int,
  ): Int =
    averageAbsolutePcm16Amplitude(
      bytes.copyOfRange(sampleOffset * CALL_AUDIO_BYTES_PER_SAMPLE, (sampleOffset + sampleCount) * CALL_AUDIO_BYTES_PER_SAMPLE),
      sampleCount * CALL_AUDIO_BYTES_PER_SAMPLE,
    )

  private fun peakAbsolutePcm16Amplitude(bytes: ByteArray): Int {
    var peak = 0
    var index = 0
    while (index + 1 < bytes.size) {
      val lo = bytes[index].toInt() and 0xff
      val hi = bytes[index + 1].toInt()
      val sample = kotlin.math.abs(((hi shl 8) or lo).toShort().toInt())
      if (sample > peak) peak = sample
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return peak
  }

  private fun residualAverageAmplitude(
    first: ByteArray,
    second: ByteArray,
  ): Int {
    val size = minOf(first.size, second.size)
    var sum = 0L
    var count = 0
    var index = 0
    while (index + 1 < size) {
      val firstSample = readPcm16(first, index)
      val secondSample = readPcm16(second, index)
      sum += kotlin.math.abs(firstSample - secondSample).toLong()
      count += 1
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return if (count == 0) 0 else (sum / count).toInt()
  }

  private fun readPcm16(bytes: ByteArray, index: Int): Int {
    val lo = bytes[index].toInt() and 0xff
    val hi = bytes[index + 1].toInt()
    return ((hi shl 8) or lo).toShort().toInt()
  }
}
