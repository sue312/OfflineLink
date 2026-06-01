package com.example.offlinelink.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothFrameCodecTest {
  @Test
  fun readsMultipleLengthPrefixedFramesFromOneStream() {
    val output = ByteArrayOutputStream()

    BluetoothFrameCodec.writeFrame(output, "hello".encodeToByteArray())
    BluetoothFrameCodec.writeFrame(output, byteArrayOf(1, 2, 3))

    val input = ByteArrayInputStream(output.toByteArray())
    assertArrayEquals("hello".encodeToByteArray(), BluetoothFrameCodec.readFrame(input))
    assertArrayEquals(byteArrayOf(1, 2, 3), BluetoothFrameCodec.readFrame(input))
    assertNull(BluetoothFrameCodec.readFrame(input))
  }

  @Test
  fun rejectsFramesLargerThanMaximumPayload() {
    val header = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(BluetoothFrameCodec.MAX_FRAME_BYTES + 1).array()

    assertThrows(IllegalArgumentException::class.java) {
      BluetoothFrameCodec.readFrame(ByteArrayInputStream(header))
    }
  }

  @Test
  fun throwsWhenPayloadEndsBeforeDeclaredLength() {
    val streamBytes =
      ByteBuffer
        .allocate(Int.SIZE_BYTES + 1)
        .putInt(3)
        .put(7)
        .array()

    assertThrows(EOFException::class.java) {
      BluetoothFrameCodec.readFrame(ByteArrayInputStream(streamBytes))
    }
  }
}
