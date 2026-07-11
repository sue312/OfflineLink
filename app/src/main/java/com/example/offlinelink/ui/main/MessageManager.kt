package com.example.offlinelink.ui.main

import android.net.Uri
import android.util.Log
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
import com.example.offlinelink.transport.GattFrameCodec
import com.example.offlinelink.transport.LatestPayloadSender
import com.example.offlinelink.transport.LONG_RANGE_GATT_VALUE_BYTES
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

class MessageManager {
  fun retryUndeliveredMessages(viewModel: MainScreenViewModel) = viewModel.retryUndeliveredMessages()

  fun sendExistingMessageTo(
    viewModel: MainScreenViewModel,
    message: ChatMessage,
    endpoints: List<NearbyEndpoint>,
  ) = viewModel.sendExistingMessageTo(message, endpoints)
}

internal fun MainScreenViewModel.cancelPendingLocationRequest() {
  val callback = pendingLocationResult
  val job = locationRequestJob
  pendingLocationResult = null
  locationRequestJob = null
  callback?.invoke(Result.failure(CancellationException("Location request cancelled")))
  job?.cancel(CancellationException("Location request cancelled"))
}

internal fun MainScreenViewModel.completeLocationRequest(result: Result<Unit>) {
  val callback = pendingLocationResult ?: return
  pendingLocationResult = null
  callback(result)
}

