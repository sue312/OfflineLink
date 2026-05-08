package com.example.offlinelink.ui.main

import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.CallToneMode
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.callToneModeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStatusLabelTest {
  @Test
  fun receivedStatusUsesDeliveredLabelForLocalMessages() {
    assertEquals("delivered", MessageStatus.Received.displayLabel(isLocal = true))
    assertEquals("received", MessageStatus.Received.displayLabel(isLocal = false))
  }

  @Test
  fun avatarInitialsUseTwoWordsWhenAvailable() {
    assertEquals("PB", avatarInitials("Phone B"))
    assertEquals("A", avatarInitials("Alice"))
    assertEquals("?", avatarInitials(" "))
  }

  @Test
  fun defaultDeviceDisplayNameUsesTrimmedModelWithFallback() {
    assertEquals("Pixel 8", defaultDeviceDisplayName(" Pixel 8 "))
    assertEquals("OfflineLink", defaultDeviceDisplayName(" "))
  }

  @Test
  fun setupPanelDefaultsCollapsedWhenMessagesExist() {
    assertEquals(true, defaultSetupExpanded(messageCount = 0))
    assertEquals(false, defaultSetupExpanded(messageCount = 1))
  }

  @Test
  fun memberPanelHidesConnectionStatusWords() {
    assertEquals("You: Phone A", localMemberSubtitle("Phone A"))
    assertNull(groupMemberStatusText(GroupMemberStatus.Online))
    assertNull(groupMemberStatusText(GroupMemberStatus.Reconnecting))
    assertNull(groupMemberStatusText(GroupMemberStatus.Offline))
  }

  @Test
  fun disconnectedSetupOnlyShowsSearchAction() {
    assertEquals(listOf("Search"), connectionSetupActionLabels(ConnectionStatus.Idle))
    assertEquals(listOf("Search"), connectionSetupActionLabels(ConnectionStatus.Discovering))
    assertEquals(emptyList<String>(), connectionSetupActionLabels(ConnectionStatus.Connected))
  }

  @Test
  fun disconnectedSetupDoesNotExposeNameFields() {
    assertEquals(emptyList<String>(), connectionSetupFieldLabels(ConnectionStatus.Idle))
    assertEquals(emptyList<String>(), connectionSetupFieldLabels(ConnectionStatus.Discovering))
    assertEquals(emptyList<String>(), connectionSetupFieldLabels(ConnectionStatus.Connected))
  }

  @Test
  fun setupDoesNotShowSeparateReconnectAction() {
    assertEquals(
      emptyList<String>(),
      connectionRecoveryActionLabels(
        ChatUiState(
          localDeviceId = "device-a",
          status = ConnectionStatus.Disconnected,
        ),
      ),
    )
    assertEquals(
      emptyList<String>(),
      connectionRecoveryActionLabels(
        ChatUiState(
          localDeviceId = "device-a",
          status = ConnectionStatus.Error,
          groupMembers = listOf(GroupMember("device-b", "Phone B")),
        ),
      ),
    )
  }

  @Test
  fun searchEmptyStateDoesNotMentionVisibleMode() {
    assertEquals(
      "No devices found yet. Keep this screen open while another phone taps Search.",
      nearbyEmptyStateText(),
    )
  }

  @Test
  fun connectedSummaryDoesNotExposeGroupName() {
    assertEquals(
      "Phone B",
      connectedSummarySubtitle(
        ChatUiState(
          localDeviceId = "device-a",
          groupName = "Field Team",
          status = ConnectionStatus.Connected,
          connectedEndpoints = listOf(com.example.offlinelink.model.NearbyEndpoint("device-b", "Phone B")),
          groupMembers = listOf(GroupMember("device-b", "Phone B")),
        ),
      ),
    )
  }

  @Test
  fun connectedSetupUsesSingleMembersSection() {
    val state =
      ChatUiState(
        localDeviceId = "device-a",
        status = ConnectionStatus.Connected,
        statusMessage = "Connected to Phone B",
        connectedEndpoints = listOf(com.example.offlinelink.model.NearbyEndpoint("device-b", "Phone B")),
        groupMembers = listOf(GroupMember("device-b", "Phone B")),
      )

    assertEquals(
      listOf("Peer"),
      connectedSetupSectionLabels(state),
    )
    assertEquals("Connection", setupHeaderTitle(state))
    assertEquals("Phone B", setupSummaryText(state))
  }

  @Test
  fun fullScreenCallUsesPhoneLikeStatusLabels() {
    assertEquals("Calling", callScreenStatusLabel(CallStatus.Outgoing))
    assertEquals("Offline call", callScreenStatusLabel(CallStatus.Active))
    assertEquals("Incoming call", callScreenStatusLabel(CallStatus.Incoming))
  }

  @Test
  fun fullScreenCallShowsNetworkQualityCopy() {
    assertEquals("OfflineLink / Strong signal", callNetworkQualityLabel())
    assertEquals("0:03", formatCallDuration(3_000L))
  }

  @Test
  fun fullScreenCallModeOnlyRunsDuringCalls() {
    assertEquals(true, shouldUseFullScreenCallUi(CallStatus.Outgoing))
    assertEquals(true, shouldUseFullScreenCallUi(CallStatus.Active))
    assertEquals(true, shouldUseFullScreenCallUi(CallStatus.Incoming))
    assertEquals(false, shouldUseFullScreenCallUi(CallStatus.Idle))
  }

  @Test
  fun callToneOnlyPlaysWhileRingingOrCalling() {
    assertEquals(CallToneMode.Outgoing, callToneModeFor(CallStatus.Outgoing))
    assertEquals(CallToneMode.Incoming, callToneModeFor(CallStatus.Incoming))
    assertEquals(CallToneMode.None, callToneModeFor(CallStatus.Active))
    assertEquals(CallToneMode.None, callToneModeFor(CallStatus.Idle))
  }

  @Test
  fun senderDisplayNameResolvesLocalAndKnownGroupMembers() {
    assertEquals(
      "You",
      senderDisplayName(
        senderId = "local",
        isLocal = true,
        localDeviceId = "local",
        groupMembers = listOf(GroupMember("device-b", "Phone B")),
      ),
    )
    assertEquals(
      "Phone B",
      senderDisplayName(
        senderId = "device-b",
        isLocal = false,
        localDeviceId = "local",
        groupMembers = listOf(GroupMember("device-b", "Phone B")),
      ),
    )
  }

  @Test
  fun senderDisplayNameFallsBackToStableDeviceShortName() {
    assertEquals(
      "Device ABCD",
      senderDisplayName(
        senderId = "abcdef1234",
        isLocal = false,
        localDeviceId = "local",
        groupMembers = emptyList(),
      ),
    )
  }
}
