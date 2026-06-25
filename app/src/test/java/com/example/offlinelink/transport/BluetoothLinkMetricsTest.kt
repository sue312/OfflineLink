package com.example.offlinelink.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLinkMetricsTest {
  @Test
  fun rollsUpBytesWriteBlockingAndQueuePressureEverySecond() {
    var now = 1_000L
    val metrics = BluetoothLinkMetrics(clockMs = { now })

    metrics.recordEnqueued(queueLength = 3)
    metrics.recordWrite(payloadBytes = 700, writeBlockedMs = 180, queueLength = 2)
    now += 1_000L
    val report = metrics.rollWindowIfDue()!!

    assertEquals(700L, report.sentBytesPerSecond)
    assertEquals(180L, report.averageWriteBlockedMs)
    assertEquals(180L, report.maxWriteBlockedMs)
    assertEquals(2, report.writeQueueLength)
    assertEquals(3, report.maxWriteQueueLength)
    assertTrue(report.socketCongested)
  }
}