internal fun MainScreenViewModel.sendVoiceMessageTo(
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
  val bytes =
    ChatProtocol.encodeVoiceMessage(
      messageId = message.id,
      conversationId = message.conversationId,
      senderId = message.senderId,
      audioBase64 = "",
      payloadRef = payloadKey,
      durationMs = durationMs,
      mimeType = mimeType,
      createdAt = message.createdAt,
    )
  val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
  var hasFailure = false
  endpoints.forEach { endpoint ->
    sendPayloadWithBinaryAttachment(endpoint.id, payloadKey, audioBytes, bytes, PayloadPriority.Voice) { result ->
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

internal fun MainScreenViewModel.sendPayload(
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

internal fun MainScreenViewModel.sendPayloadWithBinaryAttachment(
  endpointId: String,
  payloadRef: String,
  attachmentBytes: ByteArray,
  envelopeBytes: ByteArray,
  priority: PayloadPriority,
  onResult: (Result<Unit>) -> Unit = {},
) {
  val binaryBytes = ChatProtocol.encodeBinaryPayload(payloadRef = payloadRef, bytes = attachmentBytes)
  logMessageManagerDebug(
    "Sending binary attachment endpoint=${endpointId.takeLast(5)} priority=$priority " +
      "payloadRef=$payloadRef attachmentBytes=${attachmentBytes.size} " +
      "binaryBytes=${binaryBytes.size} envelopeBytes=${envelopeBytes.size} " +
      "estimatedGattFragments=${estimatedLongRangeGattFragments(binaryBytes.size)}",
  )
  sendPayload(endpointId, binaryBytes, priority) { binaryResult ->
    if (binaryResult.isFailure) {
      logMessageManagerWarning(
        "Binary attachment failed endpoint=${endpointId.takeLast(5)} priority=$priority",
        binaryResult.exceptionOrNull(),
      )
      onResult(binaryResult)
      return@sendPayload
    }
    sendPayload(endpointId, envelopeBytes, priority, onResult)
  }
}

internal fun MainScreenViewModel.clearPayloadSenders(endpointId: String) {
  payloadSender.clearEndpoint(endpointId)
  liveAudioSender.clearEndpoint(endpointId)
}

internal fun MainScreenViewModel.messagePriority(kind: MessageKind): PayloadPriority =
  when (kind) {
    MessageKind.Text -> PayloadPriority.Text
    MessageKind.Voice -> PayloadPriority.Voice
    MessageKind.Location -> PayloadPriority.Location
    MessageKind.Image -> PayloadPriority.Image
  }

internal fun MainScreenViewModel.retryUndeliveredMessages() {
  val endpoints = uiState.value.connectedEndpoints
  if (endpoints.isEmpty()) return
  store.localMessagesPendingDelivery().forEach { message ->
    val alreadyRetriedEndpointIds = retriedMessageEndpointIds[message.id].orEmpty()
    val pendingEndpoints = endpoints.filterNot { it.id in alreadyRetriedEndpointIds }
    sendExistingMessageTo(message, pendingEndpoints)
  }
}

internal fun MainScreenViewModel.sendExistingMessageTo(
  message: ChatMessage,
  endpoints: List<NearbyEndpoint>,
) {
  if (endpoints.isEmpty()) return
  val wirePayload =
    when (message.kind) {
      MessageKind.Text ->
        OutgoingWirePayload(
          envelopeBytes =
            ChatProtocol.encodeMessage(
              messageId = message.id,
              conversationId = message.conversationId,
              senderId = message.senderId,
              text = message.text,
              createdAt = message.createdAt,
            ),
        )
      MessageKind.Voice -> {
        val voice = message.voice ?: return
        val rawBytes = payloadCache.get(voice.payloadKey) ?: return
        OutgoingWirePayload(
          envelopeBytes =
            ChatProtocol.encodeVoiceMessage(
              messageId = message.id,
              conversationId = message.conversationId,
              senderId = message.senderId,
              audioBase64 = "",
              payloadRef = voice.payloadKey,
              durationMs = voice.durationMs,
              mimeType = voice.mimeType,
              createdAt = message.createdAt,
            ),
          binaryPayloadRef = voice.payloadKey,
          binaryBytes = rawBytes,
        )
      }
      MessageKind.Location -> {
        val location = message.location ?: return
        OutgoingWirePayload(
          envelopeBytes =
            ChatProtocol.encodeLocation(
              messageId = message.id,
              conversationId = message.conversationId,
              senderId = message.senderId,
              latitude = location.latitude,
              longitude = location.longitude,
              accuracy = location.accuracy,
              createdAt = message.createdAt,
            ),
        )
      }
      MessageKind.Image -> {
        val image = message.image ?: return
        val rawBytes = payloadCache.get(image.payloadKey) ?: return
        OutgoingWirePayload(
          envelopeBytes =
            ChatProtocol.encodeImage(
              messageId = message.id,
              conversationId = message.conversationId,
              senderId = message.senderId,
              imageBase64 = "",
              payloadRef = image.payloadKey,
              mimeType = image.mimeType,
              width = image.width,
              height = image.height,
              createdAt = message.createdAt,
            ),
          binaryPayloadRef = image.payloadKey,
          binaryBytes = rawBytes,
        )
      }
    }
  val pendingEndpointIds = endpoints.map { it.id }.toMutableSet()
  var hasFailure = false
  endpoints.forEach { endpoint ->
    sendExistingWirePayload(endpoint.id, wirePayload, messagePriority(message.kind)) { result ->
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

private fun estimatedLongRangeGattFragments(bytes: Int): Int {
  if (bytes <= LONG_RANGE_GATT_VALUE_BYTES) return 1
  val chunkBytes = LONG_RANGE_GATT_VALUE_BYTES - GattFrameCodec.HEADER_BYTES
  return (bytes + chunkBytes - 1) / chunkBytes
}

private const val MESSAGE_MANAGER_TAG = "MessageManager"

private fun logMessageManagerDebug(message: String) {
  runCatching { Log.d(MESSAGE_MANAGER_TAG, message) }
}

private fun logMessageManagerWarning(
  message: String,
  throwable: Throwable?,
) {
  runCatching { Log.w(MESSAGE_MANAGER_TAG, message, throwable) }
}

internal fun MainScreenViewModel.sendExistingWirePayload(
  endpointId: String,
  wirePayload: OutgoingWirePayload,
  priority: PayloadPriority,
  onResult: (Result<Unit>) -> Unit,
) {
  val binaryPayloadRef = wirePayload.binaryPayloadRef
  val binaryBytes = wirePayload.binaryBytes
  if (binaryPayloadRef != null && binaryBytes != null) {
    sendPayloadWithBinaryAttachment(endpointId, binaryPayloadRef, binaryBytes, wirePayload.envelopeBytes, priority, onResult)
  } else {
    sendPayload(endpointId, wirePayload.envelopeBytes, priority, onResult)
  }
}

internal fun MainScreenViewModel.handleIncomingBytes(endpointId: String, bytes: ByteArray) {
  if (isFromNonPeer(endpointId)) return
  val decodedMessages =
    runCatching { ChatProtocol.decodeAll(bytes) }
      .getOrElse {
        return
      }
  for (decoded in decodedMessages) {
    when (decoded) {
    is DecodedWireMessage.Hello -> {
      ensureEndpointConnected(endpointId, decoded.displayName)
      val rosterChanged = mergeIncomingRoster(endpointId, decoded)
      store.renameConnectedEndpoint(endpointId, decoded.displayName)
      lastPeerEndpoint = uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.copy(deviceId = decoded.senderId) ?: lastPeerEndpoint
      val localGroupName = uiState.value.groupName
      if (isGroupMismatch(localGroupName, decoded.groupName)) {
        store.setGroupWarning("Group mismatch: ${decoded.displayName} uses ${decoded.groupName}")
      } else {
        store.setGroupWarning(null)
      }
      if (localGroupName == MainScreenViewModel.DEFAULT_GROUP_NAME || (decoded.groupName != MainScreenViewModel.DEFAULT_GROUP_NAME && decoded.groupName == localGroupName)) {
        store.setGroupName(decoded.groupName)
      }
      if (rosterChanged) {
        broadcastHello()
      }
    }
    is DecodedWireMessage.Message -> {
      ensureEndpointConnected(endpointId)
      val conversationId = ensureConversationForIncoming(endpointId, decoded.senderId)
      val wasNewMessage =
        store.receiveRemoteMessage(
          messageId = decoded.messageId,
          conversationId = conversationId,
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
      handleIncomingVoiceMessage(endpointId, decoded)
    }
    is DecodedWireMessage.Ack -> {
      ensureEndpointConnected(endpointId)
      store.acknowledge(decoded.messageId)
    }
    is DecodedWireMessage.Disconnect -> handleEndpointDisconnected(endpointId, shouldReconnect = false)
    is DecodedWireMessage.ImageMessage -> {
      handleIncomingImageMessage(endpointId, decoded)
    }
    is DecodedWireMessage.LocationMessage -> {
      ensureEndpointConnected(endpointId)
      val conversationId = ensureConversationForIncoming(endpointId, decoded.senderId)
      val wasNewMessage =
        store.receiveRemoteLocationMessage(
          messageId = decoded.messageId,
          conversationId = conversationId,
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
      if (!isTargetedToLocalDevice(decoded.targetId)) continue
      if (!forwardCallAcceptIfNeeded(endpointId, decoded)) {
        store.acceptCall(decoded.callId)
        resetCallAudioTransmitState()
      }
    }
    is DecodedWireMessage.CallReject -> {
      ensureEndpointConnected(endpointId)
      if (!isTargetedToLocalDevice(decoded.targetId)) continue
      if (!forwardCallRejectIfNeeded(endpointId, decoded)) {
        store.rejectCall(decoded.callId)
        handledCallVoiceClipIds.clear()
        resetCallAudioTransmitState()
      }
    }
    is DecodedWireMessage.CallEnd -> {
      ensureEndpointConnected(endpointId)
      if (!isTargetedToLocalDevice(decoded.targetId)) continue
      if (!forwardCallEndIfNeeded(endpointId, decoded)) {
        store.endCall(decoded.callId)
        handledCallVoiceClipIds.clear()
        resetCallAudioTransmitState()
      }
    }
    is DecodedWireMessage.CallVoice -> {
      ensureEndpointConnected(endpointId)
      if (!isTargetedToLocalDevice(decoded.targetId)) continue
      handleIncomingCallVoice(endpointId, decoded)
    }
    is DecodedWireMessage.CallAudioFrame -> {
      ensureEndpointConnected(endpointId)
      if (!isTargetedToLocalDevice(decoded.targetId)) continue
      handleIncomingCallAudioFrame(endpointId, decoded)
    }
    is DecodedWireMessage.BinaryPayload -> {
      handleIncomingBinaryPayload(decoded)
    }
    }
  }
}

internal fun MainScreenViewModel.handleIncomingBinaryPayload(payload: DecodedWireMessage.BinaryPayload) {
  pendingBinaryPayloads[payload.payloadRef] = payload.bytes
  pendingVoiceMessages.remove(payload.payloadRef)?.let { pending ->
    handleIncomingVoiceMessage(pending.endpointId, pending.message)
  }
  pendingImageMessages.remove(payload.payloadRef)?.let { pending ->
    handleIncomingImageMessage(pending.endpointId, pending.message)
  }
}

internal fun MainScreenViewModel.handleIncomingVoiceMessage(
  endpointId: String,
  message: DecodedWireMessage.VoiceMessage,
) {
  ensureEndpointConnected(endpointId)
  val payloadBytes =
    binaryOrLegacyPayloadBytes(
      payloadRef = message.payloadRef,
      legacyBase64 = message.audioBase64,
      onMissingBinaryPayload = {
        pendingVoiceMessages[it] = PendingVoiceMessage(endpointId, message)
      },
    ) ?: return
  val payloadKey = payloadCache.put(payloadBytes)
  val conversationId = ensureConversationForIncoming(endpointId, message.senderId)
  val wasNewMessage =
    store.receiveRemoteVoiceMessage(
      messageId = message.messageId,
      conversationId = conversationId,
      senderId = message.senderId,
      payloadKey = payloadKey,
      durationMs = message.durationMs,
      mimeType = message.mimeType,
      createdAt = message.createdAt,
    )
  if (wasNewMessage) {
    forwardIncomingVoiceMessage(endpointId, message)
  }
  sendPayload(endpointId, ChatProtocol.encodeAck(message.messageId), PayloadPriority.Control) { }
}

internal fun MainScreenViewModel.handleIncomingImageMessage(
  endpointId: String,
  message: DecodedWireMessage.ImageMessage,
) {
  ensureEndpointConnected(endpointId)
  val payloadBytes =
    binaryOrLegacyPayloadBytes(
      payloadRef = message.payloadRef,
      legacyBase64 = message.imageBase64,
      onMissingBinaryPayload = {
        pendingImageMessages[it] = PendingImageMessage(endpointId, message)
      },
    ) ?: return
  val payloadKey = payloadCache.put(payloadBytes)
  val conversationId = ensureConversationForIncoming(endpointId, message.senderId)
  val wasNewMessage =
    store.receiveRemoteImageMessage(
      messageId = message.messageId,
      conversationId = conversationId,
      senderId = message.senderId,
      payloadKey = payloadKey,
      mimeType = message.mimeType,
      width = message.width,
      height = message.height,
      createdAt = message.createdAt,
    )
  if (wasNewMessage) {
    forwardIncomingImageMessage(endpointId, message)
  }
  sendPayload(endpointId, ChatProtocol.encodeAck(message.messageId), PayloadPriority.Control) { }
}

internal fun MainScreenViewModel.binaryOrLegacyPayloadBytes(
  payloadRef: String?,
  legacyBase64: String,
  onMissingBinaryPayload: (String) -> Unit,
): ByteArray? {
  val ref = payloadRef?.takeIf { it.isNotBlank() }
  if (ref != null) {
    pendingBinaryPayloads.remove(ref)?.let { return it }
    onMissingBinaryPayload(ref)
    return null
  }
  if (legacyBase64.isBlank()) return null
  return Base64.Default.decode(legacyBase64)
}
internal fun MainScreenViewModel.forwardIncomingMessage(sourceEndpointId: String, message: DecodedWireMessage.Message) = Unit

internal fun MainScreenViewModel.forwardIncomingVoiceMessage(sourceEndpointId: String, message: DecodedWireMessage.VoiceMessage) = Unit

internal fun MainScreenViewModel.forwardIncomingImageMessage(sourceEndpointId: String, message: DecodedWireMessage.ImageMessage) = Unit

internal fun MainScreenViewModel.forwardIncomingLocationMessage(sourceEndpointId: String, message: DecodedWireMessage.LocationMessage) = Unit
internal data class PendingVoiceMessage(
  val endpointId: String,
  val message: DecodedWireMessage.VoiceMessage,
)

internal data class PendingImageMessage(
  val endpointId: String,
  val message: DecodedWireMessage.ImageMessage,
)

internal data class OutgoingWirePayload(
  val envelopeBytes: ByteArray,
  val binaryPayloadRef: String? = null,
  val binaryBytes: ByteArray? = null,
)
