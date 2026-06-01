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
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val playbackLock = Object()
  private val running = AtomicBoolean(false)
  private val noiseGate = CallAudioNoiseGate()
  private val playbackBuffer = CallAudioJitterBuffer()
  private val linkMonitor = CallAudioLinkMonitor()
  private var audioEncoder: CallAudioEncoder? = null
  private var audioDecoder: CallAudioDecoder = CallAudioCodecFactory.createDecoder(appContext)
  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var audioTrackSampleRateHz: Int? = null
  private var audioEffects: List<AudioEffect> = emptyList()
  private var diagnosticRecorder: CallAudioDiagnosticRecorder? = null
  private var captureThread: Thread? = null
  private var playbackThread: Thread? = null
  private var previousAudioMode: Int? = null
  private var previousSpeakerphoneOn: Boolean? = null
  private var previousBluetoothScoOn: Boolean? = null
  private var bluetoothScoStarted = false
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

  fun linkStats(): CallAudioLinkStats = linkMonitor.snapshot()

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

      val encoder = CallAudioCodecFactory.createEncoder(appContext) { linkMonitor.snapshot() }
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
          linkMonitor.reset()
          resetPlaybackBufferLocked()
          running.set(true)
          started = true
          track.play()
          record.startRecording()
          captureThread =
            Thread({ captureLoop(record, encoder, mode, diagnostics, onFrame) }, "OfflineLinkCallAudio").apply {
              isDaemon = true
              start()
            }
          playbackThread =
            Thread({ playbackLoop() }, "OfflineLinkCallPlayback").apply {
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
      if (!linkMonitor.shouldDecode(frame.sequenceNumber)) return@runCatching
      val pcmFrame = audioDecoder.decode(frame).getOrThrow() ?: return@runCatching
      diagnosticRecorder?.writeReceivedDecoded(pcmFrame.bytes, pcmFrame.bytes.size, pcmFrame.sampleRateHz)
      val playbackFrames = linkMonitor.process(pcmFrame, frame.sequenceNumber)
      if (playbackFrames.isEmpty()) return@runCatching
      synchronized(playbackLock) {
        playbackFrames.forEach { playbackBuffer.enqueue(it) }
        linkMonitor.recordBufferedDuration(playbackBuffer.bufferedDurationMs)
        playbackLock.notifyAll()
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
    val capture: Thread?
    val playback: Thread?
    synchronized(lock) {
      running.set(false)
      record = audioRecord
      track = audioTrack
      encoder = audioEncoder
      decoder = audioDecoder
      effects = audioEffects
      diagnostics = diagnosticRecorder
      capture = captureThread
      playback = playbackThread
      audioRecord = null
      audioTrack = null
      audioTrackSampleRateHz = null
      audioEncoder = null
      audioDecoder = CallAudioCodecFactory.createDecoder(appContext)
      audioEffects = emptyList()
      diagnosticRecorder = null
      captureThread = null
      playbackThread = null
    }
    synchronized(playbackLock) {
      playbackBuffer.reset()
      linkMonitor.recordBufferedDuration(0L)
      playbackLock.notifyAll()
    }

    runCatching { record?.stop() }
    if (capture != null && capture != Thread.currentThread()) {
      runCatching { capture.join(STOP_JOIN_TIMEOUT_MS) }
    }
    if (playback != null && playback != Thread.currentThread()) {
      runCatching { playback.join(STOP_JOIN_TIMEOUT_MS) }
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

  private fun playbackLoop() {
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    while (running.get()) {
      val frame =
        synchronized(playbackLock) {
          var readyFrame = playbackBuffer.pollReady()
          linkMonitor.recordBufferedDuration(playbackBuffer.bufferedDurationMs)
          while (readyFrame == null && running.get()) {
            playbackLock.wait(PLAYBACK_WAIT_TIMEOUT_MS)
            readyFrame = playbackBuffer.pollReady()
            linkMonitor.recordBufferedDuration(playbackBuffer.bufferedDurationMs)
          }
          readyFrame
        } ?: continue
      runCatching { writePlaybackFrame(frame) }
    }
  }

  private fun writePlaybackFrame(frame: PcmAudioFrame) {
    val track =
      synchronized(lock) {
        val player = ensurePlayerLocked(frame.sampleRateHz)
        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
          player.play()
        }
        player
      }
    var offset = 0
    while (offset < frame.bytes.size && running.get()) {
      val written = track.write(frame.bytes, offset, frame.bytes.size - offset, AudioTrack.WRITE_BLOCKING)
      check(written >= 0) { "Call audio playback failed: $written" }
      if (written == 0) {
        Thread.yield()
      } else {
        offset += written
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
      if (previousBluetoothScoOn == null) {
        @Suppress("DEPRECATION")
        previousBluetoothScoOn = manager.isBluetoothScoOn
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
    val bluetoothScoOn = previousBluetoothScoOn
    val communicationDevice = previousCommunicationDevice
    val communicationDeviceCaptured = previousCommunicationDeviceCaptured
    previousAudioMode = null
    previousSpeakerphoneOn = null
    previousBluetoothScoOn = null
    previousCommunicationDevice = null
    previousCommunicationDeviceCaptured = false
    runCatching {
      stopManagedBluetoothSco(manager)
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
      if (bluetoothScoOn != null) {
        @Suppress("DEPRECATION")
        manager.isBluetoothScoOn = bluetoothScoOn
      }
    }
  }

  @SuppressLint("MissingPermission")
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
      if (outputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
        startManagedBluetoothSco(manager)
      } else {
        stopManagedBluetoothSco(manager)
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
    val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
    return if (speakerEnabled) {
      outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    } else {
      outputs.firstOrNull { it.isBluetoothCallOutput() }
        ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
  }

  private fun AudioDeviceInfo.isBluetoothCallOutput(): Boolean =
    when (type) {
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
      -> true
      else ->
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

  @Suppress("DEPRECATION")
  private fun startManagedBluetoothSco(manager: AudioManager) {
    if (!hasBluetoothConnectPermission()) return
    if (!bluetoothScoStarted) {
      manager.startBluetoothSco()
      bluetoothScoStarted = true
    }
    manager.isBluetoothScoOn = true
  }

  @Suppress("DEPRECATION")
  private fun stopManagedBluetoothSco(manager: AudioManager) {
    if (!bluetoothScoStarted) return
    if (hasBluetoothConnectPermission()) {
      manager.stopBluetoothSco()
      manager.isBluetoothScoOn = false
    }
    bluetoothScoStarted = false
  }

  private fun hasBluetoothConnectPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

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

  private fun resetPlaybackBufferLocked() {
    synchronized(playbackLock) {
      playbackBuffer.reset()
      linkMonitor.recordBufferedDuration(0L)
      playbackLock.notifyAll()
    }
  }

  private companion object {
    private const val STOP_JOIN_TIMEOUT_MS = 250L
    private const val PLAYBACK_WAIT_TIMEOUT_MS = 40L
  }
}
