package tv.cleanyour.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.ops.RemoteAndroidPolicy
import tv.cleanyour.core.ops.RemoteApp
import tv.cleanyour.core.ops.RemoteCapability
import tv.cleanyour.core.ops.RemoteKind
import tv.cleanyour.core.ops.RemoteOperation
import tv.cleanyour.core.ops.RemoteTarget
import tv.cleanyour.core.ops.RemoteVerdict
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/**
 * Explicitly authorized legacy TCP ADB only. Modern pairing-code ADB TLS is
 * NOT implemented; never claim an unencrypted endpoint is modern wireless ADB.
 *
 * The old TV planner, its sessions and its restore points are left untouched.
 */
internal class RemoteAndroidController(private val context: Context, private val graph: AppGraph) {
    data class Inventory(
        val target: RemoteTarget,
        val model: String,
        val apps: List<RemoteApp>,
        val canWrite: Boolean,
        val explanation: String,
    )

    private val lock = Mutex()
    private var session: TvSession? = null
    private var known: Inventory? = null
    private val spec = ExecSpec(timeoutMs = 12000L, idleTimeoutMs = 7000L,
        stdoutCap = 128 * 1024, stderrCap = 2048, retry = RetryClass.IDEMPOTENT_READ)
    private val pkgRegex = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63}){1,15}$")
    private val nameCapture = Regex("[A-Za-z][A-Za-z0-9_]{0,63}(?:\\.[A-Za-z][A-Za-z0-9_]{0,63}){1,15}")
    private val readLimit = 4096
    private val criticalRoles = listOf("HOME", "DIALER", "SMS", "ASSISTANT", "BROWSER", "EMERGENCY")

    private fun safePackage(raw: String): PackageName? {
        if (!pkgRegex.matches(raw) || raw.length > 255) return null
        return runCatching { PackageName(raw) }.getOrNull()
    }

    private fun extractPackages(raw: String): Set<PackageName> =
        nameCapture.findAll(raw.take(128 * 1024))
            .mapNotNull { safePackage(it.value) }.take(readLimit).toSet()

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    @OptIn(RawShell::class)
    private suspend fun read(s: TvSession, fixed: String): String {
        val r = s.exec(ShellLine(fixed), spec)
        if (r.exit != 0 || r.truncated) error("Consulta Android recusada: " + fixed.substringBefore(' '))
        return r.stdout.trim()
    }

    @OptIn(RawShell::class)
    private suspend fun snapshot(s: TvSession): Inventory {
        val features = read(s, "pm list features").lineSequence().map { it.trim() }.toSet()
        val characteristics = read(s, "getprop ro.build.characteristics").lowercase()
        val kind = when {
            "feature:android.hardware.type.watch" in features -> RemoteKind.WEAR
            "feature:android.software.leanback" in features ||
                "feature:android.hardware.type.television" in features -> RemoteKind.TV
            "tablet" in characteristics -> RemoteKind.TABLET
            "feature:android.hardware.telephony" in features ||
                "feature:android.hardware.touchscreen" in features -> RemoteKind.PHONE
            else -> RemoteKind.UNKNOWN
        }
        val fingerprint = read(s, "getprop ro.build.fingerprint")
        require(fingerprint.length in 12..512) { "Firmware não identificado" }
        val currentUser = read(s, "am get-current-user").toIntOrNull()
            ?: error("Usuário Android não identificado")
        val model = read(s, "getprop ro.product.model").take(90).ifEmpty { "Android" }
        // Do not rely on ro.serialno: modern Android often hides it or returns
        // a shared placeholder. Hash the stable shell-visible Android ID and
        // model to avoid persisting identifiers in backups or telemetry.
        val androidId = read(s, "settings get secure android_id").lowercase()
        require(Regex("^[0-9a-f]{16}$").matches(androidId)) {
            "O Android não forneceu identidade única verificável; alterações bloqueadas."
        }
        val deviceId = hash(androidId + ":" + s.identity.device).take(16)
        val firmware = hash(fingerprint)
        val installed = read(s, "pm list packages -3 --user $currentUser")
            .lineSequence().mapNotNull {
                if (it.startsWith("package:")) safePackage(it.removePrefix("package:").trim()) else null
            }.take(readLimit).toSet()
        val disabled = read(s, "pm list packages -d --user $currentUser")
            .lineSequence().mapNotNull {
                if (it.startsWith("package:")) safePackage(it.removePrefix("package:").trim()) else null
            }.take(readLimit).toSet()

        // Each of these reads must succeed for the policy to permit any write.
        // Unknown/default system roles MUST NOT become bypasses.
        var protectionsKnown = false
        val protectedRoles = mutableSetOf<PackageName>()
        if (kind == RemoteKind.PHONE || kind == RemoteKind.TABLET) {
            protectionsKnown = runCatching {
                for (role in criticalRoles) {
                    protectedRoles.addAll(extractPackages(read(s,
                        "cmd role get-role-holders --user $currentUser android.app.role.$role")))
                }
                val home = read(s, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                protectedRoles.addAll(extractPackages(home))
                val ime = read(s, "settings get secure default_input_method")
                protectedRoles.addAll(extractPackages(ime))
                val accessibility = read(s, "settings get secure enabled_accessibility_services")
                protectedRoles.addAll(extractPackages(accessibility))
                val admins = read(s, "dumpsys device_policy")
                protectedRoles.addAll(extractPackages(admins))
                true
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                false
            }
        }
        val target = RemoteTarget(kind, deviceId, firmware, currentUser, s.identity.sdk,
            authenticated = true,
            capabilities = setOf(RemoteCapability.PACKAGE_STATE_READ,
                RemoteCapability.PACKAGE_STATE_WRITE, RemoteCapability.VERIFIED_PACKAGE_IDENTITY),
            dynamicProtectionsKnown = protectionsKnown,
            protectedPackages = protectedRoles,
            certifiedWearFirmware = false)
        val apps = installed.sortedBy { it.value }.map {
            RemoteApp(it, userInstalled = true, systemApp = false, enabled = it !in disabled)
        }
        val explanation = when {
            kind == RemoteKind.TV -> "Para TVs, utilize o gerenciamento já existente."
            kind == RemoteKind.WEAR -> "Wear OS: somente leitura até validação do perfil de firmware."
            kind == RemoteKind.UNKNOWN -> "Tipo de aparelho desconhecido: alterações bloqueadas."
            currentUser != 0 -> "Somente usuário principal Android 0 é suportado."
            !protectionsKnown -> "Não foi possível verificar todos os serviços e papéis protegidos."
            else -> "Controle disponível para aplicativos de usuário não essenciais, com backup e confirmação."
        }
        return Inventory(target, model, apps,
            kind in setOf(RemoteKind.PHONE, RemoteKind.TABLET) && currentUser == 0 && protectionsKnown, explanation)
    }

    private suspend fun closeNonTvSession() {
        val previous = session ?: return
        // Never terminate a session that belongs to the existing TV manager.
        if (graph.tvs.tvs.value.none { it.id == previous.tvId }) previous.close()
    }

    suspend fun connect(ip: String): Inventory = lock.withLock {
        require(WearOsReadOnly.localIpv4(ip)) { "Informe um IP privado válido da rede local." }
        closeNonTvSession()
        session = null
        known = null
        when (val opened = graph.sessions.open(Endpoint.PlainTcp(ip.trim(), 5555), null) {}) {
            is OpenResult.Failed -> error("ADB não conectado/autorizado: " + opened.error)
            is OpenResult.Ok -> {
                val s = opened.session
                try {
                    val info = withContext(Dispatchers.IO) { snapshot(s) }
                    session = s; known = info
                    info
                } catch (e: Exception) {
                    if (graph.tvs.tvs.value.none { it.id == s.tvId }) s.close()
                    throw e
                }
            }
        }
    }

    suspend fun refresh(): Inventory = lock.withLock {
        val s = session ?: error("Conecte primeiro o outro Android.")
        val latest = withContext(Dispatchers.IO) { snapshot(s) }
        val old = known
        require(old == null || latest.target.deviceId == old.target.deviceId) { "Identidade do aparelho mudou" }
        known = latest
        latest
    }

    suspend fun backup(): RemoteStateBackup.Record = lock.withLock {
        val s = session ?: error("Conecte o aparelho.")
        val info = withContext(Dispatchers.IO) { snapshot(s) }
        require(info.target.kind != RemoteKind.TV && info.target.kind != RemoteKind.UNKNOWN)
        require(info.target.userId == 0 && info.target.deviceId == known?.target?.deviceId)
        require(info.target.firmwareId == known?.target?.firmwareId) {
            "Firmware mudou desde a conexão. Reconecte e revise o dispositivo."
        }
        // Never replace the sole restore baseline while writes are unresolved,
        // or while any previously recorded app still differs from that baseline.
        require(!journalFile(info.target.deviceId).exists()) {
            "Existe uma operação sem confirmação. Reconcilie antes de criar outro backup."
        }
        val previous = withContext(Dispatchers.IO) {
            RemoteStateBackup.read(context, info.target.deviceId)
        }
        val currentStates = info.apps.associate { it.packageName.value to it.enabled }
        val diverged = previous?.states?.firstOrNull {
            currentStates[it.packageName] != it.enabled
        }
        require(diverged == null) {
            "O estado atual difere do backup anterior (${diverged?.packageName}). " +
                "Restaure as alterações antes de substituir a referência de segurança."
        }
        val states = info.apps.map { RemoteStateBackup.State(it.packageName.value, it.enabled) }
        val saved = withContext(Dispatchers.IO) {
            RemoteStateBackup.save(context, info.target.deviceId, info.target.firmwareId,
                info.target.userId, states, System.currentTimeMillis())
        }
        known = info
        saved
    }

    suspend fun loadBackup(): RemoteStateBackup.Record? = withContext(Dispatchers.IO) {
        val id = lock.withLock { known?.target?.deviceId } ?: return@withContext null
        RemoteStateBackup.read(context, id)
    }

    private fun journalFile(deviceId: String) = File(context.filesDir, "remote-state-v1/$deviceId.pending")

    private fun externalReceiptStore() =
        context.getSharedPreferences("remote_state_external_backup_v1", Context.MODE_PRIVATE)

    /** Called only after reopening and verifying the user-selected SAF ZIP. */
    suspend fun markExportVerified(record: RemoteStateBackup.Record) = lock.withLock {
        val loaded = withContext(Dispatchers.IO) {
            RemoteStateBackup.read(context, record.deviceId)
        } ?: error("O backup local não existe mais.")
        require(loaded.sha256 == record.sha256) { "Backup exportado difere da cópia local." }
        externalReceiptStore().edit().putString(record.deviceId, record.sha256).commit().also {
            require(it) { "Falha ao registrar a exportação verificada." }
        }
    }

    suspend fun isExportVerified(record: RemoteStateBackup.Record): Boolean =
        lock.withLock {
            externalReceiptStore().getString(record.deviceId, null) == record.sha256
        }

    @OptIn(RawShell::class)
    suspend fun change(pkg: String, enable: Boolean, restoring: Boolean = false): String = lock.withLock {
        val s = session ?: error("Conecte o aparelho.")
        val info = withContext(Dispatchers.IO) { snapshot(s) }
        val former = known?.target ?: error("Identidade não registrada")
        require(info.target.deviceId == former.deviceId && info.target.firmwareId == former.firmwareId) {
            "Firmware ou dispositivo mudou. Refaça o backup e a leitura."
        }
        require(info.canWrite) { info.explanation }
        val app = info.apps.firstOrNull { it.packageName.value == pkg }
            ?: error("Aplicativo não encontrado na lista de usuário")
        val backup = withContext(Dispatchers.IO) {
            RemoteStateBackup.read(context, info.target.deviceId)
        } ?: error("Crie backup antes de alterar este dispositivo.")
        require(backup.states.any { it.packageName == pkg }) {
            "O pacote não consta do backup; crie outro antes de modificá-lo."
        }
        require(externalReceiptStore().getString(info.target.deviceId, null) == backup.sha256) {
            "Exporte o backup ZIP e confirme sua integridade antes de modificar o dispositivo."
        }
        val pending = journalFile(info.target.deviceId)
        require(!pending.exists()) { "Há operação anterior sem confirmação. Não serão feitas novas alterações." }
        val verdict = RemoteAndroidPolicy.evaluate(info.target, app,
            if (enable) RemoteOperation.ENABLE else RemoteOperation.DISABLE,
            backup.receipt(), app.enabled, System.currentTimeMillis(), restoring = restoring)
        when (verdict) {
            is RemoteVerdict.Rejected -> error(verdict.reason)
            RemoteVerdict.AlreadyInRequestedState -> return@withLock "O aplicativo já está no estado solicitado."
            RemoteVerdict.Allowed -> Unit
        }
        // Durable pending journal comes BEFORE the shell write. A failed verification
        // blocks subsequent writes; no optimistic success and no blind retries.
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("device", info.target.deviceId)
                .put("firmware", info.target.firmwareId).put("package", pkg)
                .put("previouslyEnabled", app.enabled).put("requestedEnabled", enable)
                .put("startedAtMs", System.currentTimeMillis()).toString()
            pending.outputStream().use { out ->
                out.write(payload.encodeToByteArray()); out.flush()
                (out as java.io.FileOutputStream).fd.sync()
            }
        }
        val command = if (enable) "pm enable --user 0 $pkg" else "pm disable-user --user 0 $pkg"
        val output = s.exec(ShellLine(command), ExecSpec(timeoutMs = 12000L, idleTimeoutMs = 6000L))
        if (output.exit != 0 || output.truncated) {
            error("O Android recusou a alteração. Verifique o estado do pacote antes de tentar novamente.")
        }
        val verified = withContext(Dispatchers.IO) { snapshot(s) }
        val actual = verified.apps.firstOrNull { it.packageName.value == pkg }
        if (verified.target.deviceId != info.target.deviceId ||
            verified.target.firmwareId != info.target.firmwareId || actual?.enabled != enable)
            error("Pós-condição não confirmada. A operação permanece pendente de reconciliação.")
        withContext(Dispatchers.IO) {
            require(pending.delete()) { "Estado aplicado, mas a limpeza do diário falhou" }
        }
        known = verified
        if (enable) "Aplicativo reativado e verificado no aparelho." else "Aplicativo desativado e verificado no aparelho."
    }

    /**
     * Reconcile in-doubt command using a fresh device read. Never run a second
     * command automatically; the user decides after the observed state is shown.
     */
    suspend fun reconcile(): String = lock.withLock {
        val s = session ?: error("Conecte o dispositivo.")
        val info = withContext(Dispatchers.IO) { snapshot(s) }
        val file = journalFile(info.target.deviceId)
        if (!file.exists()) { known = info; return@withLock "Sem operações pendentes." }
        val pending = withContext(Dispatchers.IO) { JSONObject(file.readText(Charsets.UTF_8)) }
        require(pending.getString("device") == info.target.deviceId &&
            pending.getString("firmware") == info.target.firmwareId) { "Diário não corresponde ao firmware atual" }
        val p = pending.getString("package")
        val actual = info.apps.firstOrNull { it.packageName.value == p }
            ?: error("Pacote desapareceu; preservar o diário para análise")
        val previous = pending.getBoolean("previouslyEnabled")
        val requested = pending.getBoolean("requestedEnabled")
        require(actual.enabled == previous || actual.enabled == requested) {
            "Estado não reconhecido; preservar diário"
        }
        withContext(Dispatchers.IO) { require(file.delete()) { "Falha ao remover diário reconciliado" } }
        known = info
        "Estado atual verificado de $p: " + if (actual.enabled) "ativado" else "desativado"
    }

    suspend fun restore(pkg: String): String {
        val b = loadBackup() ?: error("Sem backup.")
        val state = b.states.firstOrNull { it.packageName == pkg } ?: error("Pacote ausente do backup")
        return change(pkg, state.enabled, restoring = true)
    }

    /** Export an APK only if the target is still the same authenticated device. */
    suspend fun exportApk(packageName: String, output: java.io.OutputStream): String = lock.withLock {
        val s = session ?: error("Conecte primeiro o dispositivo.")
        val info = withContext(Dispatchers.IO) { snapshot(s) }
        require(info.target.deviceId == known?.target?.deviceId &&
            info.target.firmwareId == known?.target?.firmwareId &&
            info.target.userId == 0) { "Identidade ou usuário Android mudou." }
        require(info.target.kind != RemoteKind.TV && info.target.kind != RemoteKind.UNKNOWN) {
            "Este recurso é destinado a celulares, tablets e relógios autorizados."
        }
        val app = info.apps.firstOrNull { it.packageName.value == packageName }
            ?: error("Pacote de usuário não encontrado.")
        val hold = s.hold()
        try {
            val hash = RemoteApkExtractor.export(context, s, app.packageName, output)
            "APK copiado e verificado. SHA-256: " + hash
        } finally { hold.close() }
    }

    suspend fun disconnect() = lock.withLock {
        closeNonTvSession()
        session = null
        known = null
    }
}
