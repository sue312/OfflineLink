package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioProcessingModeTest {
  @Test
  fun defaultsToLongRangeMode() {
    assertEquals(CallAudioProcessingMode.LongRange, callAudioProcessingModeFromName(null))
    assertEquals(CallAudioProcessingMode.LongRange, callAudioProcessingModeFromName("missing"))
  }

  @Test
  fun legacySavedSystemDefaultMigratesToLongRangeUntilUserExplicitlySelectsMode() {
    assertEquals(CallAudioProcessingMode.LongRange, initialCallAudioProcessingMode("System", userSelected = false))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("System", userSelected = true))
  }

  @Test
  fun longRangeModeUsesReliableEncodingAndLowerBaseFrameRate() {
    assertEquals(CallAudioProcessingMode.LongRange, callAudioProcessingModeFromName("LongRange"))
    assertTrue(CallAudioProcessingMode.LongRange.forceReliableEncoding)
    assertEquals(2, CallAudioProcessingMode.LongRange.baseTransmitFrameInterval)
  }
}
