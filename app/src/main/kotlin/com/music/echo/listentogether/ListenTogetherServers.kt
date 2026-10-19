package echo.music.iad1tya.listentogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ListenTogetherServer(
  val name: String,
  val url: String,
  val location: String,
  val operator: String
)

object ListenTogetherServers {
  private const val SERVER_JSON_URL =
    "https://raw.githubusercontent.com/EchoMusicApp/Echo-Music/refs/heads/main/app/server.json"

  private const val PRIMARY_SYNC_SERVER_URL =
    "wss://devilmi-vivi-music-listen-together.hf.space/ws"

  private val _servers =
    MutableStateFlow(
      listOf(
        ListenTogetherServer(
          name = "Muse Sync Server",
          url = PRIMARY_SYNC_SERVER_URL,
          location = "Global",
          operator = "Muse"
        )
      )
    )

  val serversFlow: StateFlow<List<ListenTogetherServer>> = _servers

  val servers: List<ListenTogetherServer>
    get() = _servers.value

  init {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(SERVER_JSON_URL).build()
        val response = client.newCall(request).execute()
        response.body?.string()?.let { jsonString ->
          val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
          val name = jsonObject["name"]?.jsonPrimitive?.content ?: "Muse Sync Server"
          var url =
            jsonObject["serverUrl"]?.jsonPrimitive?.content
              ?: PRIMARY_SYNC_SERVER_URL
          val region = jsonObject["region"]?.jsonPrimitive?.content ?: "Global"

          // If remote config still points to blocked metroserverx, retain our working primary server
          if (url.contains("metroserverx")) {
            url = PRIMARY_SYNC_SERVER_URL
          } else if (!url.endsWith("/ws") && !url.contains("/ws")) {
            url = url.trimEnd('/') + "/ws"
          }

          _servers.value =
            listOf(ListenTogetherServer(name = name, url = url, location = region, operator = "Muse"))
        }
      } catch (e: Exception) {
        // Fallback implicitly retained
      }
    }
  }

  val defaultServerUrl: String
    get() = servers.first().url

  fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}
