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
    assertEquals(ChatProtocol.CURRENT_PROTOCOL_VERSION, hello.protocolVersion)
    assertEquals(ChatProtocol.DEFAULT_CAPABILITIES, hello.capabilities)
    assertEquals(1234L, hello.sentAt)
  }

  @Test
  fun decodeLegacyHelloWithoutVersionOrCapabilitiesUsesDefaults() {
    val legacyJson =
      """
      {
        "type": "hello",
        "payload": "{\"senderId\":\"device-a\",\"displayName\":\"Phone A\"}",
        "sentAt": 1234
      }
      """.trimIndent()

    val decoded = ChatProtocol.decode(legacyJson.encodeToByteArray())

    assertTrue(decoded is DecodedWireMessage.Hello)
    val hello = decoded as DecodedWireMessage.Hello
    assertEquals("device-a", hello.senderId)
    assertEquals("Phone A", hello.displayName)
    assertEquals("Offline group", hello.groupName)
    assertEquals(emptyList<WireMember>(), hello.members)
    assertEquals(ChatProtocol.CURRENT_PROTOCOL_VERSION, hello.protocolVersion)
    assertEquals(emptySet<String>(), hello.capabilities)
    assertEquals(1234L, hello.sentAt)
  }

  @Test
  fun encodeAndDecodeHelloPreservesProtocolVersionAndCapabilities() {
    val bytes =
      ChatProtocol.encodeHello(
        senderId = "device-a",
        displayName = "Phone A",
        sentAt = 1234L,
      )

    val encoded = bytes.decodeToString()
    assertTrue(encoded.contains("\"protocolVersion\":${ChatProtocol.CURRENT_PROTOCOL_VERSION}"))
    assertTrue(encoded.contains(ChatProtocol.CAPABILITY_CALL_TARGETING))

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.Hello)
    val hello = decoded as DecodedWireMessage.Hello
    assertEquals(ChatProtocol.CURRENT_PROTOCOL_VERSION, hello.protocolVersion)
    assertEquals(ChatProtocol.DEFAULT_CAPABILITIES, hello.capabilities)
    assertTrue(ChatProtocol.supportsCallTargeting(hello.capabilities))
    assertEquals(ChatProtocol.DEFAULT_CAPABILITIES.contains(ChatProtocol.CAPABILITY_PRIORITY_QUEUE), ChatProtocol.supportsPriorityQueue(hello.capabilities))
  }

  @Test
  fun decodeIgnoresUnknownEnvelopeAndPayloadFields() {
    val json =
      """
      {
        "type": "hello",
        "protocolVersion": ${ChatProtocol.CURRENT_PROTOCOL_VERSION},
        "capabilities": ["${ChatProtocol.CAPABILITY_CALL_TARGETING}", "future_feature"],
        "payload": "{\"senderId\":\"device-a\",\"displayName\":\"Phone A\",\"groupName\":\"Field Team\",\"futurePayload\":true}",
        "sentAt": 1234,
        "futureEnvelope": {"ignored": true}
      }
      """.trimIndent()

    val decoded = ChatProtocol.decode(json.encodeToByteArray())

    assertTrue(decoded is DecodedWireMessage.Hello)
    val hello = decoded as DecodedWireMessage.Hello
    assertEquals("Field Team", hello.groupName)
    assertEquals(setOf(ChatProtocol.CAPABILITY_CALL_TARGETING, "future_feature"), hello.capabilities)
    assertTrue(ChatProtocol.supportsCallTargeting(hello.capabilities))
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

  @Test
  fun encodeAndDecodeCallRequestPayloadPreservesCallMetadata() {
    val bytes =
      ChatProtocol.encodeCallRequest(
        callId = "call-1",
        senderId = "device-a",
        createdAt = 1234L,
        sentAt = 5678L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.CallRequest)
    val request = decoded as DecodedWireMessage.CallRequest
    assertEquals("call-1", request.callId)
    assertEquals("device-a", request.senderId)
    assertEquals(1234L, request.createdAt)
    assertEquals(5678L, request.sentAt)
  }

  @Test
  fun encodeAndDecodeCallResponsePayloadsPreserveCallIds() {
    val accept =
      ChatProtocol.decode(
        ChatProtocol.encodeCallAccept(
          callId = "call-1",
          senderId = "device-b",
          createdAt = 2000L,
          sentAt = 3000L,
        ),
      ) as DecodedWireMessage.CallAccept
    val reject =
      ChatProtocol.decode(
        ChatProtocol.encodeCallReject(
          callId = "call-2",
          senderId = "device-c",
          reason = "busy",
          createdAt = 4000L,
          sentAt = 5000L,
        ),
      ) as DecodedWireMessage.CallReject
    val end =
      ChatProtocol.decode(
        ChatProtocol.encodeCallEnd(
          callId = "call-3",
          senderId = "device-a",
          createdAt = 6000L,
          sentAt = 7000L,
        ),
      ) as DecodedWireMessage.CallEnd

    assertEquals("call-1", accept.callId)
    assertEquals("device-b", accept.senderId)
    assertEquals(2000L, accept.createdAt)
    assertEquals(3000L, accept.sentAt)

    assertEquals("call-2", reject.callId)
    assertEquals("device-c", reject.senderId)
    assertEquals("busy", reject.reason)
    assertEquals(4000L, reject.createdAt)
    assertEquals(5000L, reject.sentAt)

    assertEquals("call-3", end.callId)
    assertEquals("device-a", end.senderId)
    assertEquals(6000L, end.createdAt)
    assertEquals(7000L, end.sentAt)
  }

  @Test
  fun encodeAndDecodeCallVoicePayloadPreservesAudioMetadata() {
    val bytes =
      ChatProtocol.encodeCallVoice(
        callId = "call-1",
        clipId = "clip-1",
        senderId = "device-a",
        audioBase64 = "AQIDBA==",
        durationMs = 2300L,
        mimeType = "audio/3gpp",
        createdAt = 1234L,
        sentAt = 5678L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.CallVoice)
    val voice = decoded as DecodedWireMessage.CallVoice
    assertEquals("call-1", voice.callId)
    assertEquals("clip-1", voice.clipId)
    assertEquals("device-a", voice.senderId)
    assertEquals("AQIDBA==", voice.audioBase64)
    assertEquals(2300L, voice.durationMs)
    assertEquals("audio/3gpp", voice.mimeType)
    assertEquals(1234L, voice.createdAt)
    assertEquals(5678L, voice.sentAt)
  }

  @Test
  fun encodeAndDecodeBinaryCallAudioFramePreservesMetadata() {
    val bytes =
      ChatProtocol.encodeCallAudioFrame(
        callId = "call-1",
        frameId = "frame-7",
        senderId = "device-a",
        targetId = "device-b",
        audioBytes = byteArrayOf(1, 2, 3, 4, 5),
        durationMs = 20L,
        mimeType = "audio/amr-wb;rate=16000",
        sequenceNumber = 42,
        createdAt = 1234L,
        sentAt = 5678L,
      )

    val decoded = ChatProtocol.decode(bytes)

    assertTrue(decoded is DecodedWireMessage.CallAudioFrame)
    val frame = decoded as DecodedWireMessage.CallAudioFrame
    assertEquals("call-1", frame.callId)
    assertEquals("frame-7", frame.frameId)
    assertEquals("device-a", frame.senderId)
    assertEquals("device-b", frame.targetId)
    assertEquals(listOf(1, 2, 3, 4, 5), frame.audioBytes.map { it.toInt() })
    assertEquals(20L, frame.durationMs)
    assertEquals("audio/amr-wb;rate=16000", frame.mimeType)
    assertEquals(42, frame.sequenceNumber)
    assertEquals(1234L, frame.createdAt)
    assertEquals(5678L, frame.sentAt)
  }

  @Test
  fun binaryCallAudioFrameAvoidsJsonAndBase64Overhead() {
    val payload = ByteArray(80) { it.toByte() }
    val binary =
      ChatProtocol.encodeCallAudioFrame(
        callId = "call-1",
        frameId = "frame-1",
        senderId = "device-a",
        targetId = "device-b",
        audioBytes = payload,
        durationMs = 20L,
        mimeType = "audio/amr-wb;rate=16000",
        sequenceNumber = 1,
        createdAt = 1234L,
        sentAt = 5678L,
      )
    val legacyJson =
      ChatProtocol.encodeCallVoice(
        callId = "call-1",
        clipId = "frame-1",
        senderId = "device-a",
        targetId = "device-b",
        audioBase64 = java.util.Base64.getEncoder().encodeToString(payload),
        durationMs = 20L,
        mimeType = "audio/amr-wb;rate=16000",
        createdAt = 1234L,
        sentAt = 5678L,
      )

    assertTrue(binary.size < legacyJson.size)
    assertTrue(!binary.decodeToString().contains("audioBase64"))
  }
}
