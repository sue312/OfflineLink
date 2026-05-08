package com.example.offlinelink.transport

import android.content.Context
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NearbyChatTransport(context: Context) : ChatTransport {
  private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  private val endpoints = mutableMapOf<String, AdvertisedEndpoint>()
  private var localAdvertisedName = advertisedEndpointName("OfflineLink", "")

  override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

  override fun startAdvertising(
    displayName: String,
    deviceId: String,
  ) {
    localAdvertisedName = advertisedEndpointName(displayName, deviceId)
    client
      .startAdvertising(
        localAdvertisedName,
        SERVICE_ID,
        connectionLifecycleCallback,
        AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
      )
      .addOnFailureListener { emitFailure("Could not start advertising", it) }
  }

  override fun startDiscovery() {
    client
      .startDiscovery(
        SERVICE_ID,
        endpointDiscoveryCallback,
        DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
      )
      .addOnFailureListener { emitFailure("Could not start discovery", it) }
  }

  override fun stopAdvertising() {
    client.stopAdvertising()
  }

  override fun stopDiscovery() {
    client.stopDiscovery()
  }

  override fun requestConnection(
    endpoint: NearbyEndpoint,
    displayName: String,
    deviceId: String,
  ) {
    endpoints[endpoint.id] = AdvertisedEndpoint(endpoint.name, endpoint.deviceId)
    val connectionName = advertisedEndpointName(displayName, deviceId)
    client
      .requestConnection(connectionName, endpoint.id, connectionLifecycleCallback)
      .addOnFailureListener { emitFailure("Could not request connection", it) }
  }

  override fun acceptConnection(endpointId: String) {
    client.acceptConnection(endpointId, payloadCallback).addOnFailureListener { emitFailure("Could not accept connection", it) }
  }

  override fun rejectConnection(endpointId: String) {
    client.rejectConnection(endpointId)
  }

  override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
    client
      .sendPayload(endpointId, Payload.fromBytes(bytes))
      .addOnSuccessListener { onResult(Result.success(Unit)) }
      .addOnFailureListener { onResult(Result.failure(it)) }
  }

  override fun disconnectEndpoint(endpointId: String) {
    client.disconnectFromEndpoint(endpointId)
  }

  override fun stopAll() {
    client.stopAdvertising()
    client.stopDiscovery()
    client.stopAllEndpoints()
  }

  private val endpointDiscoveryCallback =
    object : EndpointDiscoveryCallback() {
      override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
        val endpoint = parseAdvertisedEndpoint(info.endpointName)
        endpoints[endpointId] = endpoint
        emit(TransportEvent.EndpointFound(NearbyEndpoint(endpointId, endpoint.displayName, endpoint.deviceId)))
      }

      override fun onEndpointLost(endpointId: String) {
        endpoints.remove(endpointId)
        emit(TransportEvent.EndpointLost(endpointId))
      }
    }

  private val connectionLifecycleCallback =
    object : ConnectionLifecycleCallback() {
      override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        val endpoint = parseAdvertisedEndpoint(info.endpointName)
        endpoints[endpointId] = endpoint
        emit(
          TransportEvent.ConnectionInitiated(
            PendingConnection(
              endpointId = endpointId,
              endpointName = endpoint.displayName,
              authenticationToken = info.authenticationDigits,
              deviceId = endpoint.deviceId,
            ),
          ),
        )
      }

      override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
        if (resolution.status.isSuccess) {
          val endpoint = endpoints[endpointId]
          emit(TransportEvent.Connected(NearbyEndpoint(endpointId, endpoint?.displayName ?: "Nearby device", endpoint?.deviceId)))
        } else {
          emitFailure("Connection failed: ${resolution.status.statusMessage ?: resolution.status.statusCode}")
        }
      }

      override fun onDisconnected(endpointId: String) {
        emit(TransportEvent.Disconnected(endpointId))
      }
    }

  private val payloadCallback =
    object : PayloadCallback() {
      override fun onPayloadReceived(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes() ?: return
        emit(TransportEvent.BytesReceived(endpointId, bytes))
      }

      override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

  private fun emitFailure(message: String, throwable: Throwable? = null) {
    emit(TransportEvent.OperationFailed(message, throwable))
  }

  private fun emit(event: TransportEvent) {
    mutableEvents.tryEmit(event)
  }

  private data class AdvertisedEndpoint(
    val displayName: String,
    val deviceId: String?,
  )

  private companion object {
    const val SERVICE_ID = "com.example.offlinelink.NEARBY_CHAT"
    const val ADVERTISED_NAME_PREFIX = "OL1:"
    val STRATEGY: Strategy = Strategy.P2P_CLUSTER

    fun advertisedEndpointName(
      displayName: String,
      deviceId: String,
    ): String = "$ADVERTISED_NAME_PREFIX$deviceId:${displayName.ifBlank { "Nearby device" }}"

    fun parseAdvertisedEndpoint(endpointName: String): AdvertisedEndpoint {
      if (!endpointName.startsWith(ADVERTISED_NAME_PREFIX)) {
        return AdvertisedEndpoint(displayName = endpointName.ifBlank { "Nearby device" }, deviceId = null)
      }
      val payload = endpointName.removePrefix(ADVERTISED_NAME_PREFIX)
      val separatorIndex = payload.indexOf(':')
      if (separatorIndex <= 0) {
        return AdvertisedEndpoint(displayName = endpointName.ifBlank { "Nearby device" }, deviceId = null)
      }
      val deviceId = payload.substring(0, separatorIndex).ifBlank { null }
      val displayName = payload.substring(separatorIndex + 1).ifBlank { "Nearby device" }
      return AdvertisedEndpoint(displayName = displayName, deviceId = deviceId)
    }
  }
}
