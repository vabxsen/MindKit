package com.localai.toolkit.domain.model

/** Theme preference, independent of the system setting until the user picks one. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * All user-adjustable settings, as one immutable value.
 *
 * Defaults are chosen so a first run is private by default and needs no setup.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // The Field Notes palette is the default brand experience; wallpaper colours remain opt-in.
    val dynamicColor: Boolean = false,
    /** History is on by default; turning it off also stops new writes immediately. */
    val saveHistory: Boolean = true,
    val onboardingCompleted: Boolean = false,
    /** Shows mapped technical detail alongside friendly errors. Off for normal users. */
    val verboseErrors: Boolean = false,
    val lastTranslateSource: String? = null,
    val lastTranslateTarget: String? = null,
    /** Transient read health, never persisted. Unknown preferences must not enable history. */
    val storageReadFailed: Boolean = false,
)
