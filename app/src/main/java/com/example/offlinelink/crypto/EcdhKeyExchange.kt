package com.example.offlinelink.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EcdhKeyExchange(
  private val keyPair: KeyPair = generateKeyPair(),
) {
  val publicKeyBytes: ByteArray = encodeCompressedPublicKey(keyPair.public as ECPublicKey)

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

  private fun decodePublicKey(encoded: ByteArray): PublicKey {
    if (isCompressedPublicKey(encoded)) {
      return decodeCompressedPublicKey(encoded, keyPair.public as ECPublicKey)
    }
    return KeyFactory
      .getInstance("EC")
      .generatePublic(X509EncodedKeySpec(encoded))
  }

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
    const val COMPRESSED_PUBLIC_KEY_BYTES = 33

    fun generateKeyPair(): KeyPair {
      val generator = KeyPairGenerator.getInstance("EC")
      generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
      return generator.generateKeyPair()
    }

    fun encodeCompressedPublicKey(publicKey: ECPublicKey): ByteArray {
      val point = publicKey.w
      val encoded = ByteArray(COMPRESSED_PUBLIC_KEY_BYTES)
      encoded[0] = if (point.affineY.testBit(0)) 0x03 else 0x02
      fixedUnsigned(point.affineX, 32).copyInto(encoded, destinationOffset = 1)
      return encoded
    }

    fun decodeCompressedPublicKey(
      encoded: ByteArray,
      localPublicKey: ECPublicKey,
    ): PublicKey {
      require(isCompressedPublicKey(encoded)) { "Invalid compressed EC public key" }
      val params = localPublicKey.params
      val curve = params.curve
      val field = curve.field as? ECFieldFp ?: error("Only prime-field EC curves are supported")
      val p = field.p
      val x = BigInteger(1, encoded.copyOfRange(1, encoded.size))
      require(x.signum() >= 0 && x < p) { "Compressed EC public key x-coordinate is out of range" }

      val ySquared =
        x.modPow(BigInteger.valueOf(3), p)
          .add(curve.a.multiply(x))
          .add(curve.b)
          .mod(p)
      val y = modularSquareRoot(ySquared, p, encoded[0] == 0x03.toByte())
      val point = ECPoint(x, y)
      return KeyFactory
        .getInstance("EC")
        .generatePublic(ECPublicKeySpec(point, params))
    }

    fun isCompressedPublicKey(encoded: ByteArray): Boolean =
      encoded.size == COMPRESSED_PUBLIC_KEY_BYTES &&
        (encoded[0] == 0x02.toByte() || encoded[0] == 0x03.toByte())

    private fun modularSquareRoot(
      value: BigInteger,
      p: BigInteger,
      preferOdd: Boolean,
    ): BigInteger {
      require(p.testBit(0) && p.testBit(1)) { "Only p mod 4 == 3 curves are supported" }
      var root = value.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
      if (root.testBit(0) != preferOdd) {
        root = p.subtract(root).mod(p)
      }
      require(root.modPow(BigInteger.TWO, p) == value.mod(p)) { "Compressed EC public key is not on curve" }
      return root
    }

    private fun fixedUnsigned(
      value: BigInteger,
      bytes: Int,
    ): ByteArray {
      val encoded = value.toByteArray()
      val raw = if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
      require(raw.size <= bytes) { "Integer does not fit in $bytes bytes" }
      return ByteArray(bytes).also { raw.copyInto(it, destinationOffset = bytes - raw.size) }
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
