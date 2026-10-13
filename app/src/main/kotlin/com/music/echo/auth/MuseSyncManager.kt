package echo.music.iad1tya.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.SongArtistMap
import echo.music.iad1tya.db.entities.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

object MuseSyncManager {
  private const val TAG = "MuseSyncManager"

  sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Resolving(val current: Int, val total: Int) : SyncStatus()
    data class Completed(val timestamp: Long) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
  }

  private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
  private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
  private val scope = CoroutineScope(Dispatchers.IO + Job())

  private var database: MusicDatabase? = null
  private var appContext: Context? = null

  private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
  val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

  private val _lastSyncedTime = MutableStateFlow<Long>(0L)
  val lastSyncedTime: StateFlow<Long> = _lastSyncedTime.asStateFlow()

  private var likesListener: ListenerRegistration? = null
  private var playlistsListener: ListenerRegistration? = null

  // Guard flag to avoid re-triggering remote uploads when applying changes from remote
  private val isApplyingRemoteUpdate = AtomicBoolean(false)
  private var activeUserId: String? = null

  // LRU cache for resolved YouTube songs to minimize network lookups
  private val trackResolutionCache = object : LinkedHashMap<String, SongItem>(500, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongItem>?): Boolean = size > 500
  }
  private val resolutionSemaphore = Semaphore(3)

  fun init(context: Context, db: MusicDatabase) {
    appContext = context.applicationContext
    database = db
    Timber.tag(TAG).d("Initialized with database")
  }

  fun start(userId: String) {
    if (activeUserId == userId && likesListener != null && playlistsListener != null) {
      Timber.tag(TAG).d("Already listening for user $userId")
      return
    }

    stop()
    activeUserId = userId
    Timber.tag(TAG).d("Starting sync for user: $userId")

    setupLikesListener(userId)
    setupPlaylistsListener(userId)

    scope.launch {
      reconcileAll(userId)
    }
  }

  fun stop() {
    likesListener?.remove()
    likesListener = null
    playlistsListener?.remove()
    playlistsListener = null
    activeUserId = null
    _syncStatus.value = SyncStatus.Idle
    Timber.tag(TAG).d("Sync stopped and listeners removed")
  }

  fun forceSyncNow() {
    val user = auth.currentUser ?: return
    scope.launch {
      reconcileAll(user.uid)
    }
  }

  /* -------------------------------------------------------------
   * YouTube Track Resolution Helpers
   * ------------------------------------------------------------- */

  fun isYouTubeVideoId(id: String): Boolean {
    return id.length == 11 && id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
  }

  private fun sanitizeTitle(title: String): String {
    return title
      .replace(Regex("\\(From \"[^\"]+\"\\)", RegexOption.IGNORE_CASE), "")
      .replace(Regex("\\(Original Motion Picture Track\\)", RegexOption.IGNORE_CASE), "")
      .replace(Regex("\\[Official Video\\]|\\[Audio\\]|\\(Official Audio\\)", RegexOption.IGNORE_CASE), "")
      .replace(Regex("\\(feat\\.[^)]+\\)", RegexOption.IGNORE_CASE), "")
      .trim()
  }

  suspend fun resolveToYouTube(title: String, artist: String, originalDuration: Int = -1): SongItem? {
    val cleanTitle = sanitizeTitle(title)
    val cleanArtist = if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) "" else artist
    val cacheKey = "$cleanTitle||$cleanArtist"

    synchronized(trackResolutionCache) {
      trackResolutionCache[cacheKey]?.let { return it }
    }

    return try {
      resolutionSemaphore.withPermit {
        synchronized(trackResolutionCache) {
          trackResolutionCache[cacheKey]?.let { return@withPermit it }
        }

        val query = "$cleanTitle $cleanArtist".trim()
        val searchResult = YouTube.search(
          query,
          YouTube.SearchFilter.FILTER_SONG
        ).getOrNull()

        val songItems = searchResult?.items?.filterIsInstance<SongItem>().orEmpty()
        val bestMatch = if (originalDuration > 0 && songItems.size > 1) {
          songItems.minByOrNull { item ->
            val d = item.duration ?: 0
            if (d > 0) Math.abs(d - originalDuration) else 9999
          } ?: songItems.firstOrNull()
        } else {
          songItems.firstOrNull()
        }

        if (bestMatch != null) {
          synchronized(trackResolutionCache) {
            trackResolutionCache[cacheKey] = bestMatch
          }
        }
        bestMatch
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Error resolving track to YouTube: $title by $artist")
      null
    }
  }

  /* -------------------------------------------------------------
   * Remote Snapshot Listeners
   * ------------------------------------------------------------- */

  private fun setupLikesListener(userId: String) {
    likesListener?.remove()
    likesListener = firestore.collection("users").document(userId)
      .collection("likes")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Timber.tag(TAG).e(error, "Likes snapshot error")
          return@addSnapshotListener
        }
        if (snapshot == null) return@addSnapshotListener

        scope.launch {
          handleLikesChanges(snapshot.documentChanges)
        }
      }
  }

  private suspend fun handleLikesChanges(changes: List<DocumentChange>) {
    val db = database ?: return
    isApplyingRemoteUpdate.set(true)
    try {
      for (change in changes) {
        val doc = change.document
        when (change.type) {
          DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
            applyRemoteSongLike(doc)
          }
          DocumentChange.Type.REMOVED -> {
            val rawId = doc.id
            val youtubeId = doc.getString("youtubeId")
            val targetSongId = youtubeId ?: rawId
            val existing = db.getSongByIdBlocking(targetSongId)
              ?: if (youtubeId != null) db.getSongByIdBlocking(rawId) else null
            if (existing != null && existing.song.liked) {
              db.updateLikedStatus(existing.song.id, false, null)
              Timber.tag(TAG).d("Unliked song locally from remote change: ${existing.song.id}")
            }
          }
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Error handling likes snapshot changes")
    } finally {
      isApplyingRemoteUpdate.set(false)
    }
  }

  private suspend fun applyRemoteSongLike(doc: DocumentSnapshot) {
    val rawId = doc.getString("id") ?: doc.id
    val youtubeId = doc.getString("youtubeId")
    val title = doc.getString("name") ?: doc.getString("title") ?: "Unknown Song"
    val artist = doc.getString("artist") ?: "Unknown Artist"
    val album = doc.getString("album")
    val duration = (doc.getLong("duration") ?: (doc.get("duration") as? Number)?.toLong())?.toInt() ?: -1

    val imageUrl = extractImageUrl(doc.get("image"))
    val likedAt = doc.getTimestamp("likedAt")?.toDate()?.toInstant()
      ?.atZone(ZoneId.systemDefault())?.toLocalDateTime() ?: LocalDateTime.now()

    val finalSongId: String
    val finalTitle: String
    val finalArtist: String
    val finalAlbum: String?
    val finalDuration: Int
    val finalThumbnail: String?

    if (!youtubeId.isNullOrBlank() && isYouTubeVideoId(youtubeId)) {
      // Already has verified YouTube ID
      finalSongId = youtubeId
      finalTitle = title
      finalArtist = artist
      finalAlbum = album
      finalDuration = duration
      finalThumbnail = imageUrl
    } else if (isYouTubeVideoId(rawId) && doc.getString("source") != "saavn") {
      // rawId is already a valid YouTube video ID
      finalSongId = rawId
      finalTitle = title
      finalArtist = artist
      finalAlbum = album
      finalDuration = duration
      finalThumbnail = imageUrl
    } else {
      // JioSaavn or external song: Resolve via YouTube Music!
      val resolvedSong = resolveToYouTube(title, artist, duration)
      if (resolvedSong != null) {
        finalSongId = resolvedSong.id
        finalTitle = resolvedSong.title
        finalArtist = resolvedSong.artists.joinToString(", ") { it.name }.ifBlank { artist }
        finalAlbum = resolvedSong.album?.name ?: album
        finalDuration = resolvedSong.duration ?: duration
        finalThumbnail = resolvedSong.thumbnail.ifBlank { imageUrl }

        // Backfill resolved YouTube ID to Firestore asynchronously
        try {
          doc.reference.set(
            mapOf("youtubeId" to resolvedSong.id, "source" to "youtube"),
            SetOptions.merge()
          )
        } catch (e: Exception) {
          Timber.tag(TAG).w(e, "Failed to backfill youtubeId to Firestore doc ${doc.id}")
        }
      } else {
        Timber.tag(TAG).w("Could not resolve Saavn track to YouTube: '$title' by '$artist'. Skipping.")
        return
      }
    }

    ensureSongEntityExists(
      songId = finalSongId,
      title = finalTitle,
      artist = finalArtist,
      album = finalAlbum,
      duration = finalDuration,
      thumbnailUrl = finalThumbnail,
      liked = true,
      likedDate = likedAt
    )
    Timber.tag(TAG).d("Liked song saved locally with YouTube ID: $finalSongId ($finalTitle)")
  }

  private fun setupPlaylistsListener(userId: String) {
    playlistsListener?.remove()
    playlistsListener = firestore.collection("users").document(userId)
      .collection("playlists")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          Timber.tag(TAG).e(error, "Playlists snapshot error")
          return@addSnapshotListener
        }
        if (snapshot == null) return@addSnapshotListener

        scope.launch {
          handlePlaylistsChanges(snapshot.documentChanges)
        }
      }
  }

  private suspend fun handlePlaylistsChanges(changes: List<DocumentChange>) {
    val db = database ?: return
    isApplyingRemoteUpdate.set(true)
    try {
      for (change in changes) {
        val doc = change.document
        val playlistId = doc.id
        when (change.type) {
          DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
            applyRemotePlaylist(doc)
          }
          DocumentChange.Type.REMOVED -> {
            val existing = db.getPlaylistByIdBlocking(playlistId)
            if (existing != null) {
              db.clearPlaylist(playlistId)
              db.delete(existing.playlist)
              Timber.tag(TAG).d("Deleted playlist locally from remote change: $playlistId")
            }
          }
        }
      }
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Error handling playlists snapshot changes")
    } finally {
      isApplyingRemoteUpdate.set(false)
    }
  }

  private suspend fun applyRemotePlaylist(doc: DocumentSnapshot) {
    val db = database ?: return
    val playlistId = doc.id
    val name = doc.getString("name") ?: "Untitled Playlist"
    val isActive = doc.getBoolean("is_active") ?: true

    if (!isActive) {
      val existing = db.getPlaylistByIdBlocking(playlistId)
      if (existing != null) {
        db.clearPlaylist(playlistId)
        db.delete(existing.playlist)
      }
      return
    }

    val existing = db.getPlaylistByIdBlocking(playlistId)
    if (existing == null) {
      db.insert(
        PlaylistEntity(
          id = playlistId,
          name = name,
          isEditable = true,
          isLocal = false
        )
      )
    } else if (existing.playlist.name != name) {
      db.update(existing.playlist.copy(name = name, lastUpdateTime = LocalDateTime.now()))
    }

    val rawSongs = doc.get("songs") as? List<*> ?: emptyList<Any>()
    val rawSongItems = parseSongItems(rawSongs)

    val resolvedItems = mutableListOf<ParsedSong>()
    var hasUpdatesForRemote = false
    val updatedRemoteSongsList = rawSongs.filterIsInstance<Map<String, Any>>().map { HashMap(it) }.toMutableList()

    rawSongItems.forEachIndexed { index, item ->
      val effectiveItem: ParsedSong
      if (!item.youtubeId.isNullOrBlank() && isYouTubeVideoId(item.youtubeId)) {
        effectiveItem = item.copy(id = item.youtubeId)
      } else if (isYouTubeVideoId(item.id)) {
        effectiveItem = item
      } else {
        // Resolve JioSaavn track to YouTube
        val ytSong = resolveToYouTube(item.title, item.artist, item.duration)
        if (ytSong != null) {
          val ytArtist = ytSong.artists.joinToString(", ") { it.name }.ifBlank { item.artist }
          effectiveItem = item.copy(
            id = ytSong.id,
            title = ytSong.title,
            artist = ytArtist,
            album = ytSong.album?.name ?: item.album,
            duration = ytSong.duration ?: item.duration,
            thumbnailUrl = ytSong.thumbnail.ifBlank { item.thumbnailUrl },
            youtubeId = ytSong.id
          )
          if (index < updatedRemoteSongsList.size) {
            val updatedMap = updatedRemoteSongsList[index]
            updatedMap["youtubeId"] = ytSong.id
            updatedMap["source"] = "youtube"
            hasUpdatesForRemote = true
          }
        } else {
          effectiveItem = item
        }
      }
      resolvedItems.add(effectiveItem)
    }

    // Backfill Firestore playlist if any tracks were resolved
    if (hasUpdatesForRemote) {
      try {
        doc.reference.update("songs", updatedRemoteSongsList)
      } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed to backfill resolved playlist tracks in Firestore")
      }
    }

    // Foreign key constraint safety: ensure all songs and artists exist in DB first!
    resolvedItems.forEach { item ->
      ensureSongEntityExists(
        songId = item.id,
        title = item.title,
        artist = item.artist,
        album = item.album,
        duration = item.duration,
        thumbnailUrl = item.thumbnailUrl,
        liked = false,
        likedDate = null
      )
    }

    // Map into playlist_song_map
    db.clearPlaylist(playlistId)
    resolvedItems.forEachIndexed { index, item ->
      db.insert(PlaylistSongMap(playlistId = playlistId, songId = item.id, position = index))
    }
    Timber.tag(TAG).d("Updated playlist $playlistId ($name) with ${resolvedItems.size} YouTube-resolved songs from remote")
  }

  /* -------------------------------------------------------------
   * Full Bidirectional Reconciliation (Restore Backup + Push Local)
   * ------------------------------------------------------------- */

  suspend fun reconcileAll(userId: String) {
    val db = database ?: return
    _syncStatus.value = SyncStatus.Syncing
    Timber.tag(TAG).d("Starting full reconciliation for user $userId")

    try {
      isApplyingRemoteUpdate.set(true)

      // 1. Reconcile Liked Songs
      val likesCollection = firestore.collection("users").document(userId).collection("likes")
      val remoteLikesSnap = likesCollection.get().await()
      val totalLikes = remoteLikesSnap.documents.size

      // Restore all remote likes to local Room DB with YouTube resolution
      for ((index, doc) in remoteLikesSnap.documents.withIndex()) {
        _syncStatus.value = SyncStatus.Resolving(index + 1, totalLikes)
        applyRemoteSongLike(doc)
      }

      // Collect all known remote YouTube IDs and document IDs
      val remoteDocIds = remoteLikesSnap.documents.map { it.id }.toSet()
      val remoteYouTubeIds = remoteLikesSnap.documents.mapNotNull {
        it.getString("youtubeId") ?: if (isYouTubeVideoId(it.id)) it.id else null
      }.toSet()

      // Query all local liked songs
      val localLikedSongs = db.likedSongsByCreateDateAsc().first()
      for (localSong in localLikedSongs) {
        if (!remoteDocIds.contains(localSong.id) && !remoteYouTubeIds.contains(localSong.id)) {
          // Push local like to remote cloud backup
          uploadSongLikeToFirestore(userId, localSong.song)
        }
      }

      // 2. Reconcile Custom Playlists
      val playlistsCollection = firestore.collection("users").document(userId).collection("playlists")
      val remotePlaylistsSnap = playlistsCollection.get().await()
      val remotePlaylistIds = remotePlaylistsSnap.documents.map { it.id }.toSet()

      // Restore all remote playlists to local Room DB
      for (doc in remotePlaylistsSnap.documents) {
        applyRemotePlaylist(doc)
      }

      // Query all local custom editable playlists
      val localPlaylists = db.editablePlaylistsByCreateDateAsc().first()
      for (localPlaylist in localPlaylists) {
        val pid = localPlaylist.id
        if (pid == PlaylistEntity.LIKED_PLAYLIST_ID || pid == PlaylistEntity.DOWNLOADED_PLAYLIST_ID) {
          continue
        }
        if (!remotePlaylistIds.contains(pid)) {
          // Push local playlist to cloud
          uploadPlaylistToFirestore(userId, localPlaylist)
        }
      }

      val now = System.currentTimeMillis()
      _lastSyncedTime.value = now
      _syncStatus.value = SyncStatus.Completed(now)
      Timber.tag(TAG).d("Full reconciliation completed successfully")
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Reconciliation failed")
      _syncStatus.value = SyncStatus.Error(e.message ?: "Sync failed")
    } finally {
      isApplyingRemoteUpdate.set(false)
    }
  }

  /* -------------------------------------------------------------
   * Outbound Sync APIs (Local APK -> Remote Cloud)
   * ------------------------------------------------------------- */

  fun syncSongLike(song: SongEntity) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      uploadSongLikeToFirestore(user.uid, song)
    }
  }

  fun syncSongUnlike(songId: String) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        val likesCol = firestore.collection("users").document(user.uid).collection("likes")
        // Delete document by docId
        likesCol.document(songId).delete().await()

        // Also delete any document whose backfilled youtubeId matches songId
        val matchingDocs = likesCol.whereEqualTo("youtubeId", songId).get().await()
        for (doc in matchingDocs.documents) {
          doc.reference.delete().await()
        }
        Timber.tag(TAG).d("Deleted remote like for $songId")
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to delete remote like for $songId")
      }
    }
  }

  fun syncCreatePlaylist(playlist: PlaylistEntity) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        val data = hashMapOf<String, Any>(
          "name" to playlist.name,
          "songs" to emptyList<Map<String, Any>>(),
          "is_active" to true,
          "createdAt" to FieldValue.serverTimestamp()
        )
        firestore.collection("users").document(user.uid)
          .collection("playlists").document(playlist.id)
          .set(data, SetOptions.merge())
          .await()
        Timber.tag(TAG).d("Created remote playlist ${playlist.id} (${playlist.name})")
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to create remote playlist ${playlist.id}")
      }
    }
  }

  fun syncRenamePlaylist(playlistId: String, newName: String) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        firestore.collection("users").document(user.uid)
          .collection("playlists").document(playlistId)
          .update("name", newName)
          .await()
        Timber.tag(TAG).d("Renamed remote playlist $playlistId to $newName")
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to rename remote playlist $playlistId")
      }
    }
  }

  fun syncDeletePlaylist(playlistId: String) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        firestore.collection("users").document(user.uid)
          .collection("playlists").document(playlistId)
          .delete()
          .await()
        Timber.tag(TAG).d("Deleted remote playlist $playlistId")
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to delete remote playlist $playlistId")
      }
    }
  }

  fun syncAddSongsToPlaylist(playlistId: String, songIds: List<String>) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        val db = database ?: return@launch
        val songMaps = songIds.mapNotNull { songId ->
          val fullSong = db.getSongByIdBlocking(songId) ?: return@mapNotNull null
          val artist = fullSong.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
          hashMapOf<String, Any>(
            "id" to fullSong.song.id,
            "youtubeId" to fullSong.song.id,
            "source" to "youtube",
            "name" to fullSong.song.title,
            "title" to fullSong.song.title,
            "artist" to artist,
            "album" to (fullSong.song.albumName ?: ""),
            "duration" to fullSong.song.duration,
            "image" to listOfNotNull(fullSong.thumbnailUrl)
          )
        }
        if (songMaps.isEmpty()) return@launch

        firestore.collection("users").document(user.uid)
          .collection("playlists").document(playlistId)
          .update("songs", FieldValue.arrayUnion(*songMaps.toTypedArray()))
          .await()
        Timber.tag(TAG).d("Added ${songMaps.size} songs to remote playlist $playlistId")
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to add songs to remote playlist $playlistId")
      }
    }
  }

  fun syncRemoveSongFromPlaylist(playlistId: String, songId: String) {
    if (isApplyingRemoteUpdate.get()) return
    val user = auth.currentUser ?: return
    scope.launch {
      try {
        val docRef = firestore.collection("users").document(user.uid)
          .collection("playlists").document(playlistId)
        val snap = docRef.get().await()
        if (snap.exists()) {
          val existingSongs = (snap.get("songs") as? List<*>)
            ?.filterIsInstance<Map<String, Any>>() ?: emptyList()
          val updatedSongs = existingSongs.filter {
            (it["id"] as? String) != songId && (it["youtubeId"] as? String) != songId
          }
          docRef.update("songs", updatedSongs).await()
          Timber.tag(TAG).d("Removed song $songId from remote playlist $playlistId")
        }
      } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to remove song $songId from remote playlist $playlistId")
      }
    }
  }

  /* -------------------------------------------------------------
   * Internal DB & Conversion Helpers
   * ------------------------------------------------------------- */

  private suspend fun uploadSongLikeToFirestore(userId: String, song: SongEntity) {
    try {
      val artistName = getArtistNameForSong(song.id)
      val data = hashMapOf<String, Any>(
        "id" to song.id,
        "youtubeId" to song.id,
        "source" to "youtube",
        "name" to song.title,
        "title" to song.title,
        "artist" to artistName,
        "album" to (song.albumName ?: ""),
        "duration" to song.duration,
        "image" to listOfNotNull(song.thumbnailUrl),
        "likedAt" to FieldValue.serverTimestamp()
      )
      firestore.collection("users").document(userId)
        .collection("likes").document(song.id)
        .set(data, SetOptions.merge())
        .await()
      Timber.tag(TAG).d("Uploaded like to Firestore: ${song.id} (${song.title})")
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to upload song like to Firestore: ${song.id}")
    }
  }

  private suspend fun uploadPlaylistToFirestore(userId: String, playlist: echo.music.iad1tya.db.entities.Playlist) {
    try {
      val db = database ?: return
      val playlistSongs = db.playlistSongs(playlist.id).first()
      val songsData = playlistSongs.map { ps ->
        val full = ps.song
        val artist = full.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
        hashMapOf<String, Any>(
          "id" to full.song.id,
          "youtubeId" to full.song.id,
          "source" to "youtube",
          "name" to full.song.title,
          "title" to full.song.title,
          "artist" to artist,
          "album" to (full.song.albumName ?: ""),
          "duration" to full.song.duration,
          "image" to listOfNotNull(full.thumbnailUrl)
        )
      }

      val data = hashMapOf<String, Any>(
        "name" to playlist.playlist.name,
        "songs" to songsData,
        "is_active" to true,
        "createdAt" to FieldValue.serverTimestamp()
      )
      firestore.collection("users").document(userId)
        .collection("playlists").document(playlist.id)
        .set(data, SetOptions.merge())
        .await()
      Timber.tag(TAG).d("Uploaded local playlist ${playlist.id} to Firestore")
    } catch (e: Exception) {
      Timber.tag(TAG).e(e, "Failed to upload playlist ${playlist.id} to Firestore")
    }
  }

  private fun ensureSongEntityExists(
    songId: String,
    title: String,
    artist: String,
    album: String?,
    duration: Int,
    thumbnailUrl: String?,
    liked: Boolean,
    likedDate: LocalDateTime?
  ) {
    val db = database ?: return
    val existing = db.getSongByIdBlocking(songId)
    if (existing == null) {
      val songEntity = SongEntity(
        id = songId,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        albumName = album,
        liked = liked,
        likedDate = likedDate,
        inLibrary = if (liked) likedDate ?: LocalDateTime.now() else null
      )
      db.insert(songEntity)

      if (artist.isNotBlank() && artist != "Unknown Artist") {
        val artistId = db.artistByName(artist)?.id ?: ArtistEntity.generateArtistId()
        db.insert(ArtistEntity(id = artistId, name = artist))
        db.insert(SongArtistMap(songId = songId, artistId = artistId, position = 0))
      }
    } else {
      if (liked && !existing.song.liked) {
        db.updateLikedStatus(songId, true, likedDate ?: LocalDateTime.now())
      } else if (!liked && existing.song.liked) {
        db.updateLikedStatus(songId, false, null)
      }
      if (existing.song.thumbnailUrl.isNullOrBlank() && !thumbnailUrl.isNullOrBlank()) {
        db.update(existing.song.copy(thumbnailUrl = thumbnailUrl))
      }
    }
  }

  private fun getArtistNameForSong(songId: String): String {
    val db = database ?: return "Unknown Artist"
    val song = db.getSongByIdBlocking(songId)
    val names = song?.artists?.map { it.name }?.filter { it.isNotBlank() }
    return if (!names.isNullOrEmpty()) names.joinToString(", ") else "Unknown Artist"
  }

  private data class ParsedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Int,
    val thumbnailUrl: String?,
    val youtubeId: String? = null
  )

  private fun parseSongItems(rawList: List<*>): List<ParsedSong> {
    return rawList.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val id = map["id"]?.toString() ?: return@mapNotNull null
      val title = map["name"]?.toString() ?: map["title"]?.toString() ?: "Unknown Song"
      val artist = map["artist"]?.toString() ?: "Unknown Artist"
      val album = map["album"]?.toString()
      val duration = (map["duration"] as? Number)?.toInt() ?: -1
      val imageUrl = extractImageUrl(map["image"])
      val youtubeId = map["youtubeId"]?.toString()
      ParsedSong(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        thumbnailUrl = imageUrl,
        youtubeId = youtubeId
      )
    }
  }

  private fun extractImageUrl(imageField: Any?): String? {
    return when (imageField) {
      is String -> imageField
      is List<*> -> {
        imageField.lastOrNull()?.toString()
          ?: imageField.firstOrNull()?.toString()
      }
      is Map<*, *> -> {
        imageField["url"]?.toString() ?: imageField["link"]?.toString()
      }
      else -> null
    }
  }
}
