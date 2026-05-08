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
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.protocol.WireMember
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.LatestPayloadSender
import com.example.offlinelink.transport.PriorityPayloadSender
import com.example.offlinelink.transport.PriorityPayloadSender.PayloadPriority
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
  private val payloadCache: PayloadCache,
) : ViewModel() {
  private val store = ChatSessionStore(localDeviceId = localDeviceId, payloadCache = payloadCache)
  private val payloadSender = PriorityPayloadSender(transport)
  private val liveAudioSender = LatestPayloadSender(transport)
  private val endpointMemberIds = mutableMapOf<String, String>()
  private var recoveryMode = false
  private val recoveryConnectionAttempts = mutableSetOf<String>()
  private val handledCallVoiceClipIds = mutableSetOf<String>()
  private val retriedMessageEndpointIds = mutableMapOf<String, MutableSet<String>>()
  private var nextCallAudioSequenceNumber = 0
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
    store.setStatus(ConnectionStatus.Advertising, "Visible and searching as ${uiState.value.displayName}")
    startAdvertisingAndDiscovery()
  }

  fun startDiscovery() {
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    if (uiState.value.connectedEndpoints.isEmpty()) {
      endpointMemberIds.clear()
      store.clearConnectedEndpoints()
      store.setDiscoveredEndpoints(emptyList())
      store.setStatus(ConnectionStatus.Discovering, "Visible and searching nearby devices")
    } else {
      store.restoreConnectedStatus()
    }
    startAdvertisingAndDiscovery()
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
    if (uiState.value.connectedEndpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Connecting, "Connecting to ${endpoint.name}")
    } else {
      store.restoreConnectedStatus()
    }
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
      sendPayload(endpoint.id, bytes, PayloadPriority.Text) { result ->
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

    val frameId = UUID.randomUUID().toString()
    val createdAt = System.currentTimeMillis()
    val isStreamingFrame = isStreamingCallAudioMimeType(mimeType)
    val bytes =
      if (isStreamingFrame) {
        ChatProtocol.encodeCallAudioFrame(
          callId = callId,
          frameId = frameId,
          senderId = uiState.value.localDeviceId,
          targetId = callState.peerMemberId,
          audioBytes = audioBytes,
          durationMs = durationMs,
          mimeType = mimeType,
          sequenceNumber = nextCallAudioSequenceNumber++,
          createdAt = createdAt,
        )
      } else {
        ChatProtocol.encodeCallVoice(
          callId = callId,
          clipId = frameId,
          senderId = uiState.value.localDeviceId,
          targetId = callState.peerMemberId,
          audioBase64 = Base64.Default.encode(audioBytes),
          durationMs = durationMs,
          mimeType = mimeType,
          createdAt = createdAt,
        )
      }
    sendPayload(endpoint.id, bytes, callVoicePriority(mimeType)) { result ->
      if (result.isSuccess) {
        store.setCallActivity(if (isStreamingFrame) "Live voice" else "Voice sent")
      } else if (!isExpectedLiveAudioDrop(result.exceptionOrNull())) {
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
            sendPayload(endpoint.id, bytes, PayloadPriority.Location) { result ->
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
        val payloadKey = payloadCache.put(img.bytes)
        val base64ForWire = Base64.Default.encode(img.bytes)
        val message =
          store.queueOutgoingImageMessage(
            payloadKey = payloadKey,
            mimeType = img.mimeType,
            width = img.width,
            height = img.height,
          )
        val bytes =
          ChatProtocol.encodeImage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            imageBase64 = base64ForWire,
            mimeType = img.mimeType,
            width = img.width,
            height = img.height,
            createdAt = message.createdAt,
          )
        val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
        var hasFailure = false
        endpoints.forEach { endpoint ->
          sendPayload(endpoint.id, bytes, PayloadPriority.Image) { result ->
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

  fun startCall(peerEndpointId: String? = null) {
    val endpoint = routeEndpointForCallTarget(peerEndpointId)
    if (endpoint == null) {
      if (peerEndpointId == null) {
        store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      } else {
        store.setStatus(ConnectionStatus.Error, "Call target unavailable", "Choose a connected device")
      }
      return
    }
    if (uiState.value.callState.status != CallStatus.Idle) return

    val callId = UUID.randomUUID().toString()
    val targetMemberId = peerEndpointId?.takeIf { isKnownGroupMember(it) } ?: endpointMemberIds[endpoint.id]
    store.startOutgoingCall(
      endpoint = endpoint,
      callId = callId,
      peerMemberId = targetMemberId,
      peerName = callTargetName(peerEndpointId, endpoint),
    )
    sendPayload(
      endpoint.id,
      ChatProtocol.encodeCallRequest(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        targetId = targetMemberId,
        createdAt = System.currentTimeMillis(),
      ),
      PayloadPriority.CallSignal,
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
    sendPayload(
      peerEndpointId,
      ChatProtocol.encodeCallAccept(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        targetId = callState.peerMemberId,
        createdAt = System.currentTimeMillis(),
      ),
      PayloadPriority.CallSignal,
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
    sendPayload(
      peerEndpointId,
      ChatProtocol.encodeCallReject(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        targetId = callState.peerMemberId,
        reason = "rejected",
        createdAt = System.currentTimeMillis(),
      ),
      PayloadPriority.CallSignal,
    ) { }
  }

  fun endCall() {
    val callState = uiState.value.callState
    val callId = callState.callId ?: return
    val peerEndpointId = callState.peerEndpointId ?: return
    if (callState.status == CallStatus.Idle) return
    store.endCall(callId)
    handledCallVoiceClipIds.clear()
    sendPayload(
      peerEndpointId,
      ChatProtocol.encodeCallEnd(
        callId = callId,
        senderId = uiState.value.localDeviceId,
        targetId = callState.peerMemberId,
        createdAt = System.currentTimeMillis(),
      ),
      PayloadPriority.CallSignal,
    ) { }
  }

  fun finishCallVoicePlayback(clipId: String) {
    store.finishCallPlayback(clipId)
  }

  fun disconnect() {
    val endpoints = uiState.value.connectedEndpoints
    endpoints.forEach { endpoint ->
      sendPayload(endpoint.id, ChatProtocol.encodeDisconnect("User disconnected"), PayloadPriority.Control) { }
    }
    transport.stopAll()
    endpoints.forEach { clearPayloadSenders(it.id) }
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

    val payloadKey = payloadCache.put(audioBytes)
    val message =
      store.queueOutgoingVoiceMessage(
        payloadKey = payloadKey,
        durationMs = durationMs,
        mimeType = mimeType,
      )
    val base64ForWire = Base64.Default.encode(audioBytes)
    val bytes =
      ChatProtocol.encodeVoiceMessage(
        messageId = message.id,
        conversationId = message.conversationId,
        senderId = message.senderId,
        audioBase64 = base64ForWire,
        durationMs = durationMs,
        mimeType = mimeType,
        createdAt = message.createdAt,
      )
    val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
    var hasFailure = false
    endpoints.forEach { endpoint ->
      sendPayload(endpoint.id, bytes, PayloadPriority.Voice) { result ->
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

  private fun sendPayload(
    endpointId: String,
    bytes: ByteArray,
    priority: PayloadPriority,
    onResult: (Result<Unit>) -> Unit = {},
  ) {
    if (priority == PayloadPriority.CallAudio) {
      liveAudioSender.send(endpointId, bytes, onResult)
      return
    }
    payloadSender.enqueue(endpointId = endpointId, bytes = bytes, priority = priority, onResult = onResult)
  }

  private fun clearPayloadSenders(endpointId: String) {
    payloadSender.clearEndpoint(endpointId)
    liveAudioSender.clearEndpoint(endpointId)
  }

  private fun isExpectedLiveAudioDrop(throwable: Throwable?): Boolean =
    throwable is LatestPayloadSender.StalePayloadDroppedException ||
      throwable is LatestPayloadSender.EndpointClearedException

  private fun messagePriority(kind: MessageKind): PayloadPriority =
    when (kind) {
      MessageKind.Text -> PayloadPriority.Text
      MessageKind.Voice -> PayloadPriority.Voice
      MessageKind.Location -> PayloadPriority.Location
      MessageKind.Image -> PayloadPriority.Image
    }

  private fun callVoicePriority(mimeType: String): PayloadPriority =
    if (isStreamingCallAudioMimeType(mimeType)) PayloadPriority.CallAudio else PayloadPriority.Voice

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
          val rawBytes = payloadCache.get(voice.payloadKey) ?: return
          ChatProtocol.encodeVoiceMessage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            audioBase64 = Base64.Default.encode(rawBytes),
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
          val rawBytes = payloadCache.get(image.payloadKey) ?: return
          ChatProtocol.encodeImage(
            messageId = message.id,
            conversationId = message.conversationId,
            senderId = message.senderId,
            imageBase64 = Base64.Default.encode(rawBytes),
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
      sendPayload(endpoint.id, bytes, messagePriority(message.kind)) { result ->
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
        if (event.endpoint.isLocalDevice()) {
          store.removeEndpoint(event.endpoint.id)
          return
        }
        store.upsertEndpoint(event.endpoint)
        maybeConnectToRecoveryEndpoint(event.endpoint)
      }
      is TransportEvent.EndpointLost -> store.removeEndpoint(event.endpointId)
      is TransportEvent.ConnectionInitiated -> {
        if (event.pendingConnection.deviceId == uiState.value.localDeviceId) {
          transport.rejectConnection(event.pendingConnection.endpointId)
          store.setPendingConnection(null)
          return
        }
        if (recoveryMode) {
          if (!isExpectedRecoveryEndpoint(event.pendingConnection.endpointName, event.pendingConnection.deviceId)) {
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
        if (event.endpoint.isLocalDevice()) {
          clearPayloadSenders(event.endpoint.id)
          return
        }
        recoveryMode = false
        recoveryConnectionAttempts.remove(event.endpoint.id)
        transport.stopDiscovery()
        store.addConnectedEndpoint(event.endpoint)
        sendHello(event.endpoint.id)
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
          store.setStatus(ConnectionStatus.Discovering, "Reforming group: searching nearby members")
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

  private fun handleIncomingBytes(endpointId: String, bytes: ByteArray) {
    when (val decoded = ChatProtocol.decode(bytes)) {
      is DecodedWireMessage.Hello -> {
        ensureEndpointConnected(endpointId, decoded.displayName)
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
        ensureEndpointConnected(endpointId)
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
        sendPayload(endpointId, ChatProtocol.encodeAck(decoded.messageId), PayloadPriority.Control) { }
      }
      is DecodedWireMessage.VoiceMessage -> {
        ensureEndpointConnected(endpointId)
        val payloadKey = payloadCache.put(Base64.Default.decode(decoded.audioBase64))
        val wasNewMessage =
          store.receiveRemoteVoiceMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            payloadKey = payloadKey,
            durationMs = decoded.durationMs,
            mimeType = decoded.mimeType,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingVoiceMessage(endpointId, decoded)
        }
        sendPayload(endpointId, ChatProtocol.encodeAck(decoded.messageId), PayloadPriority.Control) { }
      }
      is DecodedWireMessage.Ack -> {
        ensureEndpointConnected(endpointId)
        store.acknowledge(decoded.messageId)
      }
      is DecodedWireMessage.Disconnect -> handleEndpointDisconnected(endpointId, shouldReconnect = false)
      is DecodedWireMessage.ImageMessage -> {
        ensureEndpointConnected(endpointId)
        val payloadKey = payloadCache.put(Base64.Default.decode(decoded.imageBase64))
        val wasNewMessage =
          store.receiveRemoteImageMessage(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            senderId = decoded.senderId,
            payloadKey = payloadKey,
            mimeType = decoded.mimeType,
            width = decoded.width,
            height = decoded.height,
            createdAt = decoded.createdAt,
          )
        if (wasNewMessage) {
          forwardIncomingImageMessage(endpointId, decoded)
        }
        sendPayload(endpointId, ChatProtocol.encodeAck(decoded.messageId), PayloadPriority.Control) { }
      }
      is DecodedWireMessage.LocationMessage -> {
        ensureEndpointConnected(endpointId)
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
        sendPayload(endpointId, ChatProtocol.encodeAck(decoded.messageId), PayloadPriority.Control) { }
      }
      is DecodedWireMessage.CallRequest -> {
        ensureEndpointConnected(endpointId)
        handleIncomingCallRequest(endpointId, decoded)
      }
      is DecodedWireMessage.CallAccept -> {
        ensureEndpointConnected(endpointId)
        if (!forwardCallAcceptIfNeeded(endpointId, decoded)) {
          store.acceptCall(decoded.callId)
        }
      }
      is DecodedWireMessage.CallReject -> {
        ensureEndpointConnected(endpointId)
        if (!forwardCallRejectIfNeeded(endpointId, decoded)) {
          store.rejectCall(decoded.callId)
          handledCallVoiceClipIds.clear()
        }
      }
      is DecodedWireMessage.CallEnd -> {
        ensureEndpointConnected(endpointId)
        if (!forwardCallEndIfNeeded(endpointId, decoded)) {
          store.endCall(decoded.callId)
          handledCallVoiceClipIds.clear()
        }
      }
      is DecodedWireMessage.CallVoice -> {
        ensureEndpointConnected(endpointId)
        handleIncomingCallVoice(endpointId, decoded)
      }
      is DecodedWireMessage.CallAudioFrame -> {
        ensureEndpointConnected(endpointId)
        handleIncomingCallAudioFrame(endpointId, decoded)
      }
    }
  }

  private fun ensureEndpointConnected(
    endpointId: String,
    displayName: String = "Nearby device",
  ) {
    if (uiState.value.connectedEndpoints.any { it.id == endpointId }) return
    recoveryMode = false
    recoveryConnectionAttempts.remove(endpointId)
    transport.stopDiscovery()
    store.addConnectedEndpoint(NearbyEndpoint(endpointId, displayName.ifBlank { "Nearby device" }))
    retryUndeliveredMessages()
  }

  private fun handleIncomingCallRequest(
    endpointId: String,
    request: DecodedWireMessage.CallRequest,
  ) {
    if (forwardCallRequestIfNeeded(endpointId, request)) return
    val existingCall = uiState.value.callState
    if (existingCall.callId == request.callId) return
    if (existingCall.status != CallStatus.Idle) {
      sendPayload(
        endpointId,
        ChatProtocol.encodeCallReject(
          callId = request.callId,
          senderId = uiState.value.localDeviceId,
          targetId = request.senderId,
          reason = "busy",
          createdAt = System.currentTimeMillis(),
        ),
        PayloadPriority.CallSignal,
      ) { }
      return
    }
    val endpoint = uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId } ?: NearbyEndpoint(endpointId, "Nearby device")
    store.receiveIncomingCall(
      endpoint = endpoint,
      callId = request.callId,
      peerMemberId = request.senderId,
      peerName = memberName(request.senderId) ?: endpoint.name,
    )
  }

  private fun routeEndpointForCallTarget(targetId: String?): NearbyEndpoint? {
    val endpoints = uiState.value.connectedEndpoints
    if (targetId == null) return endpoints.firstOrNull()
    return endpoints.firstOrNull { endpoint -> endpoint.id == targetId || endpointMemberIds[endpoint.id] == targetId }
      ?: endpoints.firstOrNull().takeIf { isKnownGroupMember(targetId) }
  }

  private fun callTargetName(
    targetId: String?,
    routeEndpoint: NearbyEndpoint,
  ): String =
    targetId
      ?.let(::memberName)
      ?: uiState.value.connectedEndpoints.firstOrNull { it.id == targetId }?.name
      ?: routeEndpoint.name

  private fun memberName(memberId: String): String? =
    uiState.value.groupMembers.firstOrNull { it.id == memberId }?.displayName
      ?: uiState.value.connectedEndpoints.firstOrNull { endpointMemberIds[it.id] == memberId }?.name

  private fun isKnownGroupMember(memberId: String): Boolean =
    uiState.value.groupMembers.any { it.id == memberId } || endpointMemberIds.any { it.value == memberId }

  private fun shouldRelayToTarget(targetId: String?): Boolean =
    targetId != null && targetId != uiState.value.localDeviceId

  private fun relayEndpointsForTarget(
    sourceEndpointId: String,
    targetId: String,
  ): List<NearbyEndpoint> {
    val directTarget =
      uiState.value.connectedEndpoints.firstOrNull { endpoint ->
        endpoint.id != sourceEndpointId && (endpoint.id == targetId || endpointMemberIds[endpoint.id] == targetId)
      }
    if (directTarget != null) return listOf(directTarget)
    return uiState.value.connectedEndpoints.filterNot { it.id == sourceEndpointId }
  }

  private fun forwardCallBytesIfNeeded(
    sourceEndpointId: String,
    targetId: String?,
    bytes: ByteArray,
    priority: PayloadPriority = PayloadPriority.CallSignal,
  ): Boolean {
    if (!shouldRelayToTarget(targetId)) return false
    relayEndpointsForTarget(sourceEndpointId, targetId!!).forEach { endpoint ->
      sendPayload(endpoint.id, bytes, priority) { }
    }
    return true
  }

  private fun forwardCallRequestIfNeeded(
    sourceEndpointId: String,
    request: DecodedWireMessage.CallRequest,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = request.targetId,
      bytes =
        ChatProtocol.encodeCallRequest(
          callId = request.callId,
          senderId = request.senderId,
          targetId = request.targetId,
          createdAt = request.createdAt,
          sentAt = request.sentAt,
        ),
    )

  private fun forwardCallAcceptIfNeeded(
    sourceEndpointId: String,
    accept: DecodedWireMessage.CallAccept,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = accept.targetId,
      bytes =
        ChatProtocol.encodeCallAccept(
          callId = accept.callId,
          senderId = accept.senderId,
          targetId = accept.targetId,
          createdAt = accept.createdAt,
          sentAt = accept.sentAt,
        ),
    )

  private fun forwardCallRejectIfNeeded(
    sourceEndpointId: String,
    reject: DecodedWireMessage.CallReject,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = reject.targetId,
      bytes =
        ChatProtocol.encodeCallReject(
          callId = reject.callId,
          senderId = reject.senderId,
          targetId = reject.targetId,
          reason = reject.reason,
          createdAt = reject.createdAt,
          sentAt = reject.sentAt,
        ),
    )

  private fun forwardCallEndIfNeeded(
    sourceEndpointId: String,
    end: DecodedWireMessage.CallEnd,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = end.targetId,
      bytes =
        ChatProtocol.encodeCallEnd(
          callId = end.callId,
          senderId = end.senderId,
          targetId = end.targetId,
          createdAt = end.createdAt,
          sentAt = end.sentAt,
        ),
    )

  private fun forwardCallVoiceIfNeeded(
    sourceEndpointId: String,
    voice: DecodedWireMessage.CallVoice,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = voice.targetId,
      priority = callVoicePriority(voice.mimeType),
      bytes =
        ChatProtocol.encodeCallVoice(
          callId = voice.callId,
          clipId = voice.clipId,
          senderId = voice.senderId,
          targetId = voice.targetId,
          audioBase64 = voice.audioBase64,
          durationMs = voice.durationMs,
          mimeType = voice.mimeType,
          createdAt = voice.createdAt,
          sentAt = voice.sentAt,
        ),
    )

  private fun forwardCallAudioFrameIfNeeded(
    sourceEndpointId: String,
    frame: DecodedWireMessage.CallAudioFrame,
  ): Boolean =
    forwardCallBytesIfNeeded(
      sourceEndpointId = sourceEndpointId,
      targetId = frame.targetId,
      priority = PayloadPriority.CallAudio,
      bytes =
        ChatProtocol.encodeCallAudioFrame(
          callId = frame.callId,
          frameId = frame.frameId,
          senderId = frame.senderId,
          targetId = frame.targetId,
          audioBytes = frame.audioBytes,
          durationMs = frame.durationMs,
          mimeType = frame.mimeType,
          sequenceNumber = frame.sequenceNumber,
          createdAt = frame.createdAt,
          sentAt = frame.sentAt,
        ),
    )

  private fun handleIncomingCallVoice(
    endpointId: String,
    voice: DecodedWireMessage.CallVoice,
  ) {
    if (forwardCallVoiceIfNeeded(endpointId, voice)) return
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

  private fun handleIncomingCallAudioFrame(
    endpointId: String,
    frame: DecodedWireMessage.CallAudioFrame,
  ) {
    if (forwardCallAudioFrameIfNeeded(endpointId, frame)) return
    val callState = uiState.value.callState
    if (callState.status != CallStatus.Active) return
    if (callState.callId != frame.callId) return
    if (callState.peerEndpointId != endpointId) return

    val peerName = callState.peerName ?: uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.name ?: "Nearby device"
    mutableCallAudioFrames.tryEmit(
      CallAudioPlaybackFrame(
        callId = frame.callId,
        frameId = frame.frameId,
        senderId = frame.senderId,
        audioBytes = frame.audioBytes,
        durationMs = frame.durationMs,
        mimeType = frame.mimeType,
        createdAt = frame.createdAt,
      ),
    )
    store.setCallActivity("Live voice from $peerName")
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
        sendPayload(endpoint.id, bytes, PayloadPriority.Text) { }
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
        sendPayload(endpoint.id, bytes, PayloadPriority.Voice) { }
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
        sendPayload(endpoint.id, bytes, PayloadPriority.Image) { }
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
        sendPayload(endpoint.id, bytes, PayloadPriority.Location) { }
      }
  }

  private fun broadcastHello() {
    uiState.value.connectedEndpoints.forEach { endpoint ->
      sendHello(endpoint.id)
    }
  }

  private fun sendHello(endpointId: String) {
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

  private fun handleEndpointDisconnected(
    endpointId: String,
    shouldReconnect: Boolean = true,
  ) {
    clearPayloadSenders(endpointId)
    val stateBeforeRemoval = uiState.value
    val wasConnected = stateBeforeRemoval.connectedEndpoints.any { it.id == endpointId }
    val mappedMemberId = endpointMemberIds.remove(endpointId)
    val memberId = mappedMemberId ?: endpointId
    val disconnectedDisplayName =
      stateBeforeRemoval.groupMembers.firstOrNull { it.id == memberId }?.displayName
        ?: stateBeforeRemoval.connectedEndpoints.firstOrNull { it.id == endpointId }?.name
        ?: "Nearby device"
    val hadMember = stateBeforeRemoval.groupMembers.any { it.id == memberId }
    val hadTemporaryMember = memberId != endpointId && stateBeforeRemoval.groupMembers.any { it.id == endpointId }
    if (!wasConnected && mappedMemberId == null && !hadMember && !hadTemporaryMember) return
    store.removeConnectedEndpoint(endpointId)
    if (uiState.value.connectedEndpoints.isNotEmpty()) {
      val rosterChanged =
        if (shouldReconnect) {
          store.markGroupMemberReconnecting(memberId, disconnectedDisplayName) ||
            (memberId != endpointId && store.removeGroupMember(endpointId))
        } else {
          store.removeGroupMember(memberId) || (memberId != endpointId && store.removeGroupMember(endpointId))
        }
      if (rosterChanged) {
        broadcastHello()
      }
      if (shouldReconnect && (hadMember || mappedMemberId != null || hadTemporaryMember)) {
        startPartialGroupRecovery(disconnectedDisplayName)
      }
      return
    }
    val removedMember = store.removeGroupMember(memberId)
    val removedTemporaryMember = memberId != endpointId && store.removeGroupMember(endpointId)
    if (uiState.value.groupMembers.isNotEmpty()) {
      startGroupRecovery()
    } else if (removedMember || removedTemporaryMember) {
      store.setStatus(ConnectionStatus.Disconnected, "Disconnected")
    }
  }

  private fun startPartialGroupRecovery(memberName: String) {
    recoveryMode = true
    recoveryConnectionAttempts.clear()
    store.setStatus(ConnectionStatus.Connected, "Reconnecting $memberName")
    store.setDiscoveredEndpoints(emptyList())
    startAdvertisingAndDiscovery()
  }

  private fun startGroupRecovery() {
    recoveryMode = true
    recoveryConnectionAttempts.clear()
    store.markGroupMembersReconnecting()
    store.setStatus(ConnectionStatus.Discovering, "Reforming group: searching nearby members")
    store.setDiscoveredEndpoints(emptyList())
    startAdvertisingAndDiscovery()
  }

  private fun maybeConnectToRecoveryEndpoint(endpoint: NearbyEndpoint) {
    if (!recoveryMode) return
    if (uiState.value.connectedEndpoints.any { it.id == endpoint.id }) return
    if (!isExpectedRecoveryEndpoint(endpoint.name, endpoint.deviceId)) return
    if (!recoveryConnectionAttempts.add(endpoint.id)) return
    connectTo(endpoint)
  }

  private fun isExpectedRecoveryEndpoint(
    endpointName: String,
    deviceId: String? = null,
  ): Boolean {
    if (!recoveryMode) return true
    val members = uiState.value.groupMembers
    val expectedMembers = members.filter { it.status == GroupMemberStatus.Reconnecting }.ifEmpty { members }
    val expectedDeviceIds = expectedMembers.map { it.id.trim() }.filter { it.isNotEmpty() }.toSet()
    if (!deviceId.isNullOrBlank() && deviceId in expectedDeviceIds) return true
    val expectedNames =
      expectedMembers
        .map { it.displayName.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    return (expectedDeviceIds.isEmpty() && expectedNames.isEmpty()) || endpointName.trim() in expectedNames
  }

  private fun isGroupMismatch(localGroupName: String, remoteGroupName: String): Boolean =
    localGroupName != DEFAULT_GROUP_NAME &&
      remoteGroupName != DEFAULT_GROUP_NAME &&
      localGroupName != remoteGroupName

  private fun TransportEvent.OperationFailed.isAlreadyRunningNearbyOperation(): Boolean {
    val details = "${message} ${throwable?.message.orEmpty()}"
    return "STATUS_ALREADY_ADVERTISING" in details || "STATUS_ALREADY_DISCOVERING" in details
  }

  private fun TransportEvent.OperationFailed.isConnectionAttemptFailure(): Boolean =
    message.startsWith("Could not request connection") || message.startsWith("Connection failed")

  private fun startAdvertisingAndDiscovery() {
    transport.startAdvertising(uiState.value.displayName, uiState.value.localDeviceId)
    transport.startDiscovery()
  }

  private fun NearbyEndpoint.isLocalDevice(): Boolean = deviceId == uiState.value.localDeviceId

  private companion object {
    const val DEFAULT_GROUP_NAME = "Offline group"
    const val VOICE_MIME_TYPE = "audio/3gpp"
  }
}
