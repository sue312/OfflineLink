package com.example.offlinelink.crypto

import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class EncryptedChatTransport(
  private val delegate: ChatTransport,
  scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ChatTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  private val lock = Any()
  private val sessions = mutableMapOf<String, SecureSession>()

  init {
    scope.launch { delegate.events.collect(::handleDelegateEvent) }
  }

  override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

  override fun startAdvertising(
    displayName: String,
    deviceId: String,
  ) = delegate.startAdvertising(displayName, deviceId)

  override fun startDiscovery() = delegate.startDiscovery()

  override fun stopAdvertising() = delegate.stopAdvertising()

  override fun stopDiscovery() = delegate.stopDiscovery()

  override fun startSignalMonitoring(
    displayName: String,
    deviceId: String,
  ) = delegate.startSignalMonitoring(displayName, deviceId)

  override fun stopSignalMonitoring() = delegate.stopSignalMonitoring()

  override fun requestConnection(
    endpoint: NearbyEndpoint,
    displayName: String,
    deviceId: String,
  ) = delegate.requestConnection(endpoint, displayName, deviceId)

  override fun acceptConnection(endpointId: String) = delegate.acceptConnection(endpointId)

  override fun rejectConnection(endpointId: String) = delegate.rejectConnection(endpointId)

  override fun send(
    endpointId: String,
    bytes: ByteArray,
    onResult: (Result<Unit>) -> Unit,
  ) {
    val encrypted =
      synchronized(lock) {
        sessions[endpointId]?.cipher?.encrypt(bytes)
      }
    if (encrypted == null) {
      onResult(Result.failure(IllegalStateException("Secure session is not established for $endpointId")))
      return
    }
    delegate.send(endpointId, encrypted, onResult)
  }

  override fun disconnectEndpoint(endpointId: String) {
    synchronized(lock) {
      sessions.remove(endpointId)
    }
    delegate.disconnectEndpoint(endpointId)
  }

  override fun stopAll() {
    synchronized(lock) {
      sessions.clear()
    }
    delegate.stopAll()
  }

  private fun handleDelegateEvent(event: TransportEvent) {
    when (event) {
      is TransportEvent.Connected -> handleConnected(event.endpoint)
      is TransportEvent.Disconnected -> {
        synchronized(lock) {
          sessions.remove(event.endpointId)
        }
        emit(event)
      }
      is TransportEvent.BytesReceived -> handleBytesReceived(event.endpointId, event.bytes)
      else -> emit(event)
    }
  }

  private fun handleConnected(endpoint: NearbyEndpoint) {
    val session =
      synchronized(lock) {
        sessions.getOrPut(endpoint.id) { SecureSession(endpoint = endpoint) }
          .also { it.endpoint = endpoint }
      }
    sendKeyExchangeIfNeeded(session)
    emitConnectedIfSecure(session)
  }

  private fun handleBytesReceived(
    endpointId: String,
    bytes: ByteArray,
  ) {
    val frame =
      runCatching { SecureWireFrame.decode(bytes) }
        .getOrElse {
          emit(TransportEvent.OperationFailed("Could not decode secure frame", it))
          return
        } ?: return

    when (frame) {
      is SecureWireFrame.KeyExchange -> handleKeyExchange(endpointId, frame.publicKeyBytes)
      is SecureWireFrame.Encrypted -> handleEncryptedPayload(endpointId, bytes)
    }
  }

  private fun handleKeyExchange(
    endpointId: String,
    remotePublicKeyBytes: ByteArray,
  ) {
    val session =
      synchronized(lock) {
        sessions.getOrPut(endpointId) { SecureSession(endpoint = NearbyEndpoint(endpointId, "Nearby device")) }
          .also { secureSession ->
            if (secureSession.cipher == null) {
              secureSession.cipher = secureSession.keyExchange.complete(remotePublicKeyBytes)
            }
          }
      }
    sendKeyExchangeIfNeeded(session)
    emitConnectedIfSecure(session)
  }

  private fun handleEncryptedPayload(
    endpointId: String,
    encryptedBytes: ByteArray,
  ) {
    val cipher =
      synchronized(lock) {
        sessions[endpointId]?.cipher
      }
    if (cipher == null) {
      emit(TransportEvent.OperationFailed("Received encrypted payload before secure session was established"))
      return
    }
    val plaintext =
      runCatching { cipher.decrypt(encryptedBytes) }
        .getOrElse {
          emit(TransportEvent.OperationFailed("Could not decrypt secure payload", it))
          return
        }
    emit(TransportEvent.BytesReceived(endpointId, plaintext))
  }

  private fun sendKeyExchangeIfNeeded(session: SecureSession) {
    val bytes =
      synchronized(lock) {
        if (session.publicKeySent) return
        session.publicKeySent = true
        SecureWireFrame.keyExchange(session.keyExchange.publicKeyBytes)
      }
    delegate.send(session.endpoint.id, bytes) { result ->
      result.onFailure { emit(TransportEvent.OperationFailed("Could not send secure key exchange", it)) }
    }
  }

  private fun emitConnectedIfSecure(session: SecureSession) {
    val endpoint =
      synchronized(lock) {
        if (session.cipher == null || session.connectedEmitted) return
        session.connectedEmitted = true
        session.endpoint
      }
    emit(TransportEvent.Connected(endpoint))
  }

  private fun emit(event: TransportEvent) {
    mutableEvents.tryEmit(event)
  }

  private data class SecureSession(
    var endpoint: NearbyEndpoint,
    val keyExchange: EcdhKeyExchange = EcdhKeyExchange(),
    var cipher: AesGcmCipher? = null,
    var publicKeySent: Boolean = false,
    var connectedEmitted: Boolean = false,
  )
}
