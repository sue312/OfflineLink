package com.example.offlinelink.audio

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaCodecOpusCallAudioEncoder private constructor(
  private val codec: MediaCodec,
) : CallAudioEncoder {
  override val inputSampleRateHz: Int = CALL_AUDIO_OPUS_SAMPLE_RATE_HZ
  override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

  private val bufferInfo = BufferInfo()
  private val pendingOutputFrames = ArrayDeque<ByteArray>()
  private var nextPresentationTimeUs = 0L

  override fun encode(pcmBytes: ByteArray, length: Int): Result<CallAudioFrame?> =
    runCatching {
      if (pendingOutputFrames.isNotEmpty()) {
        return@runCatching opusFrame(pendingOutputFrames.removeFirst())
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
      pendingOutputFrames.removeFirstOrNull()?.let(::opusFrame)
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

  private fun opusFrame(bytes: ByteArray): CallAudioFrame =
    CallAudioFrame(
      bytes = bytes,
      durationMs = CALL_AUDIO_FRAME_DURATION_MS.toLong(),
      mimeType = CALL_AUDIO_OPUS_MIME_TYPE,
    )

  override fun close() {
    runCatching { codec.stop() }
    runCatching { codec.release() }
  }

  companion object {
    fun createOrNull(bitrateBps: Int = CALL_AUDIO_OPUS_BITRATE_BPS): MediaCodecOpusCallAudioEncoder? {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
      return runCatching {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val format =
          MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            CALL_AUDIO_OPUS_SAMPLE_RATE_HZ,
            CALL_AUDIO_CHANNEL_COUNT,
          ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            runCatching { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }
            runCatching { setInteger(MediaFormat.KEY_COMPLEXITY, OPUS_ENCODER_COMPLEXITY) }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, callAudioPcmFrameBytes(CALL_AUDIO_OPUS_SAMPLE_RATE_HZ))
          }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        MediaCodecOpusCallAudioEncoder(codec)
      }.getOrNull()
    }
  }
}

class MediaCodecOpusCallAudioDecoder private constructor(
  private val codec: MediaCodec,
) : CallAudioDecoder {
  private val bufferInfo = BufferInfo()
  private val pendingPcmFrames = ArrayDeque<PcmAudioFrame>()
  private var nextPresentationTimeUs = 0L
  private var outputSampleRateHz = CALL_AUDIO_OPUS_SAMPLE_RATE_HZ

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
              .getOrDefault(CALL_AUDIO_OPUS_SAMPLE_RATE_HZ)
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
    fun createOrNull(): MediaCodecOpusCallAudioDecoder? =
      runCatching {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(createOpusDecodeFormat(), null, null, 0)
        codec.start()
        MediaCodecOpusCallAudioDecoder(codec)
      }.getOrNull()
  }
}

private fun createOpusDecodeFormat(): MediaFormat =
  MediaFormat.createAudioFormat(
    MediaFormat.MIMETYPE_AUDIO_OPUS,
    CALL_AUDIO_OPUS_SAMPLE_RATE_HZ,
    CALL_AUDIO_CHANNEL_COUNT,
  ).apply {
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256)
    setByteBuffer("csd-0", ByteBuffer.wrap(createOpusHead()))
    setByteBuffer("csd-1", longBuffer(OPUS_CODEC_DELAY_NS))
    setByteBuffer("csd-2", longBuffer(OPUS_SEEK_PREROLL_NS))
  }

private fun createOpusHead(): ByteArray {
  val data = ByteArray(19)
  val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
  buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))
  buffer.put(1)
  buffer.put(CALL_AUDIO_CHANNEL_COUNT.toByte())
  buffer.putShort(OPUS_PRE_SKIP_SAMPLES.toShort())
  buffer.putInt(CALL_AUDIO_OPUS_SAMPLE_RATE_HZ)
  buffer.putShort(0)
  buffer.put(0)
  return data
}

private fun longBuffer(value: Long): ByteBuffer =
  ByteBuffer.allocate(Long.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .putLong(value)
    .also { it.flip() }

private const val CALL_AUDIO_CHANNEL_COUNT = 1
private const val CALL_AUDIO_OPUS_BITRATE_BPS = 16_000
private const val CODEC_TIMEOUT_US = 10_000L
private const val OPUS_ENCODER_COMPLEXITY = 10
private const val OPUS_PRE_SKIP_SAMPLES = 312
private const val OPUS_CODEC_DELAY_NS = 0L
private const val OPUS_SEEK_PREROLL_NS = 80_000_000L
