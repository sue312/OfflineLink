package com.example.offlinelink.data

import com.example.offlinelink.model.ChatMessage
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ChatHistoryRepository {
  fun loadMessages(): List<ChatMessage>

  fun saveMessages(messages: List<ChatMessage>)
}

object NoOpChatHistoryRepository : ChatHistoryRepository {
  override fun loadMessages(): List<ChatMessage> = emptyList()

  override fun saveMessages(messages: List<ChatMessage>) = Unit
}

class JsonChatHistoryRepository(
  private val file: File,
) : ChatHistoryRepository {
  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }

  override fun loadMessages(): List<ChatMessage> =
    runCatching {
      if (!file.exists()) return emptyList()
      json.decodeFromString(ChatHistorySnapshot.serializer(), file.readText()).messages
    }.getOrDefault(emptyList())

  override fun saveMessages(messages: List<ChatMessage>) {
    val parent = file.parentFile
    parent?.mkdirs()
    val snapshot = ChatHistorySnapshot(messages = messages)
    val tempFile =
      if (parent == null) {
        File("${file.path}.tmp")
      } else {
        File(parent, "${file.name}.tmp")
      }
    tempFile.writeText(json.encodeToString(snapshot))
    if (!tempFile.renameTo(file)) {
      file.writeText(tempFile.readText())
      tempFile.delete()
    }
  }
}

@Serializable
private data class ChatHistorySnapshot(
  val version: Int = 1,
  val messages: List<ChatMessage> = emptyList(),
)
