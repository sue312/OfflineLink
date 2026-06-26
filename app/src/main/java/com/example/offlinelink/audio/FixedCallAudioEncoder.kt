package com.example.offlinelink.audio

import android.content.Context
import android.util.Log

class FixedCallAudioEncoder(
  private val mode: CallAudioEncodingMode,
  context: Context? = null,
  private val encoderFactory: (CallAudioEncodingMode) -> CallAudioEncoder? = { selectedMode ->
    createModeEncoder(context, selectedMode)
  },
  private val codecLogger: (String) -> Unit = ::logCallAudioCodec,
) : CallAudioEncoder {
  private val encoder: CallAudioEncoder = encoderFactory(mode) ?: lowBandwidthPcmFallback()
  override val inputSampleRateHz: Int = encoder.inputSampleRateHz
  override val inputFrameBytes: Int = encoder.inputFrameBytes

  private var activeCodecLogKey: CodecLogKey? = null

  override fun encode(pcmBytes: ByteArray, length: Int): Result<CallAudioFrame?> =
    encoder.encode(pcmBytes, length).onSuccess { frame ->
      if (frame != null) logActiveOutputCodec(frame)
    }

  override fun close() {
    encoder.close()
    activeCodecLogKey = null
  }

  private fun logActiveOutputCodec(frame: CallAudioFrame) {
    val implementation = encoder.javaClass.simpleName.ifBlank { encoder.javaClass.name }
    val key = CodecLogKey(mode = mode, mimeType = frame.mimeType, implementation = implementation)
    if (activeCodecLogKey == key) return
    activeCodecLogKey = key
    codecLogger(
      "Call audio encoder active mode=${mode.displayName} mime=${frame.mimeType} " +
        "bitrateBps=${bitrateHintBps(mode, frame.mimeType)?.toString() ?: "unknown"} " +
        "implementation=$implementation",
    )
  }

  private data class CodecLogKey(
    val mode: CallAudioEncodingMode,
    val mimeType: String,
    val implementation: String,
  )

  private companion object {
    fun createModeEncoder(
      context: Context?,
      mode: CallAudioEncodingMode,
    ): CallAudioEncoder =
      when (mode) {
        CallAudioEncodingMode.Opus ->
          MediaCodecOpusCallAudioEncoder.createOrNull(CALL_AUDIO_OPUS_FIXED_BITRATE_BPS)
            ?: MediaCodecAmrWbCallAudioEncoder.createOrNull()
            ?: lowBandwidthPcmFallback()
        CallAudioEncodingMode.Lyra ->
          LyraCallAudioEncoder.createOrNull(context)
            ?: lowBandwidthPcmFallback()
        CallAudioEncodingMode.Pcm16 ->
          PcmCallAudioEncoder(sampleRateHz = CALL_AUDIO_SAMPLE_RATE_HZ)
      }

    fun lowBandwidthPcmFallback(): CallAudioEncoder =
      PcmCallAudioEncoder(
        sampleRateHz = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ,
        outputSampleRateHz = CALL_AUDIO_SAMPLE_RATE_HZ,
      )
  }
}

private fun bitrateHintBps(
  mode: CallAudioEncodingMode,
  mimeType: String,
): Int? {
  mimeTypeParameter(mimeType, "bitrate")?.toIntOrNull()?.let { return it }
  return when {
    mimeType.startsWith("audio/opus", ignoreCase = true) -> CALL_AUDIO_OPUS_FIXED_BITRATE_BPS
    mimeType.startsWith("audio/amr-wb", ignoreCase = true) -> CALL_AUDIO_AMR_WB_BITRATE_HINT_BPS
    mimeType.startsWith("audio/pcm", ignoreCase = true) ->
      callAudioSampleRateFromMimeType(mimeType)?.let { it * CALL_AUDIO_BYTES_PER_SAMPLE * Byte.SIZE_BITS }
    mode == CallAudioEncodingMode.Lyra -> CALL_AUDIO_LYRA_BITRATE_HINT_BPS
    else -> null
  }
}

private fun mimeTypeParameter(
  mimeType: String,
  key: String,
): String? =
  mimeType
    .split(';')
    .asSequence()
    .map { it.trim() }
    .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
    ?.substringAfter('=')

private fun logCallAudioCodec(message: String) {
  runCatching { Log.d(CALL_AUDIO_ENCODER_TAG, message) }
}

private const val CALL_AUDIO_ENCODER_TAG = "CallAudioEncoder"
private const val CALL_AUDIO_AMR_WB_BITRATE_HINT_BPS = 12_650
private const val CALL_AUDIO_LYRA_BITRATE_HINT_BPS = 3_200
