package com.example.offlinelink.model

data class NearbyEndpoint(
  val id: String,
  val name: String,
)

data class GroupMember(
  val id: String,
  val displayName: String,
)

data class PendingConnection(
  val endpointId: String,
  val endpointName: String,
  val authenticationToken: String,
)

enum class ConnectionStatus {
  Idle,
  Advertising,
  Discovering,
  Connecting,
  Connected,
  Disconnected,
  Error,
}

enum class MessageStatus {
  Queued,
  Sent,
  Received,
  Failed,
}

enum class MessageKind {
  Text,
  Voice,
  Location,
  Image,
}

data class VoiceAttachment(
  val audioBase64: String,
  val durationMs: Long,
  val mimeType: String,
)

data class LocationAttachment(
  val latitude: Double,
  val longitude: Double,
  val accuracy: Float? = null,
)

data class ImageAttachment(
  val imageBase64: String,
  val mimeType: String,
  val width: Int,
  val height: Int,
)

data class ChatMessage(
  val id: String,
  val conversationId: String,
  val senderId: String,
  val text: String,
  val createdAt: Long,
  val status: MessageStatus,
  val isLocal: Boolean,
  val kind: MessageKind = MessageKind.Text,
  val voice: VoiceAttachment? = null,
  val location: LocationAttachment? = null,
  val image: ImageAttachment? = null,
)

data class ChatUiState(
  val localDeviceId: String,
  val displayName: String = "OfflineLink",
  val groupName: String = "Offline group",
  val status: ConnectionStatus = ConnectionStatus.Idle,
  val statusMessage: String = "Ready",
  val discoveredEndpoints: List<NearbyEndpoint> = emptyList(),
  val pendingConnection: PendingConnection? = null,
  val connectedEndpoints: List<NearbyEndpoint> = emptyList(),
  val groupMembers: List<GroupMember> = emptyList(),
  val messages: List<ChatMessage> = emptyList(),
  val messageRevision: Int = 0,
  val groupWarning: String? = null,
  val lastError: String? = null,
) {
  val connectedEndpoint: NearbyEndpoint?
    get() = connectedEndpoints.firstOrNull()
}
