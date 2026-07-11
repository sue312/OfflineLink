package com.example.offlinelink.chat

import com.example.offlinelink.model.CallState
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.CallVoicePlayback
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.protocol.ChatProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallSessionState(
  val callState: CallState = CallState(),
  val callPlayback: CallVoicePlayback? = null,
)

class CallStateStore {
  private val mutableState = MutableStateFlow(CallSessionState())
  val state: StateFlow<CallSessionState> = mutableState.asStateFlow()

  fun startOutgoingCall(
    endpoint: NearbyEndpoint,
    callId: String,
    peerMemberId: String? = null,
    peerName: String = endpoint.name,
  ) = update {
    it.copy(
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
  ) = update {
    it.copy(
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

  fun acceptCall(
    callId: String,
    startedAt: Long = System.currentTimeMillis(),
  ): Boolean {
    val current = state.value
    if (!sameCallId(current.callState.callId, callId)) return false
    if (current.callState.status != CallStatus.Incoming && current.callState.status != CallStatus.Outgoing) return false
    update { it.copy(callState = it.callState.copy(status = CallStatus.Active, startedAt = startedAt)) }
    return true
  }

  fun endCall(callId: String? = null): Boolean {
    val current = state.value
    if (current.callState.status == CallStatus.Idle) return false
    if (callId != null && !sameCallId(current.callState.callId, callId)) return false
    clear()
    return true
  }

  fun rejectCall(callId: String? = null): Boolean = endCall(callId)

  fun setCallActivity(activityLabel: String?) {
    if (state.value.callState.status == CallStatus.Idle) return
    update { it.copy(callState = it.callState.copy(activityLabel = activityLabel)) }
  }

  fun showCallPlayback(
    playback: CallVoicePlayback,
    activityLabel: String,
  ) {
    val current = state.value
    if (current.callState.status != CallStatus.Active || !sameCallId(current.callState.callId, playback.callId)) return
    update { it.copy(callState = it.callState.copy(activityLabel = activityLabel), callPlayback = playback) }
  }

  fun finishCallPlayback(clipId: String) {
    if (state.value.callPlayback?.clipId != clipId) return
    update { it.copy(callState = it.callState.copy(activityLabel = null), callPlayback = null) }
  }

  fun clear() {
    mutableState.value = CallSessionState()
  }

  private fun update(reducer: (CallSessionState) -> CallSessionState) {
    mutableState.value = reducer(mutableState.value)
  }

  private fun sameCallId(
    currentCallId: String?,
    incomingCallId: String,
  ): Boolean =
    currentCallId != null && ChatProtocol.matchesWireId(currentCallId, incomingCallId)
}
