package echo.music.iad1tya.ui.component

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import echo.music.iad1tya.R
import echo.music.iad1tya.utils.LyricsAudioTrimmer
import echo.music.iad1tya.utils.LyricsSharePayload
import echo.music.iad1tya.utils.LyricsVideoEncoder
import echo.music.iad1tya.utils.LyricsVideoFrameRenderer
import echo.music.iad1tya.utils.VideoAspectRatio
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsVideoExportDialog(
  payload: LyricsSharePayload,
  onDismissRequest: () -> Unit,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var selectedAspectRatio by remember { mutableStateOf(VideoAspectRatio.STORY) }
  var selectedBackgroundStyle by remember { mutableStateOf(LyricsBackgroundStyle.BLUR) }
  var animateLyrics by remember { mutableStateOf(true) }

  var isExporting by remember { mutableStateOf(false) }
  var isExportComplete by remember { mutableStateOf(false) }
  var exportProgress by remember { mutableFloatStateOf(0f) }
  var statusMessage by remember { mutableStateOf("") }

  var coverArtBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var exportedVideoFile by remember { mutableStateOf<File?>(null) }
  var exportedVideoUri by remember { mutableStateOf<Uri?>(null) }

  LaunchedEffect(payload.mediaMetadata?.thumbnailUrl) {
    withContext(Dispatchers.IO) {
      val url = payload.mediaMetadata?.thumbnailUrl
      if (url != null) {
        try {
          val req = ImageRequest.Builder(context)
            .data(url)
            .size(1024)
            .allowHardware(false)
            .build()
          val res = context.imageLoader.execute(req)
          coverArtBitmap = res.image?.toBitmap()
        } catch (e: Exception) {
          Timber.e(e, "Failed to load cover art bitmap for video export")
        }
      }
    }
  }

  fun startExport() {
    isExporting = true
    exportProgress = 0.05f
    statusMessage = "Preparing audio stream…"

    coroutineScope.launch {
      val success = withContext(Dispatchers.IO) {
        val songId = payload.mediaMetadata?.id ?: ""
        val videoCacheDir = File(context.cacheDir, "lyrics_videos").apply { mkdirs() }
        val tempAudioFile = File.createTempFile("trimmed_audio_", ".m4a", context.cacheDir)
        val tempVideoFile = File(videoCacheDir, "echo_lyrics_${System.currentTimeMillis()}.mp4")

        try {
          val audioTrimmed = LyricsAudioTrimmer.trimAudio(
            context = context,
            songId = songId,
            startTimeMs = payload.startTimeMs,
            endTimeMs = payload.endTimeMs,
            outputFile = tempAudioFile,
            onProgress = { p, msg ->
              exportProgress = p
              statusMessage = msg
            }
          )

          if (!audioTrimmed) {
            Timber.w("Audio trimming failed, proceeding without audio")
          }

          statusMessage = context.getString(R.string.generating_video)
          val renderer = LyricsVideoFrameRenderer(
            context = context,
            coverArtBitmap = coverArtBitmap,
            songTitle = payload.songTitle,
            artistName = payload.artists,
            selectedEntries = payload.selectedEntries,
            startTimeMs = payload.startTimeMs,
            endTimeMs = payload.endTimeMs,
            aspectRatio = selectedAspectRatio,
            backgroundStyle = selectedBackgroundStyle,
            animateLyrics = animateLyrics,
          )

          val encoded = LyricsVideoEncoder.encodeVideo(
            context = context,
            renderer = renderer,
            audioFile = if (audioTrimmed && tempAudioFile.exists()) tempAudioFile else null,
            outputVideoFile = tempVideoFile,
            durationSeconds = payload.durationSeconds,
            onProgress = { p ->
              exportProgress = (0.35f + (p * 0.65f)).coerceIn(0f, 1f)
              statusMessage = "Rendering video: ${(p * 100).toInt()}%"
            }
          )

          if (encoded && tempVideoFile.exists() && tempVideoFile.length() > 0) {
            val contentUri = FileProvider.getUriForFile(
              context,
              "${context.packageName}.FileProvider",
              tempVideoFile
            )
            withContext(Dispatchers.Main) {
              exportedVideoFile = tempVideoFile
              exportedVideoUri = contentUri
              isExporting = false
              isExportComplete = true
            }
            true
          } else {
            false
          }
        } catch (e: Exception) {
          Timber.e(e, "Lyrics video export failed")
          false
        } finally {
          tempAudioFile.delete()
        }
      }

      if (!success && !isExportComplete) {
        isExporting = false
        Toast.makeText(context, context.getString(R.string.video_export_failed), Toast.LENGTH_SHORT).show()
      }
    }
  }

  BasicAlertDialog(onDismissRequest = { if (!isExporting) onDismissRequest() }) {
    Card(
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
      elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
      modifier = Modifier.fillMaxWidth(0.95f)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        if (isExportComplete && exportedVideoFile != null && exportedVideoUri != null) {
          // ==========================================
          // SUCCESS: VIDEO PREVIEW & QUICK SHARING UI
          // ==========================================
          VideoPreviewScreen(
            context = context,
            videoFile = exportedVideoFile!!,
            videoUri = exportedVideoUri!!,
            songTitle = payload.songTitle,
            durationSeconds = payload.durationSeconds,
            aspectRatio = selectedAspectRatio,
            onDone = onDismissRequest
          )
        } else if (isExporting) {
          // ==========================================
          // EXPORT IN PROGRESS UI
          // ==========================================
          Text(
            text = stringResource(R.string.generating_video),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(20.dp))
          CircularProgressIndicator(
            progress = { exportProgress },
            modifier = Modifier.size(60.dp),
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = statusMessage,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(8.dp))
          LinearProgressIndicator(
            progress = { exportProgress },
            modifier = Modifier.fillMaxWidth(0.85f).height(6.dp).clip(RoundedCornerShape(3.dp))
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "${(exportProgress * 100).toInt()}%",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
          )
        } else {
          // ==========================================
          // INITIAL CONFIGURATION DIALOG
          // ==========================================
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = stringResource(R.string.export_lyrics_video),
              fontWeight = FontWeight.Bold,
              fontSize = 20.sp,
              color = MaterialTheme.colorScheme.onSurface
            )

            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
              Text(
                text = stringResource(R.string.video_duration_chip, payload.durationSeconds),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Aspect Ratio Choice
          Column(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = stringResource(R.string.video_aspect_ratio),
              fontWeight = FontWeight.Medium,
              fontSize = 14.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = selectedAspectRatio == VideoAspectRatio.STORY,
                onClick = { selectedAspectRatio = VideoAspectRatio.STORY },
                label = { Text(stringResource(R.string.video_story_ratio)) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
              )
              FilterChip(
                selected = selectedAspectRatio == VideoAspectRatio.SQUARE,
                onClick = { selectedAspectRatio = VideoAspectRatio.SQUARE },
                label = { Text(stringResource(R.string.video_square_ratio)) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Background Style Choice
          Column(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = stringResource(R.string.player_background_style),
              fontWeight = FontWeight.Medium,
              fontSize = 14.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = selectedBackgroundStyle == LyricsBackgroundStyle.BLUR,
                onClick = { selectedBackgroundStyle = LyricsBackgroundStyle.BLUR },
                label = { Text(stringResource(R.string.player_background_blur)) }
              )
              FilterChip(
                selected = selectedBackgroundStyle == LyricsBackgroundStyle.GRADIENT,
                onClick = { selectedBackgroundStyle = LyricsBackgroundStyle.GRADIENT },
                label = { Text(stringResource(R.string.gradient)) }
              )
              FilterChip(
                selected = selectedBackgroundStyle == LyricsBackgroundStyle.SOLID,
                onClick = { selectedBackgroundStyle = LyricsBackgroundStyle.SOLID },
                label = { Text(stringResource(R.string.player_background_solid)) }
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Animated Karaoke Toggle
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.video_animate_lyrics),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Highlight lines in sync with music",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(
              checked = animateLyrics,
              onCheckedChange = { animateLyrics = it },
              colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
            )
          }

          Spacer(modifier = Modifier.height(20.dp))

          // Action Buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
          ) {
            TextButton(onClick = onDismissRequest) {
              Text(text = stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
              onClick = { startExport() },
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
              Text(text = stringResource(R.string.share))
            }
          }
        }
      }
    }
  }
}

