package com.example.offlinelink.transport

internal object BluetoothScanCompatibilityPolicy {
  fun shouldRequestExtendedAdvertisements(
    useLongRangeScan: Boolean,
    @Suppress("UNUSED_PARAMETER")
    advertiserMayFallbackToLegacy: Boolean,
  ): Boolean = useLongRangeScan
}
