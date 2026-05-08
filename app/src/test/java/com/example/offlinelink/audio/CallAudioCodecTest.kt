package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioCodecTest {
  @Test
  fun pcmEncoderUsesRequestedSampleRateAndStreamingMimeType() {
    val encoder = PcmCallAudioEncoder(sampleRateHz = 16_000)

    val frame = encoder.encode(byteArrayOf(1, 2, 3, 4)).getOrThrow()!!

    assertEquals("audio/pcm;rate=16000;encoding=pcm16", frame.mimeType)
    assertEquals(20L, frame.durationMs)
    assertEquals(listOf(1, 2, 3, 4), frame.bytes.map { it.toInt() })
    assertEquals(16_000, encoder.inputSampleRateHz)
    assertEquals(640, encoder.inputFrameBytes)
  }

  @Test
  fun pcmFallbackDownsamplesSixteenKilohertzInputToEightKilohertzOutput() {
    val encoder = PcmCallAudioEncoder(sampleRateHz = 16_000, outputSampleRateHz = 8_000)
    val input = pcmFrame(100, 200, 300, 400, 500, 600, 700, 800)

    val frame = encoder.encode(input).getOrThrow()!!

    assertEquals("audio/pcm;rate=8000;encoding=pcm16", frame.mimeType)
    assertEquals(listOf(100, 300, 500, 700), pcmSamples(frame.bytes))
    assertEquals(16_000, encoder.inputSampleRateHz)
    assertEquals(640, encoder.inputFrameBytes)
  }

  @Test
  fun pcmDecoderReadsSampleRateFromMimeType() {
    val decoder = PcmCallAudioDecoder()

    val pcm =
      decoder
        .decode(CallAudioFrame(byteArrayOf(1, 2, 3, 4), mimeType = "audio/pcm;rate=16000;encoding=pcm16"))
        .getOrThrow()!!

    assertEquals(16_000, pcm.sampleRateHz)
    assertEquals(listOf(1, 2, 3, 4), pcm.bytes.map { it.toInt() })
  }

  @Test
  fun opusMimeTypeIsTreatedAsStreamingCallAudio() {
    assertEquals(true, isStreamingCallAudioMimeType("audio/opus;rate=16000"))
  }

  private fun pcmFrame(vararg samples: Short): ByteArray {
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
      bytes[index * 2] = (sample.toInt() and 0xff).toByte()
      bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
    }
    return bytes
  }

  private fun pcmSamples(bytes: ByteArray): List<Int> =
    bytes.toList()
      .chunked(2)
      .map { pair ->
        val lo = pair[0].toInt() and 0xff
        val hi = pair[1].toInt()
        ((hi shl 8) or lo).toShort().toInt()
      }
}
