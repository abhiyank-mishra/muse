package echo.music.iad1tya.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.palette.graphics.Palette
import echo.music.iad1tya.R
import echo.music.iad1tya.lyrics.LyricsEntry
import echo.music.iad1tya.lyrics.WordTimestamp
import echo.music.iad1tya.ui.component.LyricsBackgroundStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class VideoAspectRatio(val width: Int, val height: Int) {
  STORY(1080, 1920),
  SQUARE(1080, 1080);

  val ratioText: String
    get() = if (this == STORY) "9:16" else "1:1"
}

private data class MeasuredLyricLine(
  val index: Int,
  val entry: LyricsEntry,
  val inactiveLayout: StaticLayout,
  val activeLayout: StaticLayout,
  val inactiveHeight: Float,
  val activeHeight: Float,
  val startMs: Long,
  val endMs: Long,
  val topY: Float,
  val centerY: Float,
)

class LyricsVideoFrameRenderer(
  val context: Context,
  val coverArtBitmap: Bitmap?,
  val songTitle: String,
  val artistName: String,
  val selectedEntries: List<LyricsEntry>,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val aspectRatio: VideoAspectRatio = VideoAspectRatio.STORY,
  val backgroundStyle: LyricsBackgroundStyle = LyricsBackgroundStyle.BLUR,
  val customBgColor: Int? = null,
  val customTextColor: Int? = null,
  val animateLyrics: Boolean = true,
) {
  val width: Int = aspectRatio.width
  val height: Int = aspectRatio.height

  private val totalDurationSeconds = ((endTimeMs - startTimeMs).toFloat() / 1000f).coerceAtLeast(1f)

  private val defaultBgColor = 0xFF121212.toInt()
  private val defaultTextColor = 0xFFFFFFFF.toInt()
  private val defaultSecondaryTextColor = 0xB3FFFFFF.toInt()

  private val mainTextColor = customTextColor ?: defaultTextColor
  private val secondaryTextColor = defaultSecondaryTextColor
  private val bgColor = customBgColor ?: defaultBgColor

  // Base canvas & reusable frame bitmap
  private val frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  private val canvas: Canvas = Canvas(frameBitmap)

  // Pre-rendered base background bitmap for high-performance frame rendering
  private val staticBackgroundBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

  // App official logo bitmap (NO EMOJI)
  private val appLogoBitmap: Bitmap? = try {
    BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_nobg)
      ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
  } catch (_: Exception) {
    null
  }

  // Extract accent color from cover art for active line highlight
  private val accentColor: Int = if (coverArtBitmap != null) {
    val palette = Palette.from(coverArtBitmap).generate()
    palette.getVibrantColor(
      palette.getLightVibrantColor(
        palette.getMutedColor(0xFFBB86FC.toInt())
      )
    )
  } else {
    0xFFBB86FC.toInt()
  }

  // Pre-blurred cover art for lyrics area background ornament
  private val blurredCoverForCard: Bitmap? = try {
    coverArtBitmap?.let { bmp ->
      val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
      ComposeToImage.fastBlur(small, 1f, 25)
    }
  } catch (_: Exception) {
    null
  }

  // Layout metrics (normalized to 1080p canvas)
  private val scale = width / 1080f
  private val cardMarginHorizontal = 56f * scale
  private val cardMarginVertical = if (aspectRatio == VideoAspectRatio.STORY) 140f * scale else 64f * scale
  private val cardCornerRadius = 32f * scale
  private val coverArtSize = 100f * scale
  private val coverCornerRadius = 18f * scale

  // Main container bounds
  private val cardRect = RectF(
    cardMarginHorizontal,
    cardMarginVertical,
    width - cardMarginHorizontal,
    height - cardMarginVertical
  )
  private val cardBaseColor = 0x59141416.toInt() // Frosted glass tint

  // Measured lyrics dimensions
  private var lyricsTopY = 0f
  private var lyricsBottomY = 0f
  private var lyricsAvailableHeight = 0f
  private var lyricsAvailableWidth = 0f
  private var lyricsRect = RectF()

  private var baseLyricsFontSize = 30f * scale
  private val measuredLines = mutableListOf<MeasuredLyricLine>()
  private var totalLyricsContentHeight = 0f

  // Header progress bar metrics
  private var progressY = 0f
  private var progressStartX = 0f
  private var progressEndX = 0f

  init {
    measureLayout()
    renderStaticBackground()
  }

  private fun measureLayout() {
    val cardPadding = 36f * scale
    val headerTop = cardRect.top + cardPadding
    val headerHeight = coverArtSize

    // Progress bar directly below header
    progressY = headerTop + headerHeight + (24f * scale)
    progressStartX = cardRect.left + cardPadding
    progressEndX = cardRect.right - cardPadding

    // Footer metrics at bottom of card
    val footerHeight = 48f * scale
    val footerTop = cardRect.bottom - cardPadding - footerHeight

    // Lyrics viewport
    lyricsTopY = progressY + (24f * scale)
    lyricsBottomY = footerTop - (16f * scale)
    lyricsAvailableHeight = (lyricsBottomY - lyricsTopY).coerceAtLeast(100f)
    lyricsAvailableWidth = (cardRect.width() - (cardPadding * 2f)).coerceAtLeast(100f)
    lyricsRect = RectF(progressStartX, lyricsTopY, progressEndX, lyricsBottomY)

    // Dynamic font sizing based on entry count & line length — BIGGER text for cinematic feel
    val totalLines = selectedEntries.size.coerceAtLeast(1)
    baseLyricsFontSize = when {
      totalLines <= 3 -> 48f * scale
      totalLines <= 6 -> 40f * scale
      totalLines <= 10 -> 34f * scale
      totalLines <= 15 -> 28f * scale
      else -> 24f * scale
    }

    val entryGap = 38f * scale // Generous vertical margin between lines
    var cumulativeY = 0f
    measuredLines.clear()

    for (i in selectedEntries.indices) {
      val entry = selectedEntries[i]
      val nextEntry = selectedEntries.getOrNull(i + 1)
      val startMs = entry.time
      val endMs = nextEntry?.time ?: endTimeMs

      // Inactive layout (30% alpha, slightly dimmer for more contrast)
      val inactivePaint = TextPaint().apply {
        isAntiAlias = true
        color = mainTextColor
        textSize = baseLyricsFontSize
        typeface = Typeface.DEFAULT_BOLD
        alpha = (255 * 0.30f).toInt()
        letterSpacing = 0.01f
      }
      val inactiveLayout = StaticLayout.Builder.obtain(
        entry.text, 0, entry.text.length, inactivePaint, lyricsAvailableWidth.toInt()
      )
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setLineSpacing(8f * scale, 1.18f)
        .setIncludePad(false)
        .build()

      // Active layout — larger with strong accent glow bloom
      val activePaint = TextPaint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textSize = baseLyricsFontSize * 1.15f
        typeface = Typeface.DEFAULT_BOLD
        alpha = 255
        letterSpacing = 0.01f
        // Strong dual-layer glow: accent color bloom
        setShadowLayer(28f * scale, 0f, 0f, accentColor and 0x00FFFFFF or 0xAA000000.toInt())
      }
      val activeLayout = StaticLayout.Builder.obtain(
        entry.text, 0, entry.text.length, activePaint, lyricsAvailableWidth.toInt()
      )
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setLineSpacing(8f * scale, 1.18f)
        .setIncludePad(false)
        .build()

      val inH = inactiveLayout.height.toFloat()
      val actH = activeLayout.height.toFloat()
      val maxH = maxOf(inH, actH)

      measuredLines.add(
        MeasuredLyricLine(
          index = i,
          entry = entry,
          inactiveLayout = inactiveLayout,
          activeLayout = activeLayout,
          inactiveHeight = inH,
          activeHeight = actH,
          startMs = startMs,
          endMs = endMs,
          topY = cumulativeY,
          centerY = cumulativeY + (maxH / 2f)
        )
      )
      cumulativeY += maxH + entryGap
    }
    totalLyricsContentHeight = (cumulativeY - entryGap).coerceAtLeast(0f)
  }

  private fun renderStaticBackground() {
    val bgCanvas = Canvas(staticBackgroundBitmap)
    val bgPaint = Paint().apply { isAntiAlias = true }
    val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

    // 1. Render Base Ambient Background
    when (backgroundStyle) {
      LyricsBackgroundStyle.SOLID -> {
        bgPaint.color = bgColor
        bgCanvas.drawRect(fullRect, bgPaint)
      }
      LyricsBackgroundStyle.BLUR -> {
        bgPaint.color = 0xFF0D0D0E.toInt()
        bgCanvas.drawRect(fullRect, bgPaint)

        if (coverArtBitmap != null) {
          try {
            val scaledBmp = Bitmap.createScaledBitmap(coverArtBitmap, (width / 14).coerceAtLeast(32), (height / 14).coerceAtLeast(32), true)
            val blurred = ComposeToImage.fastBlur(scaledBmp, 1f, 24)
            if (blurred != null) {
              bgCanvas.drawBitmap(blurred, null, fullRect, null)
              // Rich cinematic vignette & dimming
              val vignettePaint = Paint().apply {
                shader = LinearGradient(
                  0f, 0f, 0f, height.toFloat(),
                  intArrayOf(0x99000000.toInt(), 0x66000000.toInt(), 0xCC000000.toInt()),
                  null, Shader.TileMode.CLAMP
                )
              }
              bgCanvas.drawRect(fullRect, vignettePaint)
            }
          } catch (_: Exception) {
            bgPaint.color = bgColor
            bgCanvas.drawRect(fullRect, bgPaint)
          }
        }
      }
      LyricsBackgroundStyle.GRADIENT -> {
        if (coverArtBitmap != null) {
          val palette = Palette.from(coverArtBitmap).generate()
          val vibrant = palette.getVibrantColor(0xFF2C1E3F.toInt())
          val darkVibrant = palette.getDarkVibrantColor(0xFF0D0A14.toInt())
          bgPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(vibrant, darkVibrant, 0xFF000000.toInt()), null, Shader.TileMode.CLAMP
          )
          bgCanvas.drawRect(fullRect, bgPaint)
        } else {
          bgPaint.color = bgColor
          bgCanvas.drawRect(fullRect, bgPaint)
        }
      }
    }

    // 2. Translucent Glass Card Container
    val cardPaint = Paint().apply {
      isAntiAlias = true
      color = cardBaseColor
    }
    bgCanvas.drawRoundRect(cardRect, cardCornerRadius, cardCornerRadius, cardPaint)

    // Subtle Glass Border
    val cardBorderPaint = Paint().apply {
      isAntiAlias = true
      style = Paint.Style.STROKE
      strokeWidth = 1.8f * scale
      color = 0x24FFFFFF.toInt()
    }
    bgCanvas.drawRoundRect(cardRect, cardCornerRadius, cardCornerRadius, cardBorderPaint)

    // 2b. Blurred Cover Art Ornament inside lyrics area (bottom-right, faded)
    blurredCoverForCard?.let { blurArt ->
      bgCanvas.save()
      // Clip to card so art doesn't overflow
      val clipPath = Path().apply {
        addRoundRect(cardRect, cardCornerRadius, cardCornerRadius, Path.Direction.CW)
      }
      bgCanvas.clipPath(clipPath)

      val ornamentSize = 280f * scale
      val ornamentRect = RectF(
        cardRect.right - ornamentSize - (20f * scale),
        lyricsBottomY - ornamentSize + (40f * scale),
        cardRect.right - (20f * scale),
        lyricsBottomY + (40f * scale)
      )
      val ornamentPaint = Paint().apply {
        isAntiAlias = true
        alpha = 28 // Very subtle, ~11% opacity
      }
      bgCanvas.drawBitmap(blurArt, null, ornamentRect, ornamentPaint)

      // Radial fade-out over the ornament so it blends seamlessly
      val fadePaint = Paint().apply {
        isAntiAlias = true
        shader = RadialGradient(
          ornamentRect.centerX(), ornamentRect.centerY(),
          ornamentSize / 2f,
          0x00000000, cardBaseColor,
          Shader.TileMode.CLAMP
        )
      }
      bgCanvas.drawRect(ornamentRect, fadePaint)
      bgCanvas.restore()
    }

    // 2c. Frosted inner panel behind lyrics viewport (subtle layered glass)
    val innerPanelRect = RectF(
      lyricsRect.left - (8f * scale),
      lyricsRect.top - (8f * scale),
      lyricsRect.right + (8f * scale),
      lyricsRect.bottom + (8f * scale)
    )
    val innerPanelRadius = 20f * scale
    val innerPanelPaint = Paint().apply {
      isAntiAlias = true
      // Very subtle accent-tinted frosted glass
      color = accentColor and 0x00FFFFFF or 0x0D000000 // ~5% accent tint
    }
    bgCanvas.save()
    val innerClip = Path().apply {
      addRoundRect(cardRect, cardCornerRadius, cardCornerRadius, Path.Direction.CW)
    }
    bgCanvas.clipPath(innerClip)
    bgCanvas.drawRoundRect(innerPanelRect, innerPanelRadius, innerPanelRadius, innerPanelPaint)
    // Subtle inner border
    val innerBorderPaint = Paint().apply {
      isAntiAlias = true
      style = Paint.Style.STROKE
      strokeWidth = 1f * scale
      color = 0x12FFFFFF.toInt()
    }
    bgCanvas.drawRoundRect(innerPanelRect, innerPanelRadius, innerPanelRadius, innerBorderPaint)
    bgCanvas.restore()

    // 3. Render Header (Cover Art + Title + Artist)
    val cardPadding = 36f * scale
    val headerLeft = cardRect.left + cardPadding
    val headerTop = cardRect.top + cardPadding

    coverArtBitmap?.let { bmp ->
      val artRect = RectF(headerLeft, headerTop, headerLeft + coverArtSize, headerTop + coverArtSize)
      val artPath = Path().apply {
        addRoundRect(artRect, coverCornerRadius, coverCornerRadius, Path.Direction.CW)
      }
      bgCanvas.save()
      bgCanvas.clipPath(artPath)
      bgCanvas.drawBitmap(bmp, null, artRect, null)
      bgCanvas.restore()

      val artBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * scale
        color = 0x33FFFFFF.toInt()
      }
      bgCanvas.drawRoundRect(artRect, coverCornerRadius, coverCornerRadius, artBorderPaint)
    }

    val headerTextStartX = headerLeft + coverArtSize + (20f * scale)
    // Leave room on right for animated equalizer visualizer
    val headerTextMaxWidth = cardRect.right - headerTextStartX - (90f * scale)

    val titlePaint = TextPaint().apply {
      isAntiAlias = true
      color = mainTextColor
      textSize = 28f * scale
      typeface = Typeface.DEFAULT_BOLD
    }
    val artistPaint = TextPaint().apply {
      isAntiAlias = true
      color = secondaryTextColor
      textSize = 21f * scale
      typeface = Typeface.DEFAULT
    }

    val safeTitle = if (titlePaint.measureText(songTitle) > headerTextMaxWidth) {
      val count = titlePaint.breakText(songTitle, true, headerTextMaxWidth - 25f, null)
      songTitle.take(count) + "…"
    } else songTitle

    val safeArtist = if (artistPaint.measureText(artistName) > headerTextMaxWidth) {
      val count = artistPaint.breakText(artistName, true, headerTextMaxWidth - 25f, null)
      artistName.take(count) + "…"
    } else artistName

    val titleY = headerTop + (38f * scale)
    val artistY = titleY + (32f * scale)
    bgCanvas.drawText(safeTitle, headerTextStartX, titleY, titlePaint)
    bgCanvas.drawText(safeArtist, headerTextStartX, artistY, artistPaint)

    // 4. Render App Official Watermark in Footer (NO EMOJI - Real Logo)
    val footerY = cardRect.bottom - cardPadding - (12f * scale)
    val footerStartX = cardRect.left + cardPadding
    val logoSize = 34f * scale

    appLogoBitmap?.let { logo ->
      val logoRect = RectF(footerStartX, footerY - (logoSize * 0.85f), footerStartX + logoSize, footerY + (logoSize * 0.15f))
      bgCanvas.drawBitmap(logo, null, logoRect, Paint().apply { isAntiAlias = true })
    }

    val appNamePaint = TextPaint().apply {
      isAntiAlias = true
      color = secondaryTextColor
      textSize = 19f * scale
      typeface = Typeface.DEFAULT_BOLD
    }
    val appNameTextX = if (appLogoBitmap != null) footerStartX + logoSize + (12f * scale) else footerStartX
    val appName = context.getString(R.string.app_name)
    bgCanvas.drawText(appName, appNameTextX, footerY, appNamePaint)
  }

  /**
   * Renders a single frame at timestamp [tSeconds] into [frameBitmap].
   */
  fun renderFrame(tSeconds: Float): Bitmap {
    // 1. Ambient Background Zoom / Breathe (Ken Burns cinema effect)
    val progress01 = (tSeconds / totalDurationSeconds).coerceIn(0f, 1f)
    val ambientScale = 1.0f + (progress01 * 0.045f)

    canvas.save()
    canvas.scale(ambientScale, ambientScale, width / 2f, height / 2f)
    canvas.drawBitmap(staticBackgroundBitmap, 0f, 0f, null)
    canvas.restore()

    val currentSongTimeMs = startTimeMs + (tSeconds * 1000f).toLong()

    // 2. Draw Live Playback Progress Bar on Card
    val barTotalWidth = progressEndX - progressStartX
    val barHeight = 4f * scale
    val barPaint = Paint().apply { isAntiAlias = true }

    // Track background
    barPaint.color = 0x26FFFFFF.toInt()
    val trackRect = RectF(progressStartX, progressY, progressEndX, progressY + barHeight)
    canvas.drawRoundRect(trackRect, barHeight / 2f, barHeight / 2f, barPaint)

    // Filled Progress — accent color
    barPaint.color = accentColor
    val filledWidth = barTotalWidth * progress01
    val fillRect = RectF(progressStartX, progressY, progressStartX + filledWidth, progressY + barHeight)
    canvas.drawRoundRect(fillRect, barHeight / 2f, barHeight / 2f, barPaint)

    // 3. Draw Animated Equalizer Bars in Header (4 bouncing frequency bars)
    drawEqualizerBars(tSeconds)

    // 4. Calculate Active Line & Smooth Kinetic Scrolling Position
    if (measuredLines.isEmpty()) return frameBitmap

    // 250ms vocal anticipation (matches player LyricsUtils.findCurrentLineIndex which uses +300ms)
    val effectiveSongTimeMs = currentSongTimeMs + 250L

    var activeIndex = 0
    for (i in measuredLines.indices) {
      if (effectiveSongTimeMs >= measuredLines[i].startMs && effectiveSongTimeMs < measuredLines[i].endMs) {
        activeIndex = i
        break
      } else if (effectiveSongTimeMs >= measuredLines[i].endMs) {
        activeIndex = i
      }
    }

    // Determine camera scroll target
    val translateY = if (totalLyricsContentHeight <= lyricsAvailableHeight) {
      // All selected lines fit comfortably — vertically center entire block
      lyricsTopY + ((lyricsAvailableHeight - totalLyricsContentHeight) / 2f)
    } else {
      // Lines exceed viewport: smoothly track the active line
      val currentLine = measuredLines[activeIndex]
      val nextLine = measuredLines.getOrNull(activeIndex + 1)

      val smoothCenterY = if (nextLine != null) {
        val handoffWindowMs = 500L
        val timeUntilNext = (currentLine.endMs - effectiveSongTimeMs).coerceAtLeast(0L)
        if (timeUntilNext < handoffWindowMs) {
          val progress = 1f - (timeUntilNext.toFloat() / handoffWindowMs)
          // Smooth cosine curve
          val eased = (1f - cos(progress * Math.PI).toFloat()) / 2f
          currentLine.centerY + ((nextLine.centerY - currentLine.centerY) * eased)
        } else {
          currentLine.centerY
        }
      } else {
        currentLine.centerY
      }

      val lyricsViewportCenterY = lyricsTopY + (lyricsAvailableHeight / 2f)
      lyricsViewportCenterY - smoothCenterY
    }

    // 5. Render Lyrics with Clipping & Edge Fade Gradients
    canvas.save()
    canvas.clipRect(lyricsRect)

    val lyricsStartX = lyricsRect.left

    for (line in measuredLines) {
      val lineY = line.topY + translateY
      // Viewport culling
      if (lineY + line.activeHeight < lyricsTopY - 80f || lineY > lyricsBottomY + 80f) {
        continue
      }

      val isActive = (line.index == activeIndex)
      val distFromActive = abs(line.index - activeIndex)

      canvas.save()
      canvas.translate(lyricsStartX, lineY)

      if (isActive && animateLyrics) {
        // Draw accent glow pill behind active line
        val glowPadH = 24f * scale
        val glowPadV = 12f * scale
        val glowRect = RectF(
          -glowPadH, -glowPadV,
          lyricsAvailableWidth + glowPadH,
          line.activeHeight + glowPadV
        )
        val glowPaint = Paint().apply {
          isAntiAlias = true
          color = accentColor and 0x00FFFFFF or 0x1A000000 // ~10% accent
          maskFilter = BlurMaskFilter(20f * scale, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(glowRect, 18f * scale, 18f * scale, glowPaint)

        // Draw the actual active text
        val words = line.entry.words
        if (words != null && words.isNotEmpty()) {
          renderWordKaraoke(canvas, line, currentSongTimeMs)
        } else {
          line.activeLayout.draw(canvas)
        }
      } else {
        // Distance-based alpha fade for depth effect
        val alphaMultiplier = when (distFromActive) {
          1 -> 1.0f
          2 -> 0.8f
          3 -> 0.6f
          else -> 0.45f
        }
        val savedAlpha = line.inactiveLayout.paint.alpha
        line.inactiveLayout.paint.alpha = (savedAlpha * alphaMultiplier).toInt()
        line.inactiveLayout.draw(canvas)
        line.inactiveLayout.paint.alpha = savedAlpha
      }
      canvas.restore()
    }

    // 6. Draw Soft Top & Bottom Fade Gradients (Apple Music / Spotify aesthetic)
    val fadeHeight = 70f * scale
    val topFadePaint = Paint().apply {
      isAntiAlias = true
      shader = LinearGradient(
        0f, lyricsTopY, 0f, lyricsTopY + fadeHeight,
        cardBaseColor, 0x00000000, Shader.TileMode.CLAMP
      )
    }
    canvas.drawRect(lyricsRect.left, lyricsTopY, lyricsRect.right, lyricsTopY + fadeHeight, topFadePaint)

    val bottomFadePaint = Paint().apply {
      isAntiAlias = true
      shader = LinearGradient(
        0f, lyricsBottomY - fadeHeight, 0f, lyricsBottomY,
        0x00000000, cardBaseColor, Shader.TileMode.CLAMP
      )
    }
    canvas.drawRect(lyricsRect.left, lyricsBottomY - fadeHeight, lyricsRect.right, lyricsBottomY, bottomFadePaint)

    canvas.restore()

    return frameBitmap
  }

  private fun drawEqualizerBars(tSeconds: Float) {
    val eqRight = cardRect.right - (40f * scale)
    val eqBottom = cardRect.top + (36f * scale) + coverArtSize - (8f * scale)
    val barWidth = 6f * scale
    val barGap = 5f * scale
    val barRadius = 3f * scale
    val maxBarHeight = 36f * scale
    val minBarHeight = 8f * scale

    val eqPaint = Paint().apply {
      isAntiAlias = true
      color = 0xFFFFFFFF.toInt()
    }

    for (i in 0 until 4) {
      val barX = eqRight - (4 - i) * (barWidth + barGap)
      val wave = 0.5f + 0.5f * sin((tSeconds * 11.0f) + (i * 1.6f))
      val barH = minBarHeight + (maxBarHeight - minBarHeight) * wave
      val barRect = RectF(barX, eqBottom - barH, barX + barWidth, eqBottom)
      canvas.drawRoundRect(barRect, barRadius, barRadius, eqPaint)
    }
  }

  private fun renderWordKaraoke(
    canvas: Canvas,
    line: MeasuredLyricLine,
    currentSongTimeMs: Long,
  ) {
    // Render the base line cleanly
    line.activeLayout.draw(canvas)
  }
}
