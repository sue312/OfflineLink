package com.example.offlinelink.audio

import android.content.Context

enum class CallAudioEncodingProfile {
  HighQuality,
  ReliableSpeech,
}

object CallAudioEncodingPolicy {
  fun recommend(stats: CallAudioLinkStats): CallAudioEncodingProfile {
    if (CallAudioTransmitPolicy.shouldPreferReliableEncoding(stats.transmitStats)) {
      return CallAudioEncodingProfile.ReliableSpeech
    }
    val qualityStats = stats.qualityWindow()
    val totalFrames = qualityStats.receivedFrames + qualityStats.lostFrames
    if (totalFrames < MIN_HIGH_QUALITY_SAMPLE_FRAMES) return CallAudioEncodingProfile.ReliableSpeech
    val lossRate =
      if (totalFrames == 0L) {
        0.0
      } else {
        qualityStats.lostFrames.toDouble() / totalFrames.toDouble()
      }
    val lateRate =
      if (qualityStats.receivedFrames == 0L) {
        0.0
      } else {
        qualityStats.lateFrames.toDouble() / qualityStats.receivedFrames.toDouble()
      }

    return if (
      lossRate >= MAX_HIGH_QUALITY_LOSS_RATE ||
        lateRate >= MAX_HIGH_QUALITY_LATE_RATE ||
        qualityStats.maxGapFrames >= MAX_HIGH_QUALITY_GAP_FRAMES ||
        qualityStats.averageInterArrivalMs >= MAX_HIGH_QUALITY_AVERAGE_INTER_ARRIVAL_MS ||
        qualityStats.maxInterArrivalMs >= MAX_HIGH_QUALITY_INTER_ARRIVAL_MS ||
        stats.bufferedDurationMs >= MAX_HIGH_QUALITY_BUFFERED_DURATION_MS
    ) {
      CallAudioEncodingProfile.ReliableSpeech
    } else {
      CallAudioEncodingProfile.HighQuality
    }
  }

  private fun CallAudioLinkStats.qualityWindow(): QualityWindow {
    val recentTotalFrames = recentReceivedFrames + recentLostFrames
    if (recentTotalFrames >= MIN_HIGH_QUALITY_SAMPLE_FRAMES) {
      return QualityWindow(
        receivedFrames = recentReceivedFrames,
        lostFrames = recentLostFrames,
        lateFrames = recentLateFrames,
        maxGapFrames = recentMaxGapFrames,
        averageInterArrivalMs = recentAverageInterArrivalMs,
        maxInterArrivalMs = recentMaxInterArrivalMs,
      )
    }
    return QualityWindow(
      receivedFrames = receivedFrames,
      lostFrames = lostFrames,
      lateFrames = lateFrames,
      maxGapFrames = maxGapFrames,
      averageInterArrivalMs = averageInterArrivalMs,
      maxInterArrivalMs = maxInterArrivalMs,
    )
  }

  private const val MAX_HIGH_QUALITY_LOSS_RATE = 0.08
  private const val MAX_HIGH_QUALITY_LATE_RATE = 0.06
  private const val MAX_HIGH_QUALITY_GAP_FRAMES = 4
  private const val MAX_HIGH_QUALITY_AVERAGE_INTER_ARRIVAL_MS = 45L
  private const val MAX_HIGH_QUALITY_INTER_ARRIVAL_MS = 140L
  private const val MAX_HIGH_QUALITY_BUFFERED_DURATION_MS = 240L
  private const val MIN_HIGH_QUALITY_SAMPLE_FRAMES = 50L

  private data class QualityWindow(
    val receivedFrames: Long,
    val lostFrames: Long,
    val lateFrames: Long,
    val maxGapFrames: Int,
    val averageInterArrivalMs: Long,
    val maxInterArrivalMs: Long,
  )
}

