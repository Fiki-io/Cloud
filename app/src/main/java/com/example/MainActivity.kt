package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.gecko.CloudShellManager
import com.example.service.KeepAliveService
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlue
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalGreen
import com.example.ui.theme.TerminalRed
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalSurfaceLight
import com.example.ui.theme.TerminalYellow
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import org.mozilla.geckoview.GeckoView

class MainActivity : ComponentActivity() {

    private lateinit var cloudShellManager: CloudShellManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cloudShellManager = CloudShellManager(this)
        enableEdgeToEdge()

        requestIgnoreBatteryOptimizations()

        setContent {
            MyApplicationTheme {
                BackHandler {
                    moveTaskToBack(true)
                }

                CloudShellApp(
                    manager = cloudShellManager,
                    onStartService = { startKeepAliveService() },
                    onStopService = { stopKeepAliveService() }
                )
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onDestroy() {
        if (!KeepAliveService.isRunning.value) {
            cloudShellManager.destroy()
        }
        super.onDestroy()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CloudShellApp(
    manager: CloudShellManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current

    val isServiceRunning by KeepAliveService.isRunning.collectAsState()
    val elapsedSeconds by KeepAliveService.elapsedSeconds.collectAsState()
    val lastHeartbeat by KeepAliveService.lastHeartbeat.collectAsState()
    val heartbeatCount by KeepAliveService.heartbeatCount.collectAsState()

    val isLoading by manager.isLoading.collectAsState()
    val progress by manager.progress.collectAsState()
    val isDesktopMode by manager.isDesktopMode.collectAsState()
    val isPulseActive by manager.isPulseActive.collectAsState()

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onStartService()
        } else {
            Toast.makeText(context, "Izin notifikasi diperlukan agar sesi tetap aktif", Toast.LENGTH_SHORT).show()
            onStartService()
        }
    }

    val isImeVisible = WindowInsets.isImeVisible

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TerminalBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .statusBarsPadding()
                    .border(1.dp, TerminalBorder)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ">_ ",
                            color = TerminalGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Cloud Shell",
                            color = TextLight,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isServiceRunning) TerminalGreen.copy(alpha = 0.2f) else TerminalBorder.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isServiceRunning) TerminalGreen else TextMuted
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (isServiceRunning) TerminalGreen else TextMuted,
                                            shape = RoundedCornerShape(3.dp)
                                        )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isServiceRunning) formatDuration(elapsedSeconds) else "STANDBY",
                                    color = if (isServiceRunning) TerminalGreen else TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (isServiceRunning) {
                        Button(
                            onClick = onStopService,
                            colors = ButtonDefaults.buttonColors(containerColor = TerminalRed),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "STOP SESI",
                                color = TextLight,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onStartService()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "MULAI SESI",
                                color = TextLight,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = TerminalSurfaceLight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalBorder)
                    ) {
                        Text(
                            text = if (isPulseActive) "Anti-Stop: Aktif ($heartbeatCount pings)" else "Anti-Stop: Nonaktif",
                            color = if (isPulseActive) TerminalBlue else TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = { manager.toggleDesktopMode() },
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (isDesktopMode) "Mode: Desktop" else "Mode: Mobile",
                            color = TextLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { manager.reload() },
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "↻ Reload",
                            color = TextLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { manager.loadDefaultUrl() },
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "Home",
                            color = TextLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            manager.injectPulse()
                            KeepAliveService.recordHeartbeat()
                            Toast.makeText(context, "Sinyal Anti-Stop terkirim", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "Ping",
                            color = TerminalYellow,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // KUNCI PERBAIKAN: Seluruh Box konten diikat dengan .imePadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .navigationBarsPadding() // Jarak dari bar navigasi bawah
                .imePadding()            // Otomatis terangkat ke ATAS KEYBOARD saat keyboard muncul!
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = TerminalGreen,
                        trackColor = TerminalSurface
                    )
                }

                // GeckoView Engine
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(TerminalBg)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            GeckoView(ctx).apply {
                                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                                background = null
                                manager.attachGeckoView(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Bilah Tombol Terminal: DIJAMIN 100% NANGKRING DI ATAS KEYBOARD
            AnimatedVisibility(
                visible = isImeVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TerminalBorder),
                    color = TerminalSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalKeyButton(label = "ESC") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_ESCAPE,
                                key = "Escape",
                                code = "Escape",
                                jsKeyCode = 27,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "TAB") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_TAB,
                                key = "Tab",
                                code = "Tab",
                                jsKeyCode = 9,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(
                            label = "CTRL",
                            isActive = ctrlActive,
                            activeColor = TerminalYellow
                        ) {
                            ctrlActive = !ctrlActive
                        }
                        TerminalKeyButton(
                            label = "ALT",
                            isActive = altActive,
                            activeColor = TerminalYellow
                        ) {
                            altActive = !altActive
                        }
                        TerminalKeyButton(label = "▲") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_DPAD_UP,
                                key = "ArrowUp",
                                code = "ArrowUp",
                                jsKeyCode = 38,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "▼") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                                key = "ArrowDown",
                                code = "ArrowDown",
                                jsKeyCode = 40,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "◄") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                                key = "ArrowLeft",
                                code = "ArrowLeft",
                                jsKeyCode = 37,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "►") {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                                key = "ArrowRight",
                                code = "ArrowRight",
                                jsKeyCode = 39,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "^C", activeColor = TerminalRed) {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_C,
                                key = "c",
                                code = "KeyC",
                                jsKeyCode = 67,
                                ctrl = true,
                                alt = false
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "^D", activeColor = TerminalYellow) {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_D,
                                key = "d",
                                code = "KeyD",
                                jsKeyCode = 68,
                                ctrl = true,
                                alt = false
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "^Z", activeColor = TerminalYellow) {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_Z,
                                key = "z",
                                code = "KeyZ",
                                jsKeyCode = 90,
                                ctrl = true,
                                alt = false
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "^L", activeColor = TerminalBlue) {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_L,
                                key = "l",
                                code = "KeyL",
                                jsKeyCode = 76,
                                ctrl = true,
                                alt = false
                            )
                            ctrlActive = false
                            altActive = false
                        }
                        TerminalKeyButton(label = "ENTER", activeColor = TerminalGreen) {
                            manager.sendTerminalKey(
                                androidKeyCode = KeyEvent.KEYCODE_ENTER,
                                key = "Enter",
                                code = "Enter",
                                jsKeyCode = 13,
                                ctrl = ctrlActive,
                                alt = altActive
                            )
                            ctrlActive = false
                            altActive = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalKeyButton(
    label: String,
    isActive: Boolean = false,
    activeColor: Color = TerminalGreen,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(3.dp),
        color = if (isActive) activeColor.copy(alpha = 0.3f) else TerminalSurfaceLight,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) activeColor else TerminalBorder
        ),
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isActive) activeColor else TextLight,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return String.format("%02d:%02d:%02d", hrs, mins, secs)
}
