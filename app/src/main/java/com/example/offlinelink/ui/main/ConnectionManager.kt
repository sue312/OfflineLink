package com.example.offlinelink.ui.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinelink.audio.isStreamingCallAudioMimeType
import com.example.offlinelink.chat.ChatSessionStore
import com.example.offlinelink.data.ChatHistoryRepository
import com.example.offlinelink.data.NoOpChatHistoryRepository
import com.example.offlinelink.data.PayloadCache
import com.example.offlinelink.image.ImageCompressor
import com.example.offlinelink.location.DeviceLocation
import com.example.offlinelink.model.CallAudioPlaybackFrame
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.CallVoicePlayback
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.CallAudioFrameRedundancy
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.protocol.WireMember
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.LatestPayloadSender
import com.example.offlinelink.transport.OfflineLinkBluetoothService
import com.example.offlinelink.transport.PriorityPayloadSender
import com.example.offlinelink.transport.PriorityPayloadSender.PayloadPriority
import com.example.offlinelink.transport.TransportEvent
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ConnectionManager {
  fun handleTransportEvent(
    viewModel: MainScreenViewModel,
    event: TransportEvent,
  ) = viewModel.handleTransportEvent(event)

  fun startPeerRecovery(
    viewModel: MainScreenViewModel,
    memberName: String,
  ) = viewModel.startPeerRecovery(memberName)
}

internal fun MainScreenViewModel.normalizedDeviceId(deviceId: String?): String? =
  deviceId?.trim()?.takeIf { it.isNotEmpty() }

internal fun MainScreenViewModel.connectedEndpointForSameDevice(
  endpointId: String,
  deviceId: String?,
): NearbyEndpoint? {
  val stableDeviceId = normalizedDeviceId(deviceId) ?: return null
  return uiState.value.connectedEndpoints.firstOrNull { endpoint ->
    endpoint.id != endpointId && normalizedDeviceId(endpoint.deviceId) == stableDeviceId
  }
}

internal fun MainScreenViewModel.isConnectedToDifferentEndpoint(
  endpointId: String,
  deviceId: String? = null,
): Boolean {
  val connectedEndpoints = uiState.value.connectedEndpoints
  if (connectedEndpoints.isEmpty()) return false
  if (connectedEndpoints.any { it.id == endpointId }) return false
  return connectedEndpointForSameDevice(endpointId, deviceId) == null
}

internal fun MainScreenViewModel.isFromNonPeer(endpointId: String): Boolean =
  uiState.value.connectedEndpoints.isNotEmpty() && uiState.value.connectedEndpoints.none { it.id == endpointId }

internal fun MainScreenViewModel.connectedEndpointWithLastSignal(endpoint: NearbyEndpoint): NearbyEndpoint {
  if (endpoint.rssi != null && endpoint.signalId != null) return endpoint
  val lastKnownEndpoint = uiState.value.discoveredEndpoints.firstOrNull { discoveredEndpoint ->
    discoveredEndpoint.id == endpoint.id ||
      (!endpoint.deviceId.isNullOrBlank() && discoveredEndpoint.deviceId == endpoint.deviceId)
  }
  return lastKnownEndpoint?.let { lastKnown ->
    endpoint.copy(
      rssi = endpoint.rssi ?: lastKnown.rssi,
      signalId = endpoint.signalId ?: lastKnown.signalId,
    )
  } ?: endpoint
}

internal fun MainScreenViewModel.handleTransportEvent(event: TransportEvent) {
  when (event) {
    is TransportEvent.EndpointFound -> {
      if (isLocalDevice(event.endpoint)) {
        store.removeEndpoint(event.endpoint.id)
        return
      }
      if (uiState.value.connectedEndpoints.isNotEmpty()) {
        store.removeEndpoint(event.endpoint.id)
        return
      }
      store.upsertEndpoint(event.endpoint)
      maybeConnectToRecoveryEndpoint(event.endpoint)
    }
    is TransportEvent.EndpointSignalChanged -> {
      updateConnectedEndpointSignal(event)
    }
    is TransportEvent.EndpointLost -> store.removeEndpoint(event.endpointId)
    is TransportEvent.ConnectionInitiated -> {
      handleConnectionInitiated(event.pendingConnection)
    }
    is TransportEvent.Connected -> {
      if (isLocalDevice(event.endpoint)) {
        clearPayloadSenders(event.endpoint.id)
        return
      }
      if (isConnectedToDifferentEndpoint(event.endpoint.id, event.endpoint.deviceId)) {
        transport.disconnectEndpoint(event.endpoint.id)
        clearPayloadSenders(event.endpoint.id)
        return
      }
      connectedEndpointForSameDevice(event.endpoint.id, event.endpoint.deviceId)?.let { staleEndpoint ->
        clearPayloadSenders(staleEndpoint.id)
        endpointMemberIds.remove(staleEndpoint.id)
        transport.disconnectEndpoint(staleEndpoint.id)
      }
      val connectedEndpoint = connectedEndpointWithLastSignal(event.endpoint)
      lastPeerEndpoint = connectedEndpoint
      trustDevice(connectedEndpoint.deviceId)
      recoveryMode = false
      recoveryConnectionAttempts.remove(connectedEndpoint.id)
      stopAdvertisingAndDiscovery()
      store.addConnectedEndpoint(connectedEndpoint)
      startConnectedSignalMonitoring()
      sendHello(connectedEndpoint.id)
      retryUndeliveredMessages()
    }
    is TransportEvent.Disconnected -> handleEndpointDisconnected(event.endpointId)
    is TransportEvent.BytesReceived -> handleIncomingBytes(event.endpointId, event.bytes)
    is TransportEvent.OperationFailed -> {
      if (event.isAlreadyRunningNearbyOperation()) return
      if (uiState.value.connectedEndpoints.isNotEmpty()) {
        store.restoreConnectedStatus()
        return
      }
      if (recoveryMode && event.isConnectionAttemptFailure()) {
        recoveryConnectionAttempts.clear()
        store.setStatus(ConnectionStatus.Discovering, "Reconnecting to ${lastPeerEndpoint?.name ?: "peer"}")
        return
      }
      store.setStatus(
        ConnectionStatus.Error,
        event.message,
        event.throwable?.message,
      )
    }
  }
}

