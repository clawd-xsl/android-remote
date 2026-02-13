package com.example.androidremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.androidremote.R
import com.example.androidremote.api.RemoteHttpServer

class RemoteAgentForegroundService : Service() {

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private var httpServer: RemoteHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        screenCaptureManager = ScreenCaptureManager(this)
        createNotificationChannel()
        startForeground(1001, buildNotification("Starting remote agent..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            screenCaptureManager.setMediaProjection(resultCode, data)
        }

        if (httpServer == null) {
            httpServer = RemoteHttpServer(this, screenCaptureManager, 8080).also {
                it.start(5000, false)
            }
        }

        val status = if (screenCaptureManager.hasProjection()) "Server: 8080 (capture ready)" else "Server: 8080 (waiting capture permission)"
        startForeground(1001, buildNotification(status))
        return START_STICKY
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Android Remote Agent")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remote_agent_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(service: Service, resultCode: Int, data: Intent?) {
            val intent = Intent(service, RemoteAgentForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            service.startForegroundService(intent)
        }
    }
}
