package com.example.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.AppConstants
import com.example.service.KeepAliveService
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentSurface
import com.example.ui.theme.AppShapes
import com.example.ui.theme.BgTopGradient
import com.example.ui.theme.BorderActive
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.Dimens
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceInteractive
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TopControlBar(
    isServiceRunning: Boolean,
    isPulseActive: Boolean,
    hasDetectedPinggy: Boolean,
    onStartServiceClick: () -> Unit,
    onStopServiceClick: () -> Unit,
    onRunStartScriptClick: () -> Unit,
    onReconnectPinggyClick: () -> Unit,
    onCopyPinggyClick: () -> Unit,
    onTogglePulseClick: () -> Unit,
    onReloadClick: () -> Unit,
    onHomeClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val topBarBrush = remember {
        Brush.verticalGradient(colors = listOf(BgTopGradient, SurfaceCard))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarBrush)
            .statusBarsPadding()
            .border(Dimens.BorderThin, BorderSubtle)
            .padding(
                horizontal = Dimens.BarPaddingHorizontal,
                vertical = Dimens.BarPaddingVertical
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.terminal_prompt_prefix),
                    color = AccentPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(Dimens.SpaceSmall))
                Text(
                    text = stringResource(R.string.app_name),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(Dimens.SpaceMedium))

                SessionStatusBadge()
            }

            if (isServiceRunning) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStopServiceClick()
                    },
                    shape = AppShapes.ActionButton,
                    border = BorderStroke(Dimens.BorderThin, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceInteractive,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(
                        horizontal = Dimens.SpaceStandard,
                        vertical = Dimens.PillPaddingVertical
                    ),
                    modifier = Modifier
                        .height(Dimens.SessionButtonHeight)
                        .testTag("stop_session_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_stop_session),
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStartServiceClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = TextPrimary
                    ),
                    shape = AppShapes.ActionButton,
                    contentPadding = PaddingValues(
                        horizontal = Dimens.SpaceStandard,
                        vertical = Dimens.PillPaddingVertical
                    ),
                    modifier = Modifier
                        .height(Dimens.SessionButtonHeight)
                        .testTag("start_session_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_start_session),
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.SpaceMedium))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRunStartScriptClick()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = TextPrimary
                ),
                shape = AppShapes.KeyButton,
                contentPadding = PaddingValues(
                    horizontal = Dimens.PillPaddingHorizontal,
                    vertical = Dimens.PillPaddingVertical
                ),
                modifier = Modifier
                    .height(Dimens.TopActionHeight)
                    .testTag("run_start_script_button")
            ) {
                Text(
                    text = stringResource(R.string.action_run_start_script),
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onReconnectPinggyClick()
                },
                shape = AppShapes.KeyButton,
                border = BorderStroke(Dimens.BorderThin, BorderActive),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceInteractive,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(
                    horizontal = Dimens.PillPaddingHorizontal,
                    vertical = Dimens.PillPaddingVertical
                ),
                modifier = Modifier
                    .height(Dimens.TopActionHeight)
                    .testTag("reconnect_pinggy_button")
            ) {
                Text(
                    text = stringResource(R.string.action_reconnect_pinggy),
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCopyPinggyClick()
                },
                shape = AppShapes.KeyButton,
                border = BorderStroke(
                    Dimens.BorderThin,
                    if (hasDetectedPinggy) AccentPrimary else BorderSubtle
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (hasDetectedPinggy) AccentSurface else SurfaceInteractive,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(
                    horizontal = Dimens.PillPaddingHorizontal,
                    vertical = Dimens.PillPaddingVertical
                ),
                modifier = Modifier
                    .height(Dimens.TopActionHeight)
                    .testTag("copy_pinggy_top_button")
            ) {
                Text(
                    text = stringResource(R.string.action_copy_pinggy),
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            PulseStatusChip(
                isPulseActive = isPulseActive,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTogglePulseClick()
                }
            )

            OutlinedButton(
                onClick = onReloadClick,
                shape = AppShapes.KeyButton,
                border = BorderStroke(Dimens.BorderThin, BorderSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceInteractive,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(
                    horizontal = Dimens.PillPaddingHorizontal,
                    vertical = Dimens.PillPaddingVertical
                ),
                modifier = Modifier
                    .height(Dimens.TopActionHeight)
                    .testTag("reload_button")
            ) {
                Text(
                    text = stringResource(R.string.action_reload),
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            OutlinedButton(
                onClick = onHomeClick,
                shape = AppShapes.KeyButton,
                border = BorderStroke(Dimens.BorderThin, BorderSubtle),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SurfaceInteractive,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(
                    horizontal = Dimens.PillPaddingHorizontal,
                    vertical = Dimens.PillPaddingVertical
                ),
                modifier = Modifier
                    .height(Dimens.TopActionHeight)
                    .testTag("home_button")
            ) {
                Text(
                    text = stringResource(R.string.action_home),
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SessionStatusBadge() {
    val isRunning by KeepAliveService.isRunning.collectAsStateWithLifecycle()
    val startRealtime by KeepAliveService.startElapsedRealtime.collectAsStateWithLifecycle()
    var elapsedSec by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isRunning, startRealtime) {
        if (isRunning && startRealtime > 0L) {
            while (true) {
                elapsedSec = ((SystemClock.elapsedRealtime() - startRealtime) / 1000L).coerceAtLeast(0L)
                delay(AppConstants.UI_TIMER_TICK_MS)
            }
        } else {
            elapsedSec = 0L
        }
    }

    Surface(
        shape = AppShapes.Badge,
        color = if (isRunning) AccentSurface else SurfaceInteractive,
        border = BorderStroke(
            Dimens.BorderThin,
            if (isRunning) BorderActive else BorderSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Dimens.BadgePaddingHorizontal,
                vertical = Dimens.BadgePaddingVertical
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.StatusDotSize)
                    .background(
                        color = if (isRunning) AccentPrimary else TextSecondary,
                        shape = AppShapes.StatusDot
                    )
            )
            Spacer(modifier = Modifier.width(Dimens.SpaceXSmall))
            Text(
                text = if (isRunning) {
                    KeepAliveService.formatDuration(elapsedSec)
                } else {
                    stringResource(R.string.status_standby)
                },
                color = if (isRunning) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PulseStatusChip(
    isPulseActive: Boolean,
    onClick: () -> Unit
) {
    val heartbeatCount by KeepAliveService.heartbeatCount.collectAsStateWithLifecycle()

    Surface(
        onClick = onClick,
        shape = AppShapes.KeyButton,
        color = if (isPulseActive) AccentSurface else SurfaceInteractive,
        border = BorderStroke(
            Dimens.BorderThin,
            if (isPulseActive) BorderActive else BorderSubtle
        ),
        modifier = Modifier
            .height(Dimens.TopActionHeight)
            .testTag("toggle_pulse_chip")
    ) {
        Box(
            modifier = Modifier.padding(horizontal = Dimens.PillPaddingHorizontal),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPulseActive) {
                    stringResource(R.string.status_anti_stop_on, heartbeatCount)
                } else {
                    stringResource(R.string.status_anti_stop_off)
                },
                color = if (isPulseActive) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
