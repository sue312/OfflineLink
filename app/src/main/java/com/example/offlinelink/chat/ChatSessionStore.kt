package com.example.offlinelink.chat

import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.CallState
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.CallVoicePlayback
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.ImageAttachment
import com.example.offlinelink.model.LocationAttachment
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.model.VoiceAttachment
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatSessionStore(
  private val localDeviceId: String,
  private val conversationId: String = "one-to-one",
) {
  private val mutableState = MutableStateFlow(ChatUiState(localDeviceId = localDeviceId))
  val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

  fun setDisplayName(displayName: String) {
    mutableState.value = mutableState.value.copy(displayName = displayName.ifBlank { "OfflineLink" })
  }

  fun setAvatarName(avatarName: String) {
    mutableState.value = mutableState.value.copy(avatarName = avatarName.trim())
  }

  fun setGroupName(groupName: String) {
    mutableState.value = mutableState.value.copy(groupName = groupName.ifBlank { "Offline group" })
  }

  fun setGroupWarning(groupWarning: String?) {
    mutableState.value = mutableState.value.copy(groupWarning = groupWarning)
  }

  fun setStatus(status: ConnectionStatus, message: String, error: String? = null) {
    mutableState.value = mutableState.value.copy(status = status, statusMessage = message, lastError = error)
  }

  fun setDiscoveredEndpoints(endpoints: List<NearbyEndpoint>) {
    mutableState.value = mutableState.value.copy(discoveredEndpoints = endpoints.distinctBy { it.id })
  }

  fun upsertEndpoint(endpoint: NearbyEndpoint) {
    setDiscoveredEndpoints(state.value.discoveredEndpoints.filterNot { it.id == endpoint.id } + endpoint)
  }

  fun removeEndpoint(endpointId: String) {
    setDiscoveredEndpoints(state.value.discoveredEndpoints.filterNot { it.id == endpointId })
  }

  fun setPendingConnection(pendingConnection: PendingConnection?) {
    mutableState.value = mutableState.value.copy(pendingConnection = pendingConnection)
  }

  fun addConnectedEndpoint(endpoint: NearbyEndpoint) {
    val current = mutableState.value
    val connectedEndpoints =
      if (current.connectedEndpoints.any { it.id == endpoint.id }) {
        current.connectedEndpoints.map { if (it.id == endpoint.id) endpoint else it }
      } else {
        current.connectedEndpoints + endpoint
      }
    mutableState.value =
      current.copy(
        connectedEndpoints = connectedEndpoints,
        groupMembers = mergeGroupMembers(current.groupMembers, listOf(GroupMember(endpoint.id, endpoint.name, GroupMemberStatus.Online))),
        pendingConnection = null,
        discoveredEndpoints = current.discoveredEndpoints.filterNot { it.id == endpoint.id },
        status = ConnectionStatus.Connected,
        statusMessage = connectedStatusMessage(connectedEndpoints),
      )
  }

  fun mergeGroupMembers(members: List<GroupMember>): Boolean {
    val current = mutableState.value
    val merged = mergeGroupMembers(current.groupMembers, members)
    if (merged == current.groupMembers) return false
    mutableState.value = current.copy(groupMembers = merged)
    return true
  }

  fun replaceGroupMember(oldId: String, member: GroupMember): Boolean {
    val current = mutableState.value
    val cleanedMember = cleanGroupMember(member)
    val withoutOldMember = current.groupMembers.filterNot { it.id == oldId || it.id == cleanedMember.id }
    val groupMembers =
      if (cleanedMember.id == localDeviceId) {
        withoutOldMember
      } else {
        withoutOldMember + cleanedMember
      }
    if (groupMembers == current.groupMembers) return false
    mutableState.value = current.copy(groupMembers = groupMembers)
    return true
  }

  fun removeGroupMember(memberId: String): Boolean {
    val current = mutableState.value
    val groupMembers = current.groupMembers.filterNot { it.id == memberId }
    if (groupMembers == current.groupMembers) return false
    mutableState.value = current.copy(groupMembers = groupMembers)
    return true
  }

  fun markGroupMembersReconnecting() {
    mutableState.value =
      mutableState.value.copy(
        groupMembers =
          mutableState.value.groupMembers.map { member ->
            if (member.status == GroupMemberStatus.Online) member else member.copy(status = GroupMemberStatus.Reconnecting)
          },
      )
  }

  fun removeConnectedEndpoint(endpointId: String) {
    val connectedEndpoints = mutableState.value.connectedEndpoints.filterNot { it.id == endpointId }
    val callState =
      if (mutableState.value.callState.peerEndpointId == endpointId) {
        CallState()
      } else {
        mutableState.value.callState
      }
    mutableState.value =
      mutableState.value.copy(
        connectedEndpoints = connectedEndpoints,
        status = if (connectedEndpoints.isEmpty()) ConnectionStatus.Disconnected else ConnectionStatus.Connected,
        statusMessage = connectedStatusMessage(connectedEndpoints),
        callState = callState,
        callPlayback = if (callState.status == CallStatus.Idle) null else mutableState.value.callPlayback,
      )
  }

  fun renameConnectedEndpoint(endpointId: String, displayName: String) {
    val cleanedName = displayName.ifBlank { "Nearby device" }
    val connectedEndpoints =
      mutableState.value.connectedEndpoints.map {
        if (it.id == endpointId) it.copy(name = cleanedName) else it
      }
    mutableState.value =
      mutableState.value.copy(
        connectedEndpoints = connectedEndpoints,
        statusMessage = connectedStatusMessage(connectedEndpoints),
      )
  }

  fun setConnectedEndpoint(endpoint: NearbyEndpoint?) {
    if (endpoint == null) {
      clearConnectedEndpoints()
    } else {
      mutableState.value =
        mutableState.value.copy(
          connectedEndpoints = listOf(endpoint),
          pendingConnection = null,
          discoveredEndpoints = emptyList(),
          status = ConnectionStatus.Connected,
          statusMessage = connectedStatusMessage(listOf(endpoint)),
        )
    }
  }

  fun clearConnectedEndpoints() {
    mutableState.value =
      mutableState.value.copy(
        connectedEndpoints = emptyList(),
        groupMembers = emptyList(),
        pendingConnection = null,
        status = ConnectionStatus.Disconnected,
        statusMessage = "Disconnected",
        callState = CallState(),
        callPlayback = null,
      )
  }

  fun loadMessages(messages: List<ChatMessage>) {
    val cleanedMessages = messages.distinctBy { it.id }
    mutableState.value =
      mutableState.value.copy(
        messages = cleanedMessages,
        messageRevision = mutableState.value.messageRevision + 1,
      )
  }

  fun clearMessages() {
    mutableState.value =
      mutableState.value.copy(
        messages = emptyList(),
        messageRevision = mutableState.value.messageRevision + 1,
      )
  }

  fun deleteMessage(messageId: String) {
    val messages = mutableState.value.messages.filterNot { it.id == messageId }
    mutableState.value =
      mutableState.value.copy(
        messages = messages,
        messageRevision = mutableState.value.messageRevision + 1,
      )
  }

  fun localMessagesPendingDelivery(): List<ChatMessage> =
    state.value.messages.filter { message ->
      message.isLocal && message.status != MessageStatus.Received
    }

  fun startOutgoingCall(
    endpoint: NearbyEndpoint,
    callId: String,
    peerMemberId: String? = null,
    peerName: String = endpoint.name,
  ) {
    mutableState.value =
      mutableState.value.copy(
        callState =
          CallState(
            status = CallStatus.Outgoing,
            callId = callId,
            peerEndpointId = endpoint.id,
            peerMemberId = peerMemberId,
            peerName = peerName,
            isInitiator = true,
          ),
      )
  }

  fun receiveIncomingCall(
    endpoint: NearbyEndpoint,
    callId: String,
    peerMemberId: String? = null,
    peerName: String = endpoint.name,
  ) {
    mutableState.value =
      mutableState.value.copy(
        callState =
          CallState(
            status = CallStatus.Incoming,
            callId = callId,
            peerEndpointId = endpoint.id,
            peerMemberId = peerMemberId,
            peerName = peerName,
            isInitiator = false,
          ),
      )
  }

  fun acceptCall(callId: String, startedAt: Long = System.currentTimeMillis()): Boolean {
    val current = mutableState.value
    val callState = current.callState
    if (callState.callId != callId) return false
    if (callState.status != CallStatus.Incoming && callState.status != CallStatus.Outgoing) return false
    mutableState.value =
      current.copy(
        callState =
          callState.copy(
            status = CallStatus.Active,
            startedAt = startedAt,
          ),
      )
    return true
  }

  fun endCall(callId: String? = null): Boolean {
    val current = mutableState.value
    if (current.callState.status == CallStatus.Idle) return false
    if (callId != null && current.callState.callId != callId) return false
    mutableState.value = current.copy(callState = CallState(), callPlayback = null)
    return true
  }

  fun rejectCall(callId: String? = null): Boolean = endCall(callId)

  fun setCallActivity(activityLabel: String?) {
    val current = mutableState.value
    if (current.callState.status == CallStatus.Idle) return
    mutableState.value = current.copy(callState = current.callState.copy(activityLabel = activityLabel))
  }

  fun showCallPlayback(playback: CallVoicePlayback, activityLabel: String) {
    val current = mutableState.value
    if (current.callState.status != CallStatus.Active) return
    if (current.callState.callId != playback.callId) return
    mutableState.value =
      current.copy(
        callState = current.callState.copy(activityLabel = activityLabel),
        callPlayback = playback,
      )
  }

  fun finishCallPlayback(clipId: String) {
    val current = mutableState.value
    if (current.callPlayback?.clipId != clipId) return
    mutableState.value =
      current.copy(
        callState = current.callState.copy(activityLabel = null),
        callPlayback = null,
      )
  }

  fun queueOutgoingMessage(text: String, now: Long = System.currentTimeMillis()): ChatMessage {
    val message =
      ChatMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        senderId = localDeviceId,
        text = text,
        createdAt = now,
        status = MessageStatus.Queued,
        isLocal = true,
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return message
  }

  fun queueOutgoingVoiceMessage(
    audioBase64: String,
    durationMs: Long,
    mimeType: String,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage {
    val message =
      ChatMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        senderId = localDeviceId,
        text = voiceLabel(durationMs),
        createdAt = now,
        status = MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Voice,
        voice = VoiceAttachment(audioBase64, durationMs, mimeType),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return message
  }

  fun receiveRemoteMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    text: String,
    createdAt: Long,
  ): Boolean {
    if (state.value.messages.any { it.id == messageId }) return false
    val message =
      ChatMessage(
        id = messageId,
        conversationId = conversationId,
        senderId = senderId,
        text = text,
        createdAt = createdAt,
        status = MessageStatus.Received,
        isLocal = false,
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return true
  }

  fun receiveRemoteVoiceMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    audioBase64: String,
    durationMs: Long,
    mimeType: String,
    createdAt: Long,
  ): Boolean {
    if (state.value.messages.any { it.id == messageId }) return false
    val message =
      ChatMessage(
        id = messageId,
        conversationId = conversationId,
        senderId = senderId,
        text = voiceLabel(durationMs),
        createdAt = createdAt,
        status = MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Voice,
        voice = VoiceAttachment(audioBase64, durationMs, mimeType),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return true
  }

  fun queueOutgoingLocationMessage(
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage {
    val message =
      ChatMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        senderId = localDeviceId,
        text = locationLabel(latitude, longitude),
        createdAt = now,
        status = MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Location,
        location = LocationAttachment(latitude, longitude, accuracy),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return message
  }

  fun receiveRemoteLocationMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    createdAt: Long,
  ): Boolean {
    if (state.value.messages.any { it.id == messageId }) return false
    val message =
      ChatMessage(
        id = messageId,
        conversationId = conversationId,
        senderId = senderId,
        text = locationLabel(latitude, longitude),
        createdAt = createdAt,
        status = MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Location,
        location = LocationAttachment(latitude, longitude, accuracy),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return true
  }

  fun queueOutgoingImageMessage(
    imageBase64: String,
    mimeType: String,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage {
    val message =
      ChatMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        senderId = localDeviceId,
        text = imageLabel(width, height),
        createdAt = now,
        status = MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Image,
        image = ImageAttachment(imageBase64, mimeType, width, height),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return message
  }

  fun receiveRemoteImageMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    imageBase64: String,
    mimeType: String,
    width: Int,
    height: Int,
    createdAt: Long,
  ): Boolean {
    if (state.value.messages.any { it.id == messageId }) return false
    val message =
      ChatMessage(
        id = messageId,
        conversationId = conversationId,
        senderId = senderId,
        text = imageLabel(width, height),
        createdAt = createdAt,
        status = MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Image,
        image = ImageAttachment(imageBase64, mimeType, width, height),
      )
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages + message,
        messageRevision = mutableState.value.messageRevision + 1,
      )
    return true
  }

  fun markSent(messageId: String) {
    updateMessageStatus(messageId, MessageStatus.Sent)
  }

  fun markQueued(messageId: String) {
    updateMessageStatus(messageId, MessageStatus.Queued)
  }

  fun markFailed(messageId: String) {
    updateMessageStatus(messageId, MessageStatus.Failed)
  }

  fun acknowledge(messageId: String) {
    updateMessageStatus(messageId, MessageStatus.Received)
  }

  private fun updateMessageStatus(messageId: String, status: MessageStatus) {
    mutableState.value =
      mutableState.value.copy(
        messages = mutableState.value.messages.map { if (it.id == messageId) it.copy(status = status) else it },
      )
  }

  private fun mergeGroupMembers(current: List<GroupMember>, incoming: List<GroupMember>): List<GroupMember> {
    val currentById = current.associateBy { it.id }
    return (current + incoming.map(::cleanGroupMember))
      .filterNot { it.id == localDeviceId }
      .associateBy { it.id }
      .values
      .map { member ->
        val existing = currentById[member.id]
        if (existing?.status == GroupMemberStatus.Online && member.status != GroupMemberStatus.Online) {
          member.copy(status = GroupMemberStatus.Online)
        } else {
          member
        }
      }
      .toList()
  }

  private fun cleanGroupMember(member: GroupMember): GroupMember =
    GroupMember(
      id = member.id,
      displayName = member.displayName.ifBlank { "Nearby device" },
      status = member.status,
    )

  private fun connectedStatusMessage(endpoints: List<NearbyEndpoint>): String =
    when (endpoints.size) {
      0 -> "Disconnected"
      1 -> "Connected to ${endpoints.single().name}"
      else -> "Connected to ${endpoints.size} devices"
    }

  private fun voiceLabel(durationMs: Long): String {
    val seconds = ((durationMs.coerceAtLeast(1L) + 999L) / 1000L).coerceAtLeast(1L)
    return "Voice ${seconds}s"
  }

  private fun locationLabel(latitude: Double, longitude: Double): String =
    "%.6f, %.6f".format(latitude, longitude)

  private fun imageLabel(width: Int, height: Int): String =
    "Image ${width}x$height"
}
