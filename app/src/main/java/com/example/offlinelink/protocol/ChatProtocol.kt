package com.example.offlinelink.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface DecodedWireMessage {
  val sentAt: Long

  data class Hello(
    val senderId: String,
    val displayName: String,
    val groupName: String,
    override val sentAt: Long,
    val members: List<WireMember> = emptyList(),
  ) : DecodedWireMessage

  data class Message(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class VoiceMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val audioBase64: String,
    val durationMs: Long,
    val mimeType: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class Ack(val messageId: String, override val sentAt: Long) : DecodedWireMessage

  data class Disconnect(val reason: String, override val sentAt: Long) : DecodedWireMessage

  data class ImageMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val imageBase64: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class LocationMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class CallRequest(
    val callId: String,
    val senderId: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class CallAccept(
    val callId: String,
    val senderId: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class CallReject(
    val callId: String,
    val senderId: String,
    val reason: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class CallEnd(
    val callId: String,
    val senderId: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage

  data class CallVoice(
    val callId: String,
    val clipId: String,
    val senderId: String,
    val audioBase64: String,
    val durationMs: Long,
    val mimeType: String,
    val createdAt: Long,
    override val sentAt: Long,
  ) : DecodedWireMessage
}

data class WireMember(
  val id: String,
  val displayName: String,
)

object ChatProtocol {
  private const val PROTOCOL_VERSION = 1
  private const val TYPE_HELLO = "hello"
  private const val TYPE_MESSAGE = "message"
  private const val TYPE_VOICE_MESSAGE = "voice_message"
  private const val TYPE_ACK = "ack"
  private const val TYPE_DISCONNECT = "disconnect"
  private const val TYPE_LOCATION = "location"
  private const val TYPE_IMAGE = "image"
  private const val TYPE_CALL_REQUEST = "call_request"
  private const val TYPE_CALL_ACCEPT = "call_accept"
  private const val TYPE_CALL_REJECT = "call_reject"
  private const val TYPE_CALL_END = "call_end"
  private const val TYPE_CALL_VOICE = "call_voice"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun encodeHello(
    senderId: String,
    displayName: String,
    groupName: String = "Offline group",
    members: List<WireMember> = emptyList(),
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_HELLO, HelloPayload(senderId, displayName, groupName, members.map { MemberPayload(it.id, it.displayName) }), sentAt)

  fun encodeMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    text: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_MESSAGE, MessagePayload(messageId, conversationId, senderId, text, createdAt), sentAt)

  fun encodeVoiceMessage(
    messageId: String,
    conversationId: String,
    senderId: String,
    audioBase64: String,
    durationMs: Long,
    mimeType: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_VOICE_MESSAGE, VoiceMessagePayload(messageId, conversationId, senderId, audioBase64, durationMs, mimeType, createdAt), sentAt)

  fun encodeAck(messageId: String, sentAt: Long = System.currentTimeMillis()): ByteArray =
    encodeEnvelope(TYPE_ACK, AckPayload(messageId), sentAt)

  fun encodeDisconnect(reason: String, sentAt: Long = System.currentTimeMillis()): ByteArray =
    encodeEnvelope(TYPE_DISCONNECT, DisconnectPayload(reason), sentAt)

  fun encodeImage(
    messageId: String,
    conversationId: String,
    senderId: String,
    imageBase64: String,
    mimeType: String,
    width: Int,
    height: Int,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_IMAGE, ImagePayload(messageId, conversationId, senderId, imageBase64, mimeType, width, height, createdAt), sentAt)

  fun encodeLocation(
    messageId: String,
    conversationId: String,
    senderId: String,
    latitude: Double,
    longitude: Double,
    accuracy: Float?,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_LOCATION, LocationPayload(messageId, conversationId, senderId, latitude, longitude, accuracy, createdAt), sentAt)

  fun encodeCallRequest(
    callId: String,
    senderId: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_CALL_REQUEST, CallPayload(callId, senderId, createdAt), sentAt)

  fun encodeCallAccept(
    callId: String,
    senderId: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_CALL_ACCEPT, CallPayload(callId, senderId, createdAt), sentAt)

  fun encodeCallReject(
    callId: String,
    senderId: String,
    reason: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_CALL_REJECT, CallRejectPayload(callId, senderId, reason, createdAt), sentAt)

  fun encodeCallEnd(
    callId: String,
    senderId: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_CALL_END, CallPayload(callId, senderId, createdAt), sentAt)

  fun encodeCallVoice(
    callId: String,
    clipId: String,
    senderId: String,
    audioBase64: String,
    durationMs: Long,
    mimeType: String,
    createdAt: Long,
    sentAt: Long = System.currentTimeMillis(),
  ): ByteArray =
    encodeEnvelope(TYPE_CALL_VOICE, CallVoicePayload(callId, clipId, senderId, audioBase64, durationMs, mimeType, createdAt), sentAt)

  fun decode(bytes: ByteArray): DecodedWireMessage {
    val envelope = json.decodeFromString(WireEnvelope.serializer(), bytes.decodeToString())
    require(envelope.protocolVersion == PROTOCOL_VERSION) {
      "Unsupported protocol version ${envelope.protocolVersion}"
    }
    return when (envelope.type) {
      TYPE_HELLO -> {
        val payload = json.decodeFromString(HelloPayload.serializer(), envelope.payload)
        DecodedWireMessage.Hello(
          payload.senderId,
          payload.displayName,
          payload.groupName,
          envelope.sentAt,
          payload.members.map { WireMember(it.id, it.displayName) },
        )
      }
      TYPE_MESSAGE -> {
        val payload = json.decodeFromString(MessagePayload.serializer(), envelope.payload)
        DecodedWireMessage.Message(
          messageId = payload.messageId,
          conversationId = payload.conversationId,
          senderId = payload.senderId,
          text = payload.text,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_VOICE_MESSAGE -> {
        val payload = json.decodeFromString(VoiceMessagePayload.serializer(), envelope.payload)
        DecodedWireMessage.VoiceMessage(
          messageId = payload.messageId,
          conversationId = payload.conversationId,
          senderId = payload.senderId,
          audioBase64 = payload.audioBase64,
          durationMs = payload.durationMs,
          mimeType = payload.mimeType,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_ACK -> {
        val payload = json.decodeFromString(AckPayload.serializer(), envelope.payload)
        DecodedWireMessage.Ack(payload.messageId, envelope.sentAt)
      }
      TYPE_DISCONNECT -> {
        val payload = json.decodeFromString(DisconnectPayload.serializer(), envelope.payload)
        DecodedWireMessage.Disconnect(payload.reason, envelope.sentAt)
      }
      TYPE_IMAGE -> {
        val payload = json.decodeFromString(ImagePayload.serializer(), envelope.payload)
        DecodedWireMessage.ImageMessage(
          messageId = payload.messageId,
          conversationId = payload.conversationId,
          senderId = payload.senderId,
          imageBase64 = payload.imageBase64,
          mimeType = payload.mimeType,
          width = payload.width,
          height = payload.height,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_LOCATION -> {
        val payload = json.decodeFromString(LocationPayload.serializer(), envelope.payload)
        DecodedWireMessage.LocationMessage(
          messageId = payload.messageId,
          conversationId = payload.conversationId,
          senderId = payload.senderId,
          latitude = payload.latitude,
          longitude = payload.longitude,
          accuracy = payload.accuracy,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_CALL_REQUEST -> {
        val payload = json.decodeFromString(CallPayload.serializer(), envelope.payload)
        DecodedWireMessage.CallRequest(
          callId = payload.callId,
          senderId = payload.senderId,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_CALL_ACCEPT -> {
        val payload = json.decodeFromString(CallPayload.serializer(), envelope.payload)
        DecodedWireMessage.CallAccept(
          callId = payload.callId,
          senderId = payload.senderId,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_CALL_REJECT -> {
        val payload = json.decodeFromString(CallRejectPayload.serializer(), envelope.payload)
        DecodedWireMessage.CallReject(
          callId = payload.callId,
          senderId = payload.senderId,
          reason = payload.reason,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_CALL_END -> {
        val payload = json.decodeFromString(CallPayload.serializer(), envelope.payload)
        DecodedWireMessage.CallEnd(
          callId = payload.callId,
          senderId = payload.senderId,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      TYPE_CALL_VOICE -> {
        val payload = json.decodeFromString(CallVoicePayload.serializer(), envelope.payload)
        DecodedWireMessage.CallVoice(
          callId = payload.callId,
          clipId = payload.clipId,
          senderId = payload.senderId,
          audioBase64 = payload.audioBase64,
          durationMs = payload.durationMs,
          mimeType = payload.mimeType,
          createdAt = payload.createdAt,
          sentAt = envelope.sentAt,
        )
      }
      else -> error("Unknown wire message type ${envelope.type}")
    }
  }

  private inline fun <reified T> encodeEnvelope(type: String, payload: T, sentAt: Long): ByteArray {
    val envelope =
      WireEnvelope(
        type = type,
        protocolVersion = PROTOCOL_VERSION,
        payload = json.encodeToString(payload),
        sentAt = sentAt,
      )
    return json.encodeToString(WireEnvelope.serializer(), envelope).encodeToByteArray()
  }
}

@Serializable
private data class WireEnvelope(
  val type: String,
  val protocolVersion: Int,
  val payload: String,
  val sentAt: Long,
)

@Serializable
private data class HelloPayload(
  val senderId: String,
  val displayName: String,
  val groupName: String = "Offline group",
  val members: List<MemberPayload> = emptyList(),
)

@Serializable
private data class MemberPayload(
  val id: String,
  val displayName: String,
)

@Serializable
private data class MessagePayload(
  val messageId: String,
  val conversationId: String,
  val senderId: String,
  val text: String,
  val createdAt: Long,
)

@Serializable
private data class VoiceMessagePayload(
  val messageId: String,
  val conversationId: String,
  val senderId: String,
  val audioBase64: String,
  val durationMs: Long,
  val mimeType: String,
  val createdAt: Long,
)

@Serializable
private data class AckPayload(val messageId: String)

@Serializable
private data class DisconnectPayload(val reason: String)

@Serializable
private data class ImagePayload(
  val messageId: String,
  val conversationId: String,
  val senderId: String,
  val imageBase64: String,
  val mimeType: String,
  val width: Int,
  val height: Int,
  val createdAt: Long,
)

@Serializable
private data class LocationPayload(
  val messageId: String,
  val conversationId: String,
  val senderId: String,
  val latitude: Double,
  val longitude: Double,
  val accuracy: Float? = null,
  val createdAt: Long,
)

@Serializable
private data class CallPayload(
  val callId: String,
  val senderId: String,
  val createdAt: Long,
)

@Serializable
private data class CallRejectPayload(
  val callId: String,
  val senderId: String,
  val reason: String,
  val createdAt: Long,
)

@Serializable
private data class CallVoicePayload(
  val callId: String,
  val clipId: String,
  val senderId: String,
  val audioBase64: String,
  val durationMs: Long,
  val mimeType: String,
  val createdAt: Long,
)
