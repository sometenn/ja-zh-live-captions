package com.sometenn.callcaptions.service

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sometenn.callcaptions.CallCaptionsApplication
import com.sometenn.callcaptions.MainActivity
import com.sometenn.callcaptions.R

/**
 * Foreground service keeping microphone capture and live translation alive
 * while the user switches to another app.
 */
class CaptionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                (application as? CallCaptionsApplication)?.container?.session?.finish("已从通知栏结束收音。")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "实时字幕收音服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "用于保持日语实时字幕在后台收音和显示悬浮字幕"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, CaptionForegroundService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在使用麦克风收音，点此返回应用")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "结束收音", stopPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "callcaptions_capture"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.sometenn.callcaptions.action.START"
        private const val ACTION_STOP_SERVICE = "com.sometenn.callcaptions.action.STOP_SERVICE"
        private const val ACTION_STOP_SESSION = "com.sometenn.callcaptions.action.STOP_SESSION"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, CaptionForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, CaptionForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.applicationContext.stopService(intent)
            } catch (_: Throwable) {
                // Ignore if service was not running.
            }
        }
    }
}
