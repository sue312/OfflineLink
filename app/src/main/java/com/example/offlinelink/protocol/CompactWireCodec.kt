package com.example.offlinelink.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.roundToInt

object CompactWireCodec {
  const val MAX_RAW_SHORT_TEXT_UTF8_BYTES = 30

  private const val TYPE_ACK = 0x81
  private const val TYPE_MESSAGE = 0x82
  private const val TYPE_CALL_REQUEST = 0x83
  private const val TYPE_CALL_ACCEPT = 0x84
  private const val TYPE_CALL_REJECT = 0x85
  private const val TYPE_CALL_END = 0x86
  private const val TYPE_LOCATION = 0x87
  private const val WIRE_ID_PREFIX = "cid:"
  private const val NULL_ACCURACY_DM = 0xffff

  fun compactWireId(id: String): String =
    if (isCompactWireId(id)) {
      id.lowercase(Locale.US)
    } else {
      WIRE_ID_PREFIX + String.format(Locale.US, "%08x", compactHash(id))
    }

  fun matchesWireId(
    candidateId: String,
    wireId: String,
  ): Boolean =
    candidateId == wireId || compactWireId(candidateId) == wireId.lowercase(Locale.US)

  fun shouldUseCompactIds(ids: Collection<String?>): Boolean =
    ids.filterNotNull().any { isCompactWireId(it) || UUID_PATTERN.matches(it) }

