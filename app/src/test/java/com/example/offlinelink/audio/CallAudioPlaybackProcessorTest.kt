package com.example.offlinelink.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CallAudioPlaybackProcessorTest {
  @Test
  fun attenuatesLowLevelPlaybackNoise() {
    val processor = CallAudioPlaybackProcessor(sampleRateHz = 16_000)
    repeat(6) {
      processor.process(PcmAudioFrame(tonePcmFrame(2_600, 90, 16_000, 320), 16_000))
    }
    val noise = PcmAudioFrame(tonePcmFrame(700, 130, 16_000, 320), 16_000)

    val processed = processor.process(noise)

    val inputAmplitude = averageAbsolutePcm16Amplitude(noise.bytes)
    val outputAmplitude = averageAbsolutePcm16Amplitude(processed.bytes)
    assertTrue("input=$inputAmplitude output=$outputAmplitude", outputAmplitude < inputAmplitude / 2)
  }

  @Test
  fun preservesSpeechAmplitudeWithoutBoostingIt() {
    val processor = CallAudioPlaybackProcessor(sampleRateHz = 16_000)
    val speech = PcmAudioFrame(tonePcmFrame(700, 1_400, 16_000, 320), 16_000)

    val processed = processor.process(speech)

    val inputAmplitude = averageAbsolutePcm16Amplitude(speech.bytes)
    val outputAmplitude = averageAbsolutePcm16Amplitude(processed.bytes)
    assertTrue("input=$inputAmplitude output=$outputAmplitude", outputAmplitude > inputAmplitude * 65 / 100)
    assertTrue("input=$inputAmplitude output=$outputAmplitude", outputAmplitude <= inputAmplitude)
  }

  @Test
  fun attenuatesHighFrequencyHissMixedWithSpeech() {
    val cleanProcessor = trainedNoiseProcessor()
    val noisyProcessor = trainedNoiseProcessor()
    val cleanSpeech = PcmAudioFrame(tonePcmFrame(700, 1_000, 16_000, 320), 16_000)
    val noisySpeech =
      PcmAudioFrame(
        mixedTonePcmFrame(
          primaryFrequencyHz = 700,
          primaryAmplitude = 1_000,
          noiseFrequencyHz = 3_200,
          noiseAmplitude = 280,
          sampleRateHz = 16_000,
          sampleCount = 320,
        ),
        16_000,
      )

    val cleanProcessed = cleanProcessor.process(cleanSpeech)
    val noisyProcessed = noisyProcessor.process(noisySpeech)

    val inputResidual = residualAverageAmplitude(noisySpeech.bytes, cleanSpeech.bytes)
    val outputResidual = residualAverageAmplitude(noisyProcessed.bytes, cleanProcessed.bytes)
    assertTrue("inputResidual=$inputResidual outputResidual=$outputResidual", outputResidual < inputResidual * 65 / 100)
  }

  @Test
  fun returnsCopyWithoutMutatingDecodedFrame() {
    val processor = CallAudioPlaybackProcessor(sampleRateHz = 16_000)
    val originalBytes = tonePcmFrame(700, 1_000, 16_000, 320)
    val frame = PcmAudioFrame(originalBytes.copyOf(), 16_000)

    val processed = processor.process(frame)

    assertArrayEquals(originalBytes, frame.bytes)
    assertEquals(frame.sampleRateHz, processed.sampleRateHz)
    assertTrue(processed.bytes !== frame.bytes)
  }

  private fun trainedNoiseProcessor(): CallAudioPlaybackProcessor {
    val processor = CallAudioPlaybackProcessor(sampleRateHz = 16_000)
    repeat(6) {
      processor.process(PcmAudioFrame(tonePcmFrame(3_200, 90, 16_000, 320), 16_000))
    }
    return processor
  }

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

  private fun pcmFrame(vararg samples: Short): ByteArray {
    val bytes = ByteArray(samples.size * CALL_AUDIO_BYTES_PER_SAMPLE)
    samples.forEachIndexed { index, sample ->
      bytes[index * CALL_AUDIO_BYTES_PER_SAMPLE] = (sample.toInt() and 0xff).toByte()
      bytes[index * CALL_AUDIO_BYTES_PER_SAMPLE + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
  }

  private fun averageAbsolutePcm16Amplitude(bytes: ByteArray): Int {
    var sum = 0L
    var count = 0
    var index = 0
    while (index + 1 < bytes.size) {
      sum += kotlin.math.abs(readPcm16(bytes, index)).toLong()
      count += 1
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return if (count == 0) 0 else (sum / count).toInt()
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
      sum += kotlin.math.abs(readPcm16(first, index) - readPcm16(second, index)).toLong()
      count += 1
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return if (count == 0) 0 else (sum / count).toInt()
  }

  private fun readPcm16(
    bytes: ByteArray,
    index: Int,
  ): Int {
    val lo = bytes[index].toInt() and 0xff
    val hi = bytes[index + 1].toInt()
    return ((hi shl 8) or lo).toShort().toInt()
  }
}
