package com.localai.toolkit.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing scale.
 *
 * A small fixed set keeps rhythm consistent across nine tool screens. [ScreenHorizontal]
 * is the single source of truth for the page gutter so every screen lines up.
 */
object Spacing {
    val XXS = 2.dp
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
    val XXXL = 32.dp
    val Huge = 48.dp

    /** Page gutter. */
    val ScreenHorizontal = 20.dp

    /** Minimum touch target, per the Material accessibility guidance. */
    val MinTouchTarget = 48.dp
}
