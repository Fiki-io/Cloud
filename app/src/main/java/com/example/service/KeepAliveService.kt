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
import androidx.core.app.NotificationCompat
import com.example.MainActivity
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

class KeepAliveService : Service() {

    // Gunakan SupervisorJob agar kegagalan 1 thread tidak merusak scope
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val CHANNEL_ID = "cloud_shell_keepalive_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.cloudshell.START_SERVICE"
        const val ACTION_STOP = "com.example.cloudshell.STOP_SERVICE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _elapsedSeconds = MutableStateFlow(0L)
        val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

        private val _lastHeartbeat = MutableStateFlow(0L)
        val lastHeartbeat: StateFlow<Long> = _lastHeartbeat.asStateFlow()

        private val _heartbeatCount = MutableStateFlow(0)
        val heartbeatCount: StateFlow<Int> = _heartbeatCount.asStateFlow()

        fun recordHeartbeat() {
            _lastHeartbeat.value = System.currentTimeMillis()
            _heartbeatCount.value += 1
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Langsung tampilkan notifikasi di onCreate agar bebas dari crash batas 5 detik Android
        startForegroundSafely("Sesi Dimulai...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        startTimer()

        return START_STICKY
    }

    private fun startForegroundSafely(initialText: String) {
        val notification = buildNotification(initialText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isRunning.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireLocks() {
        // WakeLock: Mencegah CPU tidur saat layar HP mati / buka AVNC
        if (wakeLock == null || wakeLock?.isHeld == false) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CloudShell:KeepAliveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // Maksimal 4 jam pengaman
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // WifiLock: Menjaga stabilitas socket WebSocket Cloud Shell
        if (wifiLock == null || wifiLock?.isHeld == false) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "CloudShell:WifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        _elapsedSeconds.value = 0L
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                _elapsedSeconds.value += 1
                if (_elapsedSeconds.value % 60 == 0L) {
                    val formatted = formatDuration(_elapsedSeconds.value)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification("Sesi Aktif: $formatted"))
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cloud Shell Anti-Stop")
            .setContentText("$statusText (Koneksi terjaga di latar belakang)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingOpenIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sesi", pendingStopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cloud Shell Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status penjaga koneksi Cloud Shell aktif"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hrs, mins, secs)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel() // Matikan semua coroutine di scope ini
        _isRunning.value = false
        _elapsedSeconds.value = 0L

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
