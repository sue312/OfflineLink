package com.example.offlinelink.audio

enum class CallAudioEncodingMode(
  val displayName: String,
  val description: String,
) {
  Opus(
    displayName = "Opus",
    description = "6.4 kbps, long range",
  ),
  Lyra(
    displayName = "Lyra",
    description = "3.2 kbps, lowest bitrate",
  ),
  Pcm16(
    displayName = "PCM16",
    description = "Uncompressed, highest bandwidth",
  ),
  ;

  companion object {
    val Default = Opus
  }
}

val selectableCallAudioEncodingModes =
  listOf(
    CallAudioEncodingMode.Opus,
    CallAudioEncodingMode.Lyra,
    CallAudioEncodingMode.Pcm16,
  )

fun callAudioEncodingModeFromName(name: String?): CallAudioEncodingMode =
  CallAudioEncodingMode.entries.firstOrNull { it.name == name } ?: CallAudioEncodingMode.Default

internal const val CALL_AUDIO_OPUS_FIXED_BITRATE_BPS = 6_400
