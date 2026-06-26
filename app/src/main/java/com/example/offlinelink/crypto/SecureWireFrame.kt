package com.example.offlinelink.crypto

import java.nio.ByteBuffer

sealed interface SecureWireFrame {
  data class KeyExchange(val publicKeyBytes: ByteArray) : SecureWireFrame

  data class Encrypted(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
  ) : SecureWireFrame

  data class Raw(val payload: ByteArray) : SecureWireFrame

  companion object {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    private const val KIND_KEY_EXCHANGE: Byte = 1
    private const val KIND_ENCRYPTED: Byte = 2
    private const val KIND_RAW: Byte = 3

    fun keyExchange(publicKeyBytes: ByteArray): ByteArray =
      ByteBuffer
        .allocate(MAGIC.size + 1 + Int.SIZE_BYTES + publicKeyBytes.size)
        .put(MAGIC)
        .put(KIND_KEY_EXCHANGE)
        .putInt(publicKeyBytes.size)
        .put(publicKeyBytes)
        .array()

    fun encrypted(
      nonce: ByteArray,
      ciphertext: ByteArray,
    ): ByteArray {
      require(nonce.size <= MAX_UNSIGNED_BYTE) { "Nonce is too large" }
      return ByteBuffer
        .allocate(MAGIC.size + 1 + 1 + nonce.size + ciphertext.size)
        .put(MAGIC)
        .put(KIND_ENCRYPTED)
        .put(nonce.size.toByte())
        .put(nonce)
        .put(ciphertext)
        .array()
    }

    fun raw(payload: ByteArray): ByteArray =
      ByteBuffer
        .allocate(MAGIC.size + 1 + payload.size)
        .put(MAGIC)
        .put(KIND_RAW)
        .put(payload)
        .array()

    fun decode(bytes: ByteArray): SecureWireFrame? {
      if (bytes.size < MAGIC.size + 1) return null
      if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
      val buffer = ByteBuffer.wrap(bytes)
      buffer.position(MAGIC.size)
      return when (val kind = buffer.get()) {
        KIND_KEY_EXCHANGE -> {
          if (buffer.remaining() < Int.SIZE_BYTES) error("Truncated secure key exchange frame")
          val keySize = buffer.int
          require(keySize in 1..buffer.remaining()) { "Invalid secure key exchange size $keySize" }
          val keyBytes = ByteArray(keySize)
          buffer.get(keyBytes)
          KeyExchange(keyBytes)
        }
        KIND_ENCRYPTED -> {
          if (buffer.remaining() < 1) error("Truncated secure encrypted frame")
          val nonceSize = buffer.get().toInt() and 0xff
          require(nonceSize in 1..buffer.remaining()) { "Invalid secure nonce size $nonceSize" }
          val nonce = ByteArray(nonceSize)
          buffer.get(nonce)
          val ciphertext = ByteArray(buffer.remaining())
          buffer.get(ciphertext)
          Encrypted(nonce, ciphertext)
        }
        KIND_RAW -> {
          val payload = ByteArray(buffer.remaining())
          buffer.get(payload)
          Raw(payload)
        }
        else -> error("Unknown secure frame kind $kind")
      }
    }

    private const val MAX_UNSIGNED_BYTE = 0xff
  }
}
