package com.localai.toolkit.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** Motion timings. One easing curve everywhere keeps transitions feeling related. */
object Motion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    const val DURATION_SHORT = 120
    const val DURATION_MEDIUM = 240
    const val DURATION_LONG = 380
}

/**
 * Reads the system animation scale to decide whether motion should be reduced.
 *
 * Android has no dedicated "prefers reduced motion" flag; turning animations off in
 * Developer options or in Accessibility sets the animator duration scale to 0, and that
 * is the signal apps are expected to honour. Returns false inside Compose previews so
 * previews still render their intended state.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}

/**
 * A tween that collapses to an instant change when the user has asked for reduced motion.
 *
 * Using this instead of a raw [tween] means a single call site stays accessible without
 * an `if` around every animation.
 */
@Composable
fun <T> motionTween(
    durationMillis: Int = Motion.DURATION_MEDIUM,
    easing: androidx.compose.animation.core.Easing = Motion.Standard,
): FiniteAnimationSpec<T> {
    val reduced = LocalReducedMotion.current
    return tween(durationMillis = if (reduced) 0 else durationMillis, easing = easing)
}
