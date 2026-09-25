package com.localai.toolkit.core.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.localai.toolkit.R
import com.localai.toolkit.core.util.copyToClipboard
import com.localai.toolkit.core.util.shareText
import com.localai.toolkit.core.util.shouldShowCopyConfirmation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared by result and original-text cards, and by stored history. */
internal class TextActionHandler(
    private val context: Context,
    private val feedback: (Int) -> Unit,
) {
    fun copy(label: String, text: String) {
        if (!context.copyToClipboard(label, text)) {
            feedback(R.string.copy_failed)
        } else if (shouldShowCopyConfirmation()) {
            feedback(R.string.copied_to_clipboard)
        }
    }

    fun share(text: String) {
        if (!context.shareText(text)) feedback(R.string.share_failed)
    }
}

@Composable
internal fun rememberTextActionHandler(snackbar: SnackbarHostState): TextActionHandler {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return remember(context, resources, snackbar, scope) {
        var feedbackJob: Job? = null
        TextActionHandler(context) { messageRes ->
            // Repeated taps update this action's feedback rather than queueing old messages.
            feedbackJob?.cancel()
            feedbackJob = scope.launch { snackbar.showSnackbar(resources.getString(messageRes)) }
        }
    }
}
