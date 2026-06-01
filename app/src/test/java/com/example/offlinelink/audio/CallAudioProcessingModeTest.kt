package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioProcessingModeTest {
  @Test
  fun defaultsToBalancedMode() {
    assertEquals(CallAudioProcessingMode.Balanced, callAudioProcessingModeFromName(null))
    assertEquals(CallAudioProcessingMode.Balanced, callAudioProcessingModeFromName("missing"))
  }

  @Test
  fun legacySavedSystemDefaultMigratesToBalancedUntilUserExplicitlySelectsMode() {
    assertEquals(CallAudioProcessingMode.Balanced, initialCallAudioProcessingMode("System", userSelected = false))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("System", userSelected = true))
  }
}
