package com.example.offlinelink.audio

const val CALL_AUDIO_SAMPLE_RATE_HZ = 8_000
const val CALL_AUDIO_FRAME_DURATION_MS = 20
const val CALL_AUDIO_BYTES_PER_SAMPLE = 2
const val CALL_AUDIO_FRAME_BYTES = CALL_AUDIO_SAMPLE_RATE_HZ * CALL_AUDIO_BYTES_PER_SAMPLE * CALL_AUDIO_FRAME_DURATION_MS / 1_000
const val CALL_AUDIO_MIME_TYPE = "audio/pcm;rate=8000;encoding=pcm16"

data class CallAudioFrame(
  val bytes: ByteArray,
  val durationMs: Long = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
  val mimeType: String = CALL_AUDIO_MIME_TYPE,
)

fun isStreamingCallAudioMimeType(mimeType: String): Boolean =
  mimeType.trim().startsWith("audio/pcm", ignoreCase = true)
