package com.mundiah.mimunclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service ringan untuk transkripsi & render: menahan proses
 * tetap hidup (mediaProcessing) dan menampilkan notifikasi progres.
 * ViewModel memanggil [updateProgress]/[stop] secara statis; service
 * sendiri tidak menjalankan pekerjaan berat.
 */
class ProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "mimunclip_processing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.mundiah.mimunclip.STOP_PROCESSING"

        fun start(context: Context, title: String) {
            val intent = Intent(context, ProcessingService::class.java)
            intent.putExtra("title", title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, title: String, text: String, percent: Int) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            nm.notify(NOTIFICATION_ID, buildNotification(context, title, text, percent))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProcessingService::class.java))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }

        private fun ensureChannel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Proses MimunClip",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Progres transkripsi dan render video" }
                nm.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(
            context: Context, title: String, text: String, percent: Int,
        ): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra("title") ?: "MimunClip"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        startForeground(NOTIFICATION_ID, buildNotification(this, title, "Memulai…", 0))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
