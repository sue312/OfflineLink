package com.example.offlinelink.audio

enum class CallAudioProcessingMode(
  val displayName: String,
  val description: String,
  val useSystemEffects: Boolean,
  val inputProcessorProfile: CallAudioInputProcessorProfile?,
  val forceReliableEncoding: Boolean = false,
  val baseTransmitFrameInterval: Int = 1,
) {
  Raw(
    displayName = "Raw",
    description = "No Android effects or app processing",
    useSystemEffects = false,
    inputProcessorProfile = null,
  ),
  System(
    displayName = "System",
    description = "Recommended, Android voice effects only",
    useSystemEffects = true,
    inputProcessorProfile = null,
  ),
  Natural(
    displayName = "Natural",
    description = "Light cleanup, fuller voice",
    useSystemEffects = true,
    inputProcessorProfile = CallAudioInputProcessorProfiles.Natural,
  ),
  Balanced(
    displayName = "Balanced",
    description = "Current tuned call mode",
    useSystemEffects = true,
    inputProcessorProfile = CallAudioInputProcessorProfiles.Balanced,
  ),
  Strong(
    displayName = "Strong",
    description = "More noise and hiss control",
    useSystemEffects = true,
    inputProcessorProfile = CallAudioInputProcessorProfiles.Strong,
  ),
  LongRange(
    displayName = "Long range",
    description = "Reliable voice, adaptive rate",
    useSystemEffects = true,
    inputProcessorProfile = CallAudioInputProcessorProfiles.Balanced,
    forceReliableEncoding = true,
    baseTransmitFrameInterval = 1,
  ),
  ;

  companion object {
    val Default = LongRange
  }
}

val selectableCallAudioProcessingModes =
  listOf(
    CallAudioProcessingMode.System,
    CallAudioProcessingMode.LongRange,
  )

fun callAudioProcessingModeFromName(name: String?): CallAudioProcessingMode {
  val mode = CallAudioProcessingMode.entries.firstOrNull { it.name == name } ?: return CallAudioProcessingMode.Default
  return if (mode in selectableCallAudioProcessingModes) mode else CallAudioProcessingMode.System
}

fun initialCallAudioProcessingMode(
  savedName: String?,
  userSelected: Boolean,
): CallAudioProcessingMode =
  if (userSelected) {
    callAudioProcessingModeFromName(savedName)
  } else {
    CallAudioProcessingMode.Default
  }
