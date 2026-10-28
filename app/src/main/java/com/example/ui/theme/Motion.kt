package com.example.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

object Motion {
    const val DURATION_FAST = 140
    const val DURATION_STANDARD = 220

    val StandardEasing = FastOutSlowInEasing

    fun <T> standardSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val BottomBarEnter: EnterTransition = fadeIn(
        animationSpec = tween(durationMillis = DURATION_STANDARD, easing = StandardEasing)
    ) + slideInVertically(
        animationSpec = tween(durationMillis = DURATION_STANDARD, easing = StandardEasing),
        initialOffsetY = { it }
    )

    val BottomBarExit: ExitTransition = fadeOut(
        animationSpec = tween(durationMillis = DURATION_FAST, easing = StandardEasing)
    ) + slideOutVertically(
        animationSpec = tween(durationMillis = DURATION_FAST, easing = StandardEasing),
        targetOffsetY = { it }
    )
}
