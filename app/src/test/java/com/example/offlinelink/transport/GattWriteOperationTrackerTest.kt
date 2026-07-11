package com.example.offlinelink.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattWriteOperationTrackerTest {
  @Test
  fun ignoresCompletionForStaleOperationToken() {
    val tracker = GattWriteOperationTracker()
    val first = tracker.begin()
    val second = tracker.begin()

    assertFalse(tracker.complete(first, Result.success(Unit)))

    assertNull(tracker.result())
    assertTrue(tracker.complete(second, Result.success(Unit)))
    assertTrue(tracker.result()?.isSuccess == true)
  }

  @Test
  fun timeoutMarksOperationTerminalUntilNextBegin() {
    val tracker = GattWriteOperationTracker()
    val first = tracker.begin()

    assertTrue(tracker.timeout(first, IllegalStateException("timeout")))
    assertTrue(tracker.result()?.isFailure == true)
    assertFalse(tracker.complete(first, Result.success(Unit)))

    val second = tracker.begin()
    assertNull(tracker.result())
    assertTrue(tracker.complete(second, Result.success(Unit)))
    assertTrue(tracker.result()?.isSuccess == true)
  }
}
