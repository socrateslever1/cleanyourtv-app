package tv.cleanyour.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.TvId

/**
 * Explicit confirmation of package and target TV before sideload. The picker
 * uses SAF, not arbitrary network downloads, and the existing authenticated
 * ADB session performs installStream. Never hides installation errors.
 */
@Composable
internal fun TvApkInstallDialog(graph: AppGraph, tvId: TvId, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Uri?>(null) }
    var prepared by remember { mutableStateOf<TvApkSideload.Prepared?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onClose() else selection = uri
    }
    LaunchedEffect(tvId) { picker.launch(arrayOf("*/*")) }
    LaunchedEffect(selection) {
        val uri = selection ?: return@LaunchedEffect
        loading = true
        val r = runCatching { TvApkSideload.prepare(context, uri) }
        loading = false
        r.onSuccess { prepared = it }.onFailure { message = it.message ?: "APK inválido." }
    }
    if (loading || sending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.tv_apk_title)) },
            text = { Text(if (sending) stringResource(R.string.tv_apk_progress, progress)
                else stringResource(R.string.tv_apk_preparing)) },
            confirmButton = {}
        )
    } else if (prepared != null) {
        val apk = prepared!!
        AlertDialog(
            onDismissRequest = { apk.discard(); onClose() },
            title = { Text(stringResource(R.string.tv_apk_confirm_title)) },
            text = { Text(stringResource(R.string.tv_apk_confirm_body,
                apk.packageName, apk.version, apk.size / 1048576L)) },
            confirmButton = {
                TextButton(onClick = {
                    prepared = null
                    sending = true
                    scope.launch {
                        message = TvApkSideload.install(graph, tvId, apk) { progress = it }
                        sending = false
                    }
                }) { Text(stringResource(R.string.tv_apk_install)) }
            },
            dismissButton = {
                TextButton(onClick = { apk.discard(); prepared = null; onClose() }) {
                    Text(stringResource(R.string.tv_apk_cancel))
                }
            }
        )
    } else if (message != null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.tv_apk_result_title)) },
            text = { Text(message.orEmpty()) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.tv_apk_close)) }
            }
        )
    }
}
