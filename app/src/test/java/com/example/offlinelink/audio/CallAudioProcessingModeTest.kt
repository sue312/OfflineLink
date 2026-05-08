package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioProcessingModeTest {
  @Test
  fun defaultsToSystemMode() {
    assertEquals(CallAudioProcessingMode.System, callAudioProcessingModeFromName(null))
    assertEquals(CallAudioProcessingMode.System, callAudioProcessingModeFromName("missing"))
  }
}