/**
 * Renders the video preview player and direct share buttons for Instagram Story, WhatsApp, and Instagram Direct.
 */
@Composable
private fun VideoPreviewScreen(
  context: Context,
  videoFile: File,
  videoUri: Uri,
  songTitle: String,
  durationSeconds: Float,
  aspectRatio: VideoAspectRatio,
  onDone: () -> Unit,
) {
  var isPlaying by remember { mutableStateOf(true) }

  val exoPlayer = remember(videoFile) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(videoFile.toUri()))
      repeatMode = Player.REPEAT_MODE_ONE
      playWhenReady = true
      prepare()
      addListener(object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }
      })
    }
  }

  DisposableEffect(exoPlayer) {
    onDispose {
      exoPlayer.stop()
      exoPlayer.release()
    }
  }

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.video_ready),
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "$songTitle • " + String.format(java.util.Locale.US, "%.1fs", durationSeconds),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      IconButton(
        onClick = onDone,
        modifier = Modifier.size(36.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.cancel),
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Video Preview Player Box
    val previewHeight = if (aspectRatio == VideoAspectRatio.STORY) 310.dp else 240.dp
    val playerAspectRatio = if (aspectRatio == VideoAspectRatio.STORY) 9f / 16f else 1f

    Box(
      modifier = Modifier
        .height(previewHeight)
        .aspectRatio(playerAspectRatio)
        .clip(RoundedCornerShape(20.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
        .background(Color.Black)
        .clickable {
          if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        },
      contentAlignment = Alignment.Center
    ) {
      AndroidView(
        factory = { ctx ->
          PlayerView(ctx).apply {
            player = exoPlayer
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
          }
        },
        modifier = Modifier.fillMaxSize()
      )

      // Semi-transparent play pause indicator overlay when paused
      if (!isPlaying) {
        Box(
          modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
            modifier = Modifier.size(30.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Modern Social Share Strip (5 circular actions with clean typography)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // 1. Instagram Story
      SocialShareActionItem(
        title = stringResource(R.string.share_ig_story),
        icon = painterResource(R.drawable.ic_instagram_new),
        containerBrush = Brush.linearGradient(
          colors = listOf(Color(0xFF833AB4), Color(0xFFFD1D1D), Color(0xFFFCB045))
        ),
        onClick = { shareToInstagramStory(context, videoUri) }
      )

      // 2. Instagram Direct
      SocialShareActionItem(
        title = stringResource(R.string.share_ig_chat),
        icon = painterResource(R.drawable.send_chat),
        containerColor = Color(0xFF5851DB),
        onClick = { shareToInstagramDirect(context, videoUri) }
      )

      // 3. WhatsApp
      SocialShareActionItem(
        title = stringResource(R.string.share_whatsapp),
        icon = painterResource(R.drawable.ic_whatsapp),
        containerColor = Color(0xFF25D366),
        onClick = { shareToWhatsApp(context, videoUri) }
      )

      // 4. Save to Gallery
      SocialShareActionItem(
        title = stringResource(R.string.save_to_gallery),
        icon = painterResource(R.drawable.download),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        iconTint = MaterialTheme.colorScheme.onSurface,
        onClick = { saveVideoToGallery(context, videoFile) }
      )

      // 5. More Apps
      SocialShareActionItem(
        title = stringResource(R.string.more_apps),
        icon = painterResource(R.drawable.share),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        iconTint = MaterialTheme.colorScheme.onSurface,
        onClick = { shareGeneral(context, videoUri, songTitle) }
      )
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Done Button
    FilledTonalButton(
      onClick = onDone,
      modifier = Modifier
        .fillMaxWidth()
        .height(44.dp),
      shape = RoundedCornerShape(14.dp)
    ) {
      Text(
        text = stringResource(R.string.done),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

@Composable
private fun SocialShareActionItem(
  title: String,
  icon: Painter,
  containerBrush: Brush? = null,
  containerColor: Color? = null,
  iconTint: Color = Color.White,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 4.dp, vertical = 2.dp)
  ) {
    Box(
      modifier = Modifier
        .size(52.dp)
        .clip(CircleShape)
        .then(
          if (containerBrush != null) Modifier.background(containerBrush)
          else Modifier.background(containerColor ?: MaterialTheme.colorScheme.surfaceContainerHighest)
        ),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        painter = icon,
        contentDescription = title,
        tint = iconTint,
        modifier = Modifier.size(24.dp)
      )
    }
    Text(
      text = title,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1
    )
  }
}

private fun shareToInstagramStory(context: Context, videoUri: Uri) {
  val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
    setDataAndType(videoUri, "video/mp4")
    putExtra("interactive_asset_uri", videoUri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  try {
    context.startActivity(intent)
  } catch (_: Exception) {
    val fallback = Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      setPackage("com.instagram.android")
      putExtra(Intent.EXTRA_STREAM, videoUri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
      context.startActivity(fallback)
    } catch (_: Exception) {
      Toast.makeText(context, "Instagram not installed", Toast.LENGTH_SHORT).show()
    }
  }
}

private fun shareToInstagramDirect(context: Context, videoUri: Uri) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "video/mp4"
    setPackage("com.instagram.android")
    putExtra(Intent.EXTRA_STREAM, videoUri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  try {
    context.startActivity(intent)
  } catch (_: Exception) {
    Toast.makeText(context, "Instagram not installed", Toast.LENGTH_SHORT).show()
  }
}

private fun shareToWhatsApp(context: Context, videoUri: Uri) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "video/mp4"
    setPackage("com.whatsapp")
    putExtra(Intent.EXTRA_STREAM, videoUri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  try {
    context.startActivity(intent)
  } catch (_: Exception) {
    try {
      val w4b = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        setPackage("com.whatsapp.w4b")
        putExtra(Intent.EXTRA_STREAM, videoUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(w4b)
    } catch (_: Exception) {
      Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
    }
  }
}

private fun shareGeneral(context: Context, videoUri: Uri, title: String) {
  val shareIntent = Intent(Intent.ACTION_SEND).apply {
    type = "video/mp4"
    putExtra(Intent.EXTRA_STREAM, videoUri)
    putExtra(Intent.EXTRA_SUBJECT, "$title - Lyrics")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_as_video)))
}

private fun saveVideoToGallery(context: Context, videoFile: File) {
  try {
    val filename = "EchoLyrics_${System.currentTimeMillis()}.mp4"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, filename)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EchoMusic")
      }
      val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Cannot insert into MediaStore")
      context.contentResolver.openOutputStream(uri)?.use { out ->
        videoFile.inputStream().use { input -> input.copyTo(out) }
      }
    } else {
      val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "EchoMusic").apply { mkdirs() }
      val dest = File(dir, filename)
      videoFile.copyTo(dest, overwrite = true)
    }
    Toast.makeText(context, context.getString(R.string.video_saved_to_gallery), Toast.LENGTH_SHORT).show()
  } catch (e: Exception) {
    Timber.e(e, "Failed to save video to gallery")
    Toast.makeText(context, "Failed to save to gallery", Toast.LENGTH_SHORT).show()
  }
}
