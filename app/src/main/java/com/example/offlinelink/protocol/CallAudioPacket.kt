package com.example.offlinelink.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class CallAudioPacket(
  val callId: String,
  val frameId: String,
  val senderId: String,
  val targetId: String?,
  val audioBytes: ByteArray,
  val durationMs: Long,
  val mimeType: String,
  val sequenceNumber: Int,
  val createdAt: Long,
  val sentAt: Long,
)

data class CallAudioFrameRedundancy(
  val audioBytes: ByteArray,
  val durationMs: Long,
  val mimeType: String,
  val sequenceNumber: Int,
)

object CallAudioPacketCodec {
  private const val MAGIC = 0x4f4c4341 // OLCA
  private const val COMPACT_MAGIC = 0x4f4c4353 // OLCS
  private const val REDUNDANT_COMPACT_MAGIC = 0x4f4c4352 // OLCR
  private const val VERSION = 1
  private const val CODEC_PCM16 = 1
  private const val CODEC_OPUS = 2
  private const val CODEC_AMR_WB = 3
  private const val CODEC_LYRA = 4
  private const val NULL_STRING_LENGTH = 0xffff

  fun isCallAudioPacket(bytes: ByteArray): Boolean {
    if (bytes.size < Int.SIZE_BYTES) return false
    val magic = ByteBuffer.wrap(bytes, 0, Int.SIZE_BYTES).int
    return magic == MAGIC || magic == COMPACT_MAGIC || magic == REDUNDANT_COMPACT_MAGIC
  }

  fun encode(
    packet: CallAudioPacket,
    compact: Boolean = false,
    redundantPrevious: CallAudioPacket? = null,
    redundantPreviousPackets: List<CallAudioPacket> = emptyList(),
  ): ByteArray =
    when {
      compact && redundantPreviousPackets.isNotEmpty() -> encodeCompactBundle(redundantPreviousPackets + packet)
      compact && redundantPrevious != null -> encodeCompactBundle(listOf(redundantPrevious, packet))
      compact -> encodeCompact(packet)
      else -> encodeVerbose(packet)
    }

  private fun encodeVerbose(packet: CallAudioPacket): ByteArray {
    require(packet.durationMs in 0..UShort.MAX_VALUE.toLong()) { "durationMs must fit in unsigned short" }
    val callId = packet.callId.toUtf8()
    val frameId = packet.frameId.toUtf8()
    val senderId = packet.senderId.toUtf8()
    val targetId = packet.targetId?.toUtf8()
    val mimeType = packet.mimeType.toUtf8()
    val size =
      Int.SIZE_BYTES +
        1 +
        1 +
        Int.SIZE_BYTES +
        Short.SIZE_BYTES +
        Long.SIZE_BYTES +
        Long.SIZE_BYTES +
        encodedStringSize(callId) +
        encodedStringSize(frameId) +
        encodedStringSize(senderId) +
        encodedNullableStringSize(targetId) +
        encodedStringSize(mimeType) +
        packet.audioBytes.size
    return ByteBuffer.allocate(size)
      .putInt(MAGIC)
      .put(VERSION.toByte())
      .put(codecId(packet.mimeType).toByte())
      .putInt(packet.sequenceNumber)
      .putShort(packet.durationMs.toInt().toShort())
      .putLong(packet.createdAt)
      .putLong(packet.sentAt)
      .putStringBytes(callId)
      .putStringBytes(frameId)
      .putStringBytes(senderId)
      .putNullableStringBytes(targetId)
      .putStringBytes(mimeType)
      .put(packet.audioBytes)
      .array()
  }

  private fun encodeCompact(packet: CallAudioPacket): ByteArray {
    require(packet.durationMs in 0..UShort.MAX_VALUE.toLong()) { "durationMs must fit in unsigned short" }
    val codecId = codecId(packet.mimeType)
    require(codecId != 0) { "Compact call audio packet requires a known streaming codec" }
    return ByteBuffer.allocate(
      Int.SIZE_BYTES +
        1 +
        1 +
        Int.SIZE_BYTES +
        Short.SIZE_BYTES +
        packet.audioBytes.size,
    )
      .putInt(COMPACT_MAGIC)
      .put(VERSION.toByte())
      .put(codecId.toByte())
      .putInt(packet.sequenceNumber)
      .putShort(packet.durationMs.toInt().toShort())
      .put(packet.audioBytes)
      .array()
  }

