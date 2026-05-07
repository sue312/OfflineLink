package com.example.offlinelink.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinelink.audio.isStreamingCallAudioMimeType
import com.example.offlinelink.chat.ChatSessionStore
import com.example.offlinelink.data.ChatHistoryRepository
import com.example.offlinelink.data.NoOpChatHistoryRepository
import com.example.offlinelink.image.ImageCompressor
import android.net.Uri
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
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.protocol.WireMember
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalEncodingApi::class)
class MainScreenViewModel(
  private val transport: ChatTransport,
  private val requestLocation: suspend () -> Result<DeviceLocation>,
  private val compressImage: (Uri) -> Result<com.example.offlinelink.image.CompressedImage>,
  localDeviceId: String = UUID.randomUUID().toString(),
  defaultDisplayName: String = "OfflineLink",
  defaultAvatarName: String = "",
  private val historyRepository: ChatHistoryRepository = NoOpChatHistoryRepository,
) : ViewModel() {
  private val store = ChatSessionStore(localDeviceId = localDeviceId)
  private val endpointMemberIds = mutableMapOf<String, String>()
  private var recoveryMode = false
  private val recoveryConnectionAttempts = mutableSetOf<String>()
  private val handledCallVoiceClipIds = mutableSetOf<String>()
  private val retriedMessageEndpointIds = mutableMapOf<String, MutableSet<String>>()
  private val mutableCallAudioFrames =
    MutableSharedFlow<CallAudioPlaybackFrame>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  val uiState: StateFlow<com.example.offlinelink.model.ChatUiState> = store.state
  val callAudioFrames: SharedFlow<CallAudioPlaybackFrame> = mutableCallAudioFrames.asSharedFlow()

  init {
    store.setDisplayName(defaultDisplayName)
    store.setAvatarName(defaultAvatarName)
    store.loadMessages(historyRepository.loadMessages())
    viewModelScope.launch {
      store.state
        .map { it.messages }
        .distinctUntilChanged()
        .collect { messages ->
          historyRepository.saveMessages(messages)
        }
    }
    viewModelScope.launch {
      transport.events.collect(::handleTransportEvent)
    }
  }

  fun setDisplayName(displayName: String) {
    store.setDisplayName(displayName)
  }

  fun setAvatarName(avatarName: String) {
    store.setAvatarName(avatarName)
  }

  fun setGroupName(groupName: String) {
    store.setGroupName(groupName)
    store.setGroupWarning(null)
    broadcastHello()
  }

  fun startAdvertising() {
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    store.setStatus(ConnectionStatus.Advertising, "Visible as ${uiState.value.displayName}")
    transport.startAdvertising(uiState.value.displayName)
  }

  fun startDiscovery() {
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    store.setStatus(ConnectionStatus.Discovering, "Searching nearby devices")
    store.setDiscoveredEndpoints(emptyList())
    transport.startDiscovery()
  }

  fun recoverGroup() {
    if (uiState.value.groupMembers.isEmpty() && uiState.value.connectedEndpoints.isEmpty()) {
      startDiscovery()
      return
    }
    if (uiState.value.connectedEndpoints.isNotEmpty()) {
      broadcastHello()
      retryUndeliveredMessages()
      store.setStatus(ConnectionStatus.Connected, "Group status refreshed")
      return
    }
    startGroupRecovery()
  }

  fun connectTo(endpoint: NearbyEndpoint) {
    recoveryConnectionAttempts.add(endpoint.id)
    store.setStatus(ConnectionStatus.Connecting, "Connecting to ${endpoint.name}")
    transport.requestConnection(endpoint, uiState.value.displayName)
  }

  fun acceptPendingConnection() {
    val pending = uiState.value.pendingConnection ?: return
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    store.setStatus(ConnectionStatus.Connecting, "Accepting ${pending.endpointName}")
    transport.acceptConnection(pending.endpointId)
  }

  fun rejectPendingConnection() {
    val pending = uiState.value.pendingConnection ?: return
    transport.rejectConnection(pending.endpointId)
    store.setPendingConnection(null)
    store.setStatus(ConnectionStatus.Idle, "Connection rejected")
  }

  fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val endpoints = uiState.value.connectedEndpoints
    if (endpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      return
    }

    val message = store.queueOutgoingMessage(trimmed)
    val bytes =
      ChatProtocol.encodeMessage(
        messageId = message.id,
        conversationId = message.conversationId,
        senderId = message.senderId,
        text = message.text,
        createdAt = message.createdAt,
      )
    val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
    var hasFailure = false
    endpoints.forEach { endpoint ->
      transport.send(endpoint.id, bytes) { result ->
        if (result.isFailure) {
          hasFailure = true
          store.setStatus(ConnectionStatus.Error, "Message failed", result.exceptionOrNull()?.message)
        }
        pendingEndpointIds.remove(endpoint.id)
        if (pendingEndpointIds.isEmpty()) {
          if (hasFailure) {
            store.markFailed(message.id)
          } else {
            store.markSent(message.id)
          }
        }
      }
    }
  }

  fun sendVoiceMessage(
    audioBytes: ByteArray,
    durationMs: Long,
    mimeType: String = VOICE_MIME_TYPE,
  ) {
    sendVoiceMessageTo(
      endpoints = uiState.value.connectedEndpoints,
      audioBytes = audioBytes,
      durationMs = durationMs,
      mimeType = mimeType,
      failureStatus = "Voice message failed",
    )
  }

  fun sendCallVoiceMessage(
    audioBytes: ByteArray,
    durationMs: Long,
    mimeType: String = VOICE_MIME_TYPE,
  ) {
    if (audioBytes.isEmpty()) return
    val callState = uiState.value.callState
    if (callState.status != CallStatus.Active) return
    val callId = callState.callId ?: return
    val peerEndpointId = callState.peerEndpointId ?: return
    val endpoint = uiState.value.connectedEndpoints.firstOrNull { it.id == peerEndpointId } ?: return

    val clipId = UUID.randomUUID().toString()
    val bytes =
      ChatProtocol.encodeCallVoice(
        callId = callId,
        clipId = clipId,
        senderId = uiState.value.localDeviceId,
        audioBase64 = Base64.Default.encode(audioBytes),
        durationMs = durationMs,
        mimeType = mimeType,
        createdAt = System.currentTimeMillis(),
      )
    transport.send(endpoint.id, bytes) { result ->
      if (result.isSuccess) {
        store.setCallActivity(if (isStreamingCallAudioMimeType(mimeType)) "Live voice" else "Voice sent")
      } else {
        store.setStatus(ConnectionStatus.Error, "Call voice failed", result.exceptionOrNull()?.message)
      }
    }
  }

  fun sendLocation(onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
      requestLocation()
        .onSuccess { loc ->
          val endpoints = uiState.value.connectedEndpoints
          if (endpoints.isEmpty()) {
            store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
            onResult(Result.failure(IllegalStateException("Not connected")))
            return@launch
          }
          val message =
            store.queueOutgoingLocationMessage(
              latitude = loc.latitude,
              longitude = loc.longitude,
              accuracy = loc.accuracy,
            )
          val bytes =
            ChatProtocol.encodeLocation(
              messageId = message.id,
              conversationId = message.conversationId,
              senderId = message.senderId,
              latitude = loc.latitude,
              longitude = loc.longitude,
              accuracy = loc.accuracy,
              createdAt = message.createdAt,
            )
          val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
          var hasFailure = false
          endpoints.forEach { endpoint ->
            transport.send(endpoint.id, bytes) { result ->
              if (result.isFailure) {
                hasFailure = true
                store.setStatus(ConnectionStatus.Error, "Location send failed", result.exceptionOrNull()?.message)
              }
              pendingEndpointIds.remove(endpoint.id)
              if (pendingEndpointIds.isEmpty()) {
                if (hasFailure) {
                  store.markFailed(message.id)
                } else {
                  store.markSent(message.id)
                }
              }
            }
          }
          onResult(Result.success(Unit))
        }
        .onFailure { e ->
          store.setStatus(ConnectionStatus.Error, "Could not get location", e.message)
          onResult(Result.failure(e))
        }
    }
  }

  fun sendImage(uri: Uri, onResult: (Result<Unit>) -> Unit) {
    val endpoints = uiState.value.connectedEndpoints
    if (endpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      onResult(Result.failure(IllegalStateException("Not connected")))
      return
    }
    compressImage(uri)
      .onSuccess { img ->
        val message =
          store.queueOutgoingImageMessage(
            imageBase64 = img.imageBase64,
            mimeType = img.mimeType,
            width = img.width,
            height = img.height,
          )
        val bytes =
          ChatProtocol.encodeImage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            imageBase64 = img.imageBase64,
            mimeType = img.mimeType,
            width = img.width,
            height = img.height,
            createdAt = message.createdAt,
          )
        val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
        var hasFailure = false
        endpoints.forEach { endpoint ->
          transport.send(endpoint.id, bytes) { result ->
            if (result.isFailure) {
              hasFailure = true
              store.setStatus(ConnectionStatus.Error, "Image send failed", result.exceptionOrNull()?.message)
            }
            pendingEndpointIds.remove(endpoint.id)
            if (pendingEndpointIds.isEmpty()) {
              if (hasFailure) {
                store.markFailed(message.id)
              } else {
                store.markSent(message.id)
              }
            }
          }
        }
        onResult(Result.success(Unit))
      }
      .onFailure { e ->
        store.setStatus(ConnectionStatus.Error, "Could not read image", e.message)
        onResult(Result.failure(e))
      }
  }

  fun startCall() {
    val endpoint = uiState.value.connectedEndpoints.firstOrNull()
    if (endpoint == null) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      return
    }
    if (uiState.value.callState.status != CallStatus.Idle) return

    val callId = UUID.randomUUID().toString()
    store.startOutgoingCall(endpoint, callId)
    transport.send(
      endpoint.id,
      ChatProtocol.encodeCallRequest(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        createdAt = System.currentTimeMillis(),
      ),
    ) { result ->
      result.onFailure {
        store.endCall(callId)
        store.setStatus(ConnectionStatus.Error, "Call failed", it.message)
      }
    }
  }

  fun acceptCall() {
    val callState = uiState.value.callState
    val callId = callState.callId ?: return
    val peerEndpointId = callState.peerEndpointId ?: return
    if (callState.status != CallStatus.Incoming) return
    if (!store.acceptCall(callId)) return
    transport.send(
      peerEndpointId,
      ChatProtocol.encodeCallAccept(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        createdAt = System.currentTimeMillis(),
      ),
    ) { result ->
      result.onFailure {
        store.setStatus(ConnectionStatus.Error, "Could not accept call", it.message)
      }
    }
  }

  fun rejectCall() {
    val callState = uiState.value.callState
    val callId = callState.callId ?: return
    val peerEndpointId = callState.peerEndpointId ?: return
    if (callState.status != CallStatus.Incoming) return
    store.rejectCall(callId)
    handledCallVoiceClipIds.clear()
    transport.send(
      peerEndpointId,
      ChatProtocol.encodeCallReject(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        reason = "rejected",
        createdAt = System.currentTimeMillis(),
      ),
    ) { }
  }

  fun endCall() {
    val callState = uiState.value.callState
    val callId = callState.callId ?: return
    val peerEndpointId = callState.peerEndpointId ?: return
    if (callState.status == CallStatus.Idle) return
    store.endCall(callId)
    handledCallVoiceClipIds.clear()
    transport.send(
      peerEndpointId,
      ChatProtocol.encodeCallEnd(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        createdAt = System.currentTimeMillis(),
      ),
    ) { }
  }

  fun finishCallVoicePlayback(clipId: String) {
    store.finishCallPlayback(clipId)
  }

  fun disconnect() {
    uiState.value.connectedEndpoints.forEach { endpoint ->
      transport.send(endpoint.id, ChatProtocol.encodeDisconnect("User disconnected")) { }
    }
    transport.stopAll()
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    endpointMemberIds.clear()
    handledCallVoiceClipIds.clear()
    retriedMessageEndpointIds.clear()
    store.clearConnectedEndpoints()
  }

  fun retryMessage(messageId: String) {
    val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
    if (!message.isLocal || message.status != MessageStatus.Failed) return
    val endpoints = uiState.value.connectedEndpoints
    if (endpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Reconnect before retrying")
      return
    }
    retriedMessageEndpointIds.remove(message.id)
    store.markQueued(message.id)
    sendExistingMessageTo(message.copy(status = MessageStatus.Queued), endpoints)
  }

  fun deleteMessage(messageId: String) {
    retriedMessageEndpointIds.remove(messageId)
    store.deleteMessage(messageId)
  }

  fun clearMessages() {
    retriedMessageEndpointIds.clear()
    store.clearMessages()
  }

  override fun onCleared() {
    transport.stopAll()
    super.onCleared()
  }

  private fun sendVoiceMessageTo(
    endpoints: List<NearbyEndpoint>,
    audioBytes: ByteArray,
    durationMs: Long,
    mimeType: String,
    failureStatus: String,
  ) {
    if (audioBytes.isEmpty()) return
    if (endpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      return
    }

    val message =
      store.queueOutgoingVoiceMessage(
        audioBase64 = Base64.Default.encode(audioBytes),
        durationMs = durationMs,
        mimeType = mimeType,
      )
    val voice = message.voice ?: return
    val bytes =
      ChatProtocol.encodeVoiceMessage(
        messageId = message.id,
        conversationId = message.conversationId,
        senderId = message.senderId,
        audioBase64 = voice.audioBase64,
        durationMs = voice.durationMs,
        mimeType = voice.mimeType,
        createdAt = message.createdAt,
      )
    val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
    var hasFailure = false
    endpoints.forEach { endpoint ->
      transport.send(endpoint.id, bytes) { result ->
        if (result.isFailure) {
          hasFailure = true
          store.setStatus(ConnectionStatus.Error, failureStatus, result.exceptionOrNull()?.message)
        }
        pendingEndpointIds.remove(endpoint.id)
        if (pendingEndpointIds.isEmpty()) {
          if (hasFailure) {
            store.markFailed(message.id)
          } else {
            store.markSent(message.id)
          }
        }
      }
    }
  }

  private fun retryUndeliveredMessages() {
    val endpoints = uiState.value.connectedEndpoints
    if (endpoints.isEmpty()) return
    store.localMessagesPendingDelivery().forEach { message ->
      val alreadyRetriedEndpointIds = retriedMessageEndpointIds[message.id].orEmpty()
      val pendingEndpoints = endpoints.filterNot { it.id in alreadyRetriedEndpointIds }
      sendExistingMessageTo(message, pendingEndpoints)
    }
  }

  private fun sendExistingMessageTo(
    message: ChatMessage,
    endpoints: List<NearbyEndpoint>,
  ) {
    if (endpoints.isEmpty()) return
    val bytes =
      when (message.kind) {
        MessageKind.Text ->
          ChatProtocol.encodeMessage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            text = message.text,
            createdAt = message.createdAt,
          )
        MessageKind.Voice -> {
          val voice = message.voice ?: return
          ChatProtocol.encodeVoiceMessage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            audioBase64 = voice.audioBase64,
            durationMs = voice.durationMs,
            mimeType = voice.mimeType,
            createdAt = message.createdAt,
          )
        }
        MessageKind.Location -> {
          val location = message.location ?: return
          ChatProtocol.encodeLocation(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            createdAt = message.createdAt,
          )
        }
        MessageKind.Image -> {
          val image = message.image ?: return
          ChatProtocol.encodeImage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            imageBase64 = image.imageBase64,
            mimeType = image.mimeType,
            width = image.width,
            height = image.height,
            createdAt = message.createdAt,
          )
        }
      }
    val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
    var hasFailure = false
    endpoints.forEach { endpoint ->
      transport.send(endpoint.id, bytes) { result ->
        if (result.isFailure) {
          hasFailure = true
        }
        pendingEndpointIds.remove(endpoint.id)
        if (pendingEndpointIds.isEmpty()) {
          if (hasFailure) {
            store.markFailed(message.id)
          } else {
            retriedMessageEndpointIds.getOrPut(message.id) { mutableSetOf() }.addAll(endpoints.map { it.id })
            store.markSent(message.id)
          }
        }
      }
    }
  }

  private fun handleTransportEvent(event: TransportEvent) {
    when (event) {
      is TransportEvent.EndpointFound -> {
        store.upsertEndpoint(event.endpoint)
        maybeConnectToRecoveryEndpoint(event.endpoint)
      }
      is TransportEvent.EndpointLost -> store.removeEndpoint(event.endpointId)
      is TransportEvent.ConnectionInitiated -> {
        if (recoveryMode) {
          if (!isExpectedRecoveryEndpoint(event.pendingConnection.endpointName)) {
            transport.rejectConnection(event.pendingConnection.endpointId)
            store.setPendingConnection(null)
            return
          }
          store.setPendingConnection(null)
          store.setStatus(ConnectionStatus.Connecting, "Rejoining ${event.pendingConnection.endpointName}")
          transport.acceptConnection(event.pendingConnection.endpointId)
        } else {
          store.setPendingConnection(event.pendingConnection)
          store.setStatus(ConnectionStatus.Connecting, "Confirm ${event.pendingConnection.endpointName}")
        }
      }
      is TransportEvent.Connected -> {
        recoveryMode = false
        recoveryConnectionAttempts.remove(event.endpoint.id)
        store.addConnectedEndpoint(event.endpoint)
        sendHello(event.endpoint.id)
        retryUndeliveredMessages()
      }
      is TransportEvent.Disconnected -> handleEndpointDisconnected(event.endpointId)
      is TransportEvent.BytesReceived -> handleIncomingBytes(event.endpointId, event.bytes)
      is TransportEvent.OperationFailed -> {
        if (event.isAlreadyRunningNearbyOperation()) return
        store.setStatus(
          ConnectionStatus.Error,
          event.message,
          event.throwable?.message,
        )
      }
    }
  }

  private fun handleIncomingBytes(endpointId: String, bytes: ByteArray) {
    when (val decoded = ChatProtocol.decode(bytes)) {
      is DecodedWireMessage.Hello -> {
        val rosterChanged = mergeIncomingRoster(endpointId, decoded)
        store.renameConnectedEndpoint(endpointId, decoded.displayName)
        val localGroupName = uiState.value.groupName
        if (isGroupMismatch(localGroupName, decoded.groupName)) {
          store.setGroupWarning("Group mismatch: ${decoded.displayName} uses ${decoded.groupName}")
        } else {
          store.setGroupWarning(null)
        }
        if (localGroupName == DEFAULT_GROUP_NAME || (decoded.groupName != DEFAULT_GROUP_NAME && decoded.groupName == localGroupName)) {
          store.setGroupName(decoded.groupName)
        }
        if (rosterChanged) {
          broadcastHello()
        }
      }
      is DecodedWireMessage.Message -> {
        val wasNewMessage =
          store.receiveRemoteMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            text = decoded.text,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingMessage(endpointId, decoded)
        }
        transport.send(endpointId, ChatProtocol.encodeAck(decoded.messageId)) { }
      }
      is DecodedWireMessage.VoiceMessage -> {
        val wasNewMessage =
          store.receiveRemoteVoiceMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            audioBase64 = decoded.audioBase64,
            durationMs = decoded.durationMs,
            mimeType = decoded.mimeType,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingVoiceMessage(endpointId, decoded)
        }
        transport.send(endpointId, ChatProtocol.encodeAck(decoded.messageId)) { }
      }
      is DecodedWireMessage.Ack -> store.acknowledge(decoded.messageId)
      is DecodedWireMessage.Disconnect -> handleEndpointDisconnected(endpointId)
      is DecodedWireMessage.ImageMessage -> {
        val wasNewMessage =
          store.receiveRemoteImageMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            imageBase64 = decoded.imageBase64,
            mimeType = decoded.mimeType,
            width = decoded.width,
            height = decoded.height,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingImageMessage(endpointId, decoded)
        }
        transport.send(endpointId, ChatProtocol.encodeAck(decoded.messageId)) { }
      }
      is DecodedWireMessage.LocationMessage -> {
        val wasNewMessage =
          store.receiveRemoteLocationMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            latitude = decoded.latitude,
            longitude = decoded.longitude,
            accuracy = decoded.accuracy,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingLocationMessage(endpointId, decoded)
        }
        transport.send(endpointId, ChatProtocol.encodeAck(decoded.messageId)) { }
      }
      is DecodedWireMessage.CallRequest -> handleIncomingCallRequest(endpointId, decoded)
      is DecodedWireMessage.CallAccept -> store.acceptCall(decoded.callId)
      is DecodedWireMessage.CallReject -> {
        store.rejectCall(decoded.callId)
        handledCallVoiceClipIds.clear()
      }
      is DecodedWireMessage.CallEnd -> {
        store.endCall(decoded.callId)
        handledCallVoiceClipIds.clear()
      }
      is DecodedWireMessage.CallVoice -> handleIncomingCallVoice(endpointId, decoded)
    }
  }

  private fun handleIncomingCallRequest(
    endpointId: String,
    request: DecodedWireMessage.CallRequest,
  ) {
    val existingCall = uiState.value.callState
    if (existingCall.callId == request.callId) return
    if (existingCall.status != CallStatus.Idle) {
      transport.send(
        endpointId,
        ChatProtocol.encodeCallReject(
          callId = request.callId,
          senderId = uiState.value.localDeviceId,
          reason = "busy",
          createdAt = System.currentTimeMillis(),
        ),
      ) { }
      return
    }
    val endpoint = uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId } ?: NearbyEndpoint(endpointId, "Nearby device")
    store.receiveIncomingCall(endpoint, request.callId)
  }

  private fun handleIncomingCallVoice(
    endpointId: String,
    voice: DecodedWireMessage.CallVoice,
  ) {
    val callState = uiState.value.callState
    if (callState.status != CallStatus.Active) return
    if (callState.callId != voice.callId) return
    if (callState.peerEndpointId != endpointId) return
    val isStreamingFrame = isStreamingCallAudioMimeType(voice.mimeType)
    if (!isStreamingFrame && !handledCallVoiceClipIds.add(voice.clipId)) return

    val peerName = callState.peerName ?: uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.name ?: "Nearby device"
    if (isStreamingFrame) {
      val audioBytes = runCatching { Base64.Default.decode(voice.audioBase64) }.getOrNull() ?: return
      mutableCallAudioFrames.tryEmit(
        CallAudioPlaybackFrame(
          callId = voice.callId,
          frameId = voice.clipId,
          senderId = voice.senderId,
          audioBytes = audioBytes,
          durationMs = voice.durationMs,
          mimeType = voice.mimeType,
          createdAt = voice.createdAt,
        ),
      )
      store.setCallActivity("Live voice from $peerName")
      return
    }

    store.showCallPlayback(
      playback =
        CallVoicePlayback(
          callId = voice.callId,
          clipId = voice.clipId,
          senderId = voice.senderId,
          audioBase64 = voice.audioBase64,
          durationMs = voice.durationMs,
          mimeType = voice.mimeType,
          createdAt = voice.createdAt,
        ),
      activityLabel = "Playing $peerName",
    )
  }

  private fun mergeIncomingRoster(endpointId: String, hello: DecodedWireMessage.Hello): Boolean {
    endpointMemberIds[endpointId] = hello.senderId
    val senderChanged = store.replaceGroupMember(endpointId, GroupMember(hello.senderId, hello.displayName, GroupMemberStatus.Online))
    val rosterChanged =
      store.mergeGroupMembers(
        hello.members.map { member -> GroupMember(member.id, member.displayName, GroupMemberStatus.Offline) },
      )
    return senderChanged || rosterChanged
  }

  private fun forwardIncomingMessage(sourceEndpointId: String, message: DecodedWireMessage.Message) {
    val bytes =
      ChatProtocol.encodeMessage(
        messageId = message.messageId,
        conversationId = message.conversationId,
        senderId = message.senderId,
        text = message.text,
        createdAt = message.createdAt,
      )
    uiState.value.connectedEndpoints
      .filterNot { it.id == sourceEndpointId }
      .forEach { endpoint ->
        transport.send(endpoint.id, bytes) { }
      }
  }

  private fun forwardIncomingVoiceMessage(sourceEndpointId: String, message: DecodedWireMessage.VoiceMessage) {
    val bytes =
      ChatProtocol.encodeVoiceMessage(
        messageId = message.messageId,
        conversationId = message.conversationId,
        senderId = message.senderId,
        audioBase64 = message.audioBase64,
        durationMs = message.durationMs,
        mimeType = message.mimeType,
        createdAt = message.createdAt,
      )
    uiState.value.connectedEndpoints
      .filterNot { it.id == sourceEndpointId }
      .forEach { endpoint ->
        transport.send(endpoint.id, bytes) { }
      }
  }

  private fun forwardIncomingImageMessage(sourceEndpointId: String, message: DecodedWireMessage.ImageMessage) {
    val bytes =
      ChatProtocol.encodeImage(
        messageId = message.messageId,
        conversationId = message.conversationId,
        senderId = message.senderId,
        imageBase64 = message.imageBase64,
        mimeType = message.mimeType,
        width = message.width,
        height = message.height,
        createdAt = message.createdAt,
      )
    uiState.value.connectedEndpoints
      .filterNot { it.id == sourceEndpointId }
      .forEach { endpoint ->
        transport.send(endpoint.id, bytes) { }
      }
  }

  private fun forwardIncomingLocationMessage(sourceEndpointId: String, message: DecodedWireMessage.LocationMessage) {
    val bytes =
      ChatProtocol.encodeLocation(
        messageId = message.messageId,
        conversationId = message.conversationId,
        senderId = message.senderId,
        latitude = message.latitude,
        longitude = message.longitude,
        accuracy = message.accuracy,
        createdAt = message.createdAt,
      )
    uiState.value.connectedEndpoints
      .filterNot { it.id == sourceEndpointId }
      .forEach { endpoint ->
        transport.send(endpoint.id, bytes) { }
      }
  }

  private fun broadcastHello() {
    uiState.value.connectedEndpoints.forEach { endpoint ->
      sendHello(endpoint.id)
    }
  }

  private fun sendHello(endpointId: String) {
    transport.send(
      endpointId,
      ChatProtocol.encodeHello(
        senderId = uiState.value.localDeviceId,
        displayName = uiState.value.displayName,
        groupName = uiState.value.groupName,
        members = currentRosterMembers(),
      ),
    ) { }
  }

  private fun currentRosterMembers(): List<WireMember> {
    val state = uiState.value
    val temporaryEndpointIds = state.connectedEndpoints.map { it.id }.toSet()
    val stableKnownMembers =
      state.groupMembers
        .filterNot { it.id in temporaryEndpointIds }
        .map { WireMember(it.id, it.displayName) }
    return (listOf(WireMember(state.localDeviceId, state.displayName)) + stableKnownMembers)
      .associateBy { it.id }
      .values
      .toList()
  }

  private fun handleEndpointDisconnected(endpointId: String) {
    val wasConnected = uiState.value.connectedEndpoints.any { it.id == endpointId }
    val mappedMemberId = endpointMemberIds.remove(endpointId)
    val memberId = mappedMemberId ?: endpointId
    val hadMember = uiState.value.groupMembers.any { it.id == memberId }
    val hadTemporaryMember = memberId != endpointId && uiState.value.groupMembers.any { it.id == endpointId }
    if (!wasConnected && mappedMemberId == null && !hadMember && !hadTemporaryMember) return
    store.removeConnectedEndpoint(endpointId)
    val removedMember = store.removeGroupMember(memberId)
    val removedTemporaryMember = memberId != endpointId && store.removeGroupMember(endpointId)
    if ((removedMember || removedTemporaryMember) && uiState.value.connectedEndpoints.isNotEmpty()) {
      broadcastHello()
    }
    if (uiState.value.connectedEndpoints.isEmpty() && uiState.value.groupMembers.isNotEmpty()) {
      startGroupRecovery()
    }
  }

  private fun startGroupRecovery() {
    recoveryMode = true
    recoveryConnectionAttempts.clear()
    store.markGroupMembersReconnecting()
    if (shouldAdvertiseDuringRecovery()) {
      store.setStatus(ConnectionStatus.Advertising, "Reforming group: visible as ${uiState.value.displayName}")
      transport.startAdvertising(uiState.value.displayName)
    } else {
      store.setStatus(ConnectionStatus.Discovering, "Reforming group: searching nearby members")
      store.setDiscoveredEndpoints(emptyList())
      transport.startDiscovery()
    }
  }

  private fun shouldAdvertiseDuringRecovery(): Boolean {
    val state = uiState.value
    val memberIds = (state.groupMembers.map { it.id } + state.localDeviceId).filter { it.isNotBlank() }
    return state.localDeviceId == memberIds.minOrNull()
  }

  private fun maybeConnectToRecoveryEndpoint(endpoint: NearbyEndpoint) {
    if (!recoveryMode) return
    if (uiState.value.status != ConnectionStatus.Discovering) return
    if (uiState.value.connectedEndpoints.any { it.id == endpoint.id }) return
    if (!isExpectedRecoveryEndpoint(endpoint.name)) return
    if (!recoveryConnectionAttempts.add(endpoint.id)) return
    connectTo(endpoint)
  }

  private fun isExpectedRecoveryEndpoint(endpointName: String): Boolean {
    if (!recoveryMode) return true
    val expectedNames =
      uiState.value.groupMembers
        .map { it.displayName.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    return expectedNames.isEmpty() || endpointName.trim() in expectedNames
  }

  private fun isGroupMismatch(localGroupName: String, remoteGroupName: String): Boolean =
    localGroupName != DEFAULT_GROUP_NAME &&
      remoteGroupName != DEFAULT_GROUP_NAME &&
      localGroupName != remoteGroupName

  private fun TransportEvent.OperationFailed.isAlreadyRunningNearbyOperation(): Boolean {
    val details = "${message} ${throwable?.message.orEmpty()}"
    return "STATUS_ALREADY_ADVERTISING" in details || "STATUS_ALREADY_DISCOVERING" in details
  }

  private companion object {
    const val DEFAULT_GROUP_NAME = "Offline group"
    const val VOICE_MIME_TYPE = "audio/3gpp"
  }
}
