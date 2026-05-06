package com.example.offlinelink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProtocolTest {
  @Test
  fun encodeAndDecodeHelloPayloadPreservesGroupName() {
    val bytes =
      ChatProtocol.encodeHello(
        senderId = "device-a",
        displayName = "Phone A",
        groupName = "Field Team",
        members = listOf(WireMember("device-a", "Phone A"), WireMember("device-b", "Phone B")),
        sentAt = 1234L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.Hello)
    val hello = decoded as DecodedWireMessage.Hello
    assertEquals("device-a", hello.senderId)
    assertEquals("Phone A", hello.displayName)
    assertEquals("Field Team", hello.groupName)
    assertEquals(listOf(WireMember("device-a", "Phone A"), WireMember("device-b", "Phone B")), hello.members)
    assertEquals(1234L, hello.sentAt)
  }

  @Test
  fun encodeAndDecodeMessagePayloadPreservesTextAndIds() {
    val bytes =
      ChatProtocol.encodeMessage(
        messageId = "msg-1",
        conversationId = "conversation-a",
        senderId = "device-a",
        text = "hello nearby",
        createdAt = 1234L,
        sentAt = 5678L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.Message)
    val message = decoded as DecodedWireMessage.Message
    assertEquals("msg-1", message.messageId)
    assertEquals("conversation-a", message.conversationId)
    assertEquals("device-a", message.senderId)
    assertEquals("hello nearby", message.text)
    assertEquals(1234L, message.createdAt)
    assertEquals(5678L, message.sentAt)
  }

  @Test
  fun encodeAndDecodeVoiceMessagePayloadPreservesAudioMetadata() {
    val bytes =
      ChatProtocol.encodeVoiceMessage(
        messageId = "voice-1",
        conversationId = "conversation-a",
        senderId = "device-a",
        audioBase64 = "AQIDBA==",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 1234L,
        sentAt = 5678L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.VoiceMessage)
    val message = decoded as DecodedWireMessage.VoiceMessage
    assertEquals("voice-1", message.messageId)
    assertEquals("conversation-a", message.conversationId)
    assertEquals("device-a", message.senderId)
    assertEquals("AQIDBA==", message.audioBase64)
    assertEquals(2300L, message.durationMs)
    assertEquals("audio/3gpp", message.mimeType)
    assertEquals(1234L, message.createdAt)
    assertEquals(5678L, message.sentAt)
  }

  @Test
  fun encodeAndDecodeAckPayloadPreservesAcknowledgedMessageId() {
    val bytes = ChatProtocol.encodeAck(messageId = "msg-2", sentAt = 8765L)

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.Ack)
    val ack = decoded as DecodedWireMessage.Ack
    assertEquals("msg-2", ack.messageId)
    assertEquals(8765L, ack.sentAt)
  }
}
