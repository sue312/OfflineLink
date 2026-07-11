package com.example.offlinelink.audio

internal object LyraNative {
  private val loadResult = runCatching {
    System.loadLibrary("offlinelink_lyra_jni")
  }

  val isAvailable: Boolean
    get() = loadResult.isSuccess

  @JvmStatic external fun createEncoder(
    sampleRateHz: Int,
    bitrateBps: Int,
    modelPath: String,
  ): Long

  @JvmStatic external fun encode(
    nativeHandle: Long,
    pcmBytes: ByteArray,
    length: Int,
  ): ByteArray?

  @JvmStatic external fun destroyEncoder(nativeHandle: Long)

  @JvmStatic external fun createDecoder(
    sampleRateHz: Int,
    modelPath: String,
  ): Long

  @JvmStatic external fun decode(
    nativeHandle: Long,
    packet: ByteArray,
    samplesToDecode: Int,
  ): ByteArray?

  @JvmStatic external fun destroyDecoder(nativeHandle: Long)
}
