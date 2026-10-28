package com.example.data

object AppConstants {
    const val CLOUD_SHELL_URL = "https://shell.cloud.google.com"
    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"

    const val CMD_START_SCRIPT = "./start.sh"
    const val CMD_PINGGY_SSH = "ssh -p 443 -R0:localhost:5901 -o StrictHostKeyChecking=no tcp@a.pinggy.io"

    const val BRIDGE_CMD_STATUS = "__CS_CMD_STATUS__"
    const val BRIDGE_COPY_RESULT = "__CS_COPY_RESULT__"
    const val BRIDGE_PINGGY_AUTO = "__CS_PINGGY_AUTO__"
    const val BRIDGE_STATUS_OK = "OK"
    const val BRIDGE_STATUS_FALLBACK = "FALLBACK"

    const val CLIPBOARD_LABEL = "CloudShell"

    const val KEY_DEBOUNCE_MS = 60L
    const val CTRL_C_SETTLE_DELAY_MS = 180L
    const val NATIVE_PASTE_ENTER_DELAY_MS = 50L
    const val CLIPBOARD_RESTORE_DELAY_MS = 220L
    const val PAGE_STOP_INJECT_DELAY_MS = 250L

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