  private fun encodeCompactBundle(packets: List<CallAudioPacket>): ByteArray {
    require(packets.isNotEmpty()) { "Compact call audio bundle requires at least one frame" }
    require(packets.size <= UByte.MAX_VALUE.toInt()) { "Compact call audio bundle has too many frames" }
    val frameSizes =
      packets.map { packet ->
        require(packet.durationMs in 0..UShort.MAX_VALUE.toLong()) { "durationMs must fit in unsigned short" }
        val codecId = codecId(packet.mimeType)
        require(codecId != 0) { "Compact call audio packet requires a known streaming codec" }
        require(packet.audioBytes.size <= UShort.MAX_VALUE.toInt()) { "audio frame is too large for compact bundle" }
        COMPACT_BUNDLE_FRAME_HEADER_BYTES + packet.audioBytes.size
      }
    val size = Int.SIZE_BYTES + 1 + 1 + frameSizes.sum()
    val buffer =
      ByteBuffer.allocate(size)
        .putInt(REDUNDANT_COMPACT_MAGIC)
        .put(VERSION.toByte())
        .put(packets.size.toByte())
    packets.forEach { packet ->
      buffer
        .put(codecId(packet.mimeType).toByte())
        .putInt(packet.sequenceNumber)
        .putShort(packet.durationMs.toInt().toShort())
        .putShort(packet.audioBytes.size.toShort())
        .put(packet.audioBytes)
    }
    return buffer.array()
  }

  fun decode(bytes: ByteArray): CallAudioPacket? =
    decodeAll(bytes)?.lastOrNull()

  fun decodeAll(bytes: ByteArray): List<CallAudioPacket>? {
    if (!isCallAudioPacket(bytes)) return null
    return try {
      val buffer = ByteBuffer.wrap(bytes)
      val magic = buffer.int
      if (magic == COMPACT_MAGIC) {
        return listOf(decodeCompact(buffer))
      }
      if (magic == REDUNDANT_COMPACT_MAGIC) {
        return decodeCompactBundle(buffer)
      }
      require(magic == MAGIC) { "Invalid call audio packet magic" }
      val version = buffer.get().toInt() and 0xff
      require(version == VERSION) { "Unsupported call audio packet version $version" }
      buffer.get() // codec id, kept for quick sniffing and future compatibility.
      val sequenceNumber = buffer.int
      val durationMs = buffer.short.toInt() and 0xffff
      val createdAt = buffer.long
      val sentAt = buffer.long
      val callId = buffer.readString()
      val frameId = buffer.readString()
      val senderId = buffer.readString()
      val targetId = buffer.readNullableString()
      val mimeType = buffer.readString()
      val audioBytes = ByteArray(buffer.remaining())
      buffer.get(audioBytes)
      listOf(
        CallAudioPacket(
          callId = callId,
          frameId = frameId,
          senderId = senderId,
          targetId = targetId,
          audioBytes = audioBytes,
          durationMs = durationMs.toLong(),
          mimeType = mimeType,
          sequenceNumber = sequenceNumber,
          createdAt = createdAt,
          sentAt = sentAt,
        ),
      )
    } catch (exception: BufferUnderflowException) {
      throw IllegalArgumentException("Malformed call audio packet", exception)
    }
  }

  private fun decodeCompact(buffer: ByteBuffer): CallAudioPacket {
    val version = buffer.get().toInt() and 0xff
    require(version == VERSION) { "Unsupported compact call audio packet version $version" }
    val codecId = buffer.get().toInt() and 0xff
    val sequenceNumber = buffer.int
    val durationMs = buffer.short.toInt() and 0xffff
    val audioBytes = ByteArray(buffer.remaining())
    buffer.get(audioBytes)
    return CallAudioPacket(
      callId = "",
      frameId = "",
      senderId = "",
      targetId = null,
      audioBytes = audioBytes,
      durationMs = durationMs.toLong(),
      mimeType = mimeTypeForCodec(codecId),
      sequenceNumber = sequenceNumber,
      createdAt = 0L,
      sentAt = 0L,
    )
  }

