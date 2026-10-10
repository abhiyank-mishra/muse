package echo.music.iad1tya.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Hardware-accelerated video encoder that records Canvas frames into H.264
 * and multiplexes them with a trimmed AAC audio track into an MP4 file.
 */
object LyricsVideoEncoder {
  private const val TAG = "LyricsVideoEncoder"
  private const val TIMEOUT_US = 10_000L
  private const val FRAME_RATE = 30
  private const val BIT_RATE = 5_000_000 // 5 Mbps 1080p

  suspend fun encodeVideo(
    context: Context,
    renderer: LyricsVideoFrameRenderer,
    audioFile: File?,
    outputVideoFile: File,
    durationSeconds: Float,
    onProgress: (Float) -> Unit = {},
  ): Boolean = withContext(Dispatchers.Default) {
    var videoCodec: MediaCodec? = null
    var eglHelper: EglHelper? = null
    var muxer: MediaMuxer? = null
    var audioExtractor: MediaExtractor? = null

    try {
      val width = renderer.width
      val height = renderer.height

      // 1. Prepare Audio Source (if provided)
      var audioFormat: MediaFormat? = null
      var audioTrackIndex = -1
      if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
        audioExtractor = MediaExtractor().apply {
          setDataSource(audioFile.absolutePath)
        }
        for (i in 0 until audioExtractor.trackCount) {
          val format = audioExtractor.getTrackFormat(i)
          val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
          if (mime.startsWith("audio/")) {
            audioFormat = format
            audioTrackIndex = i
            audioExtractor.selectTrack(i)
            break
          }
        }
      }

      // 2. Configure Video Encoder
      val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      }

      videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      }

      val inputSurface = videoCodec.createInputSurface()
      videoCodec.start()

      // 3. Setup EGL Helper
      eglHelper = EglHelper(inputSurface, width, height)
      eglHelper.makeCurrent()

      muxer = MediaMuxer(outputVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      var muxerVideoTrack = -1
      var muxerAudioTrack = -1
      var muxerStarted = false

      val totalFrames = (durationSeconds * FRAME_RATE).toInt().coerceAtLeast(1)
      val frameDurationNs = 1_000_000_000L / FRAME_RATE

      val bufferInfo = MediaCodec.BufferInfo()

      // Function to drain video encoder packets into muxer
      fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
          videoCodec.signalEndOfInputStream()
        }

        while (true) {
          val outIndex = videoCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
          if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) break
          } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (muxerStarted) error("Format changed after muxer started")
            val newFormat = videoCodec.outputFormat
            muxerVideoTrack = muxer.addTrack(newFormat)
            if (audioFormat != null) {
              muxerAudioTrack = muxer.addTrack(audioFormat)
            }
            muxer.start()
            muxerStarted = true
          } else if (outIndex >= 0) {
            val encodedData = videoCodec.getOutputBuffer(outIndex)!!
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
              bufferInfo.size = 0
            }

            if (bufferInfo.size > 0 && muxerStarted) {
              encodedData.position(bufferInfo.offset)
              encodedData.limit(bufferInfo.offset + bufferInfo.size)
              muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
            }

            videoCodec.releaseOutputBuffer(outIndex, false)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              break
            }
          }
        }
      }

      // 4. Render and Encode Each Frame
      for (frameIndex in 0 until totalFrames) {
        val tSeconds = frameIndex.toFloat() / FRAME_RATE
        val frameBitmap = renderer.renderFrame(tSeconds)

        eglHelper.drawBitmap(frameBitmap)
        val ptsNs = frameIndex * frameDurationNs
        eglHelper.setPresentationTime(ptsNs)
        eglHelper.swapBuffers()

        drainEncoder(false)

        val progress = (frameIndex + 1).toFloat() / totalFrames * 0.85f
        onProgress(progress)
      }

      // 5. Finalize Video Stream
      drainEncoder(true)

      // 6. Multiplex Audio Stream (if present)
      if (audioExtractor != null && muxerStarted && muxerAudioTrack >= 0) {
        val audioBuffer = ByteBuffer.allocateDirect(128 * 1024)
        val audioBufferInfo = MediaCodec.BufferInfo()

        while (true) {
          audioBuffer.clear()
          val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
          if (sampleSize < 0) break

          audioBufferInfo.offset = 0
          audioBufferInfo.size = sampleSize
          audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
          audioBufferInfo.flags = audioExtractor.sampleFlags

          if (audioBufferInfo.presentationTimeUs <= (durationSeconds * 1_000_000L).toLong()) {
            muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
          }

          audioExtractor.advance()
        }
      }

      onProgress(1.0f)
      true
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to encode video")
      false
    } finally {
      try { eglHelper?.release() } catch (_: Exception) {}
      try { videoCodec?.stop() } catch (_: Exception) {}
      try { videoCodec?.release() } catch (_: Exception) {}
      try { audioExtractor?.release() } catch (_: Exception) {}
      try { muxer?.stop() } catch (_: Exception) {}
      try { muxer?.release() } catch (_: Exception) {}
    }
  }

  /**
   * Encapsulates EGL display, context, window surface, and texture shader program.
   */
  private class EglHelper(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
  ) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId = 0
    private var textureId = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uTextureHandle = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
      // 1. Initialize EGL
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("Unable to get EGL14 display")

      val version = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
        error("Unable to initialize EGL14")
      }

      val configAttribs = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        0x3142, 1, // EGL_RECORDABLE_ANDROID
        EGL14.EGL_NONE
      )

      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0)
      val eglConfig = configs[0] ?: error("Unable to find compatible EGL config")

      val contextAttribs = intArrayOf(
        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL14.EGL_NONE
      )
      eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
      if (eglContext == EGL14.EGL_NO_CONTEXT) error("Failed to create EGL context")

      val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
      eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
      if (eglSurface == EGL14.EGL_NO_SURFACE) error("Failed to create EGL window surface")

      makeCurrent()

      // Full screen quad coordinates
      val vertices = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
      )
      vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(vertices)
        position(0)
      }

      // Flip Y texture coordinates for standard Android Bitmap orientation
      val texCoords = floatArrayOf(
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
      )
      texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(texCoords)
        position(0)
      }

      setupGL()
    }

    private fun setupGL() {
      val vertexShaderSrc = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = aPosition;
          vTexCoord = aTexCoord;
        }
      """.trimIndent()

      val fragmentShaderSrc = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
          gl_FragColor = texture2D(uTexture, vTexCoord);
        }
      """.trimIndent()

      val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSrc)
      val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)

      programId = GLES20.glCreateProgram().also {
        GLES20.glAttachShader(it, vShader)
        GLES20.glAttachShader(it, fShader)
        GLES20.glLinkProgram(it)
      }

      aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
      aTexCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
      uTextureHandle = GLES20.glGetUniformLocation(programId, "uTexture")

      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      textureId = textures[0]
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

      GLES20.glViewport(0, 0, width, height)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
      return GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
      }
    }

    fun makeCurrent() {
      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        error("eglMakeCurrent failed")
      }
    }

    fun drawBitmap(bitmap: Bitmap) {
      GLES20.glUseProgram(programId)

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      GLES20.glUniform1i(uTextureHandle, 0)

      GLES20.glEnableVertexAttribArray(aPositionHandle)
      GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

      GLES20.glEnableVertexAttribArray(aTexCoordHandle)
      GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

      GLES20.glDisableVertexAttribArray(aPositionHandle)
      GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun setPresentationTime(ptsNs: Long) {
      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
    }

    fun swapBuffers() {
      EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
          EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
          EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        EGL14.eglTerminate(eglDisplay)
      }
    }
  }
}
