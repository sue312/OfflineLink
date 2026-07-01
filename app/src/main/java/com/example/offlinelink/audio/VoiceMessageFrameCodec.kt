package com.example.offlinelink.audio

import com.example.offlinelink.protocol.CallAudioPacket
import com.example.offlinelink.protocol.CallAudioPacketCodec
import java.nio.ByteBuffer

object VoiceMessageFrameCodec {
  fun encode(frames: List<CallAudioFrame>): ByteArray {
    require(frames.isNotEmpty()) { "Voice message requires at least one Opus frame" }
    require(frames.all { it.mimeType.startsWith("audio/opus", ignoreCase = true) }) {
      "Voice message frame must be Opus"
    }
    require(frames.all { it.durationMs == frames.first().durationMs }) {
      "Voice message bundle requires a common frame duration"
    }
    require(frames.first().durationMs in 0..UShort.MAX_VALUE.toLong()) {
      "Voice message frame duration must fit in unsigned short"
    }
    if (frames.size > 1) return encodeBundle(frames)

    val packets =
      frames.mapIndexed { index, frame ->
        CallAudioPacket(
          callId = "",
          frameId = "",
          senderId = "",
          targetId = null,
          audioBytes = frame.bytes,
          durationMs = frame.durationMs,
          mimeType = CALL_AUDIO_OPUS_MIME_TYPE,
          sequenceNumber = index,
          createdAt = 0L,
          sentAt = 0L,
        )
      }
    return if (packets.size == 1) {
      CallAudioPacketCodec.encode(packets.single(), compact = true)
    } else {
      CallAudioPacketCodec.encode(packets.last(), compact = true, redundantPreviousPackets = packets.dropLast(1))
    }
  }

  fun decode(bytes: ByteArray): List<CallAudioFrame> {
    decodeBundle(bytes)?.let { return it }
    val packets = CallAudioPacketCodec.decodeAll(bytes) ?: error("Voice message payload is not an Opus frame bundle")
    return packets.map {
      CallAudioFrame(
        bytes = it.audioBytes,
        durationMs = it.durationMs,
        mimeType = it.mimeType,
        sequenceNumber = it.sequenceNumber,
      )
    }
  }

  private fun encodeBundle(frames: List<CallAudioFrame>): ByteArray {
    require(frames.size <= UShort.MAX_VALUE.toInt()) { "Voice message bundle has too many frames" }
    frames.forEach { frame ->
      require(frame.bytes.size <= UShort.MAX_VALUE.toInt()) { "Voice message frame is too large" }
    }
    val fixedFrameLength = frames.first().bytes.size.takeIf { firstLength ->
      frames.all { it.bytes.size == firstLength }
    }
    val payloadBytes =
      if (fixedFrameLength != null) {
        Short.SIZE_BYTES + frames.sumOf { it.bytes.size }
      } else {
        frames.sumOf { Short.SIZE_BYTES + it.bytes.size }
      }
    val buffer =
      ByteBuffer.allocate(BUNDLE_HEADER_BYTES + payloadBytes)
        .putInt(BUNDLE_MAGIC)
        .put(BUNDLE_VERSION)
        .put(CODEC_OPUS)
        .putShort(frames.size.toShort())
        .putShort(frames.first().durationMs.toInt().toShort())
        .put(if (fixedFrameLength != null) FLAG_FIXED_LENGTH else 0.toByte())
    if (fixedFrameLength != null) {
      buffer.putShort(fixedFrameLength.toShort())
      frames.forEach { buffer.put(it.bytes) }
    } else {
      frames.forEach { frame ->
        buffer.putShort(frame.bytes.size.toShort())
        buffer.put(frame.bytes)
      }
    }
    return buffer.array()
  }

  private fun decodeBundle(bytes: ByteArray): List<CallAudioFrame>? {
    if (bytes.size < BUNDLE_HEADER_BYTES) return null
    val buffer = ByteBuffer.wrap(bytes)
    if (buffer.int != BUNDLE_MAGIC) return null
    val version = buffer.get()
    require(version == BUNDLE_VERSION) { "Unsupported voice message bundle version $version" }
    val codec = buffer.get()
    require(codec == CODEC_OPUS) { "Unsupported voice message bundle codec $codec" }
    val frameCount = buffer.short.toInt() and 0xffff
    require(frameCount > 0) { "Voice message bundle must contain at least one frame" }
    val durationMs = buffer.short.toInt() and 0xffff
    val flags = buffer.get().toInt() and 0xff
    val fixedLength = flags and FLAG_FIXED_LENGTH.toInt() != 0
    require((flags and FLAG_FIXED_LENGTH.toInt().inv()) == 0) { "Unsupported voice message bundle flags $flags" }

    return if (fixedLength) {
      require(buffer.remaining() >= Short.SIZE_BYTES) { "Voice message bundle frame length is missing" }
      val frameLength = buffer.short.toInt() and 0xffff
      require(buffer.remaining() == frameCount * frameLength) { "Voice message bundle payload length mismatch" }
      List(frameCount) { index ->
        val payload = ByteArray(frameLength)
        buffer.get(payload)
        CallAudioFrame(
          bytes = payload,
          durationMs = durationMs.toLong(),
          mimeType = CALL_AUDIO_OPUS_MIME_TYPE,
          sequenceNumber = index,
        )
      }
    } else {
      List(frameCount) { index ->
        require(buffer.remaining() >= Short.SIZE_BYTES) { "Voice message bundle frame is truncated" }
        val frameLength = buffer.short.toInt() and 0xffff
        require(buffer.remaining() >= frameLength) { "Voice message bundle frame payload is truncated" }
        val payload = ByteArray(frameLength)
        buffer.get(payload)
        CallAudioFrame(
          bytes = payload,
          durationMs = durationMs.toLong(),
          mimeType = CALL_AUDIO_OPUS_MIME_TYPE,
          sequenceNumber = index,
        )
      }.also {
        require(!buffer.hasRemaining()) { "Voice message bundle has trailing bytes" }
      }
    }
  }

  private const val BUNDLE_MAGIC = 0x4f4c564d // OLVM
  private const val BUNDLE_VERSION: Byte = 1
  private const val CODEC_OPUS: Byte = 2
  private const val FLAG_FIXED_LENGTH: Byte = 1
  private const val BUNDLE_HEADER_BYTES = Int.SIZE_BYTES + 1 + 1 + Short.SIZE_BYTES + Short.SIZE_BYTES + 1
}
