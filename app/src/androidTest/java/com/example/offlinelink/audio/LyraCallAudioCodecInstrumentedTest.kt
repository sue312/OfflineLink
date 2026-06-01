package com.example.offlinelink.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyraCallAudioCodecInstrumentedTest {
  @Test
  fun lyraNativeCodecEncodesAndDecodesSingleFrame() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val encoder = requireNotNull(LyraCallAudioEncoder.createOrNull(context)) { "Lyra encoder is not available" }
    val decoder = requireNotNull(LyraCallAudioDecoder.createOrNull(context)) { "Lyra decoder is not available" }
    try {
      val pcmBytes = ByteArray(callAudioPcmFrameBytes(CALL_AUDIO_LYRA_SAMPLE_RATE_HZ))

      val encoded = encoder.encode(pcmBytes, pcmBytes.size).getOrThrow()

      assertNotNull(encoded)
      encoded!!
      assertEquals(CALL_AUDIO_LYRA_MIME_TYPE, encoded.mimeType)
      assertTrue(encoded.bytes.isNotEmpty())

      val decoded = decoder.decode(encoded).getOrThrow()

      assertNotNull(decoded)
      decoded!!
      assertEquals(CALL_AUDIO_LYRA_SAMPLE_RATE_HZ, decoded.sampleRateHz)
      assertEquals(pcmBytes.size, decoded.bytes.size)
    } finally {
      encoder.close()
      decoder.close()
    }
  }
}
