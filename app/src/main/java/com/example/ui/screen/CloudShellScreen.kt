package com.example.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.service.KeepAliveService
import com.example.ui.components.TerminalKeyBar
import com.example.ui.components.TopControlBar
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.BgPrimary
import com.example.ui.theme.Dimens
import com.example.ui.theme.Motion
import com.example.ui.theme.SurfaceCard
import com.example.viewmodel.CloudShellViewModel
import org.mozilla.geckoview.GeckoView

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudShellScreen(
    viewModel: CloudShellViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current

    val isServiceRunning by KeepAliveService.isRunning.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val isPulseActive by viewModel.isPulseActive.collectAsStateWithLifecycle()
    val lastPinggyUrl by viewModel.lastPinggyUrl.collectAsStateWithLifecycle()

    val animatedProgress by animateFloatAsState(
        targetValue = (progress.coerceIn(0, 100)) / 100f,
        animationSpec = tween(
            durationMillis = Motion.DURATION_FAST,
            easing = Motion.StandardEasing
        ),
        label = "load_progress"
    )

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_notification_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
        onStartService()
    }

    val isImeVisible = WindowInsets.isImeVisible

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BgPrimary,
        topBar = {
            TopControlBar(
                isServiceRunning = isServiceRunning,
                isPulseActive = isPulseActive,
                hasDetectedPinggy = !lastPinggyUrl.isNullOrBlank(),
                onStartServiceClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onStartService()
                    }
                },
                onStopServiceClick = onStopService,
                onRunStartScriptClick = { viewModel.runStartScript(context) },
                onReconnectPinggyClick = { viewModel.reconnectPinggy(context) },
                onCopyPinggyClick = { viewModel.copyPinggyLink() },
                onTogglePulseClick = { viewModel.togglePulse() },
                onReloadClick = { viewModel.reload() },
                onHomeClick = { viewModel.loadHome() }
            )
        },
        bottomBar = {
            TerminalKeyBar(
                visible = isImeVisible,
                onPasteClick = { viewModel.pasteFromSystemClipboard(context) },
                onCopyClick = { viewModel.copyPinggyLink() },
                onSendKey = { keyCode, ctrl -> viewModel.sendKey(keyCode, ctrl) }
            )
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
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimens.ProgressHeight),
                        color = AccentPrimary,
                        trackColor = SurfaceCard
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(BgPrimary)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            GeckoView(ctx).apply {
                                // BACKEND_TEXTURE_VIEW memastikan tampilan GeckoView ter-render di dalam Compose
                                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                                background = null
                                viewModel.attachGeckoView(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
