package echo.music.iad1tya.auth

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import echo.music.iad1tya.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.withContext

object MuseAuthManager {
  private const val PREFS_NAME = "muse_auth_preferences"
  private const val KEY_GUEST_PLAYS = "muse_guest_plays"
  const val GUEST_PLAY_LIMIT = 5

  private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
  private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
  private val scope = CoroutineScope(Dispatchers.IO)

  private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
  val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

  private val _guestPlayCount = MutableStateFlow(0)
  val guestPlayCount: StateFlow<Int> = _guestPlayCount.asStateFlow()

  private val _showLoginDialog = MutableStateFlow(false)
  val showLoginDialog: StateFlow<Boolean> = _showLoginDialog.asStateFlow()

  private val _isGuestLimitReached = MutableStateFlow(false)
  val isGuestLimitReached: StateFlow<Boolean> = _isGuestLimitReached.asStateFlow()

  private val _todayPlayCount = MutableStateFlow(0)
  val todayPlayCount: StateFlow<Int> = _todayPlayCount.asStateFlow()

  private val _currentStreak = MutableStateFlow(0)
  val currentStreak: StateFlow<Int> = _currentStreak.asStateFlow()

  private val _customUsername = MutableStateFlow<String?>(null)
  val customUsername: StateFlow<String?> = _customUsername.asStateFlow()

  private val _fcmToken = MutableStateFlow<String?>(null)
  val fcmToken: StateFlow<String?> = _fcmToken.asStateFlow()

  private var userDocListener: ListenerRegistration? = null

  fun init(context: Context) {
    _currentUser.value = auth.currentUser
    val prefs = getPrefs(context)
    val plays = prefs.getInt(KEY_GUEST_PLAYS, 0)
    _guestPlayCount.value = plays
    if (auth.currentUser == null && plays >= GUEST_PLAY_LIMIT) {
      _isGuestLimitReached.value = true
    }

    auth.currentUser?.let { user ->
      listenToUserDoc(user.uid)
    }

    auth.addAuthStateListener { firebaseAuth ->
      val user = firebaseAuth.currentUser
      _currentUser.value = user
      if (user != null) {
        _isGuestLimitReached.value = false
        _showLoginDialog.value = false
        resetGuestPlayCount(context)
        syncUserProfile(user)
        syncFcmToken(user.uid)
        listenToUserDoc(user.uid)
      } else {
        userDocListener?.remove()
        userDocListener = null
        _todayPlayCount.value = 0
        _currentStreak.value = 0
        _customUsername.value = null
        val currentPlays = getPrefs(context).getInt(KEY_GUEST_PLAYS, 0)
        _guestPlayCount.value = currentPlays
        if (currentPlays >= GUEST_PLAY_LIMIT) {
          _isGuestLimitReached.value = true
        }
      }
    }
  }

