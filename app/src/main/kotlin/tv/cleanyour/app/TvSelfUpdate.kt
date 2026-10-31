package tv.cleanyour.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-approved APK update path. Verifies GitHub's SHA-256 and the existing
 * package's signing identity before requesting installation from Android.
 */
internal object TvSelfUpdate {
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private val hosts = setOf("github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com")

    suspend fun downloadAndInstall(context: Context, release: AppUpdateFeed.Available): String? =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "owned-updates")
            val part = File(directory, "update.part")
            val file = File(directory, "update.apk")
            try {
                if (!directory.isDirectory && !directory.mkdirs()) error("Sem espaço para salvar a atualização.")
                require(release.bytes in 1..MAX_APK_BYTES) { "APK acima do limite permitido." }
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val connection = openRelease(release.url)
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK)
                        error("Servidor respondeu HTTP ${connection.responseCode}.")
                    connection.inputStream.use { input ->
                        FileOutputStream(part).use { out ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                count += n
                                if (count > release.bytes || count > MAX_APK_BYTES)
                                    error("Download maior que o informado pelo GitHub.")
                                digest.update(chunk, 0, n)
                                out.write(chunk, 0, n)
                            }
                            out.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                if (count != release.bytes) error("Download incompleto.")
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (sha != release.sha256.lowercase()) error("SHA-256 divergente. O APK foi rejeitado.")
                if (file.exists() && !file.delete()) error("Falha ao substituir atualização anterior.")
                if (!part.renameTo(file)) error("Falha ao preparar atualização.")
                validateSignature(context, file)
                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@withContext "Autorize a instalação de APKs para o CleanYourTV nas configurações abertas e toque em baixar novamente."
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e: Exception) {
                e.message ?: "Falha ao verificar o APK."
            } finally { part.delete() }
        }

    private fun openRelease(start: String): HttpsURLConnection {
        var location = URI(start)
        repeat(6) {
            if (location.scheme != "https" || location.host !in hosts ||
                location.userInfo != null || location.port != -1) error("Destino não autorizado.")
            val c = location.toURL().openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "CleanYourTV-ptBR-updater")
            when (val code = c.responseCode) {
                HttpURLConnection.HTTP_OK -> return c
                301, 302, 303, 307, 308 -> {
                    val next = c.getHeaderField("Location") ?: error("Redirecionamento sem endereço.")
                    c.disconnect()
                    location = location.resolve(next)
                }
                else -> { c.disconnect(); error("Erro HTTP $code.") }
            }
        }
        error("Redirecionamentos em excesso.")
    }

    @Suppress("DEPRECATION")
    private fun validateSignature(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
        val current = pm.getPackageInfo(context.packageName, flags)
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Arquivo não contém APK válido.")
        if (apk.packageName != current.packageName) error("APK pertence a outro aplicativo.")
        val cv = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        val nv = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode else apk.versionCode.toLong()
        if (nv <= cv) error("O APK não é uma versão mais nova.")
        val before = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }
                     else current.signatures?.map { it.toByteArray().toList() }
        val after = if (Build.VERSION.SDK_INT >= 28) apk.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }
                    else apk.signatures?.map { it.toByteArray().toList() }
        if (before.isNullOrEmpty() || after.isNullOrEmpty() || before.toSet() != after.toSet())
            error("Assinatura digital incompatível. Configure uma chave permanente no GitHub para atualizar sem desinstalar.")
    }
}
