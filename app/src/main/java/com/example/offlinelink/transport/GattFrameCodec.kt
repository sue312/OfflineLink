package com.example.offlinelink.transport

import java.nio.ByteBuffer

object GattFrameCodec {
  const val HEADER_BYTES = 14
  const val MAX_FRAME_BYTES = BluetoothFrameCodec.MAX_FRAME_BYTES

  private const val MAGIC: Byte = 0x47
  private const val VERSION: Byte = 1

  fun fragment(
    payload: ByteArray,
    maxValueBytes: Int,
    messageId: Int,
  ): List<ByteArray> {
    require(payload.size <= MAX_FRAME_BYTES) { "GATT frame exceeds $MAX_FRAME_BYTES bytes" }
    require(maxValueBytes > HEADER_BYTES) { "GATT value size must exceed $HEADER_BYTES bytes" }

    val maxChunkBytes = maxValueBytes - HEADER_BYTES
    if (payload.isEmpty()) {
      return listOf(encodeFragment(messageId = messageId, totalLength = 0, offset = 0, chunk = ByteArray(0)))
    }

    val fragments = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < payload.size) {
      val chunkLength = minOf(maxChunkBytes, payload.size - offset)
      fragments.add(
        encodeFragment(
          messageId = messageId,
          totalLength = payload.size,
          offset = offset,
          chunk = payload.copyOfRange(offset, offset + chunkLength),
        ),
      )
      offset += chunkLength
    }
    return fragments
  }

  private fun encodeFragment(
    messageId: Int,
    totalLength: Int,
    offset: Int,
    chunk: ByteArray,
  ): ByteArray =
    ByteBuffer
      .allocate(HEADER_BYTES + chunk.size)
      .put(MAGIC)
      .put(VERSION)
      .putInt(messageId)
      .putInt(totalLength)
      .putInt(offset)
      .put(chunk)
      .array()

  class Reassembler {
    private val pending = mutableMapOf<Int, PendingFrame>()

    fun accept(fragmentBytes: ByteArray): ByteArray? {
      val fragment = decodeFragment(fragmentBytes)
      if (fragment.totalLength == 0) return ByteArray(0)

      val frame =
        pending.getOrPut(fragment.messageId) {
          PendingFrame(
            totalLength = fragment.totalLength,
            payload = ByteArray(fragment.totalLength),
            received = BooleanArray(fragment.totalLength),
          )
        }
      require(frame.totalLength == fragment.totalLength) {
        "GATT fragment total length changed for message ${fragment.messageId}"
      }

      var newBytes = 0
      for (index in fragment.chunk.indices) {
        val targetIndex = fragment.offset + index
        frame.payload[targetIndex] = fragment.chunk[index]
        if (!frame.received[targetIndex]) {
          frame.received[targetIndex] = true
          newBytes++
        }
      }
      frame.receivedBytes += newBytes

      if (frame.receivedBytes < frame.totalLength) return null
      pending.remove(fragment.messageId)
      return frame.payload
    }

    private fun decodeFragment(fragmentBytes: ByteArray): Fragment {
      require(fragmentBytes.size >= HEADER_BYTES) { "GATT fragment is shorter than $HEADER_BYTES bytes" }
      val buffer = ByteBuffer.wrap(fragmentBytes)
      val magic = buffer.get()
      val version = buffer.get()
      require(magic == MAGIC) { "Invalid GATT fragment magic" }
      require(version == VERSION) { "Unsupported GATT fragment version $version" }

      val messageId = buffer.int
      val totalLength = buffer.int
      val offset = buffer.int
      val chunk = ByteArray(fragmentBytes.size - HEADER_BYTES)
      buffer.get(chunk)

      require(totalLength in 0..MAX_FRAME_BYTES) { "Invalid GATT frame length $totalLength" }
      require(offset >= 0) { "Invalid GATT fragment offset $offset" }
      require(totalLength > 0 || chunk.isEmpty()) { "Empty GATT frame cannot carry a payload chunk" }
      require(totalLength == 0 || chunk.isNotEmpty()) { "Non-empty GATT frame cannot carry an empty payload chunk" }
      require(offset + chunk.size <= totalLength) { "GATT fragment exceeds declared frame length" }

      return Fragment(
        messageId = messageId,
        totalLength = totalLength,
        offset = offset,
        chunk = chunk,
      )
    }
  }

  private data class Fragment(
    val messageId: Int,
    val totalLength: Int,
    val offset: Int,
    val chunk: ByteArray,
  )

  private data class PendingFrame(
    val totalLength: Int,
    val payload: ByteArray,
    val received: BooleanArray,
    var receivedBytes: Int = 0,
  )
}