class AdaptiveCallAudioEncoder(
  private val linkStatsProvider: () -> CallAudioLinkStats,
  private val context: Context? = null,
  private val encoderFactory: (CallAudioEncodingProfile) -> CallAudioEncoder? = { profile ->
    createProfileEncoder(context, profile)
  },
) : CallAudioEncoder {
  override val inputSampleRateHz: Int = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ
  override val inputFrameBytes: Int = callAudioPcmFrameBytes(inputSampleRateHz)

  private val profilePolicy = StableCallAudioEncodingPolicy()
  private var activeProfile: CallAudioEncodingProfile? = null
  private var activeEncoder: CallAudioEncoder? = null

  override fun encode(
    pcmBytes: ByteArray,
    length: Int,
  ): Result<CallAudioFrame?> =
    runCatching {
      val profile = profilePolicy.recommend(linkStatsProvider())
      val encoder = encoderFor(profile)
      encoder.encode(pcmBytes, length).getOrThrow()
    }

  override fun close() {
    activeEncoder?.close()
    activeEncoder = null
    activeProfile = null
  }

  private fun encoderFor(profile: CallAudioEncodingProfile): CallAudioEncoder {
    val existing = activeEncoder
    if (existing != null && activeProfile == profile) return existing

    existing?.close()
    return (encoderFactory(profile) ?: reliablePcmFallback()).also { encoder ->
      activeProfile = profile
      activeEncoder = encoder
    }
  }

  private companion object {
    fun createProfileEncoder(
      context: Context?,
      profile: CallAudioEncodingProfile,
    ): CallAudioEncoder? =
      when (profile) {
        CallAudioEncodingProfile.HighQuality ->
          MediaCodecOpusCallAudioEncoder.createOrNull(CALL_AUDIO_OPUS_HIGH_QUALITY_BITRATE_BPS)
            ?: MediaCodecAmrWbCallAudioEncoder.createOrNull()
            ?: LyraCallAudioEncoder.createOrNull(context)
            ?: reliablePcmFallback()
        CallAudioEncodingProfile.ReliableSpeech ->
          LyraCallAudioEncoder.createOrNull(context)
            ?: MediaCodecOpusCallAudioEncoder.createOrNull(CALL_AUDIO_OPUS_RELIABLE_SPEECH_BITRATE_BPS)
            ?: MediaCodecAmrWbCallAudioEncoder.createOrNull()
            ?: reliablePcmFallback()
      }

    fun reliablePcmFallback(): CallAudioEncoder =
      PcmCallAudioEncoder(
        sampleRateHz = CALL_AUDIO_AMR_WB_SAMPLE_RATE_HZ,
        outputSampleRateHz = CALL_AUDIO_SAMPLE_RATE_HZ,
      )

    const val CALL_AUDIO_OPUS_HIGH_QUALITY_BITRATE_BPS = 32_000
    const val CALL_AUDIO_OPUS_RELIABLE_SPEECH_BITRATE_BPS = 9_200
  }
}

private class StableCallAudioEncodingPolicy(
  private val goodSamplesBeforeUpgrade: Int = GOOD_SAMPLES_BEFORE_UPGRADE,
) {
  private var activeProfile: CallAudioEncodingProfile? = null
  private var consecutiveGoodSamples = 0

  fun recommend(stats: CallAudioLinkStats): CallAudioEncodingProfile {
    val targetProfile = CallAudioEncodingPolicy.recommend(stats)
    val currentProfile = activeProfile
    if (currentProfile == null) {
      activeProfile = targetProfile
      consecutiveGoodSamples = if (targetProfile == CallAudioEncodingProfile.HighQuality) goodSamplesBeforeUpgrade else 0
      return targetProfile
    }

    if (targetProfile == CallAudioEncodingProfile.ReliableSpeech) {
      consecutiveGoodSamples = 0
      activeProfile = CallAudioEncodingProfile.ReliableSpeech
      return CallAudioEncodingProfile.ReliableSpeech
    }

    if (currentProfile == CallAudioEncodingProfile.ReliableSpeech) {
      consecutiveGoodSamples++
      if (consecutiveGoodSamples < goodSamplesBeforeUpgrade) {
        return CallAudioEncodingProfile.ReliableSpeech
      }
    }

    activeProfile = CallAudioEncodingProfile.HighQuality
    consecutiveGoodSamples = goodSamplesBeforeUpgrade
    return CallAudioEncodingProfile.HighQuality
  }

  private companion object {
    const val GOOD_SAMPLES_BEFORE_UPGRADE = 5
  }
}
