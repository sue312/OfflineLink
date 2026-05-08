package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallAudioJitterBufferTest {
  @Test
  fun holdsInitialFramesUntilTargetBufferIsReached() {
    val buffer = CallAudioJitterBuffer(targetBufferedMs = 60, maxBufferedMs = 200)

    buffer.enqueue(pcmFrame(label = 1))
    buffer.enqueue(pcmFrame(label = 2))

    assertNull(buffer.pollReady())

    buffer.enqueue(pcmFrame(label = 3))

    assertEquals(1, buffer.pollReady()?.label())
    assertEquals(2, buffer.pollReady()?.label())
  }

  @Test
  fun rePrimesAfterPlaybackUnderrun() {
    val buffer = CallAudioJitterBuffer(targetBufferedMs = 40, maxBufferedMs = 200)

    buffer.enqueue(pcmFrame(label = 1))
    buffer.enqueue(pcmFrame(label = 2))

    assertEquals(1, buffer.pollReady()?.label())
    assertEquals(2, buffer.pollReady()?.label())
    assertNull(buffer.pollReady())

    buffer.enqueue(pcmFrame(label = 3))

    assertNull(buffer.pollReady())
  }

  @Test
  fun dropsOldestFramesWhenBufferWouldGrowTooLarge() {
    val buffer = CallAudioJitterBuffer(targetBufferedMs = 40, maxBufferedMs = 80)

    repeat(6) { index ->
      buffer.enqueue(pcmFrame(label = index + 1))
    }

    assertEquals(80, buffer.bufferedDurationMs)
    assertEquals(3, buffer.pollReady()?.label())
    assertEquals(4, buffer.pollReady()?.label())
    assertEquals(5, buffer.pollReady()?.label())
    assertEquals(6, buffer.pollReady()?.label())
  }

  private fun pcmFrame(label: Int): PcmAudioFrame =
    PcmAudioFrame(
      bytes = ByteArray(callAudioPcmFrameBytes(sampleRateHz = 16_000)) { label.toByte() },
      sampleRateHz = 16_000,
    )

  private fun PcmAudioFrame.label(): Int = bytes.first().toInt()
}
