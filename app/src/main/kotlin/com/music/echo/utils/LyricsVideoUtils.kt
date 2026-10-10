package echo.music.iad1tya.utils

import echo.music.iad1tya.lyrics.LyricsEntry
import echo.music.iad1tya.models.MediaMetadata

/**
 * Encapsulates the selected lyrics range and duration metrics for video/image/text export.
 */
data class LyricsVideoSnippet(
  val selectedEntries: List<LyricsEntry>,
  val startTimeMs: Long,
  val endTimeMs: Long,
) {
  val durationMs: Long
    get() = (endTimeMs - startTimeMs).coerceAtLeast(0L)

  val durationSeconds: Float
    get() = durationMs / 1000f

  val isValidVideoDuration: Boolean
    get() = durationSeconds in 10.0f..60.0f

  val isTooShort: Boolean
    get() = durationSeconds < 10.0f

  val isTooLong: Boolean
    get() = durationSeconds > 60.0f
}

/**
 * Payload carried from lyrics selection to share dialog and export services.
 */
data class LyricsSharePayload(
  val text: String,
  val songTitle: String,
  val artists: String,
  val selectedEntries: List<LyricsEntry>,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val durationSeconds: Float,
  val mediaMetadata: MediaMetadata?,
) {
  val isValidVideoDuration: Boolean
    get() = durationSeconds in 10.0f..60.0f

  val isTooShort: Boolean
    get() = durationSeconds < 10.0f

  val isTooLong: Boolean
    get() = durationSeconds > 60.0f
}

object LyricsVideoUtils {
  const val MIN_DURATION_SECONDS = 10.0f
  const val MAX_DURATION_SECONDS = 60.0f

  /**
   * Computes the exact start timestamp and end timestamp for selected lyrics lines.
   * Enforces that duration is determined by song timings:
   * - Start time: timestamp of the first selected line.
   * - End time: timestamp of the next line, or last word's end time, or fallback.
   */
  fun calculateSelectedSnippet(
    lines: List<LyricsEntry>,
    selectedIndices: Collection<Int>,
    totalSongDurationMs: Long? = null,
  ): LyricsVideoSnippet? {
    if (selectedIndices.isEmpty() || lines.isEmpty()) return null

    val sortedIndices = selectedIndices.sorted()
    val selectedEntries = sortedIndices.mapNotNull { lines.getOrNull(it) }
    if (selectedEntries.isEmpty()) return null

    val firstIndex = sortedIndices.first()
    val lastIndex = sortedIndices.last()
    val startTimeMs = lines.getOrNull(firstIndex)?.time ?: 0L

    val lastEntry = lines.getOrNull(lastIndex)
    val words = lastEntry?.words
    val endTimeMs: Long = when {
      // 1. If last entry has word-level timestamps, use the end time of the last word
      !words.isNullOrEmpty() -> {
        val wordEndMs = (words.last().endTime * 1000.0).toLong()
        maxOf(startTimeMs + 1000L, wordEndMs)
      }
      // 2. If there is a subsequent line, use its start timestamp
      lastIndex + 1 < lines.size && lines[lastIndex + 1].time > startTimeMs -> {
        lines[lastIndex + 1].time
      }
      // 3. Last line of song: if song duration is known, use that (up to max duration)
      totalSongDurationMs != null && totalSongDurationMs > startTimeMs -> {
        minOf(totalSongDurationMs, startTimeMs + (MAX_DURATION_SECONDS * 1000L).toLong())
      }
      // 4. Default fallback: line time + 4 seconds
      else -> {
        (lastEntry?.time ?: startTimeMs) + 4000L
      }
    }

    return LyricsVideoSnippet(
      selectedEntries = selectedEntries,
      startTimeMs = startTimeMs,
      endTimeMs = maxOf(startTimeMs + 1000L, endTimeMs),
    )
  }
}
