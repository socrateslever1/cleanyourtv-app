package tv.cleanyour.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.InstallOutcome

/**
 * Local APK selected by the owner. There is no remote APK store, no arbitrary URLs,
 * no auto-install, no bulk install and no need to enable TV unknown-source settings.
 * ADB connection must already be authenticated by the same owner.
 */
internal object TvApkSideload {
    private const val MAX_BYTES = 250L * 1024L * 1024L
    data class Prepared(
        val file: File, val packageName: String, val version: String,
        val minSdk: Int, val sha256: String, val size: Long
    ) {
        fun discard() { file.delete() }
    }

    @Suppress("DEPRECATION")
    suspend fun prepare(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "tv-installer")
        if (!dir.isDirectory && !dir.mkdirs()) error("Sem armazenamento para preparar o APK.")
        val file = File(dir, "sideload-${UUID.randomUUID()}.apk")
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val stream = context.contentResolver.openInputStream(uri) ?: error("Arquivo não acessível.")
            stream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > MAX_BYTES) error("O APK excede 250 MB.")
                        sha.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (bytes < 1024L) error("O arquivo selecionado não é um APK válido.")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                        else PackageManager.GET_SIGNATURES
            val parsed = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("Arquivo não reconhecido como APK. Pacotes XAPK/APKS exigem outro instalador.")
            val signed = if (Build.VERSION.SDK_INT >= 28)
                !parsed.signingInfo?.apkContentsSigners.isNullOrEmpty()
                else !parsed.signatures.isNullOrEmpty()
            if (!signed) error("APK não possui assinatura digital verificável.")
            val minSdk = parsed.applicationInfo?.minSdkVersion ?: 1
            val hash = sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            Prepared(file, parsed.packageName, parsed.versionName.orEmpty(), minSdk, hash, bytes)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun install(graph: AppGraph, tvId: TvId, apk: Prepared, progress: (Int)->Unit): String =
        withContext(Dispatchers.IO) {
            try {
                val session = graph.access.live(tvId)
                    ?: return@withContext "TV desconectada. Reconecte a TCL antes de instalar."
                if (session.identity.sdk < apk.minSdk)
                    return@withContext "O APK exige Android ${apk.minSdk}, superior ao Android da TV (${session.identity.sdk})."
                val ref = BlobRef(Sha256(apk.sha256), apk.size, apk.file.absolutePath)
                val hold = session.hold()
                try {
                    when (val outcome = session.installStream(ref, apk.size) { transferred ->
                        progress(((transferred * 100L) / apk.size).toInt().coerceIn(0, 100))
                    }) {
                        is InstallOutcome.Installed -> "Aplicativo ${apk.packageName} instalado na TV. Abra-o na lista de aplicativos."
                        is InstallOutcome.Failed -> "A instalação foi recusada pela TV (${outcome.code}): ${outcome.detail.take(240)}"
                    }
                } finally { hold.close() }
            } catch (e: Exception) {
                "A instalação não foi concluída: ${e.message?.take(240) ?: e.javaClass.simpleName}"
            } finally {
                apk.discard()
            }
        }
}
