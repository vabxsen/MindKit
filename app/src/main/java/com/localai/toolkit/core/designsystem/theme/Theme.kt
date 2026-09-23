package com.localai.toolkit.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localai.toolkit.domain.model.ThemeMode

/** Crisp editorial geometry with rounding reserved for interactive surfaces. */
val LocalAiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * Whether the platform reports that animations should be reduced.
 *
 * Provided once at the theme so individual components do not each have to reach for a
 * Context. See [rememberReducedMotion].
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

object LocalAiTheme {
    val statusColors: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * The app theme.
 *
 * @param themeMode the user's stored preference; [ThemeMode.SYSTEM] follows the device.
 * @param dynamicColor opt in to wallpaper colours on Android 12+. The user can turn this
 *   off in Settings, which is why it is a parameter rather than an unconditional check.
 */
@Composable
fun LocalAiTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && supportsDynamic && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic && !darkTheme -> dynamicLightColorScheme(context)
        darkTheme -> LocalAiDarkColorScheme
        else -> LocalAiLightColorScheme
    }

    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(
        LocalStatusColors provides statusColors,
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LocalAiTypography,
            shapes = LocalAiShapes,
            content = content,
        )
    }
}
