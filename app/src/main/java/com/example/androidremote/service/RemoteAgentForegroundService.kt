package com.example.androidremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.androidremote.R
import com.example.androidremote.api.RemoteHttpServer

class RemoteAgentForegroundService : Service() {

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private var httpServer: RemoteHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        screenCaptureManager = ScreenCaptureManager(this)
        acquireWakeLocks()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, buildNotification("Starting remote agent..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, buildNotification("Starting remote agent..."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && data != null) {
            // Fresh projection data from user grant — always apply (even if we already have one)
            Log.d(TAG, "Received fresh projection data, applying (hasExisting=${screenCaptureManager.hasProjection()})")
            saveProjectionData(resultCode, data)
            screenCaptureManager.setMediaProjection(resultCode, data)
        } else if (!screenCaptureManager.hasProjection()) {
            // Try to restore from saved data on service restart
            tryRestoreProjection()
        }

        if (httpServer == null) {
            httpServer = RemoteHttpServer(this, screenCaptureManager, 8080).also {
                it.start(5000, false)
            }
        }

        val status = if (screenCaptureManager.hasProjection()) "Server: 8080 (capture ready)" else "Server: 8080 (waiting capture permission)"
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
        return START_STICKY
    }

    /**
     * Save the MediaProjection result code and Intent data to SharedPreferences.
     * The Intent is serialized as a URI string for safe storage.
     */
    private fun saveProjectionData(resultCode: Int, data: Intent) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_RESULT_CODE, resultCode)
            .putString(KEY_RESULT_DATA_URI, data.toUri(Intent.URI_INTENT_SCHEME))
            .apply()
        Log.d(TAG, "Saved projection data (resultCode=$resultCode)")
    }

    /**
     * Attempt to restore a MediaProjection from previously saved data.
     * This will only work on Android < 14 (API 34) where tokens can be reused.
     */
    private fun tryRestoreProjection() {
        // On Android 14+, MediaProjection tokens are single-use; skip restore attempt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "Skipping projection restore: Android 14+ tokens are single-use")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedResultCode = prefs.getInt(KEY_RESULT_CODE, 0)
        val savedDataUri = prefs.getString(KEY_RESULT_DATA_URI, null)

        if (savedResultCode != 0 && savedDataUri != null) {
            try {
                val restoredData = Intent.parseUri(savedDataUri, Intent.URI_INTENT_SCHEME)
                screenCaptureManager.setMediaProjection(savedResultCode, restoredData)
                Log.d(TAG, "Restored projection from saved data")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore projection", e)
                clearSavedProjectionData()
            }
        }
    }

    private fun acquireWakeLocks() {
        // Partial wake lock — keeps CPU running while screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidRemote::ServiceWakeLock"
        ).apply { acquire() }
        Log.d(TAG, "Acquired partial WakeLock")

        // WiFi lock — keeps WiFi active while screen is off
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(
            wifiLockMode,
            "AndroidRemote::ServiceWifiLock"
        ).apply { acquire() }
        Log.d(TAG, "Acquired WifiLock (mode=$wifiLockMode)")
    }

    private fun releaseWakeLocks() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            Log.d(TAG, "Released WakeLock")
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
            wifiLock = null
            Log.d(TAG, "Released WifiLock")
        }
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        releaseWakeLocks()
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
        private const val TAG = "RemoteAgentService"
        private const val CHANNEL_ID = "remote_agent_channel"
        private const val PREFS_NAME = "projection_prefs"
        private const val KEY_RESULT_CODE = "projection_result_code"
        private const val KEY_RESULT_DATA_URI = "projection_result_data_uri"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(service: Service, resultCode: Int, data: Intent?) {
            val intent = Intent(service, RemoteAgentForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            service.startForegroundService(intent)
        }

        /**
         * Check if saved projection data exists in SharedPreferences.
         */
        fun hasSavedProjection(context: android.content.Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            return prefs.getInt(KEY_RESULT_CODE, 0) != 0 &&
                    prefs.getString(KEY_RESULT_DATA_URI, null) != null
        }

        /**
         * Clear saved projection data (e.g., when user explicitly revokes).
         */
        fun clearSavedProjectionData(context: android.content.Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_RESULT_CODE)
                .remove(KEY_RESULT_DATA_URI)
                .apply()
        }
    }

    private fun clearSavedProjectionData() {
        Companion.clearSavedProjectionData(this)
    }
}
