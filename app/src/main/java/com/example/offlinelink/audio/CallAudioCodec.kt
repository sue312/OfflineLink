package com.example.offlinelink.audio

import android.content.Context
import java.io.Closeable

data class PcmAudioFrame(
  val bytes: ByteArray,
  val sampleRateHz: Int,
)

interface CallAudioEncoder : Closeable {
  val inputSampleRateHz: Int
  val inputFrameBytes: Int

  fun encode(pcmBytes: ByteArray, length: Int = pcmBytes.size): Result<CallAudioFrame?>

  override fun close() = Unit
}

interface CallAudioDecoder : Closeable {
  fun decode(frame: CallAudioFrame): Result<PcmAudioFrame?>

  override fun close() = Unit
}

class PcmCallAudioEncoder(
  sampleRateHz: Int = CALL_AUDIO_SAMPLE_RATE_HZ,
  private val outputSampleRateHz: Int = sampleRateHz,
) : CallAudioEncoder {
  override val inputSampleRateHz: Int = sampleRateHz
  override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

  override fun encode(pcmBytes: ByteArray, length: Int): Result<CallAudioFrame?> =
    Result.success(
      CallAudioFrame(
        bytes = downsamplePcm16(pcmBytes, length, inputSampleRateHz, outputSampleRateHz),
        durationMs = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
        mimeType = pcmCallAudioMimeType(outputSampleRateHz),
      ),
    )
}

class PcmCallAudioDecoder : CallAudioDecoder {
  override fun decode(frame: CallAudioFrame): Result<PcmAudioFrame?> =
    Result.success(
      PcmAudioFrame(
        bytes = frame.bytes,
        sampleRateHz = callAudioSampleRateFromMimeType(frame.mimeType) ?: CALL_AUDIO_SAMPLE_RATE_HZ,
      ),
    )
}

class DefaultCallAudioDecoder(
  private val context: Context? = null,
) : CallAudioDecoder {
  private val pcmDecoder = PcmCallAudioDecoder()
  private var amrWbDecoder: CallAudioDecoder? = null
  private var opusDecoder: CallAudioDecoder? = null
  private var lyraDecoder: CallAudioDecoder? = null

  override fun decode(frame: CallAudioFrame): Result<PcmAudioFrame?> {
    val mimeType = frame.mimeType.trim()
    if (mimeType.startsWith("audio/lyra", ignoreCase = true)) {
      val decoder =
        lyraDecoder
          ?: LyraCallAudioDecoder.createOrNull(context)
            ?.also { lyraDecoder = it }
          ?: return Result.failure(IllegalStateException("Lyra decoder is not available"))
      return decoder.decode(frame)
    }
    if (mimeType.startsWith("audio/amr-wb", ignoreCase = true)) {
      val decoder =
        amrWbDecoder
          ?: MediaCodecAmrWbCallAudioDecoder.createOrNull()
            ?.also { amrWbDecoder = it }
          ?: return Result.failure(IllegalStateException("AMR-WB decoder is not available"))
      return decoder.decode(frame)
    }
    if (mimeType.startsWith("audio/opus", ignoreCase = true)) {
      val decoder =
        opusDecoder
          ?: MediaCodecOpusCallAudioDecoder.createOrNull()
            ?.also { opusDecoder = it }
          ?: return Result.failure(IllegalStateException("Opus decoder is not available"))
      return decoder.decode(frame)
    }
    return pcmDecoder.decode(frame)
  }

  override fun close() {
    amrWbDecoder?.close()
    amrWbDecoder = null
    opusDecoder?.close()
    opusDecoder = null
    lyraDecoder?.close()
    lyraDecoder = null
  }
}

object CallAudioCodecFactory {
  fun createEncoder(
    context: Context? = null,
    encodingMode: CallAudioEncodingMode = CallAudioEncodingMode.Default,
  ): CallAudioEncoder =
    FixedCallAudioEncoder(
      mode = encodingMode,
      context = context,
    )

  fun createDecoder(context: Context? = null): CallAudioDecoder = DefaultCallAudioDecoder(context)
}

fun callAudioPcmFrameBytes(sampleRateHz: Int): Int =
  sampleRateHz * CALL_AUDIO_BYTES_PER_SAMPLE * CALL_AUDIO_FRAME_DURATION_MS / 1_000

fun pcmCallAudioMimeType(sampleRateHz: Int): String =
  "audio/pcm;rate=$sampleRateHz;encoding=pcm16"

fun callAudioSampleRateFromMimeType(mimeType: String): Int? {
  val rateToken =
    mimeType
      .split(';')
      .map { it.trim() }
      .firstOrNull { it.startsWith("rate=", ignoreCase = true) }
      ?: return null
  return rateToken.substringAfter('=').toIntOrNull()
}

private fun downsamplePcm16(
  pcmBytes: ByteArray,
  length: Int,
  inputSampleRateHz: Int,
  outputSampleRateHz: Int,
): ByteArray {
  val usableLength = length.coerceAtMost(pcmBytes.size).coerceAtLeast(0)
  if (inputSampleRateHz == outputSampleRateHz) return pcmBytes.copyOf(usableLength)
  require(inputSampleRateHz > outputSampleRateHz) { "output sample rate must not exceed input sample rate" }
  require(inputSampleRateHz % outputSampleRateHz == 0) { "only integer downsampling ratios are supported" }
  val ratio = inputSampleRateHz / outputSampleRateHz
  val inputSamples = usableLength / CALL_AUDIO_BYTES_PER_SAMPLE
  val outputSamples = inputSamples / ratio
  val output = ByteArray(outputSamples * CALL_AUDIO_BYTES_PER_SAMPLE)
  var inputIndex = 0
  var outputIndex = 0
  repeat(outputSamples) {
    output[outputIndex] = pcmBytes[inputIndex]
    output[outputIndex + 1] = pcmBytes[inputIndex + 1]
    inputIndex += ratio * CALL_AUDIO_BYTES_PER_SAMPLE
    outputIndex += CALL_AUDIO_BYTES_PER_SAMPLE
  }
  return output
}