internal fun MainScreenViewModel.ensureEndpointConnected(
  endpointId: String,
  displayName: String = "Nearby device",
) {
  if (uiState.value.connectedEndpoints.any { it.id == endpointId }) return
  if (isConnectedToDifferentEndpoint(endpointId)) return
  recoveryMode = false
  recoveryConnectionAttempts.remove(endpointId)
  stopAdvertisingAndDiscovery()
  val endpoint = NearbyEndpoint(endpointId, displayName.ifBlank { "Nearby device" })
  lastPeerEndpoint = endpoint
  store.addConnectedEndpoint(endpoint)
  retryUndeliveredMessages()
}

internal fun MainScreenViewModel.mergeIncomingRoster(endpointId: String, hello: DecodedWireMessage.Hello): Boolean {
  endpointMemberIds[endpointId] = hello.senderId
  lastPeerEndpoint = uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.copy(deviceId = hello.senderId) ?: lastPeerEndpoint
  return store.replaceGroupMember(endpointId, GroupMember(hello.senderId, hello.displayName, GroupMemberStatus.Online))
}

internal fun MainScreenViewModel.updateConnectedEndpointSignal(event: TransportEvent.EndpointSignalChanged) {
  val matchedEndpointId =
    event.signalId?.let { signalId ->
      uiState.value.connectedEndpoints.firstOrNull { endpoint ->
        endpoint.signalId == signalId ||
          endpointMemberIds[endpoint.id]?.let(OfflineLinkBluetoothService::signalIdForDeviceId) == signalId
      }?.id
    }
  store.updateConnectedEndpointSignal(matchedEndpointId ?: event.endpointId, event.deviceId, event.rssi, event.signalId)
}
internal fun MainScreenViewModel.handleConnectionInitiated(pendingConnection: PendingConnection) {
  if (pendingConnection.deviceId == uiState.value.localDeviceId) {
    rejectIncomingConnection(pendingConnection.endpointId)
    return
  }
  if (isConnectedToDifferentEndpoint(pendingConnection.endpointId, pendingConnection.deviceId)) {
    rejectIncomingConnection(pendingConnection.endpointId)
    store.restoreConnectedStatus()
    return
  }
  if (shouldAutoAcceptIncomingConnection(pendingConnection)) {
    store.setPendingConnection(null)
    store.setStatus(
      ConnectionStatus.Connecting,
      if (recoveryMode) "Rejoining ${pendingConnection.endpointName}" else "Connecting to ${pendingConnection.endpointName}",
    )
    trustDevice(pendingConnection.deviceId)
    transport.acceptConnection(pendingConnection.endpointId)
  } else if (uiState.value.isVisibleToNearby) {
    store.setPendingConnection(pendingConnection)
    store.setStatus(ConnectionStatus.Connecting, "Confirm ${pendingConnection.endpointName}")
  } else {
    rejectIncomingConnection(pendingConnection.endpointId)
  }
}

internal fun MainScreenViewModel.shouldAutoAcceptIncomingConnection(pendingConnection: PendingConnection): Boolean {
  if (recoveryMode) {
    return isExpectedRecoveryEndpoint(pendingConnection.endpointName, pendingConnection.deviceId)
  }
  val stableDeviceId = normalizedDeviceId(pendingConnection.deviceId)
  if (stableDeviceId != null &&
    trustedDeviceIds.contains(stableDeviceId) &&
    connectedEndpointForSameDevice(pendingConnection.endpointId, pendingConnection.deviceId) != null
  ) {
    return true
  }
  return recoveryConnectionAttempts.contains(pendingConnection.endpointId) ||
    (stableDeviceId != null && uiState.value.isVisibleToNearby && trustedDeviceIds.contains(stableDeviceId))
}

internal fun MainScreenViewModel.rejectIncomingConnection(endpointId: String) {
  transport.rejectConnection(endpointId)
  store.setPendingConnection(null)
}

