package echo.music.iad1tya.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
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
        val songId = doc.id
        when (change.type) {
          DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
            applyRemoteSongLike(doc)
          }
          DocumentChange.Type.REMOVED -> {
            val existing = db.getSongByIdBlocking(songId)
            if (existing != null && existing.song.liked) {
              db.updateLikedStatus(songId, false, null)
              Timber.tag(TAG).d("Unliked song locally from remote change: $songId")
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

  private fun applyRemoteSongLike(doc: DocumentSnapshot) {
    val db = database ?: return
    val songId = doc.getString("id") ?: doc.id
    val title = doc.getString("name") ?: doc.getString("title") ?: "Unknown Song"
    val artist = doc.getString("artist") ?: "Unknown Artist"
    val album = doc.getString("album")
    val duration = (doc.getLong("duration") ?: (doc.get("duration") as? Number)?.toLong())?.toInt() ?: -1

    val imageUrl = extractImageUrl(doc.get("image"))
    val likedAt = doc.getTimestamp("likedAt")?.toDate()?.toInstant()
      ?.atZone(ZoneId.systemDefault())?.toLocalDateTime() ?: LocalDateTime.now()

    ensureSongEntityExists(
      songId = songId,
      title = title,
      artist = artist,
      album = album,
      duration = duration,
      thumbnailUrl = imageUrl,
      liked = true,
      likedDate = likedAt
    )
    Timber.tag(TAG).d("Liked song saved locally from remote: $songId ($title)")
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

  private fun applyRemotePlaylist(doc: DocumentSnapshot) {
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
    val songItems = parseSongItems(rawSongs)

    // Foreign key constraint safety: ensure all songs and artists exist in DB first!
    songItems.forEach { item ->
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

    // Now map them into playlist_song_map
    db.clearPlaylist(playlistId)
    songItems.forEachIndexed { index, item ->
      db.insert(PlaylistSongMap(playlistId = playlistId, songId = item.id, position = index))
    }
    Timber.tag(TAG).d("Updated playlist $playlistId ($name) with ${songItems.size} songs from remote")
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
      val remoteLikedIds = remoteLikesSnap.documents.map { it.id }.toSet()

      // Restore all remote likes to local Room DB
      for (doc in remoteLikesSnap.documents) {
        applyRemoteSongLike(doc)
      }

      // Query all local liked songs
      val localLikedSongs = db.likedSongsByCreateDateAsc().first()
      for (localSong in localLikedSongs) {
        if (!remoteLikedIds.contains(localSong.id)) {
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
        firestore.collection("users").document(user.uid)
          .collection("likes").document(songId)
          .delete()
          .await()
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
          val updatedSongs = existingSongs.filter { (it["id"] as? String) != songId }
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
    val thumbnailUrl: String?
  )

  private fun parseSongItems(rawList: List<*>): List<ParsedSong> {
    return rawList.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val id = map["id"]?.toString() ?: return@mapNotNull null
      val title = map["name"]?.toString() ?: map["title"]?.toString() ?: "Unknown Song"
      val artist = map["artist"]?.toString() ?: "Unknown Artist"
      val album = map["album"]?.toString()
      val duration = (map["duration"] as? Number)?.toInt() ?: -1
      val image = extractImageUrl(map["image"])
      ParsedSong(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        thumbnailUrl = image
      )
    }
  }

  private fun extractImageUrl(imageObj: Any?): String? {
    return when (imageObj) {
      is List<*> -> imageObj.lastOrNull()?.toString() ?: imageObj.firstOrNull()?.toString()
      is String -> imageObj.takeIf { it.isNotBlank() }
      else -> null
    }
  }
}
