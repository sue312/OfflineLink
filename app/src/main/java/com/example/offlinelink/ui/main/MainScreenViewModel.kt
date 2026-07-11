package com.example.offlinelink.ui.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinelink.audio.CallAudioTransmitStats
import com.example.offlinelink.audio.VOICE_MESSAGE_MIME_TYPE
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

@OptIn(ExperimentalEncodingApi::class)
class MainScreenViewModel(
  internal val transport: ChatTransport,
  private val requestLocation: suspend () -> Result<DeviceLocation>,
  private val compressImage: (Uri) -> Result<com.example.offlinelink.image.CompressedImage>,
  localDeviceId: String = UUID.randomUUID().toString(),
  defaultDisplayName: String = "OfflineLink",
  defaultAvatarName: String = "",
  private val historyRepository: ChatHistoryRepository = NoOpChatHistoryRepository,
  internal val payloadCache: PayloadCache,
  initialTrustedDeviceIds: Set<String> = emptySet(),
  internal val onTrustedDeviceIdsChanged: (Set<String>) -> Unit = {},
) : ViewModel() {
  internal val store = ChatSessionStore(localDeviceId = localDeviceId, payloadCache = payloadCache)
  internal val payloadSender = PriorityPayloadSender(transport)
  internal val liveAudioSender = LatestPayloadSender(transport, maxPendingPerEndpoint = LIVE_AUDIO_MAX_PENDING_FRAMES)
  internal val connectionManager = ConnectionManager()
  internal val messageManager = MessageManager()
  internal val callManager = CallManager()
  internal val endpointMemberIds = mutableMapOf<String, String>()
  internal var recoveryMode = false
  internal var lastPeerEndpoint: NearbyEndpoint? = null
  internal val recoveryConnectionAttempts = mutableSetOf<String>()
  internal val handledCallVoiceClipIds = mutableSetOf<String>()
  internal val retriedMessageEndpointIds = mutableMapOf<String, MutableSet<String>>()
  internal val pendingBinaryPayloads = mutableMapOf<String, ByteArray>()
  internal val pendingVoiceMessages = mutableMapOf<String, PendingVoiceMessage>()
  internal val pendingImageMessages = mutableMapOf<String, PendingImageMessage>()
  internal val trustedDeviceIds = initialTrustedDeviceIds.toMutableSet()
  internal var persistedMessages: List<ChatMessage> = historyRepository.loadMessages().distinctBy { it.id }
  internal var activeConversationId: String? = null
  internal var nextCallAudioSequenceNumber = 0
  internal val previousRedundantCallAudioFrames = ArrayDeque<CallAudioFrameRedundancy>()
  internal var locationRequestJob: Job? = null
  internal var pendingLocationResult: ((Result<Unit>) -> Unit)? = null
  internal val mutableCallAudioFrames =
    MutableSharedFlow<CallAudioPlaybackFrame>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  val uiState: StateFlow<com.example.offlinelink.model.ChatUiState> = store.state
  val callAudioFrames: SharedFlow<CallAudioPlaybackFrame> = mutableCallAudioFrames.asSharedFlow()

  init {
    store.setDisplayName(defaultDisplayName)
    store.setAvatarName(defaultAvatarName)
    store.loadMessages(emptyList())
    viewModelScope.launch {
      store.state
        .map { it.messages }
        .distinctUntilChanged()
        .collect { messages ->
          persistVisibleMessages(messages)
        }
    }
    viewModelScope.launch {
      transport.events.collect { event -> connectionManager.handleTransportEvent(this@MainScreenViewModel, event) }
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
    setVisibleToNearby(true)
  }

  fun setVisibleToNearby(isVisible: Boolean) {
    if (uiState.value.connectedEndpoints.isNotEmpty()) {
      if (!isVisible) {
        store.setVisibleToNearby(false)
      }
      store.restoreConnectedStatus()
      return
    }
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    store.setVisibleToNearby(isVisible)
    if (isVisible) {
      lastPeerEndpoint = null
      if (uiState.value.status != ConnectionStatus.Discovering) {
        store.setStatus(ConnectionStatus.Advertising, "Visible to nearby devices")
      }
      transport.startAdvertising(uiState.value.displayName, uiState.value.localDeviceId)
    } else {
      transport.stopAdvertising()
      if (uiState.value.status == ConnectionStatus.Advertising) {
        store.setStatus(ConnectionStatus.Idle, "Ready")
      }
    }
  }

  fun startDiscovery() {
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    if (uiState.value.connectedEndpoints.isNotEmpty()) {
      store.restoreConnectedStatus()
      return
    }
    if (uiState.value.connectedEndpoints.isEmpty()) {
      lastPeerEndpoint = null
      endpointMemberIds.clear()
      store.setDiscoveredEndpoints(emptyList())
      store.setStatus(ConnectionStatus.Discovering, "Searching nearby devices")
    }
    transport.startDiscovery()
  }

  fun recoverGroup() {
    if (uiState.value.connectedEndpoints.isNotEmpty()) {
      sendHello(uiState.value.connectedEndpoints.first().id)
      messageManager.retryUndeliveredMessages(this)
      store.restoreConnectedStatus()
      return
    }
    val peer = lastPeerEndpoint
    if (peer == null) {
      startDiscovery()
      return
    }
    startPeerRecovery(peer.name)
  }

  fun connectTo(endpoint: NearbyEndpoint) {
    if (isConnectedToDifferentEndpoint(endpoint.id, endpoint.deviceId)) {
      store.restoreConnectedStatus()
      return
    }
    recoveryConnectionAttempts.add(endpoint.id)
    if (uiState.value.connectedEndpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Connecting, "Connecting to ${endpoint.name}")
    } else {
      store.restoreConnectedStatus()
    }
    transport.requestConnection(endpoint, uiState.value.displayName, uiState.value.localDeviceId)
  }

  fun acceptPendingConnection() {
    val pending = uiState.value.pendingConnection ?: return
    if (isConnectedToDifferentEndpoint(pending.endpointId, pending.deviceId)) {
      rejectIncomingConnection(pending.endpointId)
      store.restoreConnectedStatus()
      return
    }
    recoveryMode = false
    recoveryConnectionAttempts.clear()
    trustDevice(pending.deviceId)
    store.setPendingConnection(null)
    store.setStatus(ConnectionStatus.Connecting, "Connecting to ${pending.endpointName}")
    transport.acceptConnection(pending.endpointId)
  }

  fun rejectPendingConnection() {
    val pending = uiState.value.pendingConnection ?: return
    rejectIncomingConnection(pending.endpointId)
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
        val sequenceNumber = nextCallAudioSequenceNumber++
        val currentRedundancy =
          CallAudioFrameRedundancy(
            audioBytes = audioBytes,
            durationMs = durationMs,
            mimeType = mimeType,
            sequenceNumber = sequenceNumber,
          )
        val redundantPreviousFrames =
          redundantPreviousFramesFor(
            current = currentRedundancy,
            maxFrameBytes = MAX_BLUETOOTH_REDUNDANT_CALL_AUDIO_FRAME_BYTES,
            maxFrameCount = MAX_BLUETOOTH_REDUNDANT_CALL_AUDIO_FRAMES,
          )
        ChatProtocol.encodeCallAudioFrame(
          callId = callId,
          frameId = frameId,
          senderId = uiState.value.localDeviceId,
          targetId = callState.peerMemberId,
          audioBytes = audioBytes,
          durationMs = durationMs,
          mimeType = mimeType,
          sequenceNumber = sequenceNumber,
          createdAt = createdAt,
          compact = true,
          redundantPreviousFrames = redundantPreviousFrames,
        ).also {
          rememberRedundantCallAudioFrame(currentRedundancy, MAX_BLUETOOTH_REDUNDANT_CALL_AUDIO_FRAME_BYTES)
        }
      } else {
        previousRedundantCallAudioFrames.clear()
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
    sendPayload(endpoint.id, bytes, callManager.callVoicePriority(this, mimeType)) { result ->
      if (result.isSuccess) {
        store.setCallActivity(if (isStreamingFrame) "Live voice" else "Voice sent")
      } else if (!isExpectedLiveAudioDrop(result.exceptionOrNull())) {
        store.setStatus(ConnectionStatus.Error, "Call voice failed", result.exceptionOrNull()?.message)
      }
    }
  }

  fun callAudioTransmitStats(): CallAudioTransmitStats {
    val endpoint = uiState.value.connectedEndpoint ?: return CallAudioTransmitStats()
    val transportStats = transport.linkStats(endpoint.id)
    val liveAudioStats = liveAudioSender.stats(endpoint.id)
    return CallAudioTransmitStats(
      remoteRssi = endpoint.rssi,
      sentBytesPerSecond = transportStats.sentBytesPerSecond,
      averageWriteBlockedMs = transportStats.averageWriteBlockedMs,
      maxWriteBlockedMs = transportStats.maxWriteBlockedMs,
      writeQueueLength = transportStats.writeQueueLength,
      maxWriteQueueLength = transportStats.maxWriteQueueLength,
      socketCongested = transportStats.socketCongested,
      liveAudioPendingFrames = liveAudioStats.pendingCount,
      liveAudioDroppedFrames = liveAudioStats.droppedStalePayloads,
    )
  }

  fun sendLocation(onResult: (Result<Unit>) -> Unit) {
    if (locationRequestJob?.isActive == true) {
      onResult(Result.failure(IllegalStateException("Location request already in progress")))
      return
    }
    if (uiState.value.connectedEndpoints.isEmpty()) {
      store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
      onResult(Result.failure(IllegalStateException("Not connected")))
      return
    }

    pendingLocationResult = onResult
    locationRequestJob =
      viewModelScope.launch {
        try {
          withTimeout(LOCATION_REQUEST_TIMEOUT_MS) {
            requestLocation()
          }
        } catch (e: TimeoutCancellationException) {
          Result.failure(IllegalStateException(LOCATION_TIMEOUT_MESSAGE, e))
        } catch (e: CancellationException) {
          completeLocationRequest(Result.failure(e))
          throw e
        }
        .onSuccess { loc ->
          val endpoints = uiState.value.connectedEndpoints
          if (endpoints.isEmpty()) {
            store.setStatus(ConnectionStatus.Error, "No connected device", "Connect to a nearby device first")
            completeLocationRequest(Result.failure(IllegalStateException("Not connected")))
            return@launch
          }
          store.restoreConnectedStatus()
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
          completeLocationRequest(Result.success(Unit))
        }
        .onFailure { e ->
          val message = e.message ?: "Could not get location"
          if (uiState.value.connectedEndpoints.isEmpty()) {
            store.setStatus(ConnectionStatus.Error, "Could not get location", message)
          } else {
            store.restoreConnectedStatus()
          }
          completeLocationRequest(Result.failure(e))
        }
      }.also { job ->
        job.invokeOnCompletion {
          if (locationRequestJob == job) {
            locationRequestJob = null
            pendingLocationResult = null
          }
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
            imageBase64 = "",
            payloadRef = payloadKey,
            mimeType = img.mimeType,
            width = img.width,
            height = img.height,
            createdAt = message.createdAt,
          )
        val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
        var hasFailure = false
        endpoints.forEach { endpoint ->
          sendPayloadWithBinaryAttachment(endpoint.id, payloadKey, img.bytes, bytes, PayloadPriority.Image) { result ->
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

    callManager.resetTransmitState(this)
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
    callManager.resetTransmitState(this)
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
    callManager.resetTransmitState(this)
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
    callManager.resetTransmitState(this)
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
    cancelPendingLocationRequest()
    val endpoints = uiState.value.connectedEndpoints
    endpoints.forEach { endpoint ->
      sendPayload(endpoint.id, ChatProtocol.encodeDisconnect("User disconnected"), PayloadPriority.Control) { }
    }
    transport.stopAll()
    endpoints.forEach { clearPayloadSenders(it.id) }
    recoveryMode = false
    lastPeerEndpoint = null
    recoveryConnectionAttempts.clear()
    endpointMemberIds.clear()
    handledCallVoiceClipIds.clear()
    retriedMessageEndpointIds.clear()
    store.clearConnectedEndpoints()
    showConversation(null)
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
    messageManager.sendExistingMessageTo(this, message.copy(status = MessageStatus.Queued), endpoints)
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
    cancelPendingLocationRequest()
    super.onCleared()
  }

  internal companion object {
    const val DEFAULT_GROUP_NAME = "Offline group"
    const val VOICE_MIME_TYPE = VOICE_MESSAGE_MIME_TYPE
    const val LOCATION_REQUEST_TIMEOUT_MS = 15_000L
    const val LOCATION_TIMEOUT_MESSAGE = "Location timed out. Check Location is enabled and try again."
    const val LIVE_AUDIO_MAX_PENDING_FRAMES = 2
    const val MAX_BLUETOOTH_REDUNDANT_CALL_AUDIO_FRAME_BYTES = 48
    const val MAX_BLUETOOTH_REDUNDANT_CALL_AUDIO_FRAMES = 0
    const val MAX_REDUNDANT_CALL_AUDIO_FRAME_HISTORY = 2
  }

  internal fun showConversation(
    conversationId: String?,
    carryVisibleMessages: Boolean = false,
  ) {
    val previousConversationId = activeConversationId
    if (carryVisibleMessages && previousConversationId != null && conversationId != null && previousConversationId != conversationId) {
      val carriedMessages = uiState.value.messages.map { it.copy(conversationId = conversationId) }
      if (carriedMessages.isNotEmpty()) {
        persistedMessages =
          mergeMessages(
            persistedMessages.filterNot { it.conversationId == previousConversationId || it.conversationId == conversationId },
            persistedMessages.filter { it.conversationId == conversationId } + carriedMessages,
          )
      }
    }
    activeConversationId = conversationId
    if (conversationId == null) {
      store.loadMessages(emptyList())
      return
    }
    store.setConversationId(conversationId)
    store.loadMessages(persistedMessages.filter { it.conversationId == conversationId })
  }

  internal fun showConversationFor(endpoint: NearbyEndpoint) {
    showConversation(conversationIdForEndpoint(endpoint))
  }

  internal fun ensureConversationForIncoming(
    endpointId: String,
    senderId: String,
  ): String {
    val conversationId = normalizedDeviceId(senderId) ?: conversationIdForEndpointId(endpointId) ?: endpointId
    if (activeConversationId != conversationId) {
      showConversation(conversationId, carryVisibleMessages = true)
    }
    return conversationId
  }

  internal fun conversationIdForEndpoint(endpoint: NearbyEndpoint): String =
    normalizedDeviceId(endpoint.deviceId) ?: endpoint.id

  internal fun conversationIdForEndpointId(endpointId: String): String? =
    uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.let(::conversationIdForEndpoint)
      ?: normalizedDeviceId(endpointMemberIds[endpointId])

  private fun persistVisibleMessages(messages: List<ChatMessage>) {
    val conversationId = activeConversationId ?: return
    val normalizedMessages = messages.map { if (it.conversationId == conversationId) it else it.copy(conversationId = conversationId) }
    persistedMessages =
      mergeMessages(
        persistedMessages.filterNot { it.conversationId == conversationId },
        normalizedMessages,
      )
    historyRepository.saveMessages(persistedMessages)
  }

  private fun mergeMessages(
    base: List<ChatMessage>,
    replacement: List<ChatMessage>,
  ): List<ChatMessage> =
    (base + replacement)
      .distinctBy { it.id }
      .sortedWith(compareBy<ChatMessage> { it.createdAt }.thenBy { it.id })
}