  private fun decodeCompactBundle(buffer: ByteBuffer): List<CallAudioPacket> {
    val version = buffer.get().toInt() and 0xff
    require(version == VERSION) { "Unsupported compact call audio bundle version $version" }
    val frameCount = buffer.get().toInt() and 0xff
    require(frameCount > 0) { "Compact call audio bundle must contain at least one frame" }
    return List(frameCount) {
      val codecId = buffer.get().toInt() and 0xff
      val sequenceNumber = buffer.int
      val durationMs = buffer.short.toInt() and 0xffff
      val audioLength = buffer.short.toInt() and 0xffff
      require(audioLength <= buffer.remaining()) { "Compact call audio bundle frame is truncated" }
      val audioBytes = ByteArray(audioLength)
      buffer.get(audioBytes)
      CallAudioPacket(
        callId = "",
        frameId = "",
        senderId = "",
        targetId = null,
        audioBytes = audioBytes,
        durationMs = durationMs.toLong(),
        mimeType = mimeTypeForCodec(codecId),
        sequenceNumber = sequenceNumber,
        createdAt = 0L,
        sentAt = 0L,
      )
    }
  }

  private fun codecId(mimeType: String): Int =
    when {
      mimeType.startsWith("audio/lyra", ignoreCase = true) -> CODEC_LYRA
      mimeType.startsWith("audio/amr-wb", ignoreCase = true) -> CODEC_AMR_WB
      mimeType.startsWith("audio/opus", ignoreCase = true) -> CODEC_OPUS
      mimeType.startsWith("audio/pcm", ignoreCase = true) -> CODEC_PCM16
      else -> 0
    }

  private fun mimeTypeForCodec(codecId: Int): String =
    when (codecId) {
      CODEC_LYRA -> "audio/lyra;rate=16000;bitrate=3200"
      CODEC_AMR_WB -> "audio/amr-wb;rate=16000"
      CODEC_OPUS -> "audio/opus;rate=16000"
      CODEC_PCM16 -> "audio/pcm;rate=8000;encoding=pcm16"
      else -> error("Unsupported compact call audio codec id $codecId")
    }

  private fun String.toUtf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

  private fun encodedStringSize(bytes: ByteArray): Int {
    require(bytes.size < NULL_STRING_LENGTH) { "String is too long for call audio packet" }
    return Short.SIZE_BYTES + bytes.size
  }

  private fun encodedNullableStringSize(bytes: ByteArray?): Int =
    if (bytes == null) Short.SIZE_BYTES else encodedStringSize(bytes)

  private fun ByteBuffer.putStringBytes(bytes: ByteArray): ByteBuffer {
    encodedStringSize(bytes)
    putShort(bytes.size.toShort())
    put(bytes)
    return this
  }

  private fun ByteBuffer.putNullableStringBytes(bytes: ByteArray?): ByteBuffer {
    if (bytes == null) {
      putShort(NULL_STRING_LENGTH.toShort())
    } else {
      putStringBytes(bytes)
    }
    return this
  }

  private fun ByteBuffer.readString(): String {
    val length = short.toInt() and 0xffff
    require(length != NULL_STRING_LENGTH) { "Required string was null" }
    val bytes = ByteArray(length)
    get(bytes)
    return String(bytes, StandardCharsets.UTF_8)
  }

  private fun ByteBuffer.readNullableString(): String? {
    val length = short.toInt() and 0xffff
    if (length == NULL_STRING_LENGTH) return null
    val bytes = ByteArray(length)
    get(bytes)
    return String(bytes, StandardCharsets.UTF_8)
  }

  private const val COMPACT_BUNDLE_FRAME_HEADER_BYTES = 1 + Int.SIZE_BYTES + Short.SIZE_BYTES + Short.SIZE_BYTES
}
