package com.example.offlinelink.chat

import com.example.offlinelink.data.PayloadCache
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.CallVoicePlayback
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatSessionStore(
  localDeviceId: String,
  conversationId: String = "one-to-one",
  payloadCache: PayloadCache? = null,
) {
  private val connectionStore = ConnectionStateStore(localDeviceId)
  private val messageStore = MessageStateStore(localDeviceId, conversationId, payloadCache)
  private val callStore = CallStateStore()
  private val mutableState = MutableStateFlow(combineState())
  val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

  fun setDisplayName(displayName: String) = sync { connectionStore.setDisplayName(displayName) }
  fun setAvatarName(avatarName: String) = sync { connectionStore.setAvatarName(avatarName) }
  fun setGroupName(groupName: String) = sync { connectionStore.setGroupName(groupName) }
  fun setGroupWarning(groupWarning: String?) = sync { connectionStore.setGroupWarning(groupWarning) }
  fun setStatus(status: ConnectionStatus, message: String, error: String? = null) = sync { connectionStore.setStatus(status, message, error) }
  fun setVisibleToNearby(isVisible: Boolean) = sync { connectionStore.setVisibleToNearby(isVisible) }
  fun restoreConnectedStatus(error: String? = null) = sync { connectionStore.restoreConnectedStatus(error) }
  fun setDiscoveredEndpoints(endpoints: List<NearbyEndpoint>) = sync { connectionStore.setDiscoveredEndpoints(endpoints) }
  fun upsertEndpoint(endpoint: NearbyEndpoint) = sync { connectionStore.upsertEndpoint(endpoint) }
  fun removeEndpoint(endpointId: String) = sync { connectionStore.removeEndpoint(endpointId) }
  fun setPendingConnection(pendingConnection: PendingConnection?) = sync { connectionStore.setPendingConnection(pendingConnection) }
  fun addConnectedEndpoint(endpoint: NearbyEndpoint) = sync { connectionStore.addConnectedEndpoint(endpoint) }
  fun updateConnectedEndpointSignal(endpointId: String, deviceId: String?, rssi: Int, signalId: String? = null): Boolean =
    syncResult { connectionStore.updateConnectedEndpointSignal(endpointId, deviceId, rssi, signalId) }
  fun updateConnectedEndpointSignalId(endpointId: String, signalId: String?): Boolean =
    syncResult { connectionStore.updateConnectedEndpointSignalId(endpointId, signalId) }
  fun setConnectedEndpoint(endpoint: NearbyEndpoint?) = sync {
    connectionStore.setConnectedEndpoint(endpoint)
    if (endpoint == null) callStore.clear()
  }
  fun clearConnectedEndpoints() = sync {
    connectionStore.clearConnectedEndpoints()
    callStore.clear()
  }
  fun mergeGroupMembers(members: List<GroupMember>): Boolean = syncResult { connectionStore.mergeGroupMembers(members) }
  fun replaceGroupMember(oldId: String, member: GroupMember): Boolean = syncResult { connectionStore.replaceGroupMember(oldId, member) }
  fun removeGroupMember(memberId: String): Boolean = syncResult { connectionStore.removeGroupMember(memberId) }
  fun markGroupMembersReconnecting() = sync { connectionStore.markGroupMembersReconnecting() }
  fun markGroupMemberReconnecting(memberId: String, displayName: String): Boolean =
    syncResult { connectionStore.markGroupMemberReconnecting(memberId, displayName) }
  fun removeConnectedEndpoint(endpointId: String) = sync {
    val shouldClearCall = callStore.state.value.callState.peerEndpointId == endpointId
    connectionStore.removeConnectedEndpoint(endpointId)
    if (shouldClearCall) callStore.clear()
  }
  fun renameConnectedEndpoint(endpointId: String, displayName: String) = sync { connectionStore.renameConnectedEndpoint(endpointId, displayName) }

  fun loadMessages(messages: List<ChatMessage>) = sync { messageStore.loadMessages(messages) }
  fun setConversationId(conversationId: String) = sync { messageStore.setConversationId(conversationId) }
  fun clearMessages() = sync { messageStore.clearMessages() }
  fun deleteMessage(messageId: String) = sync { messageStore.deleteMessage(messageId) }
  fun localMessagesPendingDelivery(): List<ChatMessage> = messageStore.localMessagesPendingDelivery()
  fun queueOutgoingMessage(text: String, now: Long = System.currentTimeMillis()): ChatMessage = syncResult { messageStore.queueOutgoingMessage(text, now) }
  fun queueOutgoingVoiceMessage(payloadKey: String, durationMs: Long, mimeType: String, now: Long = System.currentTimeMillis()): ChatMessage =
    syncResult { messageStore.queueOutgoingVoiceMessage(payloadKey, durationMs, mimeType, now) }
  fun queueOutgoingLocationMessage(latitude: Double, longitude: Double, accuracy: Float?, now: Long = System.currentTimeMillis()): ChatMessage =
    syncResult { messageStore.queueOutgoingLocationMessage(latitude, longitude, accuracy, now) }
  fun queueOutgoingImageMessage(payloadKey: String, mimeType: String, width: Int, height: Int, now: Long = System.currentTimeMillis()): ChatMessage =
    syncResult { messageStore.queueOutgoingImageMessage(payloadKey, mimeType, width, height, now) }
  fun receiveRemoteMessage(messageId: String, conversationId: String, senderId: String, text: String, createdAt: Long): Boolean =
    syncResult { messageStore.receiveRemoteMessage(messageId, conversationId, senderId, text, createdAt) }
  fun receiveRemoteVoiceMessage(messageId: String, conversationId: String, senderId: String, payloadKey: String, durationMs: Long, mimeType: String, createdAt: Long): Boolean =
    syncResult { messageStore.receiveRemoteVoiceMessage(messageId, conversationId, senderId, payloadKey, durationMs, mimeType, createdAt) }
  fun receiveRemoteLocationMessage(messageId: String, conversationId: String, senderId: String, latitude: Double, longitude: Double, accuracy: Float?, createdAt: Long): Boolean =
    syncResult { messageStore.receiveRemoteLocationMessage(messageId, conversationId, senderId, latitude, longitude, accuracy, createdAt) }
  fun receiveRemoteImageMessage(messageId: String, conversationId: String, senderId: String, payloadKey: String, mimeType: String, width: Int, height: Int, createdAt: Long): Boolean =
    syncResult { messageStore.receiveRemoteImageMessage(messageId, conversationId, senderId, payloadKey, mimeType, width, height, createdAt) }
  fun markSent(messageId: String) = sync { messageStore.markSent(messageId) }
  fun markQueued(messageId: String) = sync { messageStore.markQueued(messageId) }
  fun markFailed(messageId: String) = sync { messageStore.markFailed(messageId) }
  fun acknowledge(messageId: String) = sync { messageStore.acknowledge(messageId) }

  fun startOutgoingCall(endpoint: NearbyEndpoint, callId: String, peerMemberId: String? = null, peerName: String = endpoint.name) =
    sync { callStore.startOutgoingCall(endpoint, callId, peerMemberId, peerName) }
  fun receiveIncomingCall(endpoint: NearbyEndpoint, callId: String, peerMemberId: String? = null, peerName: String = endpoint.name) =
    sync { callStore.receiveIncomingCall(endpoint, callId, peerMemberId, peerName) }
  fun acceptCall(callId: String, startedAt: Long = System.currentTimeMillis()): Boolean = syncResult { callStore.acceptCall(callId, startedAt) }
  fun endCall(callId: String? = null): Boolean = syncResult { callStore.endCall(callId) }
  fun rejectCall(callId: String? = null): Boolean = syncResult { callStore.rejectCall(callId) }
  fun setCallActivity(activityLabel: String?) = sync { callStore.setCallActivity(activityLabel) }
  fun showCallPlayback(playback: CallVoicePlayback, activityLabel: String) = sync { callStore.showCallPlayback(playback, activityLabel) }
  fun finishCallPlayback(clipId: String) = sync { callStore.finishCallPlayback(clipId) }

  private fun sync(block: () -> Unit) {
    block()
    mutableState.value = combineState()
  }

  private fun <T> syncResult(block: () -> T): T {
    val result = block()
    mutableState.value = combineState()
    return result
  }

  private fun combineState(): ChatUiState {
    val connection = connectionStore.state.value
    val messages = messageStore.state.value
    val call = callStore.state.value
    val callPlayback = if (call.callState.status == CallStatus.Idle) null else call.callPlayback
    return ChatUiState(
      localDeviceId = connection.localDeviceId,
      displayName = connection.displayName,
      avatarName = connection.avatarName,
      groupName = connection.groupName,
      status = connection.status,
      statusMessage = connection.statusMessage,
      isVisibleToNearby = connection.isVisibleToNearby,
      discoveredEndpoints = connection.discoveredEndpoints,
      pendingConnection = connection.pendingConnection,
      connectedEndpoints = connection.connectedEndpoints,
      groupMembers = connection.groupMembers,
      messages = messages.messages,
      messageRevision = messages.messageRevision,
      groupWarning = connection.groupWarning,
      lastError = connection.lastError,
      callState = call.callState,
      callPlayback = callPlayback,
    )
  }
}
