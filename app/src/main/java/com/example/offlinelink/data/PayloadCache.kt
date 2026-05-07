package com.example.offlinelink.data

import java.io.File
import java.util.UUID

/**
 * Disk-backed binary payload store.
 *
 * Payloads (voice recordings, images) are stored as raw bytes on disk, referenced
 * by a UUID key.  This keeps large Base64 strings out of [ChatMessage] objects
 * and out of the JSON history file.
 */
class PayloadCache(private val cacheDir: File) {
  private val payloadDir = File(cacheDir, "payloads")

  init {
    payloadDir.mkdirs()
  }

  /** Store raw bytes and return a unique key. */
  fun put(bytes: ByteArray): String {
    val key = UUID.randomUUID().toString()
    fileForKey(key).writeBytes(bytes)
    return key
  }

  /** Retrieve raw bytes by key, or null if not found. */
  fun get(key: String): ByteArray? {
    val file = fileForKey(key)
    return if (file.exists()) file.readBytes() else null
  }

  /** Remove a payload from disk. */
  fun remove(key: String) {
    fileForKey(key).delete()
  }

  /** Check whether a payload exists on disk. */
  fun contains(key: String): Boolean = fileForKey(key).exists()

  /** Delete all stored payloads. */
  fun clearAll() {
    payloadDir.listFiles()?.forEach { it.delete() }
  }

  private fun fileForKey(key: String): File = File(payloadDir, "$key.bin")
}
