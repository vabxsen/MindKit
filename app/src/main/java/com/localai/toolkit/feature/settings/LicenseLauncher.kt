package com.localai.toolkit.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import com.localai.toolkit.R
import kotlinx.coroutines.launch

/** The build plugin packages dependency notices; opening them never sends user content. */
@Composable
internal fun licenseLauncher(snackbar: SnackbarHostState): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.settings_licenses)
    val failureMessage = stringResource(R.string.licenses_open_failed)
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    return {
        try {
            // Use the currently selected app palette, including explicit Light/Dark mode.
            OssLicensesMenuActivity.setTheme(colors, colors, typography)
            OssLicensesMenuActivity.setActivityTitle(title)
            context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(failureMessage) }
        } catch (_: SecurityException) {
            scope.launch { snackbar.showSnackbar(failureMessage) }
        }
    }
}
