package com.example.offlinelink.data

import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.VoiceAttachment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonChatHistoryRepositoryTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun saveThenLoadPreservesMessagesAndAttachments() {
    val file = temporaryFolder.newFile("history.json")
    val repository = JsonChatHistoryRepository(file)
    val messages =
      listOf(
        ChatMessage(
          id = "msg-1",
          conversationId = "one-to-one",
          senderId = "local",
          text = "hello",
          createdAt = 1000L,
          status = MessageStatus.Received,
          isLocal = true,
        ),
        ChatMessage(
          id = "voice-1",
          conversationId = "one-to-one",
          senderId = "local",
          text = "Voice 3s",
          createdAt = 2000L,
          status = MessageStatus.Sent,
          isLocal = true,
          kind = MessageKind.Voice,
          voice = VoiceAttachment("AQIDBA==", 2300L, "audio/3gpp"),
        ),
      )

    repository.saveMessages(messages)

    assertEquals(messages, repository.loadMessages())
  }

  @Test
  fun loadMissingHistoryReturnsEmptyList() {
    val repository = JsonChatHistoryRepository(temporaryFolder.root.resolve("missing.json"))

    assertEquals(emptyList<ChatMessage>(), repository.loadMessages())
  }
}
