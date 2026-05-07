package com.example.offlinelink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.offlinelink.MainActivity
import com.example.offlinelink.R

class OfflineKeepAliveService : Service() {
  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    startInForeground(intent?.getStringExtra(EXTRA_MESSAGE) ?: DEFAULT_MESSAGE)
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startInForeground(message: String) {
    val notification = buildNotification(message)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun buildNotification(message: String): Notification {
    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openAppPendingIntent =
      PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, OfflineKeepAliveService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(message)
      .setContentIntent(openAppPendingIntent)
      .setOngoing(true)
      .setShowWhen(false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, "Stop", stopPendingIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val channel =
      NotificationChannel(CHANNEL_ID, "OfflineLink keep alive", NotificationManager.IMPORTANCE_LOW)
        .apply {
          description = "Keeps nearby connection and call work active."
          setShowBadge(false)
        }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  companion object {
    private const val ACTION_START = "com.example.offlinelink.service.START_KEEP_ALIVE"
    private const val ACTION_STOP = "com.example.offlinelink.service.STOP_KEEP_ALIVE"
    private const val CHANNEL_ID = "offline_link_keep_alive"
    private const val DEFAULT_MESSAGE = "OfflineLink is active"
    private const val EXTRA_MESSAGE = "extra_message"
    private const val NOTIFICATION_ID = 1001
    private const val REQUEST_OPEN_APP = 2001
    private const val REQUEST_STOP = 2002

    fun start(context: Context, message: String = DEFAULT_MESSAGE) {
      val intent =
        Intent(context, OfflineKeepAliveService::class.java)
          .setAction(ACTION_START)
          .putExtra(EXTRA_MESSAGE, message)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, OfflineKeepAliveService::class.java))
    }
  }
}
