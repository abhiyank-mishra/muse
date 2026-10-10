package echo.music.iad1tya.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import echo.music.iad1tya.R

@Composable
fun MuseLoginDialog(
  isLimitReached: Boolean,
  isLoading: Boolean = false,
  onGoogleSignIn: () -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(
    onDismissRequest = {
      if (!isLoading) {
        onDismiss()
      }
    },
    properties = DialogProperties(
      dismissOnBackPress = !isLoading,
      dismissOnClickOutside = !isLoading,
      usePlatformDefaultWidth = false
    )
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.88f)
        .wrapContentHeight(),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // App Logo / Music Note in circle badge
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center
        ) {
          Image(
            painter = painterResource(R.drawable.ic_launcher_nobg),
            contentDescription = "Muse Music",
            modifier = Modifier
              .size(42.dp)
              .clip(CircleShape)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
          text = if (isLimitReached) "Free Play Limit Reached" else "Sign in to Muse",
          style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
          ),
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Badge pill
        if (isLimitReached) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
          ) {
            Text(
              text = "5 / 5 Songs Played",
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }
        }

        // Subtitle message
        Text(
          text = if (isLimitReached) {
            "You have enjoyed your 5 free guest songs! Sign in with your Google account to unlock unlimited streaming, save your favorite tracks, and track your listening streak."
          } else {
            "Connect your Google account to sync your playlists, keep your listening history, track streaks, and enjoy unlimited music."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Feature benefits row
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 10.dp, horizontal = 12.dp),
          horizontalArrangement = Arrangement.SpaceAround,
          verticalAlignment = Alignment.CenterVertically
        ) {
          FeatureChip(icon = "🎵", label = "Unlimited")
          FeatureChip(icon = "🔥", label = "Streaks")
          FeatureChip(icon = "☁️", label = "Synced")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Google Sign-In Button
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isLoading) { onGoogleSignIn() },
          color = MaterialTheme.colorScheme.primary,
          shape = RoundedCornerShape(16.dp),
          tonalElevation = 2.dp
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            if (isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.5.dp
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = "Signing in...",
                style = MaterialTheme.typography.labelLarge.copy(
                  fontWeight = FontWeight.SemiBold,
                  fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onPrimary
              )
            } else {
              // White circular container for the Google icon
              Surface(
                modifier = Modifier.size(30.dp),
                shape = CircleShape,
                color = Color.White
              ) {
                Box(
                  modifier = Modifier.fillMaxWidth(),
                  contentAlignment = Alignment.Center
                ) {
                  Image(
                    painter = painterResource(R.drawable.ic_google),
                    contentDescription = "Google",
                    modifier = Modifier.size(18.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.width(12.dp))

              Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.labelLarge.copy(
                  fontWeight = FontWeight.SemiBold,
                  fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onPrimary
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Dismiss / Browse only button
        TextButton(
          onClick = onDismiss,
          enabled = !isLoading,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = if (isLimitReached) "Browse Library (Playback Paused)" else "Maybe Later",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@Composable
private fun FeatureChip(icon: String, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Text(text = icon, fontSize = 13.sp)
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}
