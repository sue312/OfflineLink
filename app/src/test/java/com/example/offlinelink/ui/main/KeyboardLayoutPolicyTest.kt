package com.example.offlinelink.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutPolicyTest {
  @Test
  fun rootImePaddingIsSkippedWhenWindowAlreadyResizesForKeyboard() {
    assertFalse(shouldApplyRootImePadding(windowResizesForKeyboard = true))
  }

  @Test
  fun rootImePaddingIsAppliedWhenWindowDoesNotResizeForKeyboard() {
    assertTrue(shouldApplyRootImePadding(windowResizesForKeyboard = false))
  }
}
