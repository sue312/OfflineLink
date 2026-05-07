package com.example.offlinelink.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class Base64DecodedImageCacheTest {
  @Test
  fun returnsCachedValueForTheSameBase64Key() {
    val cache = Base64DecodedImageCache<String>(maxEntries = 2)
    var decodeCount = 0

    val first = cache.getOrPut("image-a") {
      decodeCount++
      "decoded-a"
    }
    val second = cache.getOrPut("image-a") {
      decodeCount++
      "decoded-again"
    }

    assertEquals("decoded-a", first)
    assertEquals("decoded-a", second)
    assertEquals(1, decodeCount)
  }

  @Test
  fun evictsLeastRecentlyUsedValueWhenCapacityIsExceeded() {
    val cache = Base64DecodedImageCache<String>(maxEntries = 2)
    var decodeCount = 0

    cache.getOrPut("image-a") {
      decodeCount++
      "decoded-a"
    }
    cache.getOrPut("image-b") {
      decodeCount++
      "decoded-b"
    }
    cache.getOrPut("image-a") {
      decodeCount++
      "decoded-a-again"
    }
    cache.getOrPut("image-c") {
      decodeCount++
      "decoded-c"
    }
    val decodedAgain = cache.getOrPut("image-b") {
      decodeCount++
      "decoded-b-again"
    }

    assertEquals("decoded-b-again", decodedAgain)
    assertEquals(4, decodeCount)
  }
}