internal fun MainScreenViewModel.trustDevice(deviceId: String?) {
  val stableDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: return
  if (trustedDeviceIds.add(stableDeviceId)) {
    onTrustedDeviceIdsChanged(trustedDeviceIds.toSet())
  }
}

internal fun MainScreenViewModel.broadcastHello() {
  uiState.value.connectedEndpoints.forEach { endpoint ->
    sendHello(endpoint.id)
  }
}

internal fun MainScreenViewModel.sendHello(endpointId: String) {
  sendPayload(
    endpointId,
    ChatProtocol.encodeHello(
      senderId = uiState.value.localDeviceId,
      displayName = uiState.value.displayName,
      groupName = uiState.value.groupName,
      members = currentRosterMembers(),
    ),
    PayloadPriority.Control,
  ) { }
}

internal fun MainScreenViewModel.currentRosterMembers(): List<WireMember> {
  val state = uiState.value
  return listOf(WireMember(state.localDeviceId, state.displayName))
}

internal fun MainScreenViewModel.handleEndpointDisconnected(
  endpointId: String,
  shouldReconnect: Boolean = true,
) {
  clearPayloadSenders(endpointId)
  val stateBeforeRemoval = uiState.value
  val disconnectedEndpoint = stateBeforeRemoval.connectedEndpoints.firstOrNull { it.id == endpointId }
  val wasConnected = disconnectedEndpoint != null
  val mappedMemberId = endpointMemberIds.remove(endpointId)
  val previousPeer = lastPeerEndpoint
  val disconnectedDisplayName =
    mappedMemberId?.let { memberId -> stateBeforeRemoval.groupMembers.firstOrNull { it.id == memberId }?.displayName }
      ?: disconnectedEndpoint?.name
      ?: "Nearby device"
  if (!wasConnected && mappedMemberId == null) return
  if (wasConnected) {
    lastPeerEndpoint =
      disconnectedEndpoint.copy(
        name = disconnectedDisplayName,
        deviceId = previousPeer?.deviceId ?: disconnectedEndpoint.deviceId ?: mappedMemberId,
      )
    stopConnectedSignalMonitoring()
  }
  store.removeConnectedEndpoint(endpointId)
  if (shouldReconnect && wasConnected) {
    startPeerRecovery(disconnectedDisplayName)
  } else {
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    if (uiState.value.connectedEndpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Disconnected, "Disconnected")
    }
  }
}

internal fun MainScreenViewModel.startPeerRecovery(memberName: String) {
  recoveryMode = true
  recoveryConnectionAttempts.clear()
  store.setStatus(ConnectionStatus.Discovering, "Reconnecting $memberName")
  store.setDiscoveredEndpoints(emptyList())
  if (uiState.value.isVisibleToNearby) {
    transport.startAdvertising(uiState.value.displayName, uiState.value.localDeviceId)
  }
  transport.startDiscovery()
}

internal fun MainScreenViewModel.maybeConnectToRecoveryEndpoint(endpoint: NearbyEndpoint) {
  if (!recoveryMode) return
  if (uiState.value.connectedEndpoints.any { it.id == endpoint.id }) return
  if (!isExpectedRecoveryEndpoint(endpoint.name, endpoint.deviceId)) return
  if (!recoveryConnectionAttempts.add(endpoint.id)) return
  connectTo(endpoint)
}

internal fun MainScreenViewModel.isExpectedRecoveryEndpoint(
  endpointName: String,
  deviceId: String? = null,
): Boolean {
  if (!recoveryMode) return true
  val expectedPeer = lastPeerEndpoint ?: return true
  if (!deviceId.isNullOrBlank() && deviceId == expectedPeer.deviceId) return true
  return endpointName.trim() == expectedPeer.name.trim()
}

internal fun MainScreenViewModel.isGroupMismatch(localGroupName: String, remoteGroupName: String): Boolean =
  localGroupName != MainScreenViewModel.DEFAULT_GROUP_NAME &&
    remoteGroupName != MainScreenViewModel.DEFAULT_GROUP_NAME &&
    localGroupName != remoteGroupName

internal fun TransportEvent.OperationFailed.isAlreadyRunningNearbyOperation(): Boolean {
  val details = "${message} ${throwable?.message.orEmpty()}"
  return "STATUS_ALREADY_ADVERTISING" in details || "STATUS_ALREADY_DISCOVERING" in details
}

internal fun TransportEvent.OperationFailed.isConnectionAttemptFailure(): Boolean =
  message.startsWith("Could not request connection") || message.startsWith("Connection failed")

internal fun MainScreenViewModel.stopAdvertisingAndDiscovery() {
  transport.stopAdvertising()
  transport.stopDiscovery()
}

internal fun MainScreenViewModel.startConnectedSignalMonitoring() {
  transport.startSignalMonitoring(uiState.value.displayName, uiState.value.localDeviceId)
}

internal fun MainScreenViewModel.stopConnectedSignalMonitoring() {
  transport.stopSignalMonitoring()
}

internal fun MainScreenViewModel.isLocalDevice(endpoint: NearbyEndpoint): Boolean = endpoint.deviceId == uiState.value.localDeviceId
