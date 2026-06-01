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

class CallManager {
  fun resetTransmitState(viewModel: MainScreenViewModel) = viewModel.resetCallAudioTransmitState()

  fun callVoicePriority(
    viewModel: MainScreenViewModel,
    mimeType: String,
  ): PayloadPriority = viewModel.callVoicePriority(mimeType)
}

internal fun MainScreenViewModel.redundantPreviousFramesFor(
  current: CallAudioFrameRedundancy,
  maxFrameBytes: Int,
  maxFrameCount: Int,
): List<CallAudioFrameRedundancy> {
  if (!isRedundantCallAudioEligible(current, maxFrameBytes) || maxFrameCount <= 0) return emptyList()
  val selected = mutableListOf<CallAudioFrameRedundancy>()
  var expectedSequenceNumber = current.sequenceNumber - 1
  previousRedundantCallAudioFrames
    .toList()
    .asReversed()
    .forEach { previous ->
      if (selected.size >= maxFrameCount) return@forEach
      if (
        previous.sequenceNumber == expectedSequenceNumber &&
          previous.durationMs == current.durationMs &&
          previous.mimeType.equals(current.mimeType, ignoreCase = true) &&
          isRedundantCallAudioEligible(previous, maxFrameBytes)
      ) {
        selected += previous
        expectedSequenceNumber--
      } else if (previous.sequenceNumber < expectedSequenceNumber) {
        return@forEach
      }
    }
  return selected.asReversed()
}

internal fun MainScreenViewModel.isRedundantCallAudioEligible(
  frame: CallAudioFrameRedundancy,
  maxFrameBytes: Int,
): Boolean =
  frame.audioBytes.size <= maxFrameBytes &&
    isStreamingCallAudioMimeType(frame.mimeType)

internal fun MainScreenViewModel.resetCallAudioTransmitState() {
  nextCallAudioSequenceNumber = 0
  previousRedundantCallAudioFrames.clear()
}

internal fun MainScreenViewModel.rememberRedundantCallAudioFrame(
  frame: CallAudioFrameRedundancy,
  maxFrameBytes: Int,
) {
  if (!isRedundantCallAudioEligible(frame, maxFrameBytes)) {
    previousRedundantCallAudioFrames.clear()
    return
  }
  previousRedundantCallAudioFrames.addLast(frame)
  while (previousRedundantCallAudioFrames.size > MainScreenViewModel.MAX_REDUNDANT_CALL_AUDIO_FRAME_HISTORY) {
    previousRedundantCallAudioFrames.removeFirst()
  }
}

internal fun MainScreenViewModel.isExpectedLiveAudioDrop(throwable: Throwable?): Boolean =
  throwable is LatestPayloadSender.StalePayloadDroppedException

internal fun MainScreenViewModel.callVoicePriority(mimeType: String): PayloadPriority =
  if (isStreamingCallAudioMimeType(mimeType)) PayloadPriority.CallAudio else PayloadPriority.Voice

internal fun MainScreenViewModel.handleIncomingCallRequest(
  endpointId: String,
  request: DecodedWireMessage.CallRequest,
) {
  if (forwardCallRequestIfNeeded(endpointId, request)) return
  if (!isTargetedToLocalDevice(request.targetId)) return
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

internal fun MainScreenViewModel.routeEndpointForCallTarget(targetId: String?): NearbyEndpoint? {
  val endpoints = uiState.value.connectedEndpoints
  if (targetId == null) return endpoints.firstOrNull()
  return endpoints.firstOrNull { endpoint -> endpoint.id == targetId || endpointMemberIds[endpoint.id] == targetId }
    ?: endpoints.firstOrNull().takeIf { isKnownGroupMember(targetId) }
}

internal fun MainScreenViewModel.callTargetName(
  targetId: String?,
  routeEndpoint: NearbyEndpoint,
): String =
  targetId
    ?.let(::memberName)
    ?: uiState.value.connectedEndpoints.firstOrNull { it.id == targetId }?.name
    ?: routeEndpoint.name

internal fun MainScreenViewModel.memberName(memberId: String): String? =
  uiState.value.groupMembers.firstOrNull { it.id == memberId }?.displayName
    ?: uiState.value.connectedEndpoints.firstOrNull { endpointMemberIds[it.id] == memberId }?.name

internal fun MainScreenViewModel.isTargetedToLocalDevice(targetId: String?): Boolean =
  targetId == null || targetId == uiState.value.localDeviceId

internal fun MainScreenViewModel.isKnownGroupMember(memberId: String): Boolean =
  uiState.value.groupMembers.any { it.id == memberId } || endpointMemberIds.any { it.value == memberId }

internal fun MainScreenViewModel.forwardCallBytesIfNeeded(
  sourceEndpointId: String,
  targetId: String?,
  bytes: ByteArray,
  priority: PayloadPriority = PayloadPriority.CallSignal,
): Boolean = false

internal fun MainScreenViewModel.forwardCallRequestIfNeeded(
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

internal fun MainScreenViewModel.forwardCallAcceptIfNeeded(
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

internal fun MainScreenViewModel.forwardCallRejectIfNeeded(
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

internal fun MainScreenViewModel.forwardCallEndIfNeeded(
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

internal fun MainScreenViewModel.forwardCallVoiceIfNeeded(
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

internal fun MainScreenViewModel.forwardCallAudioFrameIfNeeded(
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

internal fun MainScreenViewModel.handleIncomingCallVoice(
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
        sequenceNumber = null,
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

internal fun MainScreenViewModel.handleIncomingCallAudioFrame(
  endpointId: String,
  frame: DecodedWireMessage.CallAudioFrame,
) {
  if (forwardCallAudioFrameIfNeeded(endpointId, frame)) return
  val callState = uiState.value.callState
  if (callState.status != CallStatus.Active) return
  if (frame.callId.isNotEmpty() && callState.callId != frame.callId) return
  if (callState.peerEndpointId != endpointId) return

  val peerName = callState.peerName ?: uiState.value.connectedEndpoints.firstOrNull { it.id == endpointId }?.name ?: "Nearby device"
  val playbackCallId = frame.callId.ifEmpty { callState.callId ?: "" }
  val playbackFrameId = frame.frameId.ifEmpty { "audio-${frame.sequenceNumber}" }
  val playbackSenderId = frame.senderId.ifEmpty { callState.peerMemberId ?: endpointId }
  val playbackCreatedAt = frame.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
  mutableCallAudioFrames.tryEmit(
    CallAudioPlaybackFrame(
      callId = playbackCallId,
      frameId = playbackFrameId,
      senderId = playbackSenderId,
      audioBytes = frame.audioBytes,
      durationMs = frame.durationMs,
      mimeType = frame.mimeType,
      sequenceNumber = frame.sequenceNumber,
      createdAt = playbackCreatedAt,
    ),
  )
  store.setCallActivity("Live voice from $peerName")
}
