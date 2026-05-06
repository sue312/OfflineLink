package com.example.offlinelink.chat

import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.model.VoiceAttachment
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
        audioBase64 = "AQIDBA==",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        now = 1000L,
      )

    assertEquals(MessageKind.Voice, message.kind)
    assertEquals("Voice 3s", message.text)
    assertEquals(VoiceAttachment("AQIDBA==", 2300L, "audio/3gpp"), message.voice)
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
  fun addConnectedEndpointKeepsMultipleDistinctDevices() {
    val store = ChatSessionStore(localDeviceId = "device-a")
    val phoneB = NearbyEndpoint("endpoint-b", "Phone B")
    val phoneC = NearbyEndpoint("endpoint-c", "Phone C")

    store.addConnectedEndpoint(phoneB)
    store.addConnectedEndpoint(phoneC)
    store.addConnectedEndpoint(phoneB)

    assertEquals(listOf(phoneB, phoneC), store.state.value.connectedEndpoints)
    assertEquals("Connected to 2 devices", store.state.value.statusMessage)
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
        audioBase64 = "AQIDBA==",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 2000L,
      )
    val secondInsert =
      store.receiveRemoteVoiceMessage(
        messageId = "voice-1",
        conversationId = "one-to-one",
        senderId = "device-b",
        audioBase64 = "AQIDBA==",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 2000L,
      )

    assertEquals(true, firstInsert)
    assertEquals(false, secondInsert)
    assertEquals(1, store.state.value.messages.size)
    assertEquals(MessageKind.Voice, store.state.value.messages.single().kind)
    assertEquals(VoiceAttachment("AQIDBA==", 2300L, "audio/3gpp"), store.state.value.messages.single().voice)
  }
}
