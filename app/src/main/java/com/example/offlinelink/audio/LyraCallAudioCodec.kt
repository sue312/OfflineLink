package com.example.offlinelink.audio

import android.content.Context
import kotlin.math.min

class LyraCallAudioEncoder private constructor(
  private var nativeHandle: Long,
) : CallAudioEncoder {
  override val inputSampleRateHz: Int = CALL_AUDIO_LYRA_SAMPLE_RATE_HZ
  override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

  private val pendingPcm = ByteArray(inputFrameBytes)
  private var pendingPcmLength = 0

  override fun encode(
    pcmBytes: ByteArray,
    length: Int,
  ): Result<CallAudioFrame?> =
    runCatching {
      val handle = nativeHandle
      check(handle != 0L) { "Lyra encoder is closed" }
      val inputLength = length.coerceIn(0, pcmBytes.size)
      val copyLength = min(inputLength, inputFrameBytes - pendingPcmLength)
      if (copyLength > 0) {
        System.arraycopy(pcmBytes, 0, pendingPcm, pendingPcmLength, copyLength)
        pendingPcmLength += copyLength
      }
      if (pendingPcmLength < inputFrameBytes) return@runCatching null

      pendingPcmLength = 0
      val encoded = LyraNative.encode(handle, pendingPcm, pendingPcm.size) ?: return@runCatching null
      CallAudioFrame(
        bytes = encoded,
        durationMs = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
        mimeType = CALL_AUDIO_LYRA_MIME_TYPE,
      )
    }

  override fun close() {
    val handle = nativeHandle
    if (handle != 0L) {
      runCatching { LyraNative.destroyEncoder(handle) }
      nativeHandle = 0L
    }
  }

  companion object {
    fun createOrNull(context: Context?): LyraCallAudioEncoder? {
      val appContext = context?.applicationContext ?: return null
      if (!LyraNative.isAvailable) return null
      val modelPath = LyraModelStore.modelPath(appContext) ?: return null
      val handle =
        runCatching {
          LyraNative.createEncoder(
            CALL_AUDIO_LYRA_SAMPLE_RATE_HZ,
            CALL_AUDIO_LYRA_BITRATE_BPS,
            modelPath,
          )
        }.getOrDefault(0L)
      if (handle == 0L) return null
      return LyraCallAudioEncoder(handle)
    }
  }
}

class LyraCallAudioDecoder private constructor(
  private var nativeHandle: Long,
) : CallAudioDecoder {
  override fun decode(frame: CallAudioFrame): Result<PcmAudioFrame?> =
    runCatching {
      val handle = nativeHandle
      check(handle != 0L) { "Lyra decoder is closed" }
      val pcmBytes = LyraNative.decode(handle, frame.bytes, LYRA_SAMPLES_PER_FRAME) ?: return@runCatching null
      PcmAudioFrame(
        bytes = pcmBytes,
        sampleRateHz = CALL_AUDIO_LYRA_SAMPLE_RATE_HZ,
      )
    }

  override fun close() {
    val handle = nativeHandle
    if (handle != 0L) {
      runCatching { LyraNative.destroyDecoder(handle) }
      nativeHandle = 0L
    }
  }

  companion object {
    fun createOrNull(context: Context?): LyraCallAudioDecoder? {
      val appContext = context?.applicationContext ?: return null
      if (!LyraNative.isAvailable) return null
      val modelPath = LyraModelStore.modelPath(appContext) ?: return null
      val handle =
        runCatching {
          LyraNative.createDecoder(CALL_AUDIO_LYRA_SAMPLE_RATE_HZ, modelPath)
        }.getOrDefault(0L)
      if (handle == 0L) return null
      return LyraCallAudioDecoder(handle)
    }
  }
}

private const val CALL_AUDIO_LYRA_BITRATE_BPS = 3_200
private const val LYRA_SAMPLES_PER_FRAME = CALL_AUDIO_LYRA_SAMPLE_RATE_HZ * CALL_AUDIO_FRAME_DURATION_MS / 1_000
