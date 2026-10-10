package echo.music.iad1tya.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.music.innertube.YouTube
import echo.music.iad1tya.constants.AudioQuality
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Trims and re-encodes a specific time window of song audio into a clean AAC MP4 file.
 */
object LyricsAudioTrimmer {
  private const val TAG = "LyricsAudioTrimmer"
  private const val TIMEOUT_US = 10_000L

  private val httpClient =
    OkHttpClient.Builder()
      .proxy(YouTube.proxy)
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()

  /**
   * Extracts and trims audio between [startTimeMs] and [endTimeMs].
   * Produces an AAC audio file at [outputFile].
   */
  suspend fun trimAudio(
    context: Context,
    songId: String,
    startTimeMs: Long,
    endTimeMs: Long,
    outputFile: File,
    onProgress: (Float, String) -> Unit = { _, _ -> },
  ): Boolean = withContext(Dispatchers.IO) {
    var tempDownloadFile: File? = null
    try {
      val sourceUri: Uri?
      val sourceFile: File?

      if (songId.isLocalMediaId()) {
        sourceUri = songId.toUri()
        sourceFile = null
        onProgress(0.15f, "Reading local audio…")
      } else {
        onProgress(0.05f, "Fetching audio stream…")
        val connectivityManager =
          context.getSystemService<ConnectivityManager>() ?: error("No connectivity manager")
        val playbackData =
          YTPlayerUtils.playerResponseForPlayback(
            videoId = songId,
            audioQuality = AudioQuality.OPUS,
            connectivityManager = connectivityManager,
          ).getOrThrow()

        val tempFile = File.createTempFile("lyrics_audio_raw_", ".m4a", context.cacheDir)
        tempDownloadFile = tempFile
        downloadStream(playbackData, tempFile) { downloadPercent ->
          onProgress(0.05f + (downloadPercent * 0.20f), "Downloading audio: ${(downloadPercent * 100).toInt()}%")
        }
        sourceUri = null
        sourceFile = tempFile
      }

      onProgress(0.28f, "Trimming audio slice…")
      trimAndEncodeAac(
        context = context,
        sourceFile = sourceFile,
        sourceUri = sourceUri,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        outputFile = outputFile,
      )
      onProgress(0.35f, "Audio ready")
      true
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to trim audio for songId=$songId")
      false
    } finally {
      tempDownloadFile?.delete()
    }
  }

  private fun downloadStream(
    playbackData: YTPlayerUtils.PlaybackData,
    destFile: File,
    onProgress: (Float) -> Unit = {},
  ) {
    val totalLength = playbackData.format.contentLength ?: 10_000_000L
    val rangedUrl = if (playbackData.streamUrl.contains("range=")) {
      playbackData.streamUrl
    } else {
      "${playbackData.streamUrl}&range=0-$totalLength"
    }
    val request = Request.Builder().url(rangedUrl).build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("Audio download failed with code ${response.code}")
      val body = response.body ?: error("No response body for audio download")
      val totalBytes = body.contentLength().takeIf { it > 0 } ?: totalLength
      var bytesWritten = 0L
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      body.byteStream().use { input ->
        destFile.outputStream().use { output ->
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            bytesWritten += read
            if (totalBytes > 0) {
              onProgress((bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
          }
          output.flush()
        }
      }
    }
  }

  private fun trimAndEncodeAac(
    context: Context,
    sourceFile: File?,
    sourceUri: Uri?,
    startTimeMs: Long,
    endTimeMs: Long,
    outputFile: File,
  ) {
    val extractor = MediaExtractor()
    try {
      if (sourceFile != null) {
        extractor.setDataSource(sourceFile.absolutePath)
      } else if (sourceUri != null) {
        extractor.setDataSource(context, sourceUri, null)
      } else {
        error("No audio source available")
      }

      var audioTrackIndex = -1
      var inputFormat: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) {
          audioTrackIndex = i
          inputFormat = format
          break
        }
      }
      if (audioTrackIndex < 0 || inputFormat == null) error("No audio track found in media")

      val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing input audio MIME")

      val decoder = MediaCodec.createDecoderByType(inputMime)
      decoder.configure(inputFormat, null, null, 0)
      decoder.start()

      val outputMime = MediaFormat.MIMETYPE_AUDIO_AAC
      val encoder = MediaCodec.createEncoderByType(outputMime)
      val aacFormat = MediaFormat.createAudioFormat(outputMime, sampleRate, channelCount).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
      }
      encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()

      val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      var muxerAudioTrack = -1
      var muxerStarted = false

