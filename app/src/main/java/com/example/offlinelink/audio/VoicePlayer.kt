package com.example.offlinelink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VoicePlayer(context: Context) {
  private val appContext = context.applicationContext
  private val cacheDir = context.applicationContext.cacheDir
  private val framePlaybackRunning = AtomicBoolean(false)
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val framePlaybackLock = Object()
  private var player: MediaPlayer? = null
  private var playbackFile: File? = null
  private var framePlaybackThread: Thread? = null
  private var audioTrack: AudioTrack? = null
  private var audioTrackSampleRateHz: Int? = null
  private var playbackProcessor: CallAudioPlaybackProcessor? = null
  private var playbackProcessorSampleRateHz: Int? = null

  fun play(
    bytes: ByteArray,
    mimeType: String = LEGACY_VOICE_MIME_TYPE,
  ): Result<Unit> =
    runCatching {
      stop()
      if (mimeType.startsWith("audio/opus", ignoreCase = true)) {
        playOpusFrames(bytes)
        return@runCatching
      }
      val file = File.createTempFile("offlinelink-playback-", ".3gp", cacheDir)
      file.writeBytes(bytes)
      val mediaPlayer =
        MediaPlayer().apply {
          setDataSource(file.absolutePath)
          setOnCompletionListener {
            this@VoicePlayer.stop()
          }
          setOnErrorListener { _, _, _ ->
            this@VoicePlayer.stop()
            true
          }
          prepare()
          start()
        }
      playbackFile = file
      player = mediaPlayer
    }

  fun stop() {
    player?.release()
    player = null
    playbackFile?.delete()
    playbackFile = null
    framePlaybackRunning.set(false)
    val track = synchronized(framePlaybackLock) {
      audioTrack.also {
        audioTrack = null
        audioTrackSampleRateHz = null
        playbackProcessor = null
        playbackProcessorSampleRateHz = null
      }
    }
    runCatching { track?.pause() }
    runCatching { track?.flush() }
    runCatching { track?.release() }
    val thread = framePlaybackThread
    if (thread != null && thread !== Thread.currentThread()) {
      runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
    }
    if (framePlaybackThread === thread) {
      framePlaybackThread = null
    }
  }

  private fun playOpusFrames(bytes: ByteArray) {
    val frames = VoiceMessageFrameCodec.decode(bytes)
    framePlaybackRunning.set(true)
    framePlaybackThread =
      Thread({ playOpusFramesLoop(frames) }, "OfflineLinkVoicePlayback").apply {
        isDaemon = true
        start()
      }
  }

  private fun playOpusFramesLoop(frames: List<CallAudioFrame>) {
    val decoder = CallAudioCodecFactory.createDecoder(appContext)
    try {
      frames.forEach { frame ->
        if (!framePlaybackRunning.get()) return
        val pcmFrame = decoder.decode(frame).getOrThrow() ?: return@forEach
        writePcmFrame(pcmFrame)
      }
    } finally {
      decoder.close()
      framePlaybackRunning.set(false)
      synchronized(framePlaybackLock) {
        audioTrack?.release()
        audioTrack = null
        audioTrackSampleRateHz = null
        playbackProcessor = null
        playbackProcessorSampleRateHz = null
      }
    }
  }

  private fun writePcmFrame(frame: PcmAudioFrame) {
    val playbackFrame = processPlaybackFrame(frame)
    val track = ensureAudioTrack(playbackFrame.sampleRateHz)
    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
      track.play()
    }
    var offset = 0
    while (offset < playbackFrame.bytes.size && framePlaybackRunning.get()) {
      val written = track.write(playbackFrame.bytes, offset, playbackFrame.bytes.size - offset, AudioTrack.WRITE_BLOCKING)
      check(written >= 0) { "Voice message playback failed: $written" }
      if (written == 0) {
        Thread.yield()
      } else {
        offset += written
      }
    }
  }

  private fun processPlaybackFrame(frame: PcmAudioFrame): PcmAudioFrame =
    synchronized(framePlaybackLock) {
      val existingProcessor = playbackProcessor
      val processor =
        if (existingProcessor == null || playbackProcessorSampleRateHz != frame.sampleRateHz) {
          CallAudioPlaybackProcessor(frame.sampleRateHz).also {
            playbackProcessor = it
            playbackProcessorSampleRateHz = frame.sampleRateHz
          }
        } else {
          existingProcessor
        }
      processor.process(frame)
    }

  private fun ensureAudioTrack(sampleRateHz: Int): AudioTrack =
    synchronized(framePlaybackLock) {
      val existing = audioTrack
      if (existing != null && audioTrackSampleRateHz == sampleRateHz) return existing
      existing?.release()
      createAudioTrack(sampleRateHz).also {
        audioTrack = it
        audioTrackSampleRateHz = sampleRateHz
      }
    }

  private fun createAudioTrack(sampleRateHz: Int): AudioTrack {
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
    check(track.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize voice message speaker stream" }
    return track
  }

  private companion object {
    const val LEGACY_VOICE_MIME_TYPE = "audio/3gpp"
    const val STOP_JOIN_TIMEOUT_MS = 1_000L
  }
}
