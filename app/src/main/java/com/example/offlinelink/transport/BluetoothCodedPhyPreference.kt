package com.example.offlinelink.transport

import android.os.Build

object BluetoothCodedPhyPreference {
  fun shouldRequest(
    sdkInt: Int,
    codedPhySupported: Boolean,
    hasConnectPermission: Boolean,
  ): Boolean =
    sdkInt >= Build.VERSION_CODES.O &&
      codedPhySupported &&
      hasConnectPermission
}
