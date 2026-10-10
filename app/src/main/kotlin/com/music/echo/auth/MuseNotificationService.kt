package echo.music.iad1tya.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.graphics.BitmapFactory
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import timber.log.Timber

@Suppress("DEPRECATION")
class MuseNotificationService : FirebaseMessagingService() {

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Timber.d("FCM Token refreshed: $token")
    val user = MuseAuthManager.currentUser.value
    if (user != null) {
      MuseAuthManager.saveFcmToken(user.uid, token)
    }
  }

  override fun onMessageReceived(remoteMessage: RemoteMessage) {
    super.onMessageReceived(remoteMessage)
    Timber.d("FCM Message received from: ${remoteMessage.from}")

    val title = remoteMessage.notification?.title
      ?: remoteMessage.data["title"]
      ?: "Muse Music"

    val message = remoteMessage.notification?.body
      ?: remoteMessage.data["body"]
      ?: remoteMessage.data["message"]
      ?: ""

    if (message.isNotBlank()) {
      sendNotification(this, title, message)
    }
  }

  companion object {
    fun sendNotification(context: Context, title: String, message: String) {
      val channelId = "muse_push_channel"
      val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel =
          NotificationChannel(
            channelId,
            "Muse Notifications",
            NotificationManager.IMPORTANCE_HIGH
          ).apply {
            description = "Push notifications from Muse Music"
            enableLights(true)
            enableVibration(true)
          }
        notificationManager.createNotificationChannel(channel)
      }

      val intent =
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

      val pendingIntent =
        PendingIntent.getActivity(
          context,
          0,
          intent,
          PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

      val largeIcon = try {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
      } catch (e: Exception) {
        null
      }

      val builder =
        NotificationCompat.Builder(context, channelId)
          .setSmallIcon(R.drawable.ic_notification_muse)
          .setContentTitle(title)
          .setContentText(message)
          .setAutoCancel(true)
          .setPriority(NotificationCompat.PRIORITY_HIGH)
          .setContentIntent(pendingIntent)

      if (largeIcon != null) {
        builder.setLargeIcon(largeIcon)
      }

      val notification = builder.build()
      notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
  }
}
