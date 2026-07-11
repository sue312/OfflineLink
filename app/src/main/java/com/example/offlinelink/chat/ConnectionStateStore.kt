package com.example.offlinelink.chat

import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionSessionState(
  val localDeviceId: String,
  val displayName: String = "OfflineLink",
  val avatarName: String = "",
  val groupName: String = "Offline group",
  val status: ConnectionStatus = ConnectionStatus.Idle,
  val statusMessage: String = "Ready",
  val isVisibleToNearby: Boolean = false,
  val discoveredEndpoints: List<NearbyEndpoint> = emptyList(),
  val pendingConnection: PendingConnection? = null,
  val connectedEndpoints: List<NearbyEndpoint> = emptyList(),
  val groupMembers: List<GroupMember> = emptyList(),
  val groupWarning: String? = null,
  val lastError: String? = null,
)

class ConnectionStateStore(
  private val localDeviceId: String,
) {
  private val mutableState = MutableStateFlow(ConnectionSessionState(localDeviceId = localDeviceId))
  val state: StateFlow<ConnectionSessionState> = mutableState.asStateFlow()

  fun setDisplayName(displayName: String) = update { it.copy(displayName = displayName.ifBlank { "OfflineLink" }) }

  fun setAvatarName(avatarName: String) = update { it.copy(avatarName = avatarName.trim()) }

  fun setGroupName(groupName: String) = update { it.copy(groupName = groupName.ifBlank { "Offline group" }) }

  fun setGroupWarning(groupWarning: String?) = update { it.copy(groupWarning = groupWarning) }

  fun setStatus(
    status: ConnectionStatus,
    message: String,
    error: String? = null,
  ) = update { it.copy(status = status, statusMessage = message, lastError = error) }

  fun setVisibleToNearby(isVisible: Boolean) = update { it.copy(isVisibleToNearby = isVisible) }

  fun restoreConnectedStatus(error: String? = null) {
    val current = state.value
    if (current.connectedEndpoints.isEmpty()) return
    update {
      it.copy(
        status = ConnectionStatus.Connected,
        statusMessage = connectedStatusMessage(it.connectedEndpoints),
        lastError = error,
      )
    }
  }

  fun setDiscoveredEndpoints(endpoints: List<NearbyEndpoint>) =
    update { it.copy(discoveredEndpoints = endpoints.distinctBy { endpoint -> endpoint.id }) }

  fun upsertEndpoint(endpoint: NearbyEndpoint) =
    setDiscoveredEndpoints(state.value.discoveredEndpoints.filterNot { it.id == endpoint.id } + endpoint)

  fun removeEndpoint(endpointId: String) =
    setDiscoveredEndpoints(state.value.discoveredEndpoints.filterNot { it.id == endpointId })

  fun setPendingConnection(pendingConnection: PendingConnection?) = update { it.copy(pendingConnection = pendingConnection) }

  fun addConnectedEndpoint(endpoint: NearbyEndpoint) {
    val connectedEndpoints = listOf(endpoint)
    update {
      it.copy(
        connectedEndpoints = connectedEndpoints,
        groupMembers = listOf(GroupMember(endpoint.id, endpoint.name, GroupMemberStatus.Online)),
        isVisibleToNearby = false,
        pendingConnection = null,
        discoveredEndpoints = emptyList(),
        status = ConnectionStatus.Connected,
        statusMessage = connectedStatusMessage(connectedEndpoints),
      )
    }
  }

  fun updateConnectedEndpointSignal(
    endpointId: String,
    deviceId: String?,
    rssi: Int,
    signalId: String? = null,
  ): Boolean {
    val stableDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
    val stableSignalId = signalId?.trim()?.takeIf { it.isNotEmpty() }
    var updated = false
    val connectedEndpoints =
      state.value.connectedEndpoints.map { endpoint ->
        val matches =
          endpoint.id == endpointId ||
            (stableDeviceId != null && endpoint.deviceId?.trim() == stableDeviceId) ||
            (stableSignalId != null && endpoint.signalId == stableSignalId)
        if (matches && endpoint.rssi != rssi) {
          updated = true
          endpoint.copy(rssi = rssi)
        } else {
          endpoint
        }
      }
    if (!updated) return false
    update { it.copy(connectedEndpoints = connectedEndpoints) }
    return true
  }

  fun updateConnectedEndpointSignalId(
    endpointId: String,
    signalId: String?,
  ): Boolean {
    val stableSignalId = signalId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    var updated = false
    val connectedEndpoints =
      state.value.connectedEndpoints.map { endpoint ->
        if (endpoint.id == endpointId && endpoint.signalId != stableSignalId) {
          updated = true
          endpoint.copy(signalId = stableSignalId)
        } else {
          endpoint
        }
      }
    if (!updated) return false
    update { it.copy(connectedEndpoints = connectedEndpoints) }
    return true
  }

  fun mergeGroupMembers(members: List<GroupMember>): Boolean {
    val current = state.value
    val merged = mergeGroupMembers(current.groupMembers, members)
    if (merged == current.groupMembers) return false
    update { it.copy(groupMembers = merged) }
    return true
  }

  fun replaceGroupMember(
    oldId: String,
    member: GroupMember,
  ): Boolean {
    val current = state.value
    val cleanedMember = cleanGroupMember(member)
    val withoutOldMember = current.groupMembers.filterNot { it.id == oldId || it.id == cleanedMember.id }
    val groupMembers = if (cleanedMember.id == localDeviceId) withoutOldMember else withoutOldMember + cleanedMember
    if (groupMembers == current.groupMembers) return false
    update { it.copy(groupMembers = groupMembers) }
    return true
  }

  fun removeGroupMember(memberId: String): Boolean {
    val current = state.value
    val groupMembers = current.groupMembers.filterNot { it.id == memberId }
    if (groupMembers == current.groupMembers) return false
    update { it.copy(groupMembers = groupMembers) }
    return true
  }

  fun markGroupMembersReconnecting() =
    update {
      it.copy(
        groupMembers =
          it.groupMembers.map { member ->
            if (member.status == GroupMemberStatus.Reconnecting) member else member.copy(status = GroupMemberStatus.Reconnecting)
          },
      )
    }

  fun markGroupMemberReconnecting(
    memberId: String,
    displayName: String,
  ): Boolean {
    val current = state.value
    if (memberId == localDeviceId) return false
    val cleanedName = displayName.ifBlank { "Nearby device" }
    val existing = current.groupMembers.firstOrNull { it.id == memberId }
    val groupMembers =
      if (existing == null) {
        current.groupMembers + GroupMember(memberId, cleanedName, GroupMemberStatus.Reconnecting)
      } else {
        current.groupMembers.map { member ->
          if (member.id == memberId) {
            member.copy(displayName = member.displayName.ifBlank { cleanedName }, status = GroupMemberStatus.Reconnecting)
          } else {
            member
          }
        }
      }
    if (groupMembers == current.groupMembers) return false
    update { it.copy(groupMembers = groupMembers) }
    return true
  }

  fun removeConnectedEndpoint(endpointId: String) {
    val connectedEndpoints = state.value.connectedEndpoints.filterNot { it.id == endpointId }
    val groupMembers =
      if (connectedEndpoints.isEmpty()) emptyList()
      else connectedEndpoints.map { endpoint -> GroupMember(endpoint.id, endpoint.name, GroupMemberStatus.Online) }
    update {
      it.copy(
        connectedEndpoints = connectedEndpoints,
        groupMembers = groupMembers,
        status = if (connectedEndpoints.isEmpty()) ConnectionStatus.Disconnected else ConnectionStatus.Connected,
        statusMessage = connectedStatusMessage(connectedEndpoints),
      )
    }
  }

  fun renameConnectedEndpoint(
    endpointId: String,
    displayName: String,
  ) {
    val cleanedName = displayName.ifBlank { "Nearby device" }
    val connectedEndpoints = state.value.connectedEndpoints.map { if (it.id == endpointId) it.copy(name = cleanedName) else it }
    update { it.copy(connectedEndpoints = connectedEndpoints, statusMessage = connectedStatusMessage(connectedEndpoints)) }
  }

  fun setConnectedEndpoint(endpoint: NearbyEndpoint?) {
    if (endpoint == null) {
      clearConnectedEndpoints()
    } else {
      addConnectedEndpoint(endpoint)
    }
  }

  fun clearConnectedEndpoints() =
    update {
      it.copy(
        connectedEndpoints = emptyList(),
        groupMembers = emptyList(),
        isVisibleToNearby = false,
        pendingConnection = null,
        status = ConnectionStatus.Disconnected,
        statusMessage = "Disconnected",
      )
    }

  private fun update(reducer: (ConnectionSessionState) -> ConnectionSessionState) {
    mutableState.value = reducer(mutableState.value)
  }

  private fun mergeGroupMembers(
    current: List<GroupMember>,
    incoming: List<GroupMember>,
  ): List<GroupMember> {
    val currentById = current.associateBy { it.id }
    return (current + incoming.map(::cleanGroupMember))
      .filterNot { it.id == localDeviceId }
      .associateBy { it.id }
      .values
      .map { member ->
        val existing = currentById[member.id]
        when {
          existing?.status == GroupMemberStatus.Online && member.status != GroupMemberStatus.Online -> member.copy(status = GroupMemberStatus.Online)
          existing?.status == GroupMemberStatus.Reconnecting && member.status != GroupMemberStatus.Online -> member.copy(status = GroupMemberStatus.Reconnecting)
          else -> member
        }
      }
      .toList()
  }

  private fun cleanGroupMember(member: GroupMember): GroupMember =
    GroupMember(member.id, member.displayName.ifBlank { "Nearby device" }, member.status)

  private fun connectedStatusMessage(endpoints: List<NearbyEndpoint>): String =
    when (endpoints.size) {
      0 -> "Disconnected"
      1 -> "Connected to ${endpoints.single().name}"
      else -> "Connected to ${endpoints.size} devices"
    }
}
