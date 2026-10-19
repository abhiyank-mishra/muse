package echo.music.iad1tya.listentogether

object ListenTogetherUtils {
  const val BASE_INVITE_URL = "https://muse.abhiyank.in/colab"

  fun createInviteLink(roomCode: String): String {
    val cleanCode = roomCode.trim().uppercase()
    return "$BASE_INVITE_URL?room=$cleanCode"
  }

  fun parseRoomCode(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""

    // 1. Check query parameter ?room=... or &room=...
    val roomMatch = Regex("[?&]room=([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
    if (roomMatch != null) {
      return roomMatch.groupValues[1].uppercase().take(8)
    }

    // 2. Check query parameter ?code=... or &code=...
    val codeMatch = Regex("[?&]code=([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
    if (codeMatch != null) {
      return codeMatch.groupValues[1].uppercase().take(8)
    }

    // 3. Check path segment /colab/...
    val colabMatch = Regex("/colab/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
    if (colabMatch != null) {
      return colabMatch.groupValues[1].uppercase().take(8)
    }

    // 4. Check path segment /listen/...
    val listenMatch = Regex("/listen/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
    if (listenMatch != null) {
      return listenMatch.groupValues[1].uppercase().take(8)
    }

    // 5. Raw input: filter alphanumeric only, max 8 chars
    return trimmed.filter { it.isLetterOrDigit() }.uppercase().take(8)
  }
}
