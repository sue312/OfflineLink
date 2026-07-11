package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioProcessingModeTest {
  @Test
  fun defaultsToSystemMode() {
    assertEquals(CallAudioProcessingMode.System, callAudioProcessingModeFromName(null))
    assertEquals(CallAudioProcessingMode.System, callAudioProcessingModeFromName("missing"))
  }

  @Test
  fun visibleModesAreLimitedToSystem() {
    assertEquals(
      listOf(CallAudioProcessingMode.System),
      selectableCallAudioProcessingModes,
    )
  }

  @Test
  fun legacySavedDetailedModesMigrateToSystem() {
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("System", userSelected = false))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Raw", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("System", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Natural", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Strong", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("Balanced", userSelected = true))
    assertEquals(CallAudioProcessingMode.System, initialCallAudioProcessingMode("LongRange", userSelected = true))
  }

  @Test
  fun systemModeUsesOnlyAndroidVoiceEffects() {
    assertEquals(CallAudioProcessingMode.System, callAudioProcessingModeFromName("System"))
    assertEquals(true, CallAudioProcessingMode.System.useSystemEffects)
    assertEquals(null, CallAudioProcessingMode.System.inputProcessorProfile)
    assertEquals(false, CallAudioProcessingMode.System.forceReliableEncoding)
    assertEquals(1, CallAudioProcessingMode.System.baseTransmitFrameInterval)
  }
}