  private fun listenToUserDoc(userId: String) {
    userDocListener?.remove()
    val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
    val yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1).toString()
    userDocListener = firestore.collection("users").document(userId)
      .addSnapshotListener { snapshot, error ->
        if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

        val name = snapshot.getString("displayName") ?: snapshot.getString("name")
        if (!name.isNullOrBlank()) {
          _customUsername.value = name
        }

        val playStats = snapshot.get("playStats") as? Map<*, *>
        val date = playStats?.get("date") as? String
        val count = (playStats?.get("count") as? Number)?.toInt() ?: 0
        val streak = (playStats?.get("streak") as? Number)?.toInt() ?: 1

        if (date == today) {
          _todayPlayCount.value = count
          _currentStreak.value = streak
        } else if (date == yesterday) {
          _todayPlayCount.value = 0
          _currentStreak.value = streak
        } else {
          _todayPlayCount.value = 0
          _currentStreak.value = if (date == null) 0 else 0
        }
      }
  }

  fun updateUsername(newName: String, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
    val user = auth.currentUser
    if (user == null) {
      onError(Exception("User not logged in"))
      return
    }

    val profileUpdates = UserProfileChangeRequest.Builder()
      .setDisplayName(newName)
      .build()

    user.updateProfile(profileUpdates).addOnCompleteListener { task ->
      if (task.isSuccessful) {
        _customUsername.value = newName
        scope.launch {
          try {
            firestore.collection("users").document(user.uid)
              .set(mapOf("displayName" to newName, "name" to newName), SetOptions.merge())
              .await()
            withContext(Dispatchers.Main) {
              onSuccess()
            }
          } catch (e: Exception) {
            withContext(Dispatchers.Main) {
              onSuccess()
            }
          }
        }
      } else {
        onError(task.exception ?: Exception("Failed to update profile"))
      }
    }
  }

  fun getGoogleSignInClient(context: Context): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
      .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
      .requestEmail()
      .requestProfile()
      .build()
    return GoogleSignIn.getClient(context, gso)
  }

  fun handleGoogleSignInResult(account: GoogleSignInAccount, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
    val idToken = account.idToken
    if (idToken == null) {
      onError(Exception("Google ID Token is null"))
      return
    }

    val credential = GoogleAuthProvider.getCredential(idToken, null)
    auth.signInWithCredential(credential)
      .addOnSuccessListener { result ->
        val user = result.user
        if (user != null) {
          _currentUser.value = user
          _showLoginDialog.value = false
          _isGuestLimitReached.value = false
          syncUserProfile(user)
          syncFcmToken(user.uid)
          onSuccess()
        }
      }
      .addOnFailureListener { e ->
        Timber.e(e, "Firebase Google sign-in failed")
        onError(e)
      }
  }

  fun signOut(context: Context, onComplete: () -> Unit = {}) {
    userDocListener?.remove()
    userDocListener = null
    _customUsername.value = null
    _todayPlayCount.value = 0
    _currentStreak.value = 0
    auth.signOut()
    try {
      getGoogleSignInClient(context).signOut().addOnCompleteListener {
        _currentUser.value = null
        onComplete()
      }
    } catch (e: Exception) {
      _currentUser.value = null
      onComplete()
    }
  }

  fun triggerLoginDialog(limitReached: Boolean = false) {
    if (auth.currentUser == null) {
      _isGuestLimitReached.value = limitReached
      _showLoginDialog.value = true
    }
  }

  fun dismissLoginDialog() {
    // Only dismiss if guest limit is not active
    if (!_isGuestLimitReached.value) {
      _showLoginDialog.value = false
    }
  }

  fun forceDismissDialog() {
    _showLoginDialog.value = false
  }

  /**
   * Called when a song is played.
   * Returns true if guest play limit is reached (caller should pause music).
   */
  fun onSongPlayed(
    context: Context,
    songId: String,
    title: String,
    artist: String,
    artworkUrl: String?,
    album: String?,
    duration: Long
  ): Boolean {
    val user = auth.currentUser
    if (user != null) {
      // User is logged in: record to Firestore
      scope.launch {
        recordUserSongPlay(user.uid, songId, title, artist, artworkUrl, album, duration)
      }
      return false
    } else {
      // Guest user: increment counter
      val prefs = getPrefs(context)
      val currentCount = prefs.getInt(KEY_GUEST_PLAYS, 0) + 1
      prefs.edit().putInt(KEY_GUEST_PLAYS, currentCount).apply()
      _guestPlayCount.value = currentCount

      if (currentCount >= GUEST_PLAY_LIMIT) {
        _isGuestLimitReached.value = true
        _showLoginDialog.value = true
        return true
      }
      return false
    }
  }

  private fun resetGuestPlayCount(context: Context) {
    getPrefs(context).edit().putInt(KEY_GUEST_PLAYS, 0).apply()
    _guestPlayCount.value = 0
    _isGuestLimitReached.value = false
  }

  private fun getPrefs(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  private fun syncUserProfile(user: FirebaseUser) {
    scope.launch {
      try {
        val userDoc = firestore.collection("users").document(user.uid)
        val data = hashMapOf<String, Any>(
          "uid" to user.uid,
          "displayName" to (user.displayName ?: "User"),
          "name" to (user.displayName ?: "User"),
          "email" to (user.email ?: ""),
          "photoURL" to (user.photoUrl?.toString() ?: ""),
          "lastLogin" to FieldValue.serverTimestamp(),
          "lastActive" to FieldValue.serverTimestamp(),
          "device" to "Android"
        )
        userDoc.set(data, SetOptions.merge()).await()
        Timber.d("User profile synced to Firestore: ${user.uid}")
      } catch (e: Exception) {
        Timber.e(e, "Failed to sync user profile")
      }
    }
  }

  fun syncFcmToken(userId: String) {
    FirebaseMessaging.getInstance().token
      .addOnSuccessListener { token ->
        if (!token.isNullOrBlank()) {
          _fcmToken.value = token
          saveFcmToken(userId, token)
        }
      }
      .addOnFailureListener { e ->
        Timber.e(e, "Failed to retrieve FCM token")
      }
  }

  fun saveFcmToken(userId: String, token: String) {
    scope.launch {
      try {
        val tokenDocId = "${userId}_${token.takeLast(16)}"
        val tokenDoc = firestore.collection("fcmTokens").document(tokenDocId)
        val data = hashMapOf<String, Any>(
          "userId" to userId,
          "token" to token,
          "platform" to "Android",
          "device" to "Android",
          "lastActive" to FieldValue.serverTimestamp(),
          "createdAt" to FieldValue.serverTimestamp()
        )
        tokenDoc.set(data, SetOptions.merge()).await()
        Timber.d("FCM token registered in Firestore: $tokenDocId")
      } catch (e: Exception) {
        Timber.e(e, "Failed to save FCM token")
      }
    }
  }

  private suspend fun recordUserSongPlay(
    userId: String,
    songId: String,
    title: String,
    artist: String,
    artworkUrl: String?,
    album: String?,
    duration: Long
  ) {
    try {
      val now = LocalDate.now(ZoneId.of("Asia/Kolkata"))
      val today = now.toString()
      val yesterday = now.minusDays(1).toString()

      val userDoc = firestore.collection("users").document(userId)
      val snapshot = userDoc.get().await()

      val existingPrevious = (snapshot.get("previousSongs") as? List<*>)
        ?.filterIsInstance<Map<String, Any>>()
        ?: emptyList()

      val currentSongObj = hashMapOf<String, Any>(
        "id" to songId,
        "name" to title,
        "artist" to artist,
        "image" to (artworkUrl ?: ""),
        "album" to (album ?: ""),
        "duration" to duration
      )

      val updatedPrevious = mutableListOf<Map<String, Any>>()
      updatedPrevious.add(currentSongObj)
      for (item in existingPrevious) {
        if (item["id"] != songId && updatedPrevious.size < 6) {
          updatedPrevious.add(item)
        }
      }

      val playStats = snapshot.get("playStats") as? Map<*, *>
      val lastDate = playStats?.get("date") as? String
      val lastCount = (playStats?.get("count") as? Number)?.toInt() ?: 0
      val lastStreak = (playStats?.get("streak") as? Number)?.toInt() ?: 1

      val newCount: Int
      val newStreak: Int

      when (lastDate) {
        today -> {
          newCount = lastCount + 1
          newStreak = lastStreak
        }
        yesterday -> {
          newCount = 1
          newStreak = lastStreak + 1
        }
        else -> {
          newCount = 1
          newStreak = 1
        }
      }

      val updates = hashMapOf<String, Any>(
        "lastActive" to FieldValue.serverTimestamp(),
        "totalPlays" to FieldValue.increment(1),
        "lastPlayedSong" to currentSongObj,
        "previousSongs" to updatedPrevious,
        "playStats" to hashMapOf(
          "date" to today,
          "count" to newCount,
          "streak" to newStreak
        )
      )

      userDoc.set(updates, SetOptions.merge()).await()
      Timber.d("Song play recorded in Firestore for $userId: $title")
    } catch (e: Exception) {
      Timber.e(e, "Error recording song play to Firestore")
    }
  }
}
