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
  private val endpointNames = mutableMapOf<String, String>()

  override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

  override fun startAdvertising(displayName: String) {
    client.stopDiscovery()
    client
      .startAdvertising(
        displayName,
        SERVICE_ID,
        connectionLifecycleCallback,
        AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
      )
      .addOnFailureListener { emitFailure("Could not start advertising", it) }
  }

  override fun startDiscovery() {
    client.stopAdvertising()
    client
      .startDiscovery(
        SERVICE_ID,
        endpointDiscoveryCallback,
        DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
      )
      .addOnFailureListener { emitFailure("Could not start discovery", it) }
  }

  override fun requestConnection(endpoint: NearbyEndpoint, displayName: String) {
    endpointNames[endpoint.id] = endpoint.name
    client
      .requestConnection(displayName, endpoint.id, connectionLifecycleCallback)
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

  override fun stopAll() {
    client.stopAdvertising()
    client.stopDiscovery()
    client.stopAllEndpoints()
  }

  private val endpointDiscoveryCallback =
    object : EndpointDiscoveryCallback() {
      override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
        endpointNames[endpointId] = info.endpointName
        emit(TransportEvent.EndpointFound(NearbyEndpoint(endpointId, info.endpointName)))
      }

      override fun onEndpointLost(endpointId: String) {
        endpointNames.remove(endpointId)
        emit(TransportEvent.EndpointLost(endpointId))
      }
    }

  private val connectionLifecycleCallback =
    object : ConnectionLifecycleCallback() {
      override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        endpointNames[endpointId] = info.endpointName
        emit(
          TransportEvent.ConnectionInitiated(
            PendingConnection(
              endpointId = endpointId,
              endpointName = info.endpointName,
              authenticationToken = info.authenticationDigits,
            ),
          ),
        )
      }

      override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
        if (resolution.status.isSuccess) {
          emit(TransportEvent.Connected(NearbyEndpoint(endpointId, endpointNames[endpointId] ?: "Nearby device")))
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

  private companion object {
    const val SERVICE_ID = "com.example.offlinelink.NEARBY_CHAT"
    val STRATEGY: Strategy = Strategy.P2P_CLUSTER
  }
}
