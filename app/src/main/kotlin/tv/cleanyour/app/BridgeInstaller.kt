package tv.cleanyour.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.InstallOutcome

/**
 * Explicit install of the signed, bundled TV companion. No downloads, silent
 * installation or fallback to an unverified external APK.
 */
internal object BridgeInstaller {
    private const val ASSET = "bridge/CleanYourTVBridge.apk"
    private const val MAX_APK = 8L * 1024 * 1024
    data class Result(val installed: Boolean, val message: String)

    @OptIn(RawShell::class)
    suspend fun installed(graph: AppGraph, id: TvId): Boolean = withContext(Dispatchers.IO) {
        val session = graph.access.live(id) ?: return@withContext false
        val response = session.exec(ShellLine("pm path tv.cleanyour.bridge"),
            ExecSpec(timeoutMs = 8_000, stdoutCap = 1024, stderrCap = 1024))
        response.exit == 0 && response.stdout.lineSequence().any { it.startsWith("package:") }
    }

    @Suppress("DEPRECATION")
    suspend fun install(context: Context, graph: AppGraph, id: TvId,
                        progress: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val session = graph.access.live(id)
            ?: return@withContext Result(false, "TV desconectada. Reconecte antes de instalar o módulo.")
        val dir = File(context.cacheDir, "bridge")
        if (!dir.isDirectory && !dir.mkdirs())
            return@withContext Result(false, "Sem espaço para preparar o instalador do módulo.")
        val temp = File(dir, "bridge-${UUID.randomUUID()}.apk")
        val hold = session.hold()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open(ASSET).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        if (size > MAX_APK) throw IllegalStateException("Módulo acima do limite de 8 MB.")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (size < 1024) throw IllegalStateException("Instalador do módulo incompleto.")
            val flags = if (Build.VERSION.SDK_INT >= 28)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val apk = context.packageManager.getPackageArchiveInfo(temp.absolutePath, flags)
                ?: throw IllegalStateException("Módulo não reconhecido como APK.")
            if (apk.packageName != BridgeClient.PACKAGE)
                throw IllegalStateException("O pacote do módulo não corresponde ao CleanYourTV.")
            if (session.identity.sdk < (apk.applicationInfo?.minSdkVersion ?: 1))
                throw IllegalStateException("Android da TV não suporta este módulo.")
            val isSigned = if (Build.VERSION.SDK_INT >= 28)
                !apk.signingInfo?.apkContentsSigners.isNullOrEmpty()
            else !apk.signatures.isNullOrEmpty()
            if (!isSigned) throw IllegalStateException("APK não possui assinatura válida.")
            val features = session.exec(ShellLine("pm list features"),
                ExecSpec(timeoutMs = 10_000, stdoutCap = 64 * 1024))
            if (features.exit != 0 || features.truncated ||
                features.stdout.contains("feature:android.hardware.type.watch") ||
                !features.stdout.contains("feature:android.software.leanback")) {
                throw IllegalStateException("O dispositivo não foi identificado como Android TV.")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val source = BlobRef(Sha256(hash), size, temp.absolutePath)
            when (session.installStream(source, size) { bytes ->
                progress(((bytes * 100L) / size).toInt().coerceIn(0, 100))
            }) {
                is InstallOutcome.Installed ->
                    Result(true, "Módulo instalado na TV. Ao iniciar, autorize a captura na própria TV.")
                is InstallOutcome.Failed ->
                    Result(false, "Instalação recusada. Verifique espaço, compatibilidade e assinatura anterior do módulo.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.FileNotFoundException) {
            Result(false, "O APK do módulo não está incluído nesta compilação do CleanYourTV.")
        } catch (e: Exception) {
            Result(false, e.message?.take(160) ?: "Não foi possível instalar o módulo na TV.")
        } finally {
            temp.delete()
            hold.close()
        }
    }
}
