package com.example.offlinelink.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GattFrameCodecTest {
  @Test
  fun fragmentsPayloadToFitGattValueSizeAndReassemblesIt() {
    val payload = ByteArray(55) { index -> index.toByte() }
    val fragments = GattFrameCodec.fragment(payload, maxValueBytes = GattFrameCodec.HEADER_BYTES + 9, messageId = 7)
    val reassembler = GattFrameCodec.Reassembler()

    assertEquals(7, fragments.size)
    fragments.dropLast(1).forEach { fragment ->
      assertNull(reassembler.accept(fragment))
    }
    assertArrayEquals(payload, reassembler.accept(fragments.last()))
  }

  @Test
  fun reassemblesInterleavedMessagesByMessageId() {
    val first = "first-payload".encodeToByteArray()
    val second = "second-payload".encodeToByteArray()
    val firstFragments = GattFrameCodec.fragment(first, maxValueBytes = GattFrameCodec.HEADER_BYTES + 5, messageId = 10)
    val secondFragments = GattFrameCodec.fragment(second, maxValueBytes = GattFrameCodec.HEADER_BYTES + 6, messageId = 11)
    val reassembler = GattFrameCodec.Reassembler()

    assertNull(reassembler.accept(firstFragments[0]))
    assertNull(reassembler.accept(secondFragments[0]))
    assertNull(reassembler.accept(firstFragments[1]))
    assertArrayEquals(first, reassembler.accept(firstFragments[2]))
    assertNull(reassembler.accept(secondFragments[1]))
    assertArrayEquals(second, reassembler.accept(secondFragments[2]))
  }

  @Test
  fun ignoresDuplicateFragmentsWithoutDoubleCounting() {
    val payload = ByteArray(16) { (it + 1).toByte() }
    val fragments = GattFrameCodec.fragment(payload, maxValueBytes = GattFrameCodec.HEADER_BYTES + 8, messageId = 99)
    val reassembler = GattFrameCodec.Reassembler()

    assertNull(reassembler.accept(fragments[0]))
    assertNull(reassembler.accept(fragments[0]))
    assertArrayEquals(payload, reassembler.accept(fragments[1]))
  }

  @Test
  fun rejectsMalformedFragments() {
    val goodFragment =
      GattFrameCodec.fragment(
        payload = "payload".encodeToByteArray(),
        maxValueBytes = GattFrameCodec.HEADER_BYTES + 16,
        messageId = 5,
      ).single()
    val badMagic = goodFragment.copyOf().also { it[0] = 0x00 }
    val tooShort = goodFragment.copyOf(GattFrameCodec.HEADER_BYTES - 1)

    assertThrows(IllegalArgumentException::class.java) {
      GattFrameCodec.Reassembler().accept(badMagic)
    }
    assertThrows(IllegalArgumentException::class.java) {
      GattFrameCodec.Reassembler().accept(tooShort)
    }
  }
}
