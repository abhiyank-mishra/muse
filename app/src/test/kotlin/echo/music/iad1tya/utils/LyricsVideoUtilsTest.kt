package echo.music.iad1tya.utils

import echo.music.iad1tya.lyrics.LyricsEntry
import echo.music.iad1tya.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsVideoUtilsTest {

  @Test
  fun testEmptySelectionReturnsNull() {
    val lines = listOf(LyricsEntry(time = 1000L, text = "Line 1"))
    val result = LyricsVideoUtils.calculateSelectedSnippet(lines, emptyList())
    assertNull(result)
  }

  @Test
  fun testDurationUnder10SecondsIsInvalid() {
    val lines = listOf(
      LyricsEntry(time = 10_000L, text = "Line 1"),
      LyricsEntry(time = 14_000L, text = "Line 2"),
    )
    val snippet = LyricsVideoUtils.calculateSelectedSnippet(lines, listOf(0))
    assertNotNull(snippet)
    assertEquals(10_000L, snippet!!.startTimeMs)
    assertEquals(14_000L, snippet.endTimeMs)
    assertEquals(4.0f, snippet.durationSeconds, 0.01f)
    assertTrue(snippet.isTooShort)
    assertFalse(snippet.isValidVideoDuration)
  }

  @Test
  fun testDurationBetween10And60SecondsIsValid() {
    val lines = listOf(
      LyricsEntry(time = 20_000L, text = "First line"),
      LyricsEntry(time = 30_000L, text = "Second line"),
      LyricsEntry(time = 42_500L, text = "Third line"),
      LyricsEntry(time = 50_000L, text = "Fourth line"),
    )
    // Select lines 0, 1, 2 (span: 20s to 50s -> 30s)
    val snippet = LyricsVideoUtils.calculateSelectedSnippet(lines, listOf(0, 1, 2))
    assertNotNull(snippet)
    assertEquals(20_000L, snippet!!.startTimeMs)
    assertEquals(50_000L, snippet.endTimeMs)
    assertEquals(30.0f, snippet.durationSeconds, 0.01f)
    assertTrue(snippet.isValidVideoDuration)
    assertFalse(snippet.isTooShort)
    assertFalse(snippet.isTooLong)
  }

  @Test
  fun testDurationOver60SecondsIsInvalid() {
    val lines = listOf(
      LyricsEntry(time = 10_000L, text = "Start"),
      LyricsEntry(time = 75_000L, text = "End"),
    )
    val snippet = LyricsVideoUtils.calculateSelectedSnippet(lines, listOf(0, 1))
    assertNotNull(snippet)
    assertEquals(10_000L, snippet!!.startTimeMs)
    // End line + 4s fallback = 79s
    assertEquals(79_000L, snippet.endTimeMs)
    assertEquals(69.0f, snippet.durationSeconds, 0.01f)
    assertTrue(snippet.isTooLong)
    assertFalse(snippet.isValidVideoDuration)
  }

  @Test
  fun testWordLevelTimestampEndDuration() {
    val words = listOf(
      WordTimestamp("Hello", 20.0, 21.0),
      WordTimestamp("World", 21.5, 38.5),
    )
    val lines = listOf(
      LyricsEntry(time = 20_000L, text = "Hello World", words = words),
    )
    val snippet = LyricsVideoUtils.calculateSelectedSnippet(lines, listOf(0))
    assertNotNull(snippet)
    assertEquals(20_000L, snippet!!.startTimeMs)
    assertEquals(38_500L, snippet.endTimeMs)
    assertEquals(18.5f, snippet.durationSeconds, 0.01f)
    assertTrue(snippet.isValidVideoDuration)
  }
}
