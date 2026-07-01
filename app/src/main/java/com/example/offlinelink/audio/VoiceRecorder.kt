package com.example.offlinelink.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

internal const val VOICE_MESSAGE_SAMPLE_RATE_HZ = CALL_AUDIO_OPUS_SAMPLE_RATE_HZ
internal const val VOICE_MESSAGE_OPUS_BITRATE_BPS = CALL_AUDIO_OPUS_FIXED_BITRATE_BPS
internal const val VOICE_MESSAGE_MAX_DURATION_MS = 5_000
internal const val VOICE_MESSAGE_MIME_TYPE = CALL_AUDIO_OPUS_MIME_TYPE
private const val VOICE_RECORDER_TAG = "VoiceRecorder"

data class RecordedVoiceClip(
  val bytes: ByteArray,
  val durationMs: Long,
  val mimeType: String = VOICE_MESSAGE_MIME_TYPE,
)

class VoiceRecorder(context: Context) {
  private val appContext = context.applicationContext
  private var session: RecordingSession? = null

  @SuppressLint("MissingPermission")
  fun start(): Result<Unit> =
    runCatching {
      cancel()
      require(
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
      ) {
        "Microphone permission is required"
      }
      val encoder =
        MediaCodecOpusCallAudioEncoder.createOrNull(VOICE_MESSAGE_OPUS_BITRATE_BPS)
          ?: error("Opus encoder is not available")
      val record = createAudioRecord(encoder.inputSampleRateHz, encoder.inputFrameBytes)
      val running = AtomicBoolean(true)
      val frames = mutableListOf<CallAudioFrame>()
      val startedAt = System.currentTimeMillis()
      record.startRecording()
      val captureThread =
        Thread({ captureLoop(record, encoder, running, frames, startedAt) }, "OfflineLinkVoiceMessage").apply {
          isDaemon = true
          start()
        }
      session =
        RecordingSession(
          record = record,
          encoder = encoder,
          running = running,
          frames = frames,
          thread = captureThread,
          startedAtMs = startedAt,
        )
    }

  fun stop(): Result<RecordedVoiceClip> =
    runCatching {
      val activeSession = session ?: error("Recorder is not running")
      session = null
      activeSession.running.set(false)
      runCatching { activeSession.record.stop() }
      activeSession.thread.join(STOP_JOIN_TIMEOUT_MS)
      activeSession.record.release()
      activeSession.encoder.close()
      val durationMs = (System.currentTimeMillis() - activeSession.startedAtMs).coerceAtLeast(1L)
      val clippedDurationMs = durationMs.coerceAtMost(VOICE_MESSAGE_MAX_DURATION_MS.toLong())
      val frames = synchronized(activeSession.frames) { activeSession.frames.toList() }
      require(frames.isNotEmpty()) { "Voice message recording produced no Opus frames" }
      val bytes = VoiceMessageFrameCodec.encode(frames)
      logVoiceRecorderDebug(
        "Voice message recorded opusFrames=${frames.size} bytes=${bytes.size} durationMs=$clippedDurationMs " +
          "mime=$VOICE_MESSAGE_MIME_TYPE bitrate=$VOICE_MESSAGE_OPUS_BITRATE_BPS",
      )
      RecordedVoiceClip(bytes = bytes, durationMs = clippedDurationMs)
    }.onFailure {
      cleanup()
    }

  fun cancel() {
    cleanup()
  }

  private fun cleanup() {
    val activeSession = session
    session = null
    if (activeSession != null) {
      activeSession.running.set(false)
      runCatching { activeSession.record.stop() }
      runCatching { activeSession.thread.join(STOP_JOIN_TIMEOUT_MS) }
      activeSession.record.release()
      activeSession.encoder.close()
    }
  }

  @SuppressLint("MissingPermission")
  private fun createAudioRecord(
    sampleRateHz: Int,
    frameBytes: Int,
  ): AudioRecord {
    val minBuffer =
      AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(frameBytes * 4)
    val record =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer,
      )
    check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone recorder" }
    return record
  }

  private fun captureLoop(
    record: AudioRecord,
    encoder: CallAudioEncoder,
    running: AtomicBoolean,
    frames: MutableList<CallAudioFrame>,
    startedAtMs: Long,
  ) {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    val buffer = ByteArray(encoder.inputFrameBytes)
    while (running.get() && System.currentTimeMillis() - startedAtMs < VOICE_MESSAGE_MAX_DURATION_MS) {
      val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
      if (read <= 0) continue
      val frame = encoder.encode(buffer, read).getOrNull() ?: continue
      if (frame.mimeType.startsWith("audio/opus", ignoreCase = true)) {
        synchronized(frames) {
          if (frames.size < MAX_VOICE_MESSAGE_OPUS_FRAMES) {
            frames.add(frame)
          }
        }
      }
    }
    running.set(false)
    runCatching { record.stop() }
  }

  private data class RecordingSession(
    val record: AudioRecord,
    val encoder: CallAudioEncoder,
    val running: AtomicBoolean,
    val frames: MutableList<CallAudioFrame>,
    val thread: Thread,
    val startedAtMs: Long,
  )
}

private fun logVoiceRecorderDebug(message: String) {
  runCatching { Log.d(VOICE_RECORDER_TAG, message) }
}

private const val MAX_VOICE_MESSAGE_OPUS_FRAMES = VOICE_MESSAGE_MAX_DURATION_MS / CALL_AUDIO_FRAME_DURATION_MS
private const val STOP_JOIN_TIMEOUT_MS = 1_000L
