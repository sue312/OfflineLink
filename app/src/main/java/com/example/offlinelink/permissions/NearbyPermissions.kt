package com.example.offlinelink.permissions

import android.Manifest
import android.os.Build

fun requiredNearbyRuntimePermissions(): Array<String> =
  requiredNearbyRuntimePermissionsForSdk(Build.VERSION.SDK_INT).toTypedArray()

fun requiredNearbyRuntimePermissionsForSdk(sdkInt: Int): List<String> =
  buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (sdkInt >= 31) {
      add(Manifest.permission.BLUETOOTH_ADVERTISE)
      add(Manifest.permission.BLUETOOTH_CONNECT)
      add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (sdkInt >= 33) {
      add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
  }
