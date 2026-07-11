package com.example.offlinelink.audio

import android.content.Context
import java.io.File

internal object LyraModelStore {
  private val lock = Any()

  fun modelPath(context: Context): String? =
    runCatching {
      synchronized(lock) {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.isDirectory) {
          check(modelDir.mkdirs()) { "Could not create Lyra model directory" }
        }
        MODEL_ASSETS.forEach { assetName ->
          val output = File(modelDir, assetName)
          if (!output.isFile || output.length() == 0L) {
            copyAsset(context, assetName, output)
          }
        }
        modelDir.absolutePath
      }
    }.getOrNull()

  private fun copyAsset(
    context: Context,
    assetName: String,
    output: File,
  ) {
    context.assets.open("$ASSET_DIR/$assetName").use { input ->
      output.outputStream().use { outputStream ->
        input.copyTo(outputStream)
      }
    }
  }

  private const val ASSET_DIR = "lyra/model_coeffs"
  private const val MODEL_DIR = "lyra/model_coeffs_1_3_2"
  private val MODEL_ASSETS =
    listOf(
      "lyragan.tflite",
      "quantizer.tflite",
      "soundstream_encoder.tflite",
      "lyra_config.binarypb",
    )
}
