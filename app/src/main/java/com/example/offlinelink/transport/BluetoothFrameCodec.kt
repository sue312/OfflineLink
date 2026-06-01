package com.example.offlinelink.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object BluetoothFrameCodec {
  const val MAX_FRAME_BYTES = 8 * 1024 * 1024
  private const val HEADER_BYTES = Int.SIZE_BYTES

  fun writeFrame(output: OutputStream, payload: ByteArray) {
    require(payload.size <= MAX_FRAME_BYTES) { "Bluetooth frame exceeds $MAX_FRAME_BYTES bytes" }
    output.write(ByteBuffer.allocate(HEADER_BYTES).putInt(payload.size).array())
    output.write(payload)
    output.flush()
  }

  fun readFrame(input: InputStream): ByteArray? {
    val header = ByteArray(HEADER_BYTES)
    if (!readFullyOrNull(input, header, "Bluetooth frame header")) return null
    val length = ByteBuffer.wrap(header).int
    require(length in 0..MAX_FRAME_BYTES) { "Invalid Bluetooth frame length $length" }

    val payload = ByteArray(length)
    if (length > 0) {
      if (!readFullyOrNull(input, payload, "Bluetooth frame payload")) {
        throw EOFException("Unexpected end of stream while reading Bluetooth frame payload")
      }
    }
    return payload
  }

  private fun readFullyOrNull(
    input: InputStream,
    buffer: ByteArray,
    label: String,
  ): Boolean {
    var offset = 0
    while (offset < buffer.size) {
      val count = input.read(buffer, offset, buffer.size - offset)
      if (count < 0) {
        if (offset == 0) return false
        throw EOFException("Unexpected end of stream while reading $label")
      }
      if (count == 0) {
        Thread.yield()
      } else {
        offset += count
      }
    }
    return true
  }
}
