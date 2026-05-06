package com.example.offlinelink

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class ManifestSoftInputModeTest {
  @Test
  fun mainActivityUsesAdjustResizeForKeyboard() {
    val manifest = findManifest()
    val document =
      DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(manifest)
    val androidNamespace = "http://schemas.android.com/apk/res/android"
    val application = document.getElementsByTagName("application").item(0) as Element
    val activity = findActivity(document.documentElement, ".MainActivity", androidNamespace)

    assertFalse(application.hasAttributeNS(androidNamespace, "windowSoftInputMode"))
    assertEquals("adjustResize", activity.getAttributeNS(androidNamespace, "windowSoftInputMode"))
  }

  private fun findManifest(): File =
    generateSequence(File(System.getProperty("user.dir") ?: error("user.dir is not set")) as File?) {
      it.parentFile
    }
      .map { File(it, "src/main/AndroidManifest.xml") }
      .first { it.isFile }

  private fun findActivity(
    manifest: Element,
    name: String,
    androidNamespace: String,
  ): Element {
    val activities = manifest.getElementsByTagName("activity")
    for (index in 0 until activities.length) {
      val activity = activities.item(index) as Element
      if (activity.getAttributeNS(androidNamespace, "name") == name) return activity
    }
    error("Activity $name not found")
  }
}
