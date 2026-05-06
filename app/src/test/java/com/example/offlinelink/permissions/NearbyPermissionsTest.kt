package com.example.offlinelink.permissions

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionsTest {
  @Test
  fun androidTAndAboveRequestsNearbyBluetoothAndWifiPermissions() {
    val permissions = requiredNearbyRuntimePermissionsForSdk(33)

    assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
    assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
    assertTrue(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
    assertTrue(permissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
    assertTrue(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
  }

  @Test
  fun androidQThroughSRequestsFineLocationForDiscovery() {
    val permissions = requiredNearbyRuntimePermissionsForSdk(30)

    assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
    assertFalse(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
  }
}