  fun encodeAck(
    messageId: String,
    sentAt: Long,
  ): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write(TYPE_ACK)
        writeInt32(compactHash(messageId))
        writeVarLong(sentAt)
      }
      .toByteArray()

  fun encodeMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    text: String,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write(TYPE_MESSAGE)
        writeInt32(compactHash(messageId))
        writeInt32(compactHash(conversationId))
        writeInt32(compactHash(senderId))
        writeVarLong(createdAt)
        writeVarLong(sentAt)
        writeString(text)
      }
      .toByteArray()

  fun rawShortTextPayloadOrNull(bytes: ByteArray): ByteArray? {
    val message =
      runCatching { decode(bytes) as? DecodedWireMessage.Message }
        .getOrNull()
        ?: return null
    val textByteCount = message.text.toByteArray(StandardCharsets.UTF_8).size
    return if (textByteCount <= MAX_RAW_SHORT_TEXT_UTF8_BYTES) bytes else null
  }

  fun encodeLocation(
    messageId: String,
    conversationId: String,
    senderId: String,
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write(TYPE_LOCATION)
        writeInt32(compactHash(messageId))
        writeInt32(compactHash(conversationId))
        writeInt32(compactHash(senderId))
        writeInt32(latitude.toCoordinateE7(-90.0, 90.0, "latitude"))
        writeInt32(longitude.toCoordinateE7(-180.0, 180.0, "longitude"))
        writeUInt16(accuracy.toAccuracyDecimeters())
        writeVarLong(createdAt)
        writeVarLong(sentAt)
      }
      .toByteArray()

  fun encodeCallRequest(
    callId: String,
    senderId: String,
    targetId: String?,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray = encodeCallControl(TYPE_CALL_REQUEST, callId, senderId, targetId, createdAt, sentAt)

  fun encodeCallAccept(
    callId: String,
    senderId: String,
    targetId: String?,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray = encodeCallControl(TYPE_CALL_ACCEPT, callId, senderId, targetId, createdAt, sentAt)

  fun encodeCallReject(
    callId: String,
    senderId: String,
    targetId: String?,
    reason: String,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray = encodeCallControl(TYPE_CALL_REJECT, callId, senderId, targetId, createdAt, sentAt, reason)

  fun encodeCallEnd(
    callId: String,
    senderId: String,
    targetId: String?,
    createdAt: Long,
    sentAt: Long,
  ): ByteArray = encodeCallControl(TYPE_CALL_END, callId, senderId, targetId, createdAt, sentAt)

  fun decode(bytes: ByteArray): DecodedWireMessage? {
    if (bytes.isEmpty()) return null
    val cursor = Cursor(bytes)
    return when (cursor.readUnsignedByte()) {
      TYPE_ACK ->
        DecodedWireMessage.Ack(
          messageId = wireId(cursor.readInt32()),
          sentAt = cursor.readVarLong(),
        )
      TYPE_MESSAGE ->
        DecodedWireMessage.Message(
          messageId = wireId(cursor.readInt32()),
          conversationId = wireId(cursor.readInt32()),
          senderId = wireId(cursor.readInt32()),
          createdAt = cursor.readVarLong(),
          sentAt = cursor.readVarLong(),
          text = cursor.readString(),
        )
      TYPE_CALL_REQUEST ->
        cursor.readCallControl(TYPE_CALL_REQUEST)
      TYPE_CALL_ACCEPT ->
        cursor.readCallControl(TYPE_CALL_ACCEPT)
      TYPE_CALL_REJECT ->
        cursor.readCallControl(TYPE_CALL_REJECT)
      TYPE_CALL_END ->
        cursor.readCallControl(TYPE_CALL_END)
      TYPE_LOCATION ->
        DecodedWireMessage.LocationMessage(
          messageId = wireId(cursor.readInt32()),
          conversationId = wireId(cursor.readInt32()),
          senderId = wireId(cursor.readInt32()),
          latitude = cursor.readInt32() / COORDINATE_SCALE,
          longitude = cursor.readInt32() / COORDINATE_SCALE,
          accuracy = cursor.readAccuracy(),
          createdAt = cursor.readVarLong(),
          sentAt = cursor.readVarLong(),
        )
      else -> null
    }?.also {
      require(cursor.isExhausted()) { "Compact wire frame has trailing bytes" }
    }
  }

  private fun encodeCallControl(
    type: Int,
    callId: String,
    senderId: String,
    targetId: String?,
    createdAt: Long,
    sentAt: Long,
    reason: String = "",
  ): ByteArray =
    ByteArrayOutputStream()
      .apply {
        write(type)
        writeInt32(compactHash(callId))
        writeInt32(compactHash(senderId))
        if (targetId == null) {
          write(0)
        } else {
          write(1)
          writeInt32(compactHash(targetId))
        }
        writeVarLong(createdAt)
        writeVarLong(sentAt)
        if (type == TYPE_CALL_REJECT) writeString(reason)
      }
      .toByteArray()

  private fun Cursor.readCallControl(type: Int): DecodedWireMessage {
    val callId = wireId(readInt32())
    val senderId = wireId(readInt32())
    val targetId =
      when (val flag = readUnsignedByte()) {
        0 -> null
        1 -> wireId(readInt32())
        else -> error("Invalid compact target flag $flag")
      }
    val createdAt = readVarLong()
    val sentAt = readVarLong()
    return when (type) {
      TYPE_CALL_REQUEST -> DecodedWireMessage.CallRequest(callId, senderId, targetId, createdAt, sentAt)
      TYPE_CALL_ACCEPT -> DecodedWireMessage.CallAccept(callId, senderId, targetId, createdAt, sentAt)
      TYPE_CALL_REJECT -> DecodedWireMessage.CallReject(callId, senderId, targetId, readString(), createdAt, sentAt)
      TYPE_CALL_END -> DecodedWireMessage.CallEnd(callId, senderId, targetId, createdAt, sentAt)
      else -> error("Unsupported compact call control type $type")
    }
  }

  private fun isCompactWireId(id: String): Boolean =
    id.length == WIRE_ID_PREFIX.length + 8 &&
      id.startsWith(WIRE_ID_PREFIX, ignoreCase = true) &&
      id.drop(WIRE_ID_PREFIX.length).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

  private fun compactHash(id: String): Int {
    if (isCompactWireId(id)) {
      return id.substring(WIRE_ID_PREFIX.length).toUInt(16).toInt()
    }
    val crc = CRC32()
    crc.update(id.toByteArray(StandardCharsets.UTF_8))
    return crc.value.toInt()
  }

  private fun wireId(hash: Int): String =
    WIRE_ID_PREFIX + String.format(Locale.US, "%08x", hash)

  private fun ByteArrayOutputStream.writeInt32(value: Int) {
    write((value ushr 24) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 8) and 0xff)
    write(value and 0xff)
  }

  private fun ByteArrayOutputStream.writeUInt16(value: Int) {
    require(value in 0..0xffff) { "Compact wire ushort is out of range" }
    write((value ushr 8) and 0xff)
    write(value and 0xff)
  }

  private fun ByteArrayOutputStream.writeVarLong(value: Long) {
    require(value >= 0L) { "Compact wire varint cannot encode negative values" }
    var remaining = value
    while (remaining >= 0x80L) {
      write(((remaining and 0x7fL) or 0x80L).toInt())
      remaining = remaining ushr 7
    }
    write(remaining.toInt())
  }

  private fun ByteArrayOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeVarLong(bytes.size.toLong())
    write(bytes)
  }

  private class Cursor(
    private val bytes: ByteArray,
  ) {
    private var offset = 0

    fun isExhausted(): Boolean = offset == bytes.size

    fun readUnsignedByte(): Int {
      require(offset < bytes.size) { "Compact wire frame is truncated" }
      return bytes[offset++].toInt() and 0xff
    }

    fun readInt32(): Int {
      require(offset + Int.SIZE_BYTES <= bytes.size) { "Compact wire int is truncated" }
      val value =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      offset += Int.SIZE_BYTES
      return value
    }

    fun readUInt16(): Int {
      require(offset + Short.SIZE_BYTES <= bytes.size) { "Compact wire ushort is truncated" }
      val value = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
      offset += Short.SIZE_BYTES
      return value
    }

    fun readAccuracy(): Float? {
      val decimeters = readUInt16()
      if (decimeters == NULL_ACCURACY_DM) return null
      return decimeters / 10f
    }

    fun readVarLong(): Long {
      var shift = 0
      var value = 0L
      while (shift <= 63) {
        val byte = readUnsignedByte()
        value = value or ((byte and 0x7f).toLong() shl shift)
        if ((byte and 0x80) == 0) return value
        shift += 7
      }
      error("Compact wire varint is too large")
    }

    fun readString(): String {
      val length = readVarLong()
      require(length <= Int.MAX_VALUE) { "Compact wire string is too large" }
      val end = offset + length.toInt()
      require(end <= bytes.size) { "Compact wire string is truncated" }
      return String(bytes, offset, length.toInt(), StandardCharsets.UTF_8).also {
        offset = end
      }
    }
  }

  private val UUID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

  private const val COORDINATE_SCALE = 10_000_000.0

  private fun Double.toCoordinateE7(
    min: Double,
    max: Double,
    name: String,
  ): Int {
    require(this in min..max) { "Invalid compact $name $this" }
    return (this * COORDINATE_SCALE).roundToInt()
  }

  private fun Float?.toAccuracyDecimeters(): Int {
    if (this == null) return NULL_ACCURACY_DM
    require(this >= 0f) { "Invalid compact location accuracy $this" }
    val decimeters = (this * 10f).roundToInt()
    require(decimeters < NULL_ACCURACY_DM) { "Compact location accuracy is too large" }
    return decimeters
  }
}
