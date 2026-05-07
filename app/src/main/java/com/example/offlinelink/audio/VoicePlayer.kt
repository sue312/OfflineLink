package com.example.offlinelink.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File

class VoicePlayer(context: Context) {
  private val cacheDir = context.applicationContext.cacheDir
  private var player: MediaPlayer? = null
  private var playbackFile: File? = null

  fun play(bytes: ByteArray): Result<Unit> =
    runCatching {
      stop()
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
  }
}
