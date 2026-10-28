package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.AccentSurface
import com.example.ui.theme.AppShapes
import com.example.ui.theme.BorderActive
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.Dimens
import com.example.ui.theme.Motion
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceInteractive
import com.example.ui.theme.TextPrimary

@Composable
fun TerminalKeyBar(
    visible: Boolean,
    onPasteClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSendKey: (keyCode: Int, ctrl: Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    AnimatedVisibility(
        visible = visible,
        enter = Motion.BottomBarEnter,
        exit = Motion.BottomBarExit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .border(Dimens.BorderThin, BorderSubtle),
            color = SurfaceCard
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimens.SpaceMedium,
                        vertical = Dimens.SpaceSmall
                    )
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TerminalKeyButton(
                    label = stringResource(R.string.key_paste),
                    isPrimary = true,
                    testTag = "key_paste_button"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPasteClick()
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_copy),
                    isPrimary = true,
                    testTag = "key_copy_button"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCopyClick()
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_esc),
                    testTag = "key_esc_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_ESCAPE, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_tab),
                    testTag = "key_tab_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_TAB, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_up),
                    testTag = "key_up_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_DPAD_UP, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_down),
                    testTag = "key_down_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_DPAD_DOWN, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_left),
                    testTag = "key_left_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_DPAD_LEFT, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_right),
                    testTag = "key_right_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_DPAD_RIGHT, false)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_ctrl_c),
                    testTag = "key_ctrl_c_button"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSendKey(KeyEvent.KEYCODE_C, true)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_ctrl_l),
                    testTag = "key_ctrl_l_button"
                ) {
                    onSendKey(KeyEvent.KEYCODE_L, true)
                }

                TerminalKeyButton(
                    label = stringResource(R.string.key_enter),
                    isPrimary = true,
                    testTag = "key_enter_button"
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSendKey(KeyEvent.KEYCODE_ENTER, false)
                }
            }
        }
    }
}

@Composable
fun TerminalKeyButton(
    label: String,
    isPrimary: Boolean = false,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = AppShapes.KeyButton,
        color = if (isPrimary) AccentSurface else SurfaceInteractive,
        border = BorderStroke(
            Dimens.BorderThin,
            if (isPrimary) BorderActive else BorderSubtle
        ),
        modifier = Modifier
            .height(Dimens.KeyButtonHeight)
            .defaultMinSize(minWidth = Dimens.KeyButtonMinWidth)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = Dimens.PillPaddingHorizontal),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = TextPrimary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
