package com.example.offlinelink.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

data class RecordedVoiceClip(
  val bytes: ByteArray,
  val durationMs: Long,
  val mimeType: String = "audio/3gpp",
)

class VoiceRecorder(context: Context) {
  private val cacheDir = context.applicationContext.cacheDir
  private var recorder: MediaRecorder? = null
  private var outputFile: File? = null
  private var startedAt: Long = 0L

  fun start(): Result<Unit> =
    runCatching {
      cancel()
      val file = File.createTempFile("offlinelink-voice-", ".3gp", cacheDir)
      @Suppress("DEPRECATION")
      val mediaRecorder =
        MediaRecorder().apply {
          setAudioSource(MediaRecorder.AudioSource.MIC)
          setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
          setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
          setAudioEncodingBitRate(12_200)
          setAudioSamplingRate(8_000)
          setMaxDuration(MAX_DURATION_MS)
          setOutputFile(file.absolutePath)
          prepare()
          start()
        }
      outputFile = file
      recorder = mediaRecorder
      startedAt = System.currentTimeMillis()
    }

  fun stop(): Result<RecordedVoiceClip> =
    runCatching {
      val mediaRecorder = recorder ?: error("Recorder is not running")
      val file = outputFile ?: error("Recorder output is missing")
      val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
      try {
        mediaRecorder.stop()
      } finally {
        mediaRecorder.release()
        recorder = null
        outputFile = null
      }
      val bytes = file.readBytes()
      file.delete()
      RecordedVoiceClip(bytes = bytes, durationMs = durationMs.coerceAtMost(MAX_DURATION_MS.toLong()))
    }.onFailure {
      cleanup()
    }

  fun cancel() {
    cleanup()
  }

  private fun cleanup() {
    runCatching {
      recorder?.stop()
    }
    recorder?.release()
    recorder = null
    outputFile?.delete()
    outputFile = null
    startedAt = 0L
  }

  private companion object {
    const val MAX_DURATION_MS = 10_000
    const val VOICE_MIME_TYPE = "audio/3gpp"
  }
}
