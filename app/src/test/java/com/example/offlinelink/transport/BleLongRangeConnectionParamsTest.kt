package com.example.offlinelink.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLongRangeConnectionParamsTest {
  @Test
  fun longRangeVoiceProfileUsesLongerIntervalAndTimeout() {
    assertEquals(48, BleLongRangeConnectionParams.MIN_INTERVAL_UNITS)
    assertEquals(72, BleLongRangeConnectionParams.MAX_INTERVAL_UNITS)
    assertEquals(0, BleLongRangeConnectionParams.PERIPHERAL_LATENCY)
    assertEquals(2_000, BleLongRangeConnectionParams.SUPERVISION_TIMEOUT_UNITS)
    assertEquals(60.0, BleLongRangeConnectionParams.intervalMs(BleLongRangeConnectionParams.MIN_INTERVAL_UNITS), 0.01)
    assertEquals(90.0, BleLongRangeConnectionParams.intervalMs(BleLongRangeConnectionParams.MAX_INTERVAL_UNITS), 0.01)
    assertEquals(20_000, BleLongRangeConnectionParams.supervisionTimeoutMs)
    assertTrue(BleLongRangeConnectionParams.supervisionTimeoutMs > 100 * 2)
  }
}
