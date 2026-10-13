package echo.music.iad1tya.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.YouTube
import com.music.innertube.utils.parseCookieString
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.R
import echo.music.iad1tya.auth.MuseAuthManager
import echo.music.iad1tya.auth.MuseSyncManager
import echo.music.iad1tya.constants.*
import echo.music.iad1tya.constants.SavedAccountsKey
import echo.music.iad1tya.models.AccountData
import echo.music.iad1tya.ui.component.*
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.viewmodels.AccountSettingsViewModel
import echo.music.iad1tya.viewmodels.HomeViewModel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountSettingsScreen(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  highlightKey: String? = null
) {
  val scrollState = androidx.compose.foundation.rememberScrollState()

  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current

  val (accountNamePref, _) = rememberPreference(AccountNameKey, "")
  val (accountEmail, _) = rememberPreference(AccountEmailKey, "")
  val (accountChannelHandle, _) = rememberPreference(AccountChannelHandleKey, "")
  val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
  val (visitorData, _) = rememberPreference(VisitorDataKey, "")
  val (dataSyncId, _) = rememberPreference(DataSyncIdKey, "")
  val (savedAccountsJson, onSavedAccountsJsonChange) = rememberPreference(SavedAccountsKey, "[]")

  val (listenBrainzEnabled, onListenBrainzEnabledChange) =
    rememberPreference(ListenBrainzEnabledKey, false)
  val (listenBrainzToken, onListenBrainzTokenChange) = rememberPreference(ListenBrainzTokenKey, "")

  val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }
  val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
  val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

  val homeViewModel: HomeViewModel = hiltViewModel()
  val accountSettingsViewModel: AccountSettingsViewModel = hiltViewModel()
  val accountName by homeViewModel.accountName.collectAsState()
  val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.account)) },
        navigationIcon = {
          IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
            Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
          }
        },
        scrollBehavior = scrollBehavior
      )
    }
  ) { padding ->
    Column(
      modifier =
        Modifier.padding(padding)
          .windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
          )
          .verticalScroll(scrollState)
          .padding(horizontal = 16.dp)
    ) {
      Spacer(modifier = Modifier.height(16.dp))

      val museUser by MuseAuthManager.currentUser.collectAsState()
      val customUsername by MuseAuthManager.customUsername.collectAsState()
      var showEditUsernameDialog by remember { mutableStateOf(false) }
      var editUsernameText by remember { mutableStateOf("") }
      var isUpdatingUsername by remember { mutableStateOf(false) }
      val syncStatus by MuseSyncManager.syncStatus.collectAsState()

      val museDisplayName = customUsername
        ?: museUser?.displayName?.takeIf { it.isNotBlank() }
        ?: museUser?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
        ?: "Muse User"

      Material3SettingsGroup(
        scrollState = scrollState,
        title = "Muse Account",
        items = if (museUser != null) {
          listOf(
            Material3SettingsItem(
              icon = null,
              title = {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  if (!museUser?.photoUrl?.toString().isNullOrBlank()) {
                    AsyncImage(
                      model = museUser?.photoUrl?.toString(),
                      contentDescription = null,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.size(44.dp).clip(CircleShape)
                    )
                  } else {
                    Surface(
                      shape = CircleShape,
                      color = MaterialTheme.colorScheme.primaryContainer,
                      modifier = Modifier.size(44.dp)
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(
                          painter = painterResource(R.drawable.person),
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.onPrimaryContainer,
                          modifier = Modifier.size(24.dp)
                        )
                      }
                    }
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = museDisplayName,
                      style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!museUser?.email.isNullOrBlank()) {
                      Text(
                        text = museUser?.email ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                  IconButton(
                    onClick = {
                      editUsernameText = museDisplayName
                      showEditUsernameDialog = true
                    }
                  ) {
                    Icon(
                      painter = painterResource(R.drawable.edit),
                      contentDescription = "Edit Username",
                      tint = MaterialTheme.colorScheme.primary
                    )
                  }
                }
              },
              trailingContent = null,
              onClick = {
                editUsernameText = museDisplayName
                showEditUsernameDialog = true
              }
            ),
            Material3SettingsItem(
              icon = painterResource(R.drawable.sync),
              title = { Text("Muse Cloud Sync") },
              description = {
                val statusText = when (val s = syncStatus) {
                  is MuseSyncManager.SyncStatus.Syncing -> "Syncing with Muse Web..."
                  is MuseSyncManager.SyncStatus.Resolving -> "Resolving with YouTube (${s.current}/${s.total})..."
                  is MuseSyncManager.SyncStatus.Completed -> "Synced with Muse Web (YouTube Verified)"
                  is MuseSyncManager.SyncStatus.Error -> "Sync error: ${s.message}"
                  else -> "Liked songs & playlists synced"
                }
                Text(statusText)
              },
              trailingContent = {
                val isBusy = syncStatus is MuseSyncManager.SyncStatus.Syncing || syncStatus is MuseSyncManager.SyncStatus.Resolving
                FilledTonalButton(
                  onClick = { MuseSyncManager.forceSyncNow() },
                  enabled = !isBusy,
                  contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                  Text(
                    text = if (isBusy) "Syncing" else "Sync Now",
                    style = MaterialTheme.typography.labelMedium
                  )
                }
              },
              onClick = {
                val isBusy = syncStatus is MuseSyncManager.SyncStatus.Syncing || syncStatus is MuseSyncManager.SyncStatus.Resolving
                if (!isBusy) {
                  MuseSyncManager.forceSyncNow()
                }
              }
            ),
            Material3SettingsItem(
              icon = painterResource(R.drawable.edit),
              title = { Text("Change Username") },
              description = { Text(museDisplayName) },
              onClick = {
                editUsernameText = museDisplayName
                showEditUsernameDialog = true
              }
            ),
            Material3SettingsItem(
              icon = painterResource(R.drawable.logout),
              title = { Text("Sign Out", color = MaterialTheme.colorScheme.error) },
              onClick = {
                MuseAuthManager.signOut(context)
              }
            )
          )
        } else {
          listOf(
            Material3SettingsItem(
              icon = painterResource(R.drawable.ic_google),
              title = { Text("Sign In with Google") },
              description = { Text("Sync your streaks, play counts, and playlists") },
              onClick = {
                MuseAuthManager.triggerLoginDialog()
              }
            )
          )
        }
      )
      Spacer(modifier = Modifier.height(16.dp))

      if (showEditUsernameDialog) {
        AlertDialog(
          onDismissRequest = {
            if (!isUpdatingUsername) showEditUsernameDialog = false
          },
          title = { Text("Change Username") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "Enter your display name for Muse Music:",
                style = MaterialTheme.typography.bodyMedium
              )
              OutlinedTextField(
                value = editUsernameText,
                onValueChange = { editUsernameText = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Your name") }
              )
            }
          },
          confirmButton = {
            Button(
              enabled = editUsernameText.trim().isNotEmpty() && !isUpdatingUsername,
              onClick = {
                val newName = editUsernameText.trim()
                isUpdatingUsername = true
                MuseAuthManager.updateUsername(
                  newName = newName,
                  onSuccess = {
                    isUpdatingUsername = false
                    showEditUsernameDialog = false
                    Toast.makeText(context, "Username updated", Toast.LENGTH_SHORT).show()
                  },
                  onError = { e ->
                    isUpdatingUsername = false
                    Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                  }
                )
              }
            ) {
              if (isUpdatingUsername) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onPrimary
                )
              } else {
                Text("Save")
              }
            }
          },
          dismissButton = {
            TextButton(
              enabled = !isUpdatingUsername,
              onClick = { showEditUsernameDialog = false }
            ) {
              Text("Cancel")
            }
          }
        )
      }

      val fcmToken by MuseAuthManager.fcmToken.collectAsState()

      Material3SettingsGroup(
        scrollState = scrollState,
        title = "Notifications & Sync",
        items = listOf(
          Material3SettingsItem(
            icon = painterResource(R.drawable.notification),
            title = { Text("Send Test Notification") },
            description = { Text("Check push notifications on this device") },
            onClick = {
              echo.music.iad1tya.auth.MuseNotificationService.sendNotification(
                context = context,
                title = "Muse Music \uD83D\uDD25",
                message = "Hello $museDisplayName! Your streak is active. Keep listening! \uD83C\uDFB5"
              )
              Toast.makeText(context, "Test notification sent! Check notification bar", Toast.LENGTH_SHORT).show()
            }
          ),
          Material3SettingsItem(
            icon = painterResource(R.drawable.content_copy),
            title = { Text("Copy FCM Device Token") },
            description = {
              Text(
                if (fcmToken != null) "Token: ${fcmToken?.take(16)}... (Tap to copy)"
                else "Tap to retrieve and copy FCM token"
              )
            },
            onClick = {
              val token = fcmToken
              if (!token.isNullOrBlank()) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("FCM Token", token)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "FCM Token copied to clipboard!", Toast.LENGTH_SHORT).show()
              } else {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { newToken ->
                  val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                  val clip = android.content.ClipData.newPlainText("FCM Token", newToken)
                  clipboard.setPrimaryClip(clip)
                  Toast.makeText(context, "FCM Token copied to clipboard!", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener {
                  Toast.makeText(context, "Failed to get FCM token: ${it.message}", Toast.LENGTH_SHORT).show()
                }
              }
            }
          )
        )
      )
    }

    Spacer(Modifier.height(100.dp))

    Spacer(Modifier.height(100.dp))
    Spacer(
      Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
      )
    )
  }
}
