package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecorderConfigTest {
  @Test
  fun usesOpusSmallFrameVoiceMessageRecordingDefaults() {
    assertEquals(CALL_AUDIO_OPUS_MIME_TYPE, VOICE_MESSAGE_MIME_TYPE)
    assertEquals(CALL_AUDIO_OPUS_SAMPLE_RATE_HZ, VOICE_MESSAGE_SAMPLE_RATE_HZ)
    assertEquals(CALL_AUDIO_OPUS_FIXED_BITRATE_BPS, VOICE_MESSAGE_OPUS_BITRATE_BPS)
    assertEquals(5_000, VOICE_MESSAGE_MAX_DURATION_MS)
  }
}
