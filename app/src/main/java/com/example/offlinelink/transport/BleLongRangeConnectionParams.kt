package com.example.offlinelink.transport

internal object BleLongRangeConnectionParams {
  const val MIN_INTERVAL_UNITS = 48
  const val MAX_INTERVAL_UNITS = 72
  const val PERIPHERAL_LATENCY = 0
  const val SUPERVISION_TIMEOUT_UNITS = 2_000
  const val MIN_CONNECTION_EVENT_LENGTH_UNITS = 0
  const val MAX_CONNECTION_EVENT_LENGTH_UNITS = 0

  val supervisionTimeoutMs: Int = SUPERVISION_TIMEOUT_UNITS * 10

  fun intervalMs(units: Int): Double = units * 1.25
}
