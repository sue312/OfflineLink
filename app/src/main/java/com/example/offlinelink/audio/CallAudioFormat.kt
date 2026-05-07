package com.example.offlinelink.audio

const val CALL_AUDIO_SAMPLE_RATE_HZ = 8_000
const val CALL_AUDIO_FRAME_DURATION_MS = 40
const val CALL_AUDIO_MIME_TYPE = "audio/pcm;rate=8000;encoding=pcm16"

fun isStreamingCallAudioMimeType(mimeType: String): Boolean =
  mimeType.trim().startsWith("audio/pcm", ignoreCase = true)
