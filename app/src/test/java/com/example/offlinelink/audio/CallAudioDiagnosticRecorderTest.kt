package com.example.offlinelink.audio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioDiagnosticRecorderTest {
  @Test
  fun wavWriterUpdatesHeaderWithPcmDataLength() {
    val dir = Files.createTempDirectory("offline-link-wav").toFile()
    val wavFile = dir.resolve("sample.wav")
    val writer = Pcm16WavFileWriter(wavFile, sampleRateHz = 16_000)

    writer.write(byteArrayOf(1, 2, 3, 4), length = 4)
    writer.close()

    val bytes = wavFile.readBytes()
    assertEquals("RIFF", bytes.decodeAscii(0, 4))
    assertEquals("WAVE", bytes.decodeAscii(8, 4))
    assertEquals("fmt ", bytes.decodeAscii(12, 4))
    assertEquals("data", bytes.decodeAscii(36, 4))
    assertEquals(48, bytes.size)
    assertEquals(40, bytes.readIntLe(4))
    assertEquals(16_000, bytes.readIntLe(24))
    assertEquals(32_000, bytes.readIntLe(28))
    assertEquals(4, bytes.readIntLe(40))
    assertTrue(bytes.takeLast(4) == listOf(1, 2, 3, 4).map { it.toByte() })
  }

  @Test
  fun diagnosticRecorderCreatesCaptureAndPlaybackWavFiles() {
    val dir = Files.createTempDirectory("offline-link-diagnostics").toFile()
    val recorder =
      CallAudioDiagnosticRecorder.start(
        dir,
        captureSampleRateHz = 16_000,
        processingMode = CallAudioProcessingMode.System,
        nowMs = 1_700_000_000_000L,
      )

    recorder.writeCaptureRaw(byteArrayOf(1, 0), length = 2)
    recorder.writeCaptureProcessed(byteArrayOf(2, 0), length = 2)
    recorder.writeReceivedDecoded(byteArrayOf(3, 0), length = 2, sampleRateHz = 16_000)
    recorder.close()

    assertTrue(recorder.sessionDir.resolve("capture-raw.wav").isFile)
    assertTrue(recorder.sessionDir.resolve("capture-processed.wav").isFile)
    assertTrue(recorder.sessionDir.resolve("received-decoded.wav").isFile)
    assertTrue(recorder.sessionDir.resolve("metadata.txt").readText().contains("processingMode=System"))
  }

  @Test
  fun diagnosticRecorderIgnoresWritesAfterClose() {
    val dir = Files.createTempDirectory("offline-link-diagnostics").toFile()
    val recorder = CallAudioDiagnosticRecorder.start(dir, captureSampleRateHz = 16_000, nowMs = 1_700_000_000_000L)

    recorder.close()
    recorder.writeReceivedDecoded(byteArrayOf(3, 0), length = 2, sampleRateHz = 16_000)

    assertTrue(!recorder.sessionDir.resolve("received-decoded.wav").exists())
  }

  private fun ByteArray.decodeAscii(
    offset: Int,
    length: Int,
  ): String = copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

  private fun ByteArray.readIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
      ((this[offset + 1].toInt() and 0xff) shl 8) or
      ((this[offset + 2].toInt() and 0xff) shl 16) or
      ((this[offset + 3].toInt() and 0xff) shl 24)
}