      extractor.selectTrack(audioTrackIndex)
      val startUs = startTimeMs * 1000L
      val endUs = endTimeMs * 1000L
      extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

      var extractorDone = false
      var decoderDone = false
      var encoderDone = false

      val decoderBufferInfo = MediaCodec.BufferInfo()
      val encoderBufferInfo = MediaCodec.BufferInfo()

      try {
        while (!encoderDone) {
          // 1. Feed Extractor to Decoder
          if (!extractorDone) {
            val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
              val inBuffer = decoder.getInputBuffer(inIndex)!!
              val sampleSize = extractor.readSampleData(inBuffer, 0)
              val sampleTime = extractor.sampleTime

              if (sampleSize < 0 || sampleTime > endUs + 1_000_000L) {
                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                extractorDone = true
              } else {
                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                extractor.advance()
              }
            }
          }

          // 2. Read PCM from Decoder & Feed to Encoder
          if (!decoderDone) {
            val decOutIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)
            if (decOutIndex >= 0) {
              val pcmBuffer = decoder.getOutputBuffer(decOutIndex)
              val isEos = (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
              val pts = decoderBufferInfo.presentationTimeUs

              if (isEos || pts >= endUs) {
                // Signal EOS to encoder
                val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (encInIndex >= 0) {
                  encoder.queueInputBuffer(encInIndex, 0, 0, (endUs - startUs).coerceAtLeast(0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  decoderDone = true
                }
              } else if (pcmBuffer != null && decoderBufferInfo.size > 0 && pts >= startUs) {
                val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (encInIndex >= 0) {
                  val encBuffer = encoder.getInputBuffer(encInIndex)!!
                  encBuffer.clear()

                  pcmBuffer.position(decoderBufferInfo.offset)
                  pcmBuffer.limit(decoderBufferInfo.offset + decoderBufferInfo.size)

                  // Write into encBuffer FIRST (encBuffer is WRITABLE!)
                  encBuffer.put(pcmBuffer)
                  encBuffer.position(0)
                  encBuffer.limit(decoderBufferInfo.size)

                  // Apply micro fade on writable encBuffer
                  applyMicroFade(
                    pcmBuffer = encBuffer,
                    ptsUs = pts,
                    startUs = startUs,
                    endUs = endUs,
                    fadeDurationUs = 50_000L,
                  )

                  encBuffer.position(0)
                  val newPts = (pts - startUs).coerceAtLeast(0L)
                  encoder.queueInputBuffer(encInIndex, 0, decoderBufferInfo.size, newPts, 0)
                }
              }
              decoder.releaseOutputBuffer(decOutIndex, false)
            } else if (decOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
              // Decoder format changed
            }
          }

          // 3. Read Encoded AAC & Write to Muxer
          val encOutIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)
          if (encOutIndex >= 0) {
            val encodedData = encoder.getOutputBuffer(encOutIndex)!!
            if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
              encoderBufferInfo.size = 0
            }

            if (encoderBufferInfo.size > 0 && muxerStarted) {
              encodedData.position(encoderBufferInfo.offset)
              encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
              muxer.writeSampleData(muxerAudioTrack, encodedData, encoderBufferInfo)
            }

            encoder.releaseOutputBuffer(encOutIndex, false)

            if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              encoderDone = true
            }
          } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val newFormat = encoder.outputFormat
            muxerAudioTrack = muxer.addTrack(newFormat)
            muxer.start()
            muxerStarted = true
          }
        }
      } finally {
        try { decoder.stop() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try {
          if (muxerStarted) muxer.stop()
        } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
      }
    } finally {
      try { extractor.release() } catch (_: Exception) {}
    }
  }

  /**
   * Applies a linear amplitude ramp on 16-bit PCM samples to prevent clicking artifacts at cut points.
   * Assumes [pcmBuffer] is writable and its position is 0.
   */
  private fun applyMicroFade(
    pcmBuffer: ByteBuffer,
    ptsUs: Long,
    startUs: Long,
    endUs: Long,
    fadeDurationUs: Long,
  ) {
    val relativeFromStart = ptsUs - startUs
    val relativeFromEnd = endUs - ptsUs

    val factor: Float = when {
      relativeFromStart in 0L..fadeDurationUs -> relativeFromStart.toFloat() / fadeDurationUs
      relativeFromEnd in 0L..fadeDurationUs -> (relativeFromEnd.toFloat() / fadeDurationUs).coerceIn(0f, 1f)
      else -> return
    }

    val shortBuffer = pcmBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val numSamples = shortBuffer.remaining()
    for (i in 0 until numSamples) {
      val sample = shortBuffer.get(i)
      shortBuffer.put(i, (sample * factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
  }
}
