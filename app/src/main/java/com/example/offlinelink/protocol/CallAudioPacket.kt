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

object CallAudioPacketCodec {
  private const val MAGIC = 0x4f4c4341 // OLCA
  private const val VERSION = 1
  private const val CODEC_PCM16 = 1
  private const val CODEC_OPUS = 2
  private const val NULL_STRING_LENGTH = 0xffff

  fun isCallAudioPacket(bytes: ByteArray): Boolean {
    if (bytes.size < Int.SIZE_BYTES) return false
    return ByteBuffer.wrap(bytes, 0, Int.SIZE_BYTES).int == MAGIC
  }

  fun encode(packet: CallAudioPacket): ByteArray {
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

  fun decode(bytes: ByteArray): CallAudioPacket? {
    if (!isCallAudioPacket(bytes)) return null
    return try {
      val buffer = ByteBuffer.wrap(bytes)
      val magic = buffer.int
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
      )
    } catch (exception: BufferUnderflowException) {
      throw IllegalArgumentException("Malformed call audio packet", exception)
    }
  }

  private fun codecId(mimeType: String): Int =
    when {
      mimeType.startsWith("audio/opus", ignoreCase = true) -> CODEC_OPUS
      mimeType.startsWith("audio/pcm", ignoreCase = true) -> CODEC_PCM16
      else -> 0
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
}
