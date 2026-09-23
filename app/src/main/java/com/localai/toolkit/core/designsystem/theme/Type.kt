package com.localai.toolkit.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val UiFont = FontFamily.SansSerif

private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/** Editorial type scale: bold display faces, quiet body copy and compact labels. */
val LocalAiTypography = Typography(
    displaySmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp, lineHeightStyle = TrimmedLineHeight),
    headlineLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-0.8).sp, lineHeightStyle = TrimmedLineHeight),
    headlineMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.45).sp, lineHeightStyle = TrimmedLineHeight),
    headlineSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.25).sp, lineHeightStyle = TrimmedLineHeight),
    titleLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.15).sp, lineHeightStyle = TrimmedLineHeight),
    titleMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, lineHeightStyle = TrimmedLineHeight),
    titleSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.15.sp, lineHeightStyle = TrimmedLineHeight),
    bodyLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.05.sp, lineHeightStyle = TrimmedLineHeight),
    bodyMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp, lineHeightStyle = TrimmedLineHeight),
    bodySmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp, lineHeightStyle = TrimmedLineHeight),
    labelLarge = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp, lineHeightStyle = TrimmedLineHeight),
    labelMedium = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp, lineHeightStyle = TrimmedLineHeight),
    labelSmall = TextStyle(fontFamily = UiFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.65.sp, lineHeightStyle = TrimmedLineHeight),
)

val MonospaceBody = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)
