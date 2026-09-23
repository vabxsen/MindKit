package com.localai.toolkit.core.navigation

/**
 * Every route in the app, in one place.
 *
 * Routes are plain strings rather than typed objects because they are also referenced
 * from the share router and from deep links. Long payloads are never encoded into a
 * route - see [com.localai.toolkit.core.navigation.ToolHandoff] for how text and images
 * move between tools.
 */
object Destination {
    const val ONBOARDING = "onboarding"

    // Bottom navigation destinations.
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    // Settings detail.
    const val DEVICE_AI = "settings/device_ai"
    const val MODELS = "settings/models"
    const val PRIVACY = "settings/privacy"
    const val ABOUT = "settings/about"

    // Tools.
    const val ASK = "tool/ask"
    const val SUMMARIZE = "tool/summarize"
    const val REWRITE = "tool/rewrite"
    const val PROOFREAD = "tool/proofread"
    const val OCR = "tool/ocr"
    const val TRANSLATE = "tool/translate"
    const val IMAGE = "tool/image"
    const val TRANSCRIBE = "tool/transcribe"
    const val DEVELOPER = "tool/developer"

    /** Developer sub-tool. [DEVELOPER_TOOL_ARG] is the enum name of the sub-tool. */
    const val DEVELOPER_TOOL_ARG = "devTool"
    const val DEVELOPER_TOOL = "tool/developer/{$DEVELOPER_TOOL_ARG}"

    fun developerTool(name: String): String = "tool/developer/$name"

    /** Landing screen for content shared into the app from another app. */
    const val SHARE_ROUTER = "share"

    /** History detail. [HISTORY_ID_ARG] is the row id. */
    const val HISTORY_ID_ARG = "historyId"
    const val HISTORY_DETAIL = "history/{$HISTORY_ID_ARG}"

    fun historyDetail(id: Long): String = "history/$id"

    /** Routes that show the bottom navigation bar. */
    val topLevelRoutes: Set<String> = setOf(HOME, HISTORY, SETTINGS)
}
