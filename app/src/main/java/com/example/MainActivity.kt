package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
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

                // Baris Tombol Atas: START, PINGGY, Desktop, Reload, Home
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Tombol Otomatis Jalankan ./start.sh
                    Button(
                        onClick = {
                            manager.runTerminalCommand("./start.sh")
                            Toast.makeText(context, "Menjalankan ./start.sh...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen),
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "▶ START",
                            color = TerminalBg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Tombol Otomatis Reconnect Pinggy
                    Button(
                        onClick = {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_C, ctrl = true)
                            manager.runTerminalCommand("ssh -p 443 -R0:localhost:5901 -o StrictHostKeyChecking=no tcp@a.pinggy.io")
                            Toast.makeText(context, "Menyambungkan ulang Pinggy...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TerminalYellow),
                        shape = RoundedCornerShape(3.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "⚡ PINGGY",
                            color = TerminalBg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = TerminalSurfaceLight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TerminalBorder)
                    ) {
                        Text(
                            text = if (isPulseActive) "Anti-Stop ($heartbeatCount pings)" else "Anti-Stop: Off",
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
                            text = if (isDesktopMode) "Desktop" else "Mobile",
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
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isImeVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
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
                        // 1. PASTE: Langsung mengetikkan isi clipboard HP ke terminal
                        TerminalKeyButton(label = "PASTE", activeColor = TerminalGreen) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotEmpty()) {
                                manager.pasteText(text)
                                Toast.makeText(context, "Ditempel!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Papan klip HP kosong", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // 2. COPY: Salin teks yang diblok di layar terminal ke HP
                        TerminalKeyButton(label = "COPY", activeColor = TerminalBlue) {
                            manager.copySelectedText()
                        }

                        // 3. Tombol ESC
                        TerminalKeyButton(label = "ESC") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_ESCAPE)
                        }

                        // 4. Tombol TAB (Auto-Complete)
                        TerminalKeyButton(label = "TAB") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_TAB)
                        }

                        // 5. Tombol Panah (Bekerja Tepat 1 Kali Respon!)
                        TerminalKeyButton(label = "▲") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_DPAD_UP)
                        }
                        TerminalKeyButton(label = "▼") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                        TerminalKeyButton(label = "◄") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        }
                        TerminalKeyButton(label = "►") {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        }

                        // 6. Tombol Batal / Stop (^C)
                        TerminalKeyButton(label = "^C", activeColor = TerminalRed) {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_C, ctrl = true)
                        }

                        // 7. Tombol Clear Layar (^L)
                        TerminalKeyButton(label = "^L", activeColor = TerminalYellow) {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_L, ctrl = true)
                        }

                        // 8. Tombol Enter
                        TerminalKeyButton(label = "ENTER", activeColor = TerminalGreen) {
                            manager.sendTerminalKey(KeyEvent.KEYCODE_ENTER)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .navigationBarsPadding()
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
