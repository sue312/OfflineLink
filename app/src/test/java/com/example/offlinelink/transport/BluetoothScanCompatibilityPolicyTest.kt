package com.example.offlinelink.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothScanCompatibilityPolicyTest {
  @Test
  fun requestsExtendedAdvertisementsWhenLongRangeScanIsEnabledEvenIfAdvertiserCanFallback() {
    assertTrue(
      BluetoothScanCompatibilityPolicy.shouldRequestExtendedAdvertisements(
        useLongRangeScan = true,
        advertiserMayFallbackToLegacy = true,
      ),
    )
  }

  @Test
  fun doesNotRequestExtendedAdvertisementsWhenLongRangeScanIsDisabled() {
    assertFalse(
      BluetoothScanCompatibilityPolicy.shouldRequestExtendedAdvertisements(
        useLongRangeScan = false,
        advertiserMayFallbackToLegacy = true,
      ),
    )
  }

  @Test
  fun stillRequestsExtendedAdvertisementsForStrictExtendedCodedScanning() {
    assertTrue(
      BluetoothScanCompatibilityPolicy.shouldRequestExtendedAdvertisements(
        useLongRangeScan = true,
        advertiserMayFallbackToLegacy = false,
      ),
    )
  }
}
