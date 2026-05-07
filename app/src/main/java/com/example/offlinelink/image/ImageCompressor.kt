package com.example.offlinelink.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

data class CompressedImage(
  val bytes: ByteArray,
  val mimeType: String,
  val width: Int,
  val height: Int,
)

object ImageCompressor {
  private const val MAX_DIMENSION = 1024
  private const val JPEG_QUALITY = 80

  fun compress(contentResolver: ContentResolver, uri: Uri): Result<CompressedImage> =
    runCatching {
      val inputStream = contentResolver.openInputStream(uri) ?: error("Cannot open image")
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeStream(inputStream, null, options)
      inputStream.close()

      val scaleFactor = sampleSizeFor(options.outWidth, options.outHeight)

      val stream = contentResolver.openInputStream(uri) ?: error("Cannot open image")
      val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scaleFactor }
      val bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
      stream.close()

      val original = bitmap ?: error("Failed to decode image")

      val scaled: Bitmap =
        if (original.width > MAX_DIMENSION || original.height > MAX_DIMENSION) {
          val ratio = minOf(MAX_DIMENSION.toFloat() / original.width, MAX_DIMENSION.toFloat() / original.height)
          val w = (original.width * ratio).toInt()
          val h = (original.height * ratio).toInt()
          val scaledBmp = Bitmap.createScaledBitmap(original, w, h, true)
          if (scaledBmp != original) original.recycle()
          scaledBmp
        } else {
          original
        }

      val outputStream = ByteArrayOutputStream()
      scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
      val bytes = outputStream.toByteArray()
      outputStream.close()

      val result = CompressedImage(
        bytes = bytes,
        mimeType = "image/jpeg",
        width = scaled.width,
        height = scaled.height,
      )

      if (scaled !== original) scaled.recycle() else original.recycle()
      result
    }

  private fun sampleSizeFor(width: Int, height: Int): Int {
    var size = 1
    val maxDim = maxOf(width, height)
    while (maxDim / size > MAX_DIMENSION * 2) {
      size *= 2
    }
    return size
  }
}
