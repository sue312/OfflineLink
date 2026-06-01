package com.example.offlinelink.audio

import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.pow

data class CallAudioLinkStats(
  val receivedFrames: Long = 0,
  val lostFrames: Long = 0,
  val concealedFrames: Long = 0,
  val lateFrames: Long = 0,
  val maxGapFrames: Int = 0,
  val averageInterArrivalMs: Long = 0,
  val maxInterArrivalMs: Long = 0,
  val bufferedDurationMs: Long = 0,
  val lastSequenceNumber: Int? = null,
  val recentReceivedFrames: Long = 0,
  val recentLostFrames: Long = 0,
  val recentConcealedFrames: Long = 0,
  val recentLateFrames: Long = 0,
  val recentMaxGapFrames: Int = 0,
  val recentAverageInterArrivalMs: Long = 0,
  val recentMaxInterArrivalMs: Long = 0,
)

class CallAudioLinkMonitor(
  private val maxConcealedGapFrames: Int = DEFAULT_MAX_CONCEALED_GAP_FRAMES,
  private val recentWindowFrames: Int = DEFAULT_RECENT_WINDOW_FRAMES,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  private var expectedSequenceNumber: Int? = null
  private var lastFrame: PcmAudioFrame? = null
  private var lastArrivalMs: Long? = null
  private var receivedFrames = 0L
  private var lostFrames = 0L
  private var concealedFrames = 0L
  private var lateFrames = 0L
  private var maxGapFrames = 0
  private var interArrivalTotalMs = 0L
  private var interArrivalSamples = 0L
  private var maxInterArrivalMs = 0L
  private var bufferedDurationMs = 0L
  private var lastSequenceNumber: Int? = null
  private val recentSamples = ArrayDeque<RecentLinkSample>()

  init {
    require(maxConcealedGapFrames >= 0) { "maxConcealedGapFrames must not be negative" }
    require(recentWindowFrames >= 1) { "recentWindowFrames must be at least 1" }
  }

  @Synchronized
  fun shouldDecode(sequenceNumber: Int?): Boolean {
    val expected = expectedSequenceNumber ?: return true
    if (sequenceNumber == null || sequenceNumber >= expected) return true
    lateFrames++
    recordRecentSample(lateFrames = 1)
    return false
  }

  @Synchronized
  fun process(
    frame: PcmAudioFrame,
    sequenceNumber: Int?,
  ): List<PcmAudioFrame> {
    val interArrivalMs = recordArrival()
    if (sequenceNumber == null) {
      receivedFrames++
      lastFrame = frame
      recordRecentSample(receivedFrames = 1, interArrivalMs = interArrivalMs)
      return listOf(frame)
    }

    val expected = expectedSequenceNumber
    if (expected != null && sequenceNumber < expected) {
      lateFrames++
      recordRecentSample(lateFrames = 1, interArrivalMs = interArrivalMs)
      return emptyList()
    }

    receivedFrames++
    val output = mutableListOf<PcmAudioFrame>()
    var lostInFrame = 0
    var concealedInFrame = 0
    if (expected != null && sequenceNumber > expected) {
      val gap = sequenceNumber - expected
      lostInFrame = gap
      lostFrames += gap.toLong()
      maxGapFrames = maxOf(maxGapFrames, gap)
      val concealed = min(gap, maxConcealedGapFrames)
      concealedInFrame = concealed
      repeat(concealed) { index ->
        output += concealmentFrame(reference = frame, distance = index + 1)
      }
      concealedFrames += concealed.toLong()
    }
    output += frame
    expectedSequenceNumber = sequenceNumber + 1
    lastSequenceNumber = sequenceNumber
    lastFrame = frame
    recordRecentSample(
      receivedFrames = 1,
      lostFrames = lostInFrame,
      concealedFrames = concealedInFrame,
      gapFrames = lostInFrame,
      interArrivalMs = interArrivalMs,
    )
    return output
  }

  @Synchronized
  fun recordBufferedDuration(durationMs: Long) {
    bufferedDurationMs = durationMs.coerceAtLeast(0L)
  }

  @Synchronized
  fun snapshot(): CallAudioLinkStats {
    val recent = recentSnapshot()
    return CallAudioLinkStats(
      receivedFrames = receivedFrames,
      lostFrames = lostFrames,
      concealedFrames = concealedFrames,
      lateFrames = lateFrames,
      maxGapFrames = maxGapFrames,
      averageInterArrivalMs = if (interArrivalSamples == 0L) 0L else interArrivalTotalMs / interArrivalSamples,
      maxInterArrivalMs = maxInterArrivalMs,
      bufferedDurationMs = bufferedDurationMs,
      lastSequenceNumber = lastSequenceNumber,
      recentReceivedFrames = recent.receivedFrames,
      recentLostFrames = recent.lostFrames,
      recentConcealedFrames = recent.concealedFrames,
      recentLateFrames = recent.lateFrames,
      recentMaxGapFrames = recent.maxGapFrames,
      recentAverageInterArrivalMs = recent.averageInterArrivalMs,
      recentMaxInterArrivalMs = recent.maxInterArrivalMs,
    )
  }

  @Synchronized
  fun reset() {
    expectedSequenceNumber = null
    lastFrame = null
    lastArrivalMs = null
    receivedFrames = 0L
    lostFrames = 0L
    concealedFrames = 0L
    lateFrames = 0L
    maxGapFrames = 0
    interArrivalTotalMs = 0L
    interArrivalSamples = 0L
    maxInterArrivalMs = 0L
    bufferedDurationMs = 0L
    lastSequenceNumber = null
    recentSamples.clear()
  }

  private fun recordArrival(): Long? {
    val now = clockMs()
    val interArrivalMs =
      lastArrivalMs?.let { previous ->
      val delta = (now - previous).coerceAtLeast(0L)
      interArrivalTotalMs += delta
      interArrivalSamples++
      maxInterArrivalMs = maxOf(maxInterArrivalMs, delta)
      delta
    }
    lastArrivalMs = now
    return interArrivalMs
  }

  private fun recordRecentSample(
    receivedFrames: Int = 0,
    lostFrames: Int = 0,
    concealedFrames: Int = 0,
    lateFrames: Int = 0,
    gapFrames: Int = 0,
    interArrivalMs: Long? = null,
  ) {
    recentSamples +=
      RecentLinkSample(
        receivedFrames = receivedFrames,
        lostFrames = lostFrames,
        concealedFrames = concealedFrames,
        lateFrames = lateFrames,
        gapFrames = gapFrames,
        interArrivalMs = interArrivalMs,
      )
    while (recentSamples.size > recentWindowFrames) {
      recentSamples.removeFirst()
    }
  }

  private fun recentSnapshot(): RecentLinkStats {
    var received = 0L
    var lost = 0L
    var concealed = 0L
    var late = 0L
    var maxGap = 0
    var interArrivalTotal = 0L
    var interArrivalCount = 0L
    var maxInterArrival = 0L
    recentSamples.forEach { sample ->
      received += sample.receivedFrames.toLong()
      lost += sample.lostFrames.toLong()
      concealed += sample.concealedFrames.toLong()
      late += sample.lateFrames.toLong()
      maxGap = maxOf(maxGap, sample.gapFrames)
      sample.interArrivalMs?.let { interArrival ->
        interArrivalTotal += interArrival
        interArrivalCount++
        maxInterArrival = maxOf(maxInterArrival, interArrival)
      }
    }
    return RecentLinkStats(
      receivedFrames = received,
      lostFrames = lost,
      concealedFrames = concealed,
      lateFrames = late,
      maxGapFrames = maxGap,
      averageInterArrivalMs = if (interArrivalCount == 0L) 0L else interArrivalTotal / interArrivalCount,
      maxInterArrivalMs = maxInterArrival,
    )
  }

  private fun concealmentFrame(
    reference: PcmAudioFrame,
    distance: Int,
  ): PcmAudioFrame {
    val source = lastFrame ?: return PcmAudioFrame(ByteArray(reference.bytes.size), reference.sampleRateHz)
    if (source.sampleRateHz != reference.sampleRateHz || source.bytes.size != reference.bytes.size) {
      return PcmAudioFrame(ByteArray(reference.bytes.size), reference.sampleRateHz)
    }
    return PcmAudioFrame(
      bytes = attenuatePcm16(source.bytes, CONCEALMENT_GAIN.pow(distance.toDouble())),
      sampleRateHz = source.sampleRateHz,
    )
  }

  private fun attenuatePcm16(
    bytes: ByteArray,
    gain: Double,
  ): ByteArray {
    val output = bytes.copyOf()
    var index = 0
    while (index + 1 < output.size) {
      val lo = output[index].toInt() and 0xff
      val hi = output[index + 1].toInt()
      val sample = ((hi shl 8) or lo).toShort().toInt()
      val attenuated = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      output[index] = (attenuated and 0xff).toByte()
      output[index + 1] = ((attenuated shr 8) and 0xff).toByte()
      index += CALL_AUDIO_BYTES_PER_SAMPLE
    }
    return output
  }

  private companion object {
    const val DEFAULT_MAX_CONCEALED_GAP_FRAMES = 3
    const val DEFAULT_RECENT_WINDOW_FRAMES = 96
    const val CONCEALMENT_GAIN = 0.78
  }

  private data class RecentLinkSample(
    val receivedFrames: Int,
    val lostFrames: Int,
    val concealedFrames: Int,
    val lateFrames: Int,
    val gapFrames: Int,
    val interArrivalMs: Long?,
  )

  private data class RecentLinkStats(
    val receivedFrames: Long,
    val lostFrames: Long,
    val concealedFrames: Long,
    val lateFrames: Long,
    val maxGapFrames: Int,
    val averageInterArrivalMs: Long,
    val maxInterArrivalMs: Long,
  )
}
