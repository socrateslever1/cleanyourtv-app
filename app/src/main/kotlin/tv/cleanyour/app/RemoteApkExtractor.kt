package tv.cleanyour.app

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.TvSession
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Saves an accessible standalone APK, never private application data.
 * Split APKs are refused: exporting base.apk alone would be misleading.
 */
internal object RemoteApkExtractor {
    private const val MAX_APK = 64L * 1024L * 1024L
    private val pathPattern = Regex("""^/data/app/[A-Za-z0-9_./~+=-]{1,512}/base\.apk$""")
    private val shaPattern = Regex("^[a-f0-9]{64}$")
    private val spec = ExecSpec(timeoutMs = 20_000, idleTimeoutMs = 8_000,
        stdoutCap = 4096, stderrCap = 2048)

    fun eligibleSource(output: String): String? {
        val paths = output.lineSequence().filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.toList()
        if (paths.size != 1) return null
        val only = paths.single()
        return only.takeIf {
            pathPattern.matches(it) && !it.contains("..") && !it.contains("//")
        }
    }

    @OptIn(RawShell::class)
    suspend fun export(context: Context, session: TvSession,
                       pkg: PackageName, destination: OutputStream): String =
        withContext(Dispatchers.IO) {
            require(pkg.value.length <= 180)
            suspend fun run(command: String): String {
                val r = session.exec(ShellLine(command), spec)
                if (r.exit != 0 || r.truncated) error("ADB recusou uma etapa da cópia do APK.")
                return r.stdout.trim()
            }

            val source = eligibleSource(run("pm path --user 0 " + pkg.value))
                ?: error("APK dividido, inacessível ou não reconhecido. Nenhum instalador parcial será exportado.")
            val quoted = "'" + source + "'"
            val size = run("stat -c %s " + quoted).toLongOrNull()
                ?: error("Não foi possível verificar o tamanho do APK.")
            require(size in 1..MAX_APK) { "APK excede o limite de 64 MiB." }
            val expected = run("sha256sum " + quoted).substringBefore(' ').lowercase()
            require(shaPattern.matches(expected)) { "Hash do APK não disponível." }

            val name = RemoteStateBackup.sha256(
                (session.tvId.value + ":" + pkg.value).encodeToByteArray()
            ).take(24) + ".apk"
            val staged = RemotePath(RemoteDir.TMP, listOf(name))
            run("mkdir -p " + RemoteDir.TMP.path)
            try {
                run("cp " + quoted + " '" + staged.absolute + "'")
                val data = session.pull(staged, MAX_APK)
                require(data.size.toLong() == size) { "Cópia incompleta do APK." }
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(actual == expected) { "Hash do APK divergente." }
                val temp = File.createTempFile("cleanyour-", ".apk", context.cacheDir)
                try {
                    temp.outputStream().use { it.write(data) }
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                    require(info?.packageName == pkg.value) { "APK não pertence ao pacote selecionado." }
                } finally { temp.delete() }
                destination.write(data)
                destination.flush()
                actual
            } finally {
                // A failed copy is never left in /data/local/tmp.
                try { run("rm -f '" + staged.absolute + "'") } catch (_: Exception) { }
            }
        }
}
