package com.example.offlinelink.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EcdhKeyExchange(
  private val keyPair: KeyPair = generateKeyPair(),
) {
  val publicKeyBytes: ByteArray = keyPair.public.encoded

  fun complete(remotePublicKeyBytes: ByteArray): AesGcmCipher {
    val remotePublicKey = decodePublicKey(remotePublicKeyBytes)
    val sharedSecret = agree(keyPair.private, remotePublicKey)
    val salt = sessionSalt(publicKeyBytes, remotePublicKeyBytes)
    return AesGcmCipher(deriveAesKey(sharedSecret, salt))
  }

  private fun agree(
    privateKey: PrivateKey,
    remotePublicKey: PublicKey,
  ): ByteArray {
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(privateKey)
    agreement.doPhase(remotePublicKey, true)
    return agreement.generateSecret()
  }

  private fun decodePublicKey(encoded: ByteArray): PublicKey =
    KeyFactory
      .getInstance("EC")
      .generatePublic(X509EncodedKeySpec(encoded))

  private fun sessionSalt(
    localPublicKey: ByteArray,
    remotePublicKey: ByteArray,
  ): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(localPublicKey, remotePublicKey)
      .sortedWith { left, right -> compareBytes(left, right) }
      .forEach(digest::update)
    return digest.digest()
  }

  private fun compareBytes(
    left: ByteArray,
    right: ByteArray,
  ): Int {
    val count = minOf(left.size, right.size)
    for (index in 0 until count) {
      val diff = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
      if (diff != 0) return diff
    }
    return left.size - right.size
  }

  private fun deriveAesKey(
    sharedSecret: ByteArray,
    salt: ByteArray,
  ): SecretKeySpec {
    val pseudoRandomKey = hmacSha256(salt, sharedSecret)
    val info = "OfflineLink ECDH AES-GCM v1".encodeToByteArray()
    val output = ByteArray(AES_KEY_BYTES)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < output.size) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
      mac.update(previous)
      mac.update(info)
      mac.update(counter.toByte())
      previous = mac.doFinal()
      val bytesToCopy = minOf(previous.size, output.size - offset)
      previous.copyInto(output, offset, 0, bytesToCopy)
      offset += bytesToCopy
      counter += 1
    }
    return SecretKeySpec(output, "AES")
  }

  private fun hmacSha256(
    key: ByteArray,
    value: ByteArray,
  ): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(value)
  }

  private companion object {
    const val AES_KEY_BYTES = 32

    fun generateKeyPair(): KeyPair {
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
      return generator.generateKeyPair()
    }
  }
}

class AesGcmCipher(
  private val key: SecretKeySpec,
  private val random: SecureRandom = SecureRandom(),
) {
  fun encrypt(plaintext: ByteArray): ByteArray {
    val nonce = ByteArray(NONCE_BYTES)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
    val ciphertext = cipher.doFinal(plaintext)
    return SecureWireFrame.encrypted(nonce, ciphertext)
  }

  fun decrypt(frameBytes: ByteArray): ByteArray {
    val frame = SecureWireFrame.decode(frameBytes) as? SecureWireFrame.Encrypted
      ?: error("Expected encrypted secure frame")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frame.nonce))
    return cipher.doFinal(frame.ciphertext)
  }

  private companion object {
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
  }
}
