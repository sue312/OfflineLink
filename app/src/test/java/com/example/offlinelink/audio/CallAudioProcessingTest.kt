package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioProcessingTest {

  @Test
  fun callAudioFrameSizeUsesLowLatencyTwentyMillisecondFrames() {
    assertEquals(20, CALL_AUDIO_FRAME_DURATION_MS)
    assertEquals(320, CALL_AUDIO_FRAME_BYTES)
  }

  @Test
  fun averageAbsolutePcm16AmplitudeReadsLittleEndianSamples() {
    val frame = pcmFrame(0, 1024, -1024)

    assertEquals(682, averageAbsolutePcm16Amplitude(frame, frame.size))
  }

  @Test
  fun noiseGateSuppressesQuietFrames() {
    val gate = CallAudioNoiseGate(threshold = 200, hangoverFrames = 2)
    val quietFrame = repeatedPcmFrame(sample = 20, sampleCount = 160)

    assertFalse(gate.shouldTransmit(quietFrame, quietFrame.size))
  }

  @Test
  fun noiseGateTransmitsVoiceAndKeepsShortHangover() {
    val gate = CallAudioNoiseGate(threshold = 200, hangoverFrames = 2)
    val voiceFrame = repeatedPcmFrame(sample = 800, sampleCount = 160)
    val quietFrame = repeatedPcmFrame(sample = 20, sampleCount = 160)

    assertTrue(gate.shouldTransmit(voiceFrame, voiceFrame.size))
    assertTrue(gate.shouldTransmit(quietFrame, quietFrame.size))
    assertTrue(gate.shouldTransmit(quietFrame, quietFrame.size))
    assertFalse(gate.shouldTransmit(quietFrame, quietFrame.size))
  }

  @Test
  fun defaultNoiseGatePreservesSoftSpeechAndLongerWordEndings() {
    val gate = CallAudioNoiseGate()
    val softVoiceFrame = repeatedPcmFrame(sample = 64, sampleCount = 320)
    val quietFrame = repeatedPcmFrame(sample = 8, sampleCount = 320)

    assertTrue(gate.shouldTransmit(softVoiceFrame, softVoiceFrame.size))
    repeat(8) {
      assertTrue(gate.shouldTransmit(quietFrame, quietFrame.size))
    }
    assertFalse(gate.shouldTransmit(quietFrame, quietFrame.size))
  }

  private fun repeatedPcmFrame(sample: Short, sampleCount: Int): ByteArray =
    pcmFrame(*ShortArray(sampleCount) { sample })

  private fun pcmFrame(vararg samples: Short): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
      val value = sample.toInt()
      bytes[index * 2] = (value and 0xff).toByte()
      bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
    }
    return bytes
  }
}
