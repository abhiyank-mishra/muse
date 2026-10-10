package echo.music.iad1tya.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.innertube.utils.parseCookieString
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.R
import echo.music.iad1tya.auth.MuseAuthManager
import echo.music.iad1tya.constants.AccountEmailKey
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.AudioQualityKey
import echo.music.iad1tya.constants.InnerTubeCookieKey
import echo.music.iad1tya.constants.UseLoginForBrowse
import echo.music.iad1tya.constants.YtmSyncKey
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.utils.rememberEnumPreference
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingDialoge(
  onDismissRequest: () -> Unit,
  onNavigate: (String) -> Unit,
  homeViewModel: HomeViewModel
) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val (audioQuality) = rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.OPUS)
  val (innerTubeCookie, _) = rememberPreference(InnerTubeCookieKey, "")
  val isLoggedIn =
    remember(innerTubeCookie) {
      innerTubeCookie.isNotEmpty() && "SAPISID" in parseCookieString(innerTubeCookie)
    }

  val (accountEmail, _) = rememberPreference(AccountEmailKey, "")
  val accountName by homeViewModel.accountName.collectAsState()
  val accountImageUrl by homeViewModel.accountImageUrl.collectAsState()

  val museUser by MuseAuthManager.currentUser.collectAsState()
  val customUsername by MuseAuthManager.customUsername.collectAsState()
  val guestPlays by MuseAuthManager.guestPlayCount.collectAsState()

  val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)
  val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, true)

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    containerColor = MaterialTheme.colorScheme.surfaceContainer
  ) {
    val primaryColor = MaterialTheme.colorScheme.onSurface
    val onSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Header
      // Removed Echo Music text

      // Account Group
      Material3SettingsGroup(
        title = "Account",
        compact = true,
        items =
          buildList {
            add(
              Material3SettingsItem(
                title = {
                  Text(
                    when {
                      museUser != null -> customUsername ?: museUser?.displayName ?: "Google User"
                      isLoggedIn -> accountName
                      else -> "Guest User"
                    }
                  )
                },
                description = {
                  Text(
                    when {
                      museUser != null -> museUser?.email ?: "Signed in with Google"
                      isLoggedIn -> accountEmail.ifEmpty { "Logged In" }
                      else -> if (guestPlays >= 5) "5/5 songs played (Sign in required)" else "$guestPlays / 5 free songs played"
                    }
                  )
                },
                icon = painterResource(R.drawable.account),
                trailingContent =
                  if (museUser?.photoUrl != null) {
                    {
                      AsyncImage(
                        model = museUser?.photoUrl.toString(),
                        contentDescription = "Profile Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                      )
                    }
                  } else if (isLoggedIn && !accountImageUrl.isNullOrBlank()) {
                    {
                      AsyncImage(
                        model = accountImageUrl,
                        contentDescription = "Profile Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                      )
                    }
                  } else null,
                onClick = {
                  if (museUser != null || isLoggedIn) {
                    onDismissRequest()
                    onNavigate("settings/account")
                  } else {
                    onDismissRequest()
                    MuseAuthManager.triggerLoginDialog()
                  }
                }
              )
            )

            add(
              Material3SettingsItem(
                title = {
                  Text(androidx.compose.ui.res.stringResource(R.string.ai_lyrics_translation))
                },
                description = {
                  Text(androidx.compose.ui.res.stringResource(R.string.setting_desc_ai))
                },
                customIcon = {
                  Text(
                    text = "Ai",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                  )
                },
                onClick = {
                  onDismissRequest()
                  onNavigate("settings/ai")
                }
              )
            )
            add(
              Material3SettingsItem(
                title = { Text("Extensions") },
                description = { Text("Manage third-party content plugins") },
                icon = androidx.compose.ui.res.painterResource(echo.music.iad1tya.R.drawable.extension),
                onClick = {
                  onDismissRequest()
                  onNavigate("settings/extensions")
                }
              )
            )
          }
      )

      if (isLoggedIn) {
        Material3SettingsGroup(
          title = "Preferences",
          compact = true,
          items =
            listOf(
              Material3SettingsItem(
                title = { Text("Use Account for Browsing") },
                icon = painterResource(R.drawable.add_circle),
                trailingContent = {
                  Switch(
                    checked = useLoginForBrowse,
                    onCheckedChange = {
                      com.music.innertube.YouTube.useLoginForBrowse = it
                      onUseLoginForBrowseChange(it)
                    },
                    modifier = Modifier.scale(0.8f),
                    colors =
                      SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                      )
                  )
                },
                onClick = {
                  val newVal = !useLoginForBrowse
                  com.music.innertube.YouTube.useLoginForBrowse = newVal
                  onUseLoginForBrowseChange(newVal)
                }
              ),
              Material3SettingsItem(
                title = { Text("YouTube Music Sync") },
                icon = painterResource(R.drawable.cached),
                trailingContent = {
                  Switch(
                    checked = ytmSync,
                    onCheckedChange = onYtmSyncChange,
                    modifier = Modifier.scale(0.8f),
                    colors =
                      SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                      )
                  )
                },
                onClick = { onYtmSyncChange(!ytmSync) }
              )
            )
        )
      }

      Material3SettingsGroup(
        title = "App",
        compact = true,
        items =
          listOf(
            Material3SettingsItem(
              title = { Text(androidx.compose.ui.res.stringResource(R.string.settings)) },
              description = {
                Text(androidx.compose.ui.res.stringResource(R.string.setting_desc_settings_main))
              },
              icon = painterResource(R.drawable.settings),
              onClick = {
                onDismissRequest()
                onNavigate("settings")
              }
            ),
            Material3SettingsItem(
              title = { Text("About") },
              icon = painterResource(R.drawable.info),
              trailingContent = {
                Text(
                  BuildConfig.VERSION_NAME,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              },
              onClick = {
                onDismissRequest()
                onNavigate("settings/about")
              }
            )
          )
      )

      // Footer Links
      Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Just vibe to the music — the dev's got the rest handled.",
          style = MaterialTheme.typography.bodySmall,
          color = onSecondaryColor,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              try {
                onDismissRequest()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://abhiyank.in")).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
              } catch (e: Exception) {
                try {
                  uriHandler.openUri("https://abhiyank.in")
                } catch (_: Exception) {}
              }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center
        ) {
          Text(
            text = "Powered by Abhiyank",
            style = MaterialTheme.typography.bodySmall.copy(
              fontWeight = FontWeight.SemiBold,
              letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    }
  }
}
