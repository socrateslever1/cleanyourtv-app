package tv.cleanyour.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Updates are opt-in: no APK is installed silently. Only an HTTPS asset from the
 * authenticated owner's GitHub release is accepted. Android still enforces the
 * installed package's signing identity, and always asks the user to install.
 */
internal object AppUpdateFeed {
    const val API = "https://api.github.com/repos/socrateslever1/cleanyourtv-app/releases/latest"
    private const val APK = "CleanYourTV-PTBR-Chromecast4K.apk"
    private val tagPattern = Regex("""^ptbr-r([0-9]+)-[a-f0-9]{8}$""")
    private val installedPattern = Regex("""-ptbr\.r([0-9]+)$""")

    data class Available(val tag: String, val url: String, val sha256: String, val bytes: Long)

    internal fun offered(tag: String, version: String): Boolean {
        val remote = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        val current = installedPattern.find(version)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return remote > current
    }

    suspend fun available(context: Context): Available? = withContext(Dispatchers.IO) {
        val connection = (URL(API).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CleanYourTV-ptBR-Android")
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (payload.length > 524288) return@withContext null
            val json = JSONObject(payload)
            val tag = json.optString("tag_name")
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            if (!offered(tag, installed)) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") != APK) continue
                val link = asset.optString("browser_download_url")
                val uri = runCatching { URI(link) }.getOrNull() ?: continue
                if (uri.scheme != "https" || uri.host != "github.com"
                    || uri.rawUserInfo != null || uri.port != -1
                    || uri.rawQuery != null || uri.rawFragment != null
                    || !uri.path.startsWith("/socrateslever1/cleanyourtv-app/releases/download/$tag/")) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                val size = asset.optLong("size")
                if (!digest.matches(Regex("[a-fA-F0-9]{64}")) || size !in 1..157286400) continue
                return@withContext Available(tag, link, digest.lowercase(), size)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Read-only release check on app start (max once each 12 h).
 * No background jobs, no service and no privileged install permission.
 */
@Composable
internal fun UpdateNotifier() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppUpdateFeed.Available?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("owned_build_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("checked_at", 0L)
        if (last > 0L && now >= last && now - last < 12L * 60L * 60L * 1000L) return@LaunchedEffect
        prefs.edit().putLong("checked_at", now).apply()
        val candidate = AppUpdateFeed.available(context) ?: return@LaunchedEffect
        if (prefs.getString("dismissed", "") != candidate.tag) release = candidate
    }
    val found = release
    if (found != null) {
        AlertDialog(
            onDismissRequest = {
                context.getSharedPreferences("owned_build_updates", Context.MODE_PRIVATE)
                    .edit().putString("dismissed", found.tag).apply()
                release = null
            },
            title = { Text(stringResource(R.string.app_update_title)) },
            text = { Text(stringResource(if (busy) R.string.app_update_downloading else R.string.app_update_body)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        result = TvSelfUpdate.downloadAndInstall(context, found)
                        busy = false
                        if (result == null) {
                            context.getSharedPreferences("owned_build_updates", Context.MODE_PRIVATE)
                                .edit().putString("dismissed", found.tag).apply()
                        }
                        release = null
                    }
                }) {
                    Text(stringResource(R.string.app_update_download))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    context.getSharedPreferences("owned_build_updates", Context.MODE_PRIVATE)
                        .edit().putString("dismissed", found.tag).apply()
                    release = null
                }) {
                    Text(stringResource(R.string.app_update_later))
                }
            }
        )
    }
    result?.let { err ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.app_update_install_problem)) },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } }
        )
    }
}
