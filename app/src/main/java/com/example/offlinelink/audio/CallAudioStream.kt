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
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CallAudioStream(context: Context) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val lock = Any()
  private val running = AtomicBoolean(false)
  private val noiseGate = CallAudioNoiseGate()
  private var audioEncoder: CallAudioEncoder? = null
  private var audioDecoder: CallAudioDecoder = CallAudioCodecFactory.createDecoder()
  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var audioTrackSampleRateHz: Int? = null
  private var audioEffects: List<AudioEffect> = emptyList()
  private var diagnosticRecorder: CallAudioDiagnosticRecorder? = null
  private var captureThread: Thread? = null
  private var previousAudioMode: Int? = null
  private var previousSpeakerphoneOn: Boolean? = null
  private var previousCommunicationDevice: AudioDeviceInfo? = null
  private var previousCommunicationDeviceCaptured = false
  @Volatile private var muted = false
  @Volatile private var speakerEnabled = true
  @Volatile private var processingMode = CallAudioProcessingMode.Default
  @Volatile private var diagnosticsEnabled = false

  fun setProcessingMode(mode: CallAudioProcessingMode) {
    processingMode = mode
  }

  fun setDiagnosticsEnabled(enabled: Boolean) {
    diagnosticsEnabled = enabled
  }

  fun diagnosticsDirectoryPath(): String = diagnosticsDirectory().absolutePath

  @SuppressLint("MissingPermission")
  fun start(onFrame: (CallAudioFrame) -> Unit): Result<Unit> {
    synchronized(lock) {
      if (running.get()) return Result.success(Unit)
    }

    var newRecord: AudioRecord? = null
    var newTrack: AudioTrack? = null
    var newEncoder: CallAudioEncoder? = null
    var newDiagnostics: CallAudioDiagnosticRecorder? = null
    var started = false
    return runCatching {
      require(
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
      ) {
        "Microphone permission is required"
      }

      val encoder = CallAudioCodecFactory.createEncoder()
      val mode = processingMode
      val record = createRecorder(encoder.inputSampleRateHz, encoder.inputFrameBytes)
      val track = createPlayer(encoder.inputSampleRateHz)
      val diagnostics = createDiagnosticRecorder(encoder.inputSampleRateHz, mode)
      newEncoder = encoder
      newRecord = record
      newTrack = track
      newDiagnostics = diagnostics

      var releaseNewStreams = false
      synchronized(lock) {
        if (running.get()) {
          releaseNewStreams = true
        } else {
          audioRecord = record
          audioTrack = track
          audioTrackSampleRateHz = encoder.inputSampleRateHz
          audioEncoder = encoder
          configureAudioMode()
          audioEffects = if (mode.useSystemEffects) createVoiceEffects(record) else emptyList()
          diagnosticRecorder = diagnostics
          noiseGate.reset()
          running.set(true)
          started = true
          track.play()
          record.startRecording()
          captureThread =
            Thread({ captureLoop(record, encoder, mode, diagnostics, onFrame) }, "OfflineLinkCallAudio").apply {
              isDaemon = true
              start()
            }
        }
      }

      if (releaseNewStreams) {
        diagnostics?.close()
        encoder.close()
        record.release()
        track.release()
      }
    }.onFailure {
      if (started) {
        stop()
      } else {
        newEncoder?.close()
        newDiagnostics?.close()
        releaseRecord(newRecord)
        releaseTrack(newTrack)
        synchronized(lock) {
          if (audioRecord === newRecord) audioRecord = null
          if (audioTrack === newTrack) audioTrack = null
          if (audioEncoder === newEncoder) audioEncoder = null
          if (audioTrack === newTrack) audioTrackSampleRateHz = null
          if (diagnosticRecorder === newDiagnostics) diagnosticRecorder = null
          running.set(false)
        }
      }
    }
  }

  fun play(frame: CallAudioFrame): Result<Unit> =
    runCatching {
      if (frame.bytes.isEmpty()) return@runCatching
      val pcmFrame = audioDecoder.decode(frame).getOrThrow() ?: return@runCatching
      diagnosticRecorder?.writeReceivedDecoded(pcmFrame.bytes, pcmFrame.bytes.size, pcmFrame.sampleRateHz)
      synchronized(lock) {
        val track = ensurePlayerLocked(pcmFrame.sampleRateHz)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
          track.play()
        }
        val written = track.write(pcmFrame.bytes, 0, pcmFrame.bytes.size, AudioTrack.WRITE_NON_BLOCKING)
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
    val encoder: CallAudioEncoder?
    val decoder: CallAudioDecoder
    val effects: List<AudioEffect>
    val diagnostics: CallAudioDiagnosticRecorder?
    val thread: Thread?
    synchronized(lock) {
      running.set(false)
      record = audioRecord
      track = audioTrack
      encoder = audioEncoder
      decoder = audioDecoder
      effects = audioEffects
      diagnostics = diagnosticRecorder
      thread = captureThread
      audioRecord = null
      audioTrack = null
      audioTrackSampleRateHz = null
      audioEncoder = null
      audioDecoder = CallAudioCodecFactory.createDecoder()
      audioEffects = emptyList()
      diagnosticRecorder = null
      captureThread = null
    }

    runCatching { record?.stop() }
    if (thread != null && thread != Thread.currentThread()) {
      runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
    }
    releaseEffects(effects)
    diagnostics?.close()
    encoder?.close()
    decoder.close()
    releaseRecord(record)
    releaseTrack(track)
    restoreAudioMode()
  }

  private fun captureLoop(
    record: AudioRecord,
    initialEncoder: CallAudioEncoder,
    mode: CallAudioProcessingMode,
    diagnostics: CallAudioDiagnosticRecorder?,
    onFrame: (CallAudioFrame) -> Unit,
  ) {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    var encoder = initialEncoder
    val inputProcessor = mode.inputProcessorProfile?.let { CallAudioInputProcessor(encoder.inputSampleRateHz, it) }
    val buffer = ByteArray(encoder.inputFrameBytes)
    while (running.get()) {
      val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
      if (read > 0) {
        runCatching {
          diagnostics?.writeCaptureRaw(buffer, read)
          if (muted) {
            noiseGate.reset()
            inputProcessor?.reset()
          } else {
            inputProcessor?.process(buffer, read)
            diagnostics?.writeCaptureProcessed(buffer, read)
            if (!noiseGate.shouldTransmit(buffer, read)) return@runCatching
            val encodedFrame =
              encoder.encode(buffer, read).getOrElse {
                val fallback =
                  PcmCallAudioEncoder(
                    sampleRateHz = encoder.inputSampleRateHz,
                    outputSampleRateHz = CALL_AUDIO_SAMPLE_RATE_HZ,
                  )
                encoder.close()
                synchronized(lock) {
                  if (audioEncoder === encoder) {
                    audioEncoder = fallback
                  }
                }
                encoder = fallback
                encoder.encode(buffer, read).getOrNull()
              }
            if (encodedFrame != null) {
              onFrame(encodedFrame)
            }
          }
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun createRecorder(sampleRateHz: Int, frameBytes: Int): AudioRecord {
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
    check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone stream" }
    return record
  }

  private fun createPlayer(sampleRateHz: Int): AudioTrack {
    val frameBytes = callAudioPcmFrameBytes(sampleRateHz)
    val minBuffer =
      AudioTrack.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(frameBytes * 4)
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
            .setSampleRate(sampleRateHz)
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

  private fun ensurePlayerLocked(sampleRateHz: Int): AudioTrack {
    val existingTrack = audioTrack
    if (existingTrack != null && audioTrackSampleRateHz == sampleRateHz) {
      return existingTrack
    }
    releaseTrack(existingTrack)
    return createPlayer(sampleRateHz).also {
      audioTrack = it
      audioTrackSampleRateHz = sampleRateHz
      configureAudioMode()
    }
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

  private fun createDiagnosticRecorder(
    sampleRateHz: Int,
    mode: CallAudioProcessingMode,
  ): CallAudioDiagnosticRecorder? =
    if (diagnosticsEnabled) {
      runCatching { CallAudioDiagnosticRecorder.start(diagnosticsDirectory(), sampleRateHz, mode) }.getOrNull()
    } else {
      null
    }

  private fun diagnosticsDirectory(): File =
    File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "call-audio-diagnostics")

  private companion object {
    private const val STOP_JOIN_TIMEOUT_MS = 250L
  }
}
