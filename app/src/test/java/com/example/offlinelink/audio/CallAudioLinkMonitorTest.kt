package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioLinkMonitorTest {
  @Test
  fun insertsConcealmentFramesForSequenceGaps() {
    var nowMs = 1_000L
    val monitor = CallAudioLinkMonitor(clockMs = { nowMs })

    val first = monitor.process(pcmFrame(sample = 1_000), sequenceNumber = 1)
    nowMs += 60
    val repaired = monitor.process(pcmFrame(sample = 2_000), sequenceNumber = 3)

    assertEquals(1, first.size)
    assertEquals(2, repaired.size)
    assertTrue(repaired.first().firstSample() in 600..900)
    assertEquals(2_000, repaired.last().firstSample())

    val stats = monitor.snapshot()
    assertEquals(2, stats.receivedFrames)
    assertEquals(1, stats.lostFrames)
    assertEquals(1, stats.concealedFrames)
    assertEquals(60L, stats.maxInterArrivalMs)
  }

  @Test
  fun limitsConcealmentForLargeGapsToAvoidRunawayDelay() {
    val monitor = CallAudioLinkMonitor(maxConcealedGapFrames = 3)

    monitor.process(pcmFrame(sample = 1_000), sequenceNumber = 10)
    val repaired = monitor.process(pcmFrame(sample = 2_000), sequenceNumber = 20)

    assertEquals(4, repaired.size)
    assertEquals(9, monitor.snapshot().lostFrames)
    assertEquals(3, monitor.snapshot().concealedFrames)
  }

  @Test
  fun dropsLateOutOfOrderFrames() {
    val monitor = CallAudioLinkMonitor()

    monitor.process(pcmFrame(sample = 1_000), sequenceNumber = 5)
    monitor.process(pcmFrame(sample = 2_000), sequenceNumber = 6)
    val repaired = monitor.process(pcmFrame(sample = 3_000), sequenceNumber = 5)

    assertEquals(emptyList<PcmAudioFrame>(), repaired)
    assertEquals(1, monitor.snapshot().lateFrames)
  }

  private fun pcmFrame(sample: Short): PcmAudioFrame {
    val bytes = ByteArray(callAudioPcmFrameBytes(sampleRateHz = 16_000))
    var index = 0
    while (index + 1 < bytes.size) {
      bytes[index] = (sample.toInt() and 0xff).toByte()
      bytes[index + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return PcmAudioFrame(bytes = bytes, sampleRateHz = 16_000)
  }

  private fun PcmAudioFrame.firstSample(): Int {
    val lo = bytes[0].toInt() and 0xff
    val hi = bytes[1].toInt()
    return ((hi shl 8) or lo).toShort().toInt()
  }
}
