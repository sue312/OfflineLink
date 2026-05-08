package com.example.offlinelink.audio

const val CALL_AUDIO_SAMPLE_RATE_HZ = 8_000
const val CALL_AUDIO_OPUS_SAMPLE_RATE_HZ = 16_000
const val CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ = 16_000
const val CALL_AUDIO_FRAME_DURATION_MS = 20
const val CALL_AUDIO_BYTES_PER_SAMPLE = 2
const val CALL_AUDIO_FRAME_BYTES = CALL_AUDIO_SAMPLE_RATE_HZ * CALL_AUDIO_BYTES_PER_SAMPLE * CALL_AUDIO_FRAME_DURATION_MS / 1_000
const val CALL_AUDIO_MIME_TYPE = "audio/pcm;rate=8000;encoding=pcm16"
const val CALL_AUDIO_OPUS_MIME_TYPE = "audio/opus;rate=16000"
const val CALL_AUDIO_AMR_WB_MIME_TYPE = "audio/amr-wb;rate=16000"

data class CallAudioFrame(
  val bytes: ByteArray,
  val durationMs: Long = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
  val mimeType: String = CALL_AUDIO_MIME_TYPE,
)

fun isStreamingCallAudioMimeType(mimeType: String): Boolean =
  mimeType.trim().let {
    it.startsWith("audio/pcm", ignoreCase = true) ||
      it.startsWith("audio/opus", ignoreCase = true) ||
      it.startsWith("audio/amr-wb", ignoreCase = true)
  }
