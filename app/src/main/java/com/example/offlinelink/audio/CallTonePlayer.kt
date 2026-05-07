package com.example.offlinelink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import com.example.offlinelink.model.CallToneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallTonePlayer(context: Context) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var currentMode = CallToneMode.None
  private var incomingPlayer: MediaPlayer? = null
  private var toneGenerator: ToneGenerator? = null
  private var toneJob: Job? = null

  @Synchronized
  fun play(mode: CallToneMode) {
    if (currentMode == mode) return
    stopLocked()
    currentMode = mode
    when (mode) {
      CallToneMode.Incoming -> startIncomingTone()
      CallToneMode.Outgoing -> startOutgoingTone()
      CallToneMode.None -> Unit
    }
  }

  @Synchronized
  fun stop() {
    stopLocked()
    currentMode = CallToneMode.None
  }

  fun release() {
    stop()
    scope.cancel()
  }

  private fun startIncomingTone() {
    if (!startIncomingRingtone()) {
      startRepeatingTone(
        toneType = ToneGenerator.TONE_SUP_RINGTONE,
        toneDurationMs = INCOMING_TONE_MS,
        repeatDelayMs = INCOMING_REPEAT_MS,
        streamType = AudioManager.STREAM_RING,
      )
    }
  }

  private fun startIncomingRingtone(): Boolean {
    val ringtoneUri =
      RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ?: return false
    val player = MediaPlayer()
    return runCatching {
      player.setDataSource(appContext, ringtoneUri)
      player.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build(),
      )
      player.isLooping = true
      player.prepare()
      player.start()
      incomingPlayer = player
      true
    }.getOrElse {
      runCatching { player.release() }
      false
    }
  }

  private fun startOutgoingTone() {
    startRepeatingTone(
      toneType = ToneGenerator.TONE_SUP_RINGTONE,
      toneDurationMs = OUTGOING_TONE_MS,
      repeatDelayMs = OUTGOING_REPEAT_MS,
      streamType = AudioManager.STREAM_VOICE_CALL,
    )
  }

  private fun startRepeatingTone(
    toneType: Int,
    toneDurationMs: Int,
    repeatDelayMs: Long,
    streamType: Int,
  ) {
    val generator = runCatching { ToneGenerator(streamType, TONE_VOLUME) }.getOrNull() ?: return
    toneGenerator = generator
    toneJob =
      scope.launch {
        while (isActive) {
          runCatching { generator.startTone(toneType, toneDurationMs) }
          delay(repeatDelayMs)
        }
      }
  }

  private fun stopLocked() {
    toneJob?.cancel()
    toneJob = null
    toneGenerator?.let { generator ->
      runCatching { generator.stopTone() }
      runCatching { generator.release() }
    }
    toneGenerator = null
    incomingPlayer?.let { player ->
      runCatching {
        if (player.isPlaying) {
          player.stop()
        }
      }
      runCatching { player.release() }
    }
    incomingPlayer = null
  }

  private companion object {
    private const val TONE_VOLUME = 80
    private const val INCOMING_TONE_MS = 1800
    private const val INCOMING_REPEAT_MS = 2600L
    private const val OUTGOING_TONE_MS = 1600
    private const val OUTGOING_REPEAT_MS = 3600L
  }
}
