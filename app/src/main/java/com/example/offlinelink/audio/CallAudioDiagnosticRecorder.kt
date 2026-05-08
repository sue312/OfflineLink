package com.example.offlinelink.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallAudioDiagnosticRecorder private constructor(
  val sessionDir: File,
  private val captureSampleRateHz: Int,
) : Closeable {
  private val captureRaw = Pcm16WavFileWriter(File(sessionDir, "capture-raw.wav"), captureSampleRateHz)
  private val captureProcessed = Pcm16WavFileWriter(File(sessionDir, "capture-processed.wav"), captureSampleRateHz)
  private var receivedDecoded: Pcm16WavFileWriter? = null
  private var closed = false

  @Synchronized
  fun writeCaptureRaw(
    bytes: ByteArray,
    length: Int,
  ) {
    if (closed) return
    captureRaw.write(bytes, length)
  }

  @Synchronized
  fun writeCaptureProcessed(
    bytes: ByteArray,
    length: Int,
  ) {
    if (closed) return
    captureProcessed.write(bytes, length)
  }

  @Synchronized
  fun writeReceivedDecoded(
    bytes: ByteArray,
    length: Int,
    sampleRateHz: Int,
  ) {
    if (closed) return
    val writer =
      receivedDecoded
        ?: Pcm16WavFileWriter(File(sessionDir, "received-decoded.wav"), sampleRateHz)
          .also { receivedDecoded = it }
    writer.write(bytes, length)
  }

  @Synchronized
  override fun close() {
    if (closed) return
    captureRaw.close()
    captureProcessed.close()
    receivedDecoded?.close()
    receivedDecoded = null
    closed = true
  }

  companion object {
    fun start(
      baseDir: File,
      captureSampleRateHz: Int,
      processingMode: CallAudioProcessingMode? = null,
      nowMs: Long = System.currentTimeMillis(),
    ): CallAudioDiagnosticRecorder {
      baseDir.mkdirs()
      trimOldSessions(baseDir)
      val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
      val sessionDir = File(baseDir, timestamp).also { it.mkdirs() }
      writeMetadata(sessionDir, captureSampleRateHz, processingMode, nowMs)
      return CallAudioDiagnosticRecorder(sessionDir, captureSampleRateHz)
    }

    private fun writeMetadata(
      sessionDir: File,
      captureSampleRateHz: Int,
      processingMode: CallAudioProcessingMode?,
      nowMs: Long,
    ) {
      val mode = processingMode ?: CallAudioProcessingMode.Default
      File(sessionDir, "metadata.txt").writeText(
        buildString {
          appendLine("createdAtMs=$nowMs")
          appendLine("captureSampleRateHz=$captureSampleRateHz")
          appendLine("processingMode=${mode.name}")
          appendLine("systemEffects=${mode.useSystemEffects}")
          appendLine("appInputProcessing=${mode.inputProcessorProfile != null}")
        },
        Charsets.UTF_8,
      )
    }

    private fun trimOldSessions(baseDir: File) {
      val sessions =
        baseDir
          .listFiles()
          ?.filter { it.isDirectory }
          ?.sortedByDescending { it.lastModified() }
          .orEmpty()
      sessions.drop(MAX_SESSIONS - 1).forEach { session ->
        runCatching { session.deleteRecursively() }
      }
    }

    private const val MAX_SESSIONS = 6
  }
}

class Pcm16WavFileWriter(
  file: File,
  private val sampleRateHz: Int,
  private val maxDataBytes: Int = MAX_DATA_BYTES,
) : Closeable {
  private val output: RandomAccessFile
  private var dataBytes = 0
  private var closed = false

  init {
    file.parentFile?.mkdirs()
    output = RandomAccessFile(file, "rw")
    output.setLength(0L)
    writeHeader(dataSize = 0)
  }

  @Synchronized
  fun write(
    bytes: ByteArray,
    length: Int,
  ) {
    if (closed || dataBytes >= maxDataBytes) return
    val byteCount = length.coerceAtMost(bytes.size).coerceAtLeast(0)
    val writableBytes = byteCount.coerceAtMost(maxDataBytes - dataBytes)
    output.write(bytes, 0, writableBytes)
    dataBytes += writableBytes
  }

  @Synchronized
  override fun close() {
    if (closed) return
    output.seek(0L)
    writeHeader(dataBytes)
    output.close()
    closed = true
  }

  private fun writeHeader(dataSize: Int) {
    output.writeAscii("RIFF")
    output.writeIntLe(36 + dataSize)
    output.writeAscii("WAVE")
    output.writeAscii("fmt ")
    output.writeIntLe(16)
    output.writeShortLe(1)
    output.writeShortLe(1)
    output.writeIntLe(sampleRateHz)
    output.writeIntLe(sampleRateHz * CALL_AUDIO_BYTES_PER_SAMPLE)
    output.writeShortLe(CALL_AUDIO_BYTES_PER_SAMPLE)
    output.writeShortLe(16)
    output.writeAscii("data")
    output.writeIntLe(dataSize)
  }

  private fun RandomAccessFile.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
  }

  private fun RandomAccessFile.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
  }

  private fun RandomAccessFile.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
  }

  private companion object {
    private const val MAX_DATA_BYTES = 16_000 * CALL_AUDIO_BYTES_PER_SAMPLE * 45
  }
}
