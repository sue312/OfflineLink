package com.example.offlinelink.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DeviceLocation(
  val latitude: Double,
  val longitude: Double,
  val accuracy: Float?,
)

class LocationHelper(context: Context) {
  private val client: FusedLocationProviderClient =
    LocationServices.getFusedLocationProviderClient(context.applicationContext)

  @SuppressLint("MissingPermission")
  suspend fun currentLocation(): Result<DeviceLocation> =
    suspendCancellableCoroutine { continuation ->
      val request =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
          .setMinUpdateIntervalMillis(5_000L)
          .setMaxUpdates(1)
          .build()

      val callback =
        object : LocationCallback() {
          override fun onLocationResult(result: LocationResult) {
            val location: Location = result.lastLocation ?: return
            client.removeLocationUpdates(this)
            if (continuation.isActive) {
              continuation.resume(
                Result.success(
                  DeviceLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy else null,
                  ),
                ),
              )
            }
          }
        }

      client
        .requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { e ->
          client.removeLocationUpdates(callback)
          if (continuation.isActive) {
            continuation.resume(Result.failure(e))
          }
        }

      continuation.invokeOnCancellation {
        client.removeLocationUpdates(callback)
      }
    }
}
