package com.example.offlinelink.audio

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
)

class CallAudioLinkMonitor(
  private val maxConcealedGapFrames: Int = DEFAULT_MAX_CONCEALED_GAP_FRAMES,
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

  init {
    require(maxConcealedGapFrames >= 0) { "maxConcealedGapFrames must not be negative" }
  }

  @Synchronized
  fun process(
    frame: PcmAudioFrame,
    sequenceNumber: Int?,
  ): List<PcmAudioFrame> {
    recordArrival()
    if (sequenceNumber == null) {
      receivedFrames++
      lastFrame = frame
      return listOf(frame)
    }

    val expected = expectedSequenceNumber
    if (expected != null && sequenceNumber < expected) {
      lateFrames++
      return emptyList()
    }

    receivedFrames++
    val output = mutableListOf<PcmAudioFrame>()
    if (expected != null && sequenceNumber > expected) {
      val gap = sequenceNumber - expected
      lostFrames += gap.toLong()
      maxGapFrames = maxOf(maxGapFrames, gap)
      val concealed = min(gap, maxConcealedGapFrames)
      repeat(concealed) { index ->
        output += concealmentFrame(reference = frame, distance = index + 1)
      }
      concealedFrames += concealed.toLong()
    }
    output += frame
    expectedSequenceNumber = sequenceNumber + 1
    lastSequenceNumber = sequenceNumber
    lastFrame = frame
    return output
  }

  @Synchronized
  fun recordBufferedDuration(durationMs: Long) {
    bufferedDurationMs = durationMs.coerceAtLeast(0L)
  }

  @Synchronized
  fun snapshot(): CallAudioLinkStats =
    CallAudioLinkStats(
      receivedFrames = receivedFrames,
      lostFrames = lostFrames,
      concealedFrames = concealedFrames,
      lateFrames = lateFrames,
      maxGapFrames = maxGapFrames,
      averageInterArrivalMs = if (interArrivalSamples == 0L) 0L else interArrivalTotalMs / interArrivalSamples,
      maxInterArrivalMs = maxInterArrivalMs,
      bufferedDurationMs = bufferedDurationMs,
      lastSequenceNumber = lastSequenceNumber,
    )

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
  }

  private fun recordArrival() {
    val now = clockMs()
    lastArrivalMs?.let { previous ->
      val delta = (now - previous).coerceAtLeast(0L)
      interArrivalTotalMs += delta
      interArrivalSamples++
      maxInterArrivalMs = maxOf(maxInterArrivalMs, delta)
    }
    lastArrivalMs = now
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
    const val CONCEALMENT_GAIN = 0.78
  }
}
