package com.localai.toolkit.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Field Notes palette.
 *
 * Warm paper and ink carry the interface. The supporting print colours are intentionally
 * earthy rather than neon so screenshots, photos and long-form results remain the focus.
 * Every accent is paired with text or shape; colour is never the only status signal.
 */
val FieldNotesInk = Color(0xFF102A43)
val FieldNotesInkSoft = Color(0xFF365066)
val FieldNotesPaper = Color(0xFFFFFBF2)
val FieldNotesCanvas = Color(0xFFDCE2D4)
val FieldNotesSage = Color(0xFF55705E)
val FieldNotesSageLight = Color(0xFFDDE7D8)
val FieldNotesTomato = Color(0xFFC85D3D)
val FieldNotesTomatoLight = Color(0xFFF4D7CB)
val FieldNotesMustard = Color(0xFFE0AE3A)
val FieldNotesMustardLight = Color(0xFFF5E7B8)
val FieldNotesSky = Color(0xFF6EA8D0)
val FieldNotesSkyLight = Color(0xFFD9EAF3)

private val DarkInk = Color(0xFF091621)
private val DarkPaper = Color(0xFF111F2A)
private val DarkPaperHigh = Color(0xFF1A2B37)
private val DarkOutline = Color(0xFF516777)

val LocalAiDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFC6AF),
    onPrimary = Color(0xFF54200F),
    primaryContainer = Color(0xFF74351F),
    onPrimaryContainer = Color(0xFFFFDBCE),
    inversePrimary = FieldNotesTomato,
    secondary = Color(0xFFB9CEB8),
    onSecondary = Color(0xFF213525),
    secondaryContainer = Color(0xFF374C3A),
    onSecondaryContainer = Color(0xFFD5EAD4),
    tertiary = Color(0xFFA9CFE8),
    onTertiary = Color(0xFF123447),
    tertiaryContainer = Color(0xFF2B4C60),
    onTertiaryContainer = Color(0xFFCBE9FA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkInk,
    onBackground = Color(0xFFF1EDE3),
    surface = DarkInk,
    onSurface = Color(0xFFF1EDE3),
    surfaceVariant = DarkPaperHigh,
    onSurfaceVariant = Color(0xFFC8D0D4),
    surfaceContainerLowest = Color(0xFF061019),
    surfaceContainerLow = DarkPaper,
    surfaceContainer = Color(0xFF152530),
    surfaceContainerHigh = DarkPaperHigh,
    surfaceContainerHighest = Color(0xFF223541),
    outline = Color(0xFF8A9AA4),
    outlineVariant = DarkOutline,
    scrim = Color.Black,
    inverseSurface = FieldNotesPaper,
    inverseOnSurface = FieldNotesInk,
)

val LocalAiLightColorScheme = lightColorScheme(
    primary = FieldNotesInk,
    onPrimary = FieldNotesPaper,
    primaryContainer = FieldNotesSkyLight,
    onPrimaryContainer = FieldNotesInk,
    inversePrimary = Color(0xFFA9CFE8),
    secondary = FieldNotesSage,
    onSecondary = Color.White,
    secondaryContainer = FieldNotesSageLight,
    onSecondaryContainer = Color(0xFF183022),
    tertiary = FieldNotesTomato,
    onTertiary = Color.White,
    tertiaryContainer = FieldNotesTomatoLight,
    onTertiaryContainer = Color(0xFF4C1707),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = FieldNotesPaper,
    onBackground = FieldNotesInk,
    surface = FieldNotesPaper,
    onSurface = FieldNotesInk,
    surfaceVariant = Color(0xFFE8E5DA),
    onSurfaceVariant = FieldNotesInkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF6EC),
    surfaceContainer = Color(0xFFF3EEE3),
    surfaceContainerHigh = Color(0xFFECE6D9),
    surfaceContainerHighest = Color(0xFFE4DED1),
    outline = Color(0xFF6C777B),
    outlineVariant = Color(0xFFC9C8C0),
    scrim = Color.Black,
    inverseSurface = FieldNotesInk,
    inverseOnSurface = FieldNotesPaper,
)

data class StatusColors(
    val ready: Color,
    val onReadyContainer: Color,
    val readyContainer: Color,
    val needsDownload: Color,
    val needsDownloadContainer: Color,
    val onNeedsDownloadContainer: Color,
    val unsupported: Color,
    val unsupportedContainer: Color,
    val onUnsupportedContainer: Color,
)

val DarkStatusColors = StatusColors(
    ready = Color(0xFFB9CEB8),
    readyContainer = Color(0xFF374C3A),
    onReadyContainer = Color(0xFFD5EAD4),
    needsDownload = Color(0xFFA9CFE8),
    needsDownloadContainer = Color(0xFF2B4C60),
    onNeedsDownloadContainer = Color(0xFFCBE9FA),
    unsupported = Color(0xFF93A1A9),
    unsupportedContainer = DarkPaperHigh,
    onUnsupportedContainer = Color(0xFFC8D0D4),
)

val LightStatusColors = StatusColors(
    ready = FieldNotesSage,
    readyContainer = FieldNotesSageLight,
    onReadyContainer = Color(0xFF183022),
    needsDownload = FieldNotesSky,
    needsDownloadContainer = FieldNotesSkyLight,
    onNeedsDownloadContainer = FieldNotesInk,
    unsupported = Color(0xFF707775),
    unsupportedContainer = Color(0xFFE8E5DA),
    onUnsupportedContainer = Color(0xFF3F4946),
)
