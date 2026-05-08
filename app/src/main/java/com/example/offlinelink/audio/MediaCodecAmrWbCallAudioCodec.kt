package com.example.offlinelink.audio

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat

class MediaCodecAmrWbCallAudioEncoder private constructor(
  private val codec: MediaCodec,
) : CallAudioEncoder {
  override val inputSampleRateHz: Int = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ
  override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

  private val bufferInfo = BufferInfo()
  private val pendingOutputFrames = ArrayDeque<ByteArray>()
  private var nextPresentationTimeUs = 0L

  override fun encode(pcmBytes: ByteArray, length: Int): Result<CallAudioFrame?> =
    runCatching {
      if (pendingOutputFrames.isNotEmpty()) {
        return@runCatching amrWbFrame(pendingOutputFrames.removeFirst())
      }

      val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
      if (inputIndex < 0) return@runCatching null

      val inputBuffer = codec.getInputBuffer(inputIndex) ?: return@runCatching null
      val inputLength = length.coerceAtMost(pcmBytes.size).coerceAtLeast(0)
      inputBuffer.clear()
      inputBuffer.put(pcmBytes, 0, inputLength)
      codec.queueInputBuffer(inputIndex, 0, inputLength, nextPresentationTimeUs, 0)
      nextPresentationTimeUs += CALL_AUDIO_FRAME_DURATION_MS * 1_000L

      drainEncoder()
      pendingOutputFrames.removeFirstOrNull()?.let(::amrWbFrame)
    }

  private fun drainEncoder() {
    while (true) {
      val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
        outputIndex >= 0 -> {
          if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
              outputBuffer.position(bufferInfo.offset)
              outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
              val encoded = ByteArray(bufferInfo.size)
              outputBuffer.get(encoded)
              pendingOutputFrames.addLast(encoded)
            }
          }
          codec.releaseOutputBuffer(outputIndex, false)
        }
      }
    }
  }

  private fun amrWbFrame(bytes: ByteArray): CallAudioFrame =
    CallAudioFrame(
      bytes = bytes,
      durationMs = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
      mimeType = CALL_AUDIO_AMR_WB_MIME_TYPE,
    )

  override fun close() {
    runCatching { codec.stop() }
    runCatching { codec.release() }
  }

  companion object {
    fun createOrNull(): MediaCodecAmrWbCallAudioEncoder? =
      runCatching {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_WB)
        val format =
          MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
            CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ,
            CALL_AUDIO_CHANNEL_COUNT,
          ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, CALL_AUDIO_AMR_WB_BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, callAudioPcmFrameBytes(CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ))
          }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        MediaCodecAmrWbCallAudioEncoder(codec)
      }.getOrNull()
  }
}

class MediaCodecAmrWbCallAudioDecoder private constructor(
  private val codec: MediaCodec,
) : CallAudioDecoder {
  private val bufferInfo = BufferInfo()
  private val pendingPcmFrames = ArrayDeque<PcmAudioFrame>()
  private var nextPresentationTimeUs = 0L
  private var outputSampleRateHz = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ

  override fun decode(frame: CallAudioFrame): Result<PcmAudioFrame?> =
    runCatching {
      if (pendingPcmFrames.isNotEmpty()) return@runCatching pendingPcmFrames.removeFirst()

      val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
      if (inputIndex < 0) return@runCatching null

      val inputBuffer = codec.getInputBuffer(inputIndex) ?: return@runCatching null
      inputBuffer.clear()
      inputBuffer.put(frame.bytes)
      codec.queueInputBuffer(inputIndex, 0, frame.bytes.size, nextPresentationTimeUs, 0)
      nextPresentationTimeUs += frame.durationMs * 1_000L

      drainDecoder()
      pendingPcmFrames.removeFirstOrNull()
    }

  private fun drainDecoder() {
    while (true) {
      val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          outputSampleRateHz =
            runCatching { codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
              .getOrDefault(CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ)
        }
        outputIndex >= 0 -> {
          if (bufferInfo.size > 0) {
            codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
              outputBuffer.position(bufferInfo.offset)
              outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
              val pcm = ByteArray(bufferInfo.size)
              outputBuffer.get(pcm)
              pendingPcmFrames.addLast(PcmAudioFrame(bytes = pcm, sampleRateHz = outputSampleRateHz))
            }
          }
          codec.releaseOutputBuffer(outputIndex, false)
        }
      }
    }
  }

  override fun close() {
    runCatching { codec.stop() }
    runCatching { codec.release() }
  }

  companion object {
    fun createOrNull(): MediaCodecAmrWbCallAudioDecoder? =
      runCatching {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_WB)
        val format =
          MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
            CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ,
            CALL_AUDIO_CHANNEL_COUNT,
          ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CALL_AUDIO_AMR_WB_MAX_FRAME_BYTES)
          }
        codec.configure(format, null, null, 0)
        codec.start()
        MediaCodecAmrWbCallAudioDecoder(codec)
      }.getOrNull()
  }
}

private const val CALL_AUDIO_CHANNEL_COUNT = 1
private const val CALL_AUDIO_AMR_WB_BITRATE_BPS = 12_650
private const val CALL_AUDIO_AMR_WB_MAX_FRAME_BYTES = 64
private const val CODEC_TIMEOUT_US = 1_000L
