package com.example.offlinelink.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureRefactorTest {
  private val projectRoot: File =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "settings.gradle.kts").exists() }

  @Test
  fun mainScreenViewModelIsSplitIntoManagers() {
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/ConnectionManager.kt", "class ConnectionManager")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/MessageManager.kt", "class MessageManager")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/CallManager.kt", "class CallManager")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/MainScreenViewModel.kt", "ConnectionManager()")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/MainScreenViewModel.kt", "MessageManager()")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/main/MainScreenViewModel.kt", "CallManager()")
    assertLineCountAtMost("app/src/main/java/com/example/offlinelink/ui/main/MainScreenViewModel.kt", 900)
  }

  @Test
  fun mainScreenIsSplitIntoUiComponents() {
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/HeaderSection.kt", "fun HeaderSection")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/SetupSection.kt", "fun SetupSection")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/MessageListSection.kt", "fun MessageListSection")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/MessageComposer.kt", "fun MessageComposer")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/CallSection.kt", "fun CallSection")
    assertSourceFile("app/src/main/java/com/example/offlinelink/ui/components/SettingsSection.kt", "fun SettingsSection")
    assertLineCountAtMost("app/src/main/java/com/example/offlinelink/ui/main/MainScreen.kt", 900)
  }

  @Test
  fun chatSessionStoreIsSplitIntoStateStores() {
    assertSourceFile("app/src/main/java/com/example/offlinelink/chat/ConnectionStateStore.kt", "class ConnectionStateStore")
    assertSourceFile("app/src/main/java/com/example/offlinelink/chat/MessageStateStore.kt", "class MessageStateStore")
    assertSourceFile("app/src/main/java/com/example/offlinelink/chat/CallStateStore.kt", "class CallStateStore")
    assertLineCountAtMost("app/src/main/java/com/example/offlinelink/chat/ChatSessionStore.kt", 260)
  }

  private fun assertSourceFile(
    relativePath: String,
    expectedText: String,
  ) {
    val source = File(projectRoot, relativePath)
    assertTrue("$relativePath should exist", source.exists())
    assertTrue("$relativePath should contain $expectedText", source.readText().contains(expectedText))
  }

  private fun assertLineCountAtMost(
    relativePath: String,
    maxLines: Int,
  ) {
    val source = File(projectRoot, relativePath)
    assertTrue("$relativePath should exist", source.exists())
    val actualLines = source.readLines().size
    assertTrue("$relativePath should be at most $maxLines lines but was $actualLines", actualLines <= maxLines)
  }
}
