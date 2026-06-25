package com.example.offlinelink.transport

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCodedPhyPreferenceTest {
  @Test
  fun requestsCodedPhyOnlyWhenApiDeviceAndPermissionAllowIt() {
    assertTrue(
      BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION_CODES.O,
        codedPhySupported = true,
        hasConnectPermission = true,
      ),
    )
    assertFalse(
      BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION_CODES.N_MR1,
        codedPhySupported = true,
        hasConnectPermission = true,
      ),
    )
    assertFalse(
      BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION_CODES.O,
        codedPhySupported = false,
        hasConnectPermission = true,
      ),
    )
    assertFalse(
      BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION_CODES.O,
        codedPhySupported = true,
        hasConnectPermission = false,
      ),
    )
  }
}
