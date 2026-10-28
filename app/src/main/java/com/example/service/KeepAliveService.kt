package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class KeepAliveService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var notificationUpdateJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = AppConstants.ACTION_START_SERVICE
        const val ACTION_STOP = AppConstants.ACTION_STOP_SERVICE

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _startElapsedRealtime = MutableStateFlow(0L)
        val startElapsedRealtime: StateFlow<Long> = _startElapsedRealtime.asStateFlow()

        private val _lastHeartbeat = MutableStateFlow(0L)
        val lastHeartbeat: StateFlow<Long> = _lastHeartbeat.asStateFlow()

        private val _heartbeatCount = MutableStateFlow(0)
        val heartbeatCount: StateFlow<Int> = _heartbeatCount.asStateFlow()

        fun recordHeartbeat() {
            _lastHeartbeat.value = System.currentTimeMillis()
            _heartbeatCount.value += 1
        }

        fun formatDuration(seconds: Long): String {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafely(getString(R.string.notif_status_starting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        startNotificationUpdater()

        return START_STICKY
    }

    private fun startForegroundSafely(initialText: String) {
        val notification = buildNotification(initialText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AppConstants.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(AppConstants.SERVICE_NOTIFICATION_ID, notification)
            }
            _isRunning.value = true
        } catch (_: Exception) {
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    AppConstants.WAKELOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire(AppConstants.WAKELOCK_TIMEOUT_MS)
                }
            } catch (_: Exception) {
            }
        }

        if (wifiLock == null || wifiLock?.isHeld == false) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(
                    lockMode,
                    AppConstants.WIFILOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startNotificationUpdater() {
        if (_startElapsedRealtime.value == 0L) {
            _startElapsedRealtime.value = SystemClock.elapsedRealtime()
        }
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            while (true) {
                delay(AppConstants.NOTIFICATION_UPDATE_INTERVAL_MS)
                val startRealtime = _startElapsedRealtime.value
                if (startRealtime > 0L) {
                    val elapsedSec = ((SystemClock.elapsedRealtime() - startRealtime) / 1000L).coerceAtLeast(0L)
                    val formatted = formatDuration(elapsedSec)
                    val statusText = getString(R.string.notif_status_active, formatted)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(AppConstants.SERVICE_NOTIFICATION_ID, buildNotification(statusText))
                }
            }
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AppConstants.SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content_suffix, statusText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpenIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop),
                pendingStopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.SERVICE_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        notificationUpdateJob?.cancel()
        serviceScope.cancel()
        _isRunning.value = false
        _startElapsedRealtime.value = 0L

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
