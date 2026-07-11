package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioEncodingModeTest {
  @Test
  fun defaultsToOpusEncoding() {
    assertEquals(CallAudioEncodingMode.Opus, callAudioEncodingModeFromName(null))
    assertEquals(CallAudioEncodingMode.Opus, callAudioEncodingModeFromName("missing"))
  }

  @Test
  fun exposesSelectableEncodingModes() {
    assertEquals(
      listOf(CallAudioEncodingMode.Opus, CallAudioEncodingMode.Lyra, CallAudioEncodingMode.Pcm16),
      selectableCallAudioEncodingModes,
    )
  }

  @Test
  fun usesLowBitrateOpusForFixedEncoding() {
    assertEquals(6_400, CALL_AUDIO_OPUS_FIXED_BITRATE_BPS)
    assertEquals("6.4 kbps, long range", CallAudioEncodingMode.Opus.description)
  }
}
