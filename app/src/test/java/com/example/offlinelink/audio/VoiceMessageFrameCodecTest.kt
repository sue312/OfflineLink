package com.example.offlinelink.audio

import com.example.offlinelink.protocol.CallAudioPacket
import com.example.offlinelink.protocol.CallAudioPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageFrameCodecTest {
  @Test
  fun encodesVoiceMessageAsOrderedOpusSmallFrames() {
    val frames =
      listOf(
        CallAudioFrame(bytes = byteArrayOf(1, 2, 3), durationMs = 20, mimeType = CALL_AUDIO_OPUS_MIME_TYPE),
        CallAudioFrame(bytes = byteArrayOf(4, 5), durationMs = 20, mimeType = CALL_AUDIO_OPUS_MIME_TYPE),
      )

    val encoded = VoiceMessageFrameCodec.encode(frames)
    val decoded = VoiceMessageFrameCodec.decode(encoded)

    assertEquals(2, decoded.size)
    assertArrayEquals(byteArrayOf(1, 2, 3), decoded[0].bytes)
    assertArrayEquals(byteArrayOf(4, 5), decoded[1].bytes)
    assertEquals(listOf(0, 1), decoded.map { it.sequenceNumber })
    assertEquals(listOf(CALL_AUDIO_OPUS_MIME_TYPE, CALL_AUDIO_OPUS_MIME_TYPE), decoded.map { it.mimeType })
  }

  @Test
  fun encodesVoiceMessageFramesWithSharedBundleHeader() {
    val frames =
      List(3) { frameIndex ->
        CallAudioFrame(
          bytes = ByteArray(16) { (frameIndex * 16 + it).toByte() },
          durationMs = 20,
          mimeType = CALL_AUDIO_OPUS_MIME_TYPE,
        )
      }
    val legacy =
      CallAudioPacketCodec.encode(
        CallAudioPacket(
          callId = "",
          frameId = "",
          senderId = "",
          targetId = null,
          audioBytes = frames.last().bytes,
          durationMs = frames.last().durationMs,
          mimeType = frames.last().mimeType,
          sequenceNumber = frames.lastIndex,
          createdAt = 0L,
          sentAt = 0L,
        ),
        compact = true,
        redundantPreviousPackets =
          frames.dropLast(1).mapIndexed { index, frame ->
            CallAudioPacket(
              callId = "",
              frameId = "",
              senderId = "",
              targetId = null,
              audioBytes = frame.bytes,
              durationMs = frame.durationMs,
              mimeType = frame.mimeType,
              sequenceNumber = index,
              createdAt = 0L,
              sentAt = 0L,
            )
          },
      )

    val encoded = VoiceMessageFrameCodec.encode(frames)
    val decoded = VoiceMessageFrameCodec.decode(encoded)

    assertEquals(61, encoded.size)
    assertTrue(encoded.size < legacy.size)
    assertEquals(frames.size, decoded.size)
    decoded.forEachIndexed { index, frame ->
      assertArrayEquals(frames[index].bytes, frame.bytes)
      assertEquals(20L, frame.durationMs)
      assertEquals(CALL_AUDIO_OPUS_MIME_TYPE, frame.mimeType)
      assertEquals(index, frame.sequenceNumber)
    }
  }

  @Test
  fun rejectsNonOpusVoiceMessageFrames() {
    val frames = listOf(CallAudioFrame(bytes = byteArrayOf(1), durationMs = 20, mimeType = CALL_AUDIO_MIME_TYPE))

    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      VoiceMessageFrameCodec.encode(frames)
    }
  }
}
