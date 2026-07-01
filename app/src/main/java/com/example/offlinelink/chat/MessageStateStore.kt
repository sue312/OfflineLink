package com.example.offlinelink.chat

import com.example.offlinelink.data.PayloadCache
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ImageAttachment
import com.example.offlinelink.model.LocationAttachment
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.model.VoiceAttachment
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageSessionState(
  val messages: List<ChatMessage> = emptyList(),
  val messageRevision: Int = 0,
)

class MessageStateStore(
  private val localDeviceId: String,
  conversationId: String = "one-to-one",
  private val payloadCache: PayloadCache? = null,
) {
  private val mutableState = MutableStateFlow(MessageSessionState())
  private var activeConversationId = conversationId
  val state: StateFlow<MessageSessionState> = mutableState.asStateFlow()

  fun setConversationId(conversationId: String) {
    activeConversationId = conversationId.ifBlank { "one-to-one" }
  }

  fun loadMessages(messages: List<ChatMessage>) =
    update { it.copy(messages = messages.distinctBy { message -> message.id }, messageRevision = it.messageRevision + 1) }

  fun clearMessages() {
    state.value.messages.forEach(::deletePayloadFor)
    update { it.copy(messages = emptyList(), messageRevision = it.messageRevision + 1) }
  }

  fun deleteMessage(messageId: String) {
    state.value.messages.firstOrNull { it.id == messageId }?.let(::deletePayloadFor)
    update { it.copy(messages = it.messages.filterNot { message -> message.id == messageId }, messageRevision = it.messageRevision + 1) }
  }

  fun localMessagesPendingDelivery(): List<ChatMessage> =
    state.value.messages.filter { it.isLocal && it.status != MessageStatus.Received }

  fun queueOutgoingMessage(
    text: String,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage =
    append(
      ChatMessage(UUID.randomUUID().toString(), activeConversationId, localDeviceId, text, now, MessageStatus.Queued, isLocal = true),
    )

  fun queueOutgoingVoiceMessage(
    payloadKey: String,
    durationMs: Long,
    mimeType: String,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage =
    append(
      ChatMessage(
        id = UUID.randomUUID().toString(),
        conversationId = activeConversationId,
        senderId = localDeviceId,
        text = voiceLabel(durationMs),
        createdAt = now,
        status = MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Voice,
        voice = VoiceAttachment(payloadKey, durationMs, mimeType),
      ),
    )

  fun queueOutgoingLocationMessage(
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage =
    append(
      ChatMessage(
        UUID.randomUUID().toString(),
        activeConversationId,
        localDeviceId,
        locationLabel(latitude, longitude),
        now,
        MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Location,
        location = LocationAttachment(latitude, longitude, accuracy),
      ),
    )

  fun queueOutgoingImageMessage(
    payloadKey: String,
    mimeType: String,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
  ): ChatMessage =
    append(
      ChatMessage(
        UUID.randomUUID().toString(),
        activeConversationId,
        localDeviceId,
        imageLabel(width, height),
        now,
        MessageStatus.Queued,
        isLocal = true,
        kind = MessageKind.Image,
        image = ImageAttachment(payloadKey, mimeType, width, height),
      ),
    )

  fun receiveRemoteMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    text: String,
    createdAt: Long,
  ): Boolean =
    appendRemote(ChatMessage(messageId, conversationId, senderId, text, createdAt, MessageStatus.Received, isLocal = false))

  fun receiveRemoteVoiceMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    payloadKey: String,
    durationMs: Long,
    mimeType: String,
    createdAt: Long,
  ): Boolean =
    appendRemote(
      ChatMessage(
        messageId,
        conversationId,
        senderId,
        voiceLabel(durationMs),
        createdAt,
        MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Voice,
        voice = VoiceAttachment(payloadKey, durationMs, mimeType),
      ),
    )

  fun receiveRemoteLocationMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    createdAt: Long,
  ): Boolean =
    appendRemote(
      ChatMessage(
        messageId,
        conversationId,
        senderId,
        locationLabel(latitude, longitude),
        createdAt,
        MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Location,
        location = LocationAttachment(latitude, longitude, accuracy),
      ),
    )

  fun receiveRemoteImageMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    payloadKey: String,
    mimeType: String,
    width: Int,
    height: Int,
    createdAt: Long,
  ): Boolean =
    appendRemote(
      ChatMessage(
        messageId,
        conversationId,
        senderId,
        imageLabel(width, height),
        createdAt,
        MessageStatus.Received,
        isLocal = false,
        kind = MessageKind.Image,
        image = ImageAttachment(payloadKey, mimeType, width, height),
      ),
    )

  fun markSent(messageId: String) = updateMessageStatus(messageId, MessageStatus.Sent)

  fun markQueued(messageId: String) = updateMessageStatus(messageId, MessageStatus.Queued)

  fun markFailed(messageId: String) = updateMessageStatus(messageId, MessageStatus.Failed)

  fun acknowledge(messageId: String) = updateMessageStatus(messageId, MessageStatus.Received)

  private fun append(message: ChatMessage): ChatMessage {
    update { it.copy(messages = it.messages + message, messageRevision = it.messageRevision + 1) }
    return message
  }

  private fun appendRemote(message: ChatMessage): Boolean {
    if (state.value.messages.any { it.id == message.id }) return false
    append(message)
    return true
  }

  private fun updateMessageStatus(
    messageId: String,
    status: MessageStatus,
  ) = update {
    it.copy(
      messages =
        it.messages.map { message ->
          if (ChatProtocol.matchesWireId(message.id, messageId)) message.copy(status = status) else message
        },
    )
  }

  private fun update(reducer: (MessageSessionState) -> MessageSessionState) {
    mutableState.value = reducer(mutableState.value)
  }

  private fun deletePayloadFor(message: ChatMessage) {
    message.voice?.let { payloadCache?.remove(it.payloadKey) }
    message.image?.let { payloadCache?.remove(it.payloadKey) }
  }

  private fun voiceLabel(durationMs: Long): String {
    val seconds = ((durationMs.coerceAtLeast(1L) + 999L) / 1000L).coerceAtLeast(1L)
    return "Voice ${seconds}s"
  }

  private fun locationLabel(
    latitude: Double,
    longitude: Double,
  ): String = "%.6f, %.6f".format(latitude, longitude)

  private fun imageLabel(
    width: Int,
    height: Int,
  ): String = "Image ${width}x$height"
}
