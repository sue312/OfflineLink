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
  fun visibleModesAreLimitedToSystemAndLongRange() {
    assertEquals(
      listOf(CallAudioProcessingMode.System, CallAudioProcessingMode.LongRange),
      selectableCallAudioProcessingModes,
    )
  }

  @Test
  fun legacySavedDetailedModesMigrateToSystem() {
    assertEquals(CallAudioProcessingMode.LongRange, initialCallAudioProcessingMode("System", userSelected = false))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Raw", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("System", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Natural", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Strong", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Balanced", userSelected = true))
    assertEquals(CallAudioProcessingMode.LongRange, initialCallAudioProcessingMode("LongRange", userSelected = true))
  }

  @Test
  fun longRangeModeUsesReliableEncodingWithoutDroppingFramesByDefault() {
    assertEquals(CallAudioProcessingMode.LongRange, callAudioProcessingModeFromName("LongRange"))
    assertTrue(CallAudioProcessingMode.LongRange.forceReliableEncoding)
    assertEquals(CallAudioInputProcessorProfiles.Balanced, CallAudioProcessingMode.LongRange.inputProcessorProfile)
    assertEquals(1, CallAudioProcessingMode.LongRange.baseTransmitFrameInterval)
  }
}
