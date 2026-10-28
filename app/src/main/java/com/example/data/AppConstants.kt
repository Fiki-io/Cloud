package com.example.data

object AppConstants {
    const val CLOUD_SHELL_URL = "https://shell.cloud.google.com"
    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"

    const val WEB_EXT_URI = "resource://android/assets/cloudshell_ext/"
    const val WEB_EXT_ID = "bridge@cloudshell.aistudio.com"
    const val WEB_EXT_NATIVE_APP = "cloudshell"

    const val CMD_START_SCRIPT = "./start.sh"
    // Menggunakan flag huruf kecil (-p443 -o stricthostkeychecking=no) yang 100% standar OpenSSH
    // sehingga bebas dari puluhan event tombol SHIFT dan jauh lebih cepat diketik ke terminal
    const val CMD_PINGGY_SSH = "ssh -p443 -R0:localhost:5901 -o stricthostkeychecking=no tcp@a.pinggy.io"

    const val CLIPBOARD_LABEL = "CloudShell"

    const val KEY_DEBOUNCE_MS = 60L
    const val CTRL_C_SETTLE_DELAY_MS = 220L
    const val COPY_SCAN_TIMEOUT_MS = 350L

    const val HEARTBEAT_MIN_SECONDS = 180L
    const val HEARTBEAT_MAX_SECONDS = 260L

    const val SERVICE_CHANNEL_ID = "cloud_shell_keepalive_channel"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val ACTION_START_SERVICE = "com.example.cloudshell.START_SERVICE"
    const val ACTION_STOP_SERVICE = "com.example.cloudshell.STOP_SERVICE"
    const val WAKELOCK_TAG = "CloudShell:KeepAliveWakeLock"
    const val WIFILOCK_TAG = "CloudShell:WifiLock"
    const val WAKELOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L
    const val NOTIFICATION_UPDATE_INTERVAL_MS = 60_000L
    const val UI_TIMER_TICK_MS = 1_000L
}
