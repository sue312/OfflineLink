package com.example.offlinelink.transport

import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import kotlinx.coroutines.flow.Flow

interface ChatTransport {
  val events: Flow<TransportEvent>

  fun startAdvertising(
    displayName: String,
    deviceId: String,
  )

  fun startDiscovery()

  fun stopDiscovery()

  fun requestConnection(endpoint: NearbyEndpoint, displayName: String)

  fun acceptConnection(endpointId: String)

  fun rejectConnection(endpointId: String)

  fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit)

  fun stopAll()
}

sealed interface TransportEvent {
  data class EndpointFound(val endpoint: NearbyEndpoint) : TransportEvent

  data class EndpointLost(val endpointId: String) : TransportEvent

  data class ConnectionInitiated(val pendingConnection: PendingConnection) : TransportEvent

  data class Connected(val endpoint: NearbyEndpoint) : TransportEvent

  data class Disconnected(val endpointId: String) : TransportEvent

  data class BytesReceived(val endpointId: String, val bytes: ByteArray) : TransportEvent

  data class OperationFailed(val message: String, val throwable: Throwable? = null) : TransportEvent
}
