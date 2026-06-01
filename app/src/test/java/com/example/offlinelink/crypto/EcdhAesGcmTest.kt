package com.example.offlinelink.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EcdhAesGcmTest {
  @Test
  fun ecdhPeersDeriveCompatibleAesGcmCipher() {
    val alice = EcdhKeyExchange()
    val bob = EcdhKeyExchange()

    val aliceCipher = alice.complete(remotePublicKeyBytes = bob.publicKeyBytes)
    val bobCipher = bob.complete(remotePublicKeyBytes = alice.publicKeyBytes)

    val plaintext = "hello over bluetooth".encodeToByteArray()
    val encrypted = aliceCipher.encrypt(plaintext)

    assertFalse(encrypted.decodeToString().contains("hello over bluetooth"))
    assertArrayEquals(plaintext, bobCipher.decrypt(encrypted))
  }
}
