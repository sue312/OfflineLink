package com.example.offlinelink.ui.main

import com.example.offlinelink.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStatusLabelTest {
  @Test
  fun receivedStatusUsesDeliveredLabelForLocalMessages() {
    assertEquals("delivered", MessageStatus.Received.displayLabel(isLocal = true))
    assertEquals("received", MessageStatus.Received.displayLabel(isLocal = false))
  }
}
