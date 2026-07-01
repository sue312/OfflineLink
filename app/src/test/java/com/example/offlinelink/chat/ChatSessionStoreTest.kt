package com.example.offlinelink.chat

import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.model.VoiceAttachment
import com.example.offlinelink.protocol.ChatProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSessionStoreTest {
  @Test
  fun setGroupNameFallsBackToDefaultWhenBlank() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    store.setGroupName("Field Team")
    assertEquals("Field Team", store.state.value.groupName)

    store.setGroupName("  ")
    assertEquals("Offline group", store.state.value.groupName)
  }

  @Test
  fun queueOutgoingMessageAddsQueuedLocalMessage() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    val message = store.queueOutgoingMessage("hello", now = 1000L)

    assertEquals("hello", message.text)
    assertEquals("device-a", message.senderId)
    assertEquals(MessageStatus.Queued, message.status)
    assertEquals(listOf(message), store.state.value.messages)
  }

  @Test
  fun queueOutgoingVoiceMessageAddsQueuedLocalVoiceMessage() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    val message =
      store.queueOutgoingVoiceMessage(
        payloadKey = "voice-key",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        now = 1000L,
      )

    assertEquals(MessageKind.Voice, message.kind)
    assertEquals("Voice 3s", message.text)
    assertEquals(VoiceAttachment("voice-key", 2300L, "audio/3gpp"), message.voice)
    assertEquals(MessageStatus.Queued, message.status)
    assertEquals(listOf(message), store.state.value.messages)
  }

  @Test
  fun markSentThenAcknowledgeTransitionsOutgoingMessageStatus() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val message = store.queueOutgoingMessage("hello", now = 1000L)

    store.markSent(message.id)
    assertEquals(MessageStatus.Sent, store.state.value.messages.single().status)

    store.acknowledge(message.id)
    assertEquals(MessageStatus.Received, store.state.value.messages.single().status)
  }

  @Test
  fun compactWireAckTransitionsOutgoingMessageStatus() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val message = store.queueOutgoingMessage("hello", now = 1000L)

    store.markSent(message.id)
    store.acknowledge(ChatProtocol.compactWireId(message.id))

    assertEquals(MessageStatus.Received, store.state.value.messages.single().status)
  }

  @Test
  fun setConnectedEndpointClearsDiscoveryAndPendingConnection() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val endpoint = NearbyEndpoint("endpoint-b", "Phone B")
    store.upsertEndpoint(endpoint)
    store.setPendingConnection(PendingConnection("endpoint-b", "Phone B", "1234"))

    store.addConnectedEndpoint(endpoint)

    assertEquals(listOf(endpoint), store.state.value.connectedEndpoints)
    assertEquals(emptyList<NearbyEndpoint>(), store.state.value.discoveredEndpoints)
    assertNull(store.state.value.pendingConnection)
  }

  @Test
  fun addConnectedEndpointReplacesExistingPeerForOneToOneMode() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")
    val phoneC = NearbyEndpoint("endpoint-c", "Phone C")

    store.addConnectedEndpoint(phoneB)
    store.addConnectedEndpoint(phoneC)
    store.addConnectedEndpoint(phoneB)

    assertEquals(listOf(phoneB), store.state.value.connectedEndpoints)
    assertEquals(listOf(GroupMember("endpoint-b", "Phone B")), store.state.value.groupMembers)
    assertEquals("Connected to Phone B", store.state.value.statusMessage)
  }

  @Test
  fun groupMembersCanReplaceTemporaryEndpointIdsWithStableDeviceIds() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    store.addConnectedEndpoint(NearbyEndpoint("endpoint-b", "Nearby device"))
    store.replaceGroupMember("endpoint-b", GroupMember("device-b", "Phone B"))
    store.mergeGroupMembers(listOf(GroupMember("device-c", "Phone C")))

    assertEquals(
      listOf(GroupMember("device-b", "Phone B"), GroupMember("device-c", "Phone C")),
      store.state.value.groupMembers,
    )
  }

  @Test
  fun removeConnectedEndpointUpdatesStatusAndKeepsRemainingDevices() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")
    val phoneC = NearbyEndpoint("endpoint-c", "Phone C")

    store.addConnectedEndpoint(phoneB)
    store.addConnectedEndpoint(phoneC)
    store.removeConnectedEndpoint(phoneB.id)

    assertEquals(listOf(phoneC), store.state.value.connectedEndpoints)
    assertEquals("Connected to Phone C", store.state.value.statusMessage)
  }

  @Test
  fun renameConnectedEndpointUsesHelloDisplayName() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    store.addConnectedEndpoint(NearbyEndpoint("endpoint-b", "Nearby device"))

    store.renameConnectedEndpoint("endpoint-b", "Phone B")

    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), store.state.value.connectedEndpoints)
    assertEquals("Connected to Phone B", store.state.value.statusMessage)
  }

  @Test
  fun messageRevisionIncrementsWhenMessagesAreAppended() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    assertEquals(0, store.state.value.messageRevision)

    store.queueOutgoingMessage("hello", now = 1000L)
    assertEquals(1, store.state.value.messageRevision)

    store.receiveRemoteMessage(
      messageId = "remote-1",
      conversationId = "one-to-one",
      senderId = "device-b",
      text = "hi",
      createdAt = 2000L,
    )
    assertEquals(2, store.state.value.messageRevision)
  }

  @Test
  fun receiveRemoteVoiceMessageDeduplicatesAndStoresAttachment() {
    val store = ChatSessionStore(localDeviceId = "device-a")

    val firstInsert =
      store.receiveRemoteVoiceMessage(
        messageId = "voice-1",
        conversationId = "one-to-one",
        senderId = "device-b",
        payloadKey = "voice-key",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 2000L,
      )
    val secondInsert =
      store.receiveRemoteVoiceMessage(
        messageId = "voice-1",
        conversationId = "one-to-one",
        senderId = "device-b",
        payloadKey = "voice-key",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 2000L,
      )

    assertEquals(true, firstInsert)
    assertEquals(false, secondInsert)
    assertEquals(1, store.state.value.messages.size)
    assertEquals(MessageKind.Voice, store.state.value.messages.single().kind)
    assertEquals(VoiceAttachment("voice-key", 2300L, "audio/3gpp"), store.state.value.messages.single().voice)
  }

  @Test
  fun startOutgoingCallTracksTargetEndpoint() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")

    store.startOutgoingCall(phoneB, callId = "call-1")

    assertEquals(CallStatus.Outgoing, store.state.value.callState.status)
    assertEquals("call-1", store.state.value.callState.callId)
    assertEquals("endpoint-b", store.state.value.callState.peerEndpointId)
    assertEquals("Phone B", store.state.value.callState.peerName)
  }

  @Test
  fun receiveIncomingCallThenAcceptTransitionsToActive() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")

    store.receiveIncomingCall(phoneB, callId = "call-1")
    store.acceptCall("call-1", startedAt = 5000L)

    assertEquals(CallStatus.Active, store.state.value.callState.status)
    assertEquals("call-1", store.state.value.callState.callId)
    assertEquals("endpoint-b", store.state.value.callState.peerEndpointId)
    assertEquals(5000L, store.state.value.callState.startedAt)
  }

  @Test
  fun compactWireCallIdCanAcceptOutgoingCall() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")
    val callId = "33333333-3333-3333-3333-333333333333"

    store.startOutgoingCall(phoneB, callId = callId)
    store.acceptCall(ChatProtocol.compactWireId(callId), startedAt = 5000L)

    assertEquals(CallStatus.Active, store.state.value.callState.status)
    assertEquals(callId, store.state.value.callState.callId)
  }

  @Test
  fun removingConnectedEndpointClearsMatchingCallState() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")

    store.addConnectedEndpoint(phoneB)
    store.startOutgoingCall(phoneB, callId = "call-1")
    store.removeConnectedEndpoint("endpoint-b")

    assertEquals(CallStatus.Idle, store.state.value.callState.status)
    assertNull(store.state.value.callState.callId)
  }
}
