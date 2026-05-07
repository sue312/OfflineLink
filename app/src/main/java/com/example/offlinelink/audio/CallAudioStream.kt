package com.example.offlinelink.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

data class CallAudioFrame(
  val bytes: ByteArray,
  val durationMs: Long = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
  val mimeType: String = CALL_AUDIO_MIME_TYPE,
)

class CallAudioStream(context: Context) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val lock = Any()
  private val running = AtomicBoolean(false)
  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var audioEffects: List<AudioEffect> = emptyList()
  private var captureThread: Thread? = null
  private var previousAudioMode: Int? = null
  private var previousSpeakerphoneOn: Boolean? = null
  private var previousCommunicationDevice: AudioDeviceInfo? = null
  private var previousCommunicationDeviceCaptured = false
  @Volatile private var muted = false
  @Volatile private var speakerEnabled = true

  @SuppressLint("MissingPermission")
  fun start(onFrame: (CallAudioFrame) -> Unit): Result<Unit> {
    synchronized(lock) {
      if (running.get()) return Result.success(Unit)
    }

    var newRecord: AudioRecord? = null
    var newTrack: AudioTrack? = null
    var started = false
    return runCatching {
      require(
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
      ) {
        "Microphone permission is required"
      }

      val record = createRecorder()
      val track = createPlayer()
      newRecord = record
      newTrack = track

      var releaseNewStreams = false
      synchronized(lock) {
        if (running.get()) {
          releaseNewStreams = true
        } else {
          audioRecord = record
          audioTrack = track
          configureAudioMode()
          audioEffects = createVoiceEffects(record)
          running.set(true)
          started = true
          track.play()
          record.startRecording()
          captureThread =
            Thread({ captureLoop(record, onFrame) }, "OfflineLinkCallAudio").apply {
              isDaemon = true
              start()
            }
        }
      }

      if (releaseNewStreams) {
        record.release()
        track.release()
      }
    }.onFailure {
      if (started) {
        stop()
      } else {
        releaseRecord(newRecord)
        releaseTrack(newTrack)
        synchronized(lock) {
          if (audioRecord === newRecord) audioRecord = null
          if (audioTrack === newTrack) audioTrack = null
          running.set(false)
        }
      }
    }
  }

  fun play(frame: CallAudioFrame): Result<Unit> =
    runCatching {
      if (frame.bytes.isEmpty()) return@runCatching
      synchronized(lock) {
        val track =
          audioTrack
            ?: createPlayer().also {
              audioTrack = it
              configureAudioMode()
            }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
          track.play()
        }
        val written = track.write(frame.bytes, 0, frame.bytes.size, AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "Call audio playback failed: $written" }
      }
    }

  fun setMuted(isMuted: Boolean) {
    muted = isMuted
  }

  fun setSpeakerEnabled(enabled: Boolean) {
    speakerEnabled = enabled
    synchronized(lock) {
      if (previousAudioMode != null || audioTrack != null) {
        applySpeakerRoute()
      }
    }
  }

  fun stop() {
    val record: AudioRecord?
    val track: AudioTrack?
    val effects: List<AudioEffect>
    val thread: Thread?
    synchronized(lock) {
      running.set(false)
      record = audioRecord
      track = audioTrack
      effects = audioEffects
      thread = captureThread
      audioRecord = null
      audioTrack = null
      audioEffects = emptyList()
      captureThread = null
    }

    runCatching { record?.stop() }
    if (thread != null && thread != Thread.currentThread()) {
      runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
    }
    releaseEffects(effects)
    releaseRecord(record)
    releaseTrack(track)
    restoreAudioMode()
  }

  private fun captureLoop(
    record: AudioRecord,
    onFrame: (CallAudioFrame) -> Unit,
  ) {
    val buffer = ByteArray(FRAME_BYTES)
    while (running.get()) {
      val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
      if (read > 0) {
        runCatching {
          if (!muted) {
            onFrame(CallAudioFrame(bytes = buffer.copyOf(read)))
          }
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun createRecorder(): AudioRecord {
    val minBuffer =
      AudioRecord.getMinBufferSize(
        CALL_AUDIO_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(FRAME_BYTES * 4)
    val record =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        CALL_AUDIO_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer,
      )
    check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone stream" }
    return record
  }

  private fun createPlayer(): AudioTrack {
    val minBuffer =
      AudioTrack.getMinBufferSize(
        CALL_AUDIO_SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(FRAME_BYTES * 8)
    val track =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CALL_AUDIO_SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setBufferSizeInBytes(minBuffer)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    check(track.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize call speaker stream" }
    applyPreferredOutput(track)
    return track
  }

  private fun configureAudioMode() {
    val manager = audioManager ?: return
    runCatching {
      if (previousAudioMode == null) {
        previousAudioMode = manager.mode
      }
      if (previousSpeakerphoneOn == null) {
        @Suppress("DEPRECATION")
        previousSpeakerphoneOn = manager.isSpeakerphoneOn
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !previousCommunicationDeviceCaptured) {
        previousCommunicationDevice = manager.communicationDevice
        previousCommunicationDeviceCaptured = true
      }
      manager.mode = AudioManager.MODE_IN_COMMUNICATION
      applySpeakerRoute()
    }
  }

  private fun restoreAudioMode() {
    val manager = audioManager ?: return
    val mode = previousAudioMode ?: return
    val speakerphoneOn = previousSpeakerphoneOn
    val communicationDevice = previousCommunicationDevice
    val communicationDeviceCaptured = previousCommunicationDeviceCaptured
    previousAudioMode = null
    previousSpeakerphoneOn = null
    previousCommunicationDevice = null
    previousCommunicationDeviceCaptured = false
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceCaptured) {
        if (communicationDevice != null) {
          manager.setCommunicationDevice(communicationDevice)
        } else {
          manager.clearCommunicationDevice()
        }
      }
      manager.mode = mode
      if (speakerphoneOn != null) {
        @Suppress("DEPRECATION")
        manager.isSpeakerphoneOn = speakerphoneOn
      }
    }
  }

  private fun applySpeakerRoute() {
    val manager = audioManager ?: return
    val outputDevice = preferredOutputDevice()
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (outputDevice != null) {
          manager.setCommunicationDevice(outputDevice)
        } else {
          manager.clearCommunicationDevice()
        }
      }
      @Suppress("DEPRECATION")
      manager.isSpeakerphoneOn = speakerEnabled
      audioTrack?.let(::applyPreferredOutput)
    }
  }

  private fun applyPreferredOutput(track: AudioTrack) {
    runCatching {
      track.preferredDevice = preferredOutputDevice()
    }
  }

  private fun preferredOutputDevice(): AudioDeviceInfo? {
    val targetType =
      if (speakerEnabled) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
      } else {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
      }
    return audioManager
      ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      ?.firstOrNull { it.type == targetType }
  }

  private fun createVoiceEffects(record: AudioRecord): List<AudioEffect> =
    listOfNotNull(
      createVoiceEffect(AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(record.audioSessionId) },
      createVoiceEffect(NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(record.audioSessionId) },
      createVoiceEffect(AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(record.audioSessionId) },
    )

  private fun <T : AudioEffect> createVoiceEffect(
    available: Boolean,
    factory: () -> T?,
  ): T? {
    if (!available) return null
    val effect = runCatching { factory() }.getOrNull() ?: return null
    runCatching {
      effect.enabled = true
    }
    return effect
  }

  private fun releaseEffects(effects: List<AudioEffect>) {
    effects.forEach { effect ->
      runCatching {
        effect.enabled = false
      }
      runCatching {
        effect.release()
      }
    }
  }

  private fun releaseRecord(record: AudioRecord?) {
    record ?: return
    runCatching { record.release() }
  }

  private fun releaseTrack(track: AudioTrack?) {
    track ?: return
    runCatching { track.pause() }
    runCatching { track.flush() }
    runCatching { track.stop() }
    runCatching { track.release() }
  }

  private companion object {
    private const val BYTES_PER_SAMPLE = 2
    private const val FRAME_BYTES = CALL_AUDIO_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * CALL_AUDIO_FRAME_DURATION_MS / 1_000
    private const val STOP_JOIN_TIMEOUT_MS = 250L
  }
}
