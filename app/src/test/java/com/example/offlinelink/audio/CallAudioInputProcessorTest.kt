package com.example.offlinelink.audio

import org.junit.Assert.assertTrue
import org.junit.Test

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

  private fun repeatedPcmFrame(sample: Short, sampleCount: Int): ByteArray =
    pcmFrame(*ShortArray(sampleCount) { sample })

  private fun alternatingPcmFrame(sample: Short, sampleCount: Int): ByteArray =
    pcmFrame(*ShortArray(sampleCount) { index -> if (index % 2 == 0) sample else (-sample).toShort() })

  private fun pcmFrame(vararg samples: Short): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
      bytes[index * 2] = (sample.toInt() and 0xff).toByte()
      bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
  }
}
