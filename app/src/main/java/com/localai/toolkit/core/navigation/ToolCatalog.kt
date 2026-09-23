package com.localai.toolkit.core.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import com.localai.toolkit.R
import com.localai.toolkit.domain.model.ToolId

/**
 * Presentation metadata for each tool.
 *
 * Home, the share router and the universal action screen all render from this single
 * list, so adding a tool means adding one entry here plus its screen - no screen has a
 * hand-maintained copy of the tool list.
 */
data class ToolEntry(
    val id: ToolId,
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
)

object ToolCatalog {

    val entries: List<ToolEntry> = listOf(
        ToolEntry(
            id = ToolId.ASK,
            route = Destination.ASK,
            titleRes = R.string.tool_ask_title,
            descriptionRes = R.string.tool_ask_description,
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
        ),
        ToolEntry(
            id = ToolId.SUMMARIZE,
            route = Destination.SUMMARIZE,
            titleRes = R.string.tool_summarize_title,
            descriptionRes = R.string.tool_summarize_description,
            icon = Icons.AutoMirrored.Outlined.Notes,
        ),
        ToolEntry(
            id = ToolId.REWRITE,
            route = Destination.REWRITE,
            titleRes = R.string.tool_rewrite_title,
            descriptionRes = R.string.tool_rewrite_description,
            icon = Icons.Outlined.AutoAwesome,
        ),
        ToolEntry(
            id = ToolId.PROOFREAD,
            route = Destination.PROOFREAD,
            titleRes = R.string.tool_proofread_title,
            descriptionRes = R.string.tool_proofread_description,
            icon = Icons.Outlined.Spellcheck,
        ),
        ToolEntry(
            id = ToolId.OCR,
            route = Destination.OCR,
            titleRes = R.string.tool_ocr_title,
            descriptionRes = R.string.tool_ocr_description,
            icon = Icons.Outlined.TextFields,
        ),
        ToolEntry(
            id = ToolId.TRANSLATE,
            route = Destination.TRANSLATE,
            titleRes = R.string.tool_translate_title,
            descriptionRes = R.string.tool_translate_description,
            icon = Icons.Outlined.Translate,
        ),
        ToolEntry(
            id = ToolId.IMAGE,
            route = Destination.IMAGE,
            titleRes = R.string.tool_image_title,
            descriptionRes = R.string.tool_image_description,
            icon = Icons.Outlined.Image,
        ),
        ToolEntry(
            id = ToolId.TRANSCRIBE,
            route = Destination.TRANSCRIBE,
            titleRes = R.string.tool_transcribe_title,
            descriptionRes = R.string.tool_transcribe_description,
            icon = Icons.Outlined.GraphicEq,
        ),
        ToolEntry(
            id = ToolId.DEVELOPER,
            route = Destination.DEVELOPER,
            titleRes = R.string.tool_developer_title,
            descriptionRes = R.string.tool_developer_description,
            icon = Icons.Outlined.Code,
        ),
    )

    private val byId: Map<ToolId, ToolEntry> = entries.associateBy { it.id }

    operator fun get(id: ToolId): ToolEntry = requireNotNull(byId[id]) {
        "No ToolCatalog entry for $id"
    }
}
