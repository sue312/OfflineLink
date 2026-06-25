package com.example.offlinelink.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioAdaptiveEncodingTest {
  @Test
  fun policyUsesHighQualityWhenLossAndJitterAreLow() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 80,
        lostFrames = 0,
        lateFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
      )

    assertEquals(CallAudioEncodingProfile.HighQuality, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyStartsWithReliableSpeechUntilLinkHasEnoughSamples() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 0,
        lostFrames = 0,
        lateFrames = 0,
        averageInterArrivalMs = 0,
        maxInterArrivalMs = 0,
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenLossIsHigh() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 80,
        lostFrames = 12,
        lateFrames = 1,
        averageInterArrivalMs = 26,
        maxInterArrivalMs = 80,
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenJitterIsHigh() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 80,
        lostFrames = 1,
        lateFrames = 0,
        averageInterArrivalMs = 58,
        maxInterArrivalMs = 190,
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenPlaybackBufferIsBackedUp() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 120,
        lostFrames = 0,
        lateFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
        bufferedDurationMs = 280,
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenRemoteRssiIsWeak() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 120,
        lostFrames = 0,
        lateFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
        transmitStats = CallAudioTransmitStats(remoteRssi = -94),
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenTransmitPathIsCongested() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 120,
        lostFrames = 0,
        lateFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
        transmitStats =
          CallAudioTransmitStats(
            socketCongested = true,
            liveAudioPendingFrames = 3,
            writeQueueLength = 4,
            maxWriteBlockedMs = 190,
          ),
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun longRangeTransmitPolicyReducesCaptureFrameRate() {
    assertEquals(2, CallAudioTransmitPolicy.captureFrameInterval(CallAudioProcessingMode.LongRange, CallAudioTransmitStats()))
    assertEquals(
      3,
      CallAudioTransmitPolicy.captureFrameInterval(
        CallAudioProcessingMode.LongRange,
        CallAudioTransmitStats(socketCongested = true),
      ),
    )
  }

  @Test
  fun policyUsesRecentWindowSoOldJitterDoesNotKeepQualityLowForever() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 220,
        lostFrames = 6,
        lateFrames = 0,
        averageInterArrivalMs = 24,
        maxInterArrivalMs = 220,
        recentReceivedFrames = 80,
        recentLostFrames = 0,
        recentLateFrames = 0,
        recentAverageInterArrivalMs = 20,
        recentMaxInterArrivalMs = 32,
      )

    assertEquals(CallAudioEncodingProfile.HighQuality, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun policyUsesReliableSpeechWhenRecentWindowIsBadEvenIfAggregateLooksGood() {
    val stats =
      CallAudioLinkStats(
        receivedFrames = 400,
        lostFrames = 4,
        lateFrames = 0,
        averageInterArrivalMs = 21,
        maxInterArrivalMs = 60,
        recentReceivedFrames = 60,
        recentLostFrames = 8,
        recentLateFrames = 0,
        recentAverageInterArrivalMs = 24,
        recentMaxInterArrivalMs = 72,
      )

    assertEquals(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingPolicy.recommend(stats))
  }

  @Test
  fun adaptiveEncoderSwitchesProfilesFromLinkStats() {
    var stats =
      CallAudioLinkStats(
        receivedFrames = 80,
        lostFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
      )
    val createdProfiles = mutableListOf<CallAudioEncodingProfile>()
    val encoder =
      AdaptiveCallAudioEncoder(
        linkStatsProvider = { stats },
        encoderFactory = { profile ->
          createdProfiles += profile
          TaggedEncoder(profile)
        },
      )

    val highQuality = encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()!!
    stats = stats.copy(lostFrames = 16, maxInterArrivalMs = 160)
    val reliable = encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()!!

    assertEquals("test/highquality", highQuality.mimeType)
    assertEquals("test/reliablespeech", reliable.mimeType)
    assertEquals(listOf(CallAudioEncodingProfile.HighQuality, CallAudioEncodingProfile.ReliableSpeech), createdProfiles)
  }

  @Test
  fun adaptiveEncoderRequiresSustainedGoodLinkBeforeReturningToHighQuality() {
    var stats =
      CallAudioLinkStats(
        receivedFrames = 80,
        lostFrames = 12,
        averageInterArrivalMs = 26,
        maxInterArrivalMs = 80,
      )
    val createdProfiles = mutableListOf<CallAudioEncodingProfile>()
    val encoder =
      AdaptiveCallAudioEncoder(
        linkStatsProvider = { stats },
        encoderFactory = { profile ->
          createdProfiles += profile
          TaggedEncoder(profile)
        },
      )

    val reliable = encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()!!
    stats =
      CallAudioLinkStats(
        receivedFrames = 120,
        lostFrames = 0,
        averageInterArrivalMs = 20,
        maxInterArrivalMs = 34,
      )
    val firstGoodSample = encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()!!
    repeat(4) {
      encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()
    }
    val sustainedGood = encoder.encode(ByteArray(encoder.inputFrameBytes)).getOrThrow()!!

    assertEquals("test/reliablespeech", reliable.mimeType)
    assertEquals("test/reliablespeech", firstGoodSample.mimeType)
    assertEquals("test/highquality", sustainedGood.mimeType)
    assertEquals(listOf(CallAudioEncodingProfile.ReliableSpeech, CallAudioEncodingProfile.HighQuality), createdProfiles)
  }

  private class TaggedEncoder(
    private val profile: CallAudioEncodingProfile,
  ) : CallAudioEncoder {
    override val inputSampleRateHz: Int = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ
    override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

    override fun encode(pcmBytes: ByteArray, length: Int): Result<CallAudioFrame?> =
      Result.success(
        CallAudioFrame(
          bytes = byteArrayOf(profile.ordinal.toByte()),
          mimeType = "test/${profile.name.lowercase()}",
        ),
      )
  }
}
