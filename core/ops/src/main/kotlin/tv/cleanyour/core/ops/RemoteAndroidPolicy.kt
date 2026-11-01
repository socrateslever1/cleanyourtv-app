package tv.cleanyour.core.ops

import tv.cleanyour.core.model.PackageName

/**
 * Authorization policy for a FUTURE remote-Android control workflow.
 * Fail closed. Not wired to UI, ADB or TV's existing plan engine yet.
 * A snapshot/backup is a package-state restore point, not a copy of app data.
 */
enum class RemoteKind { TV, PHONE, TABLET, WEAR, UNKNOWN }
enum class RemoteOperation { DISABLE, ENABLE }
enum class RemoteCapability { PACKAGE_STATE_READ, PACKAGE_STATE_WRITE, VERIFIED_PACKAGE_IDENTITY }

data class RemoteTarget(
    val kind: RemoteKind,
    val deviceId: String,
    val firmwareId: String,
    val userId: Int,
    val sdk: Int,
    val authenticated: Boolean,
    val capabilities: Set<RemoteCapability>,
    val dynamicProtectionsKnown: Boolean,
    val protectedPackages: Set<PackageName>,
    val certifiedWearFirmware: Boolean = false,
)

data class RemoteApp(
    val packageName: PackageName,
    val userInstalled: Boolean,
    val systemApp: Boolean,
    val enabled: Boolean,
)

data class RemoteRestoreReceipt(
    val deviceId: String,
    val firmwareId: String,
    val userId: Int,
    val capturedAtMs: Long,
    val persisted: Boolean,
    val hashVerified: Boolean,
)

sealed interface RemoteVerdict {
    data object Allowed : RemoteVerdict
    data object AlreadyInRequestedState : RemoteVerdict
    data class Rejected(val reason: String) : RemoteVerdict
}

/**
 * Security boundary: the TV's mature planner must remain the only path for
 * TV modifications. Generic controls are for later phone/tablet and certified
 * Wear OS implementations and require independent dynamic role discovery.
 */
object RemoteAndroidPolicy {
    const val MAX_BACKUP_AGE_MS = 15L * 60L * 1000L
    private const val MAX_ID_LENGTH = 160

    // These are an additional fail-closed gate, not a substitute for live
    // roles, active IME, accessibility services or device administration checks.
    private val protectedPrefixes = listOf(
        "com.android.", "com.google.android.", "com.samsung.android.",
        "com.samsung.wearable.", "com.tcl.", "android.", "com.qualcomm.",
        "com.mediatek.", "com.miui.", "com.coloros.", "com.oplus.",
    )
    private val criticalNameFragments = listOf(
        "telephony", "dialer", "launcher", "systemui", "bluetooth", "wifi",
        "keyguard", "ime", "keyboard", "accessibility", "setupwizard",
        "devicepolicy", "permission", "security", "emergency", "sos",
        "health", "wearable", "watchface", "sensor", "wallet", "payment",
        "ota", "systemupdate", "companion",
    )
    private val protectedExact = setOf(
        "android", "com.google.android.gms", "com.google.android.gsf",
        "com.android.vending", "com.android.shell", "com.android.adbd",
        "com.android.systemui", "com.google.android.apps.wearable",
    )

    fun evaluate(
        target: RemoteTarget,
        app: RemoteApp,
        operation: RemoteOperation,
        backup: RemoteRestoreReceipt?,
        expectedEnabled: Boolean,
        nowMs: Long,
    ): RemoteVerdict {
        fun no(why: String) = RemoteVerdict.Rejected(why)
        if (!target.authenticated) return no("ADB não autenticado")
        if (!target.deviceId.validId() || !target.firmwareId.validId())
            return no("Identidade ou firmware não verificados")
        if (target.sdk < 28 || target.userId !in 0..9999)
            return no("Versão do Android ou usuário não suportado")
        if (target.kind == RemoteKind.TV) return no("TV exige o planejador específico existente")
        if (target.kind == RemoteKind.UNKNOWN) return no("Plataforma desconhecida")
        if (target.kind == RemoteKind.WEAR && !target.certifiedWearFirmware)
            return no("Relógio sem perfil certificado para seu firmware")
        if (!target.capabilities.containsAll(setOf(
                RemoteCapability.PACKAGE_STATE_READ,
                RemoteCapability.PACKAGE_STATE_WRITE,
                RemoteCapability.VERIFIED_PACKAGE_IDENTITY,
            ))) return no("Capacidades de controle não comprovadas")
        if (!target.dynamicProtectionsKnown) return no("Proteções dinâmicas não verificadas")
        val p = app.packageName.value.lowercase()
        if (!app.userInstalled || app.systemApp)
            return no("Somente aplicativos de usuário são elegíveis")
        if (p in protectedExact || protectedPrefixes.any(p::startsWith) ||
            criticalNameFragments.any(p::contains) || app.packageName in target.protectedPackages ||
            Denylist.staticReason(app.packageName.value) != null)
            return no("Pacote crítico, de papel ativo ou não certificado")
        if (backup == null || !backup.persisted || !backup.hashVerified)
            return no("Backup externo íntegro obrigatório antes da operação")
        if (backup.deviceId != target.deviceId || backup.firmwareId != target.firmwareId ||
            backup.userId != target.userId)
            return no("Backup é de outro dispositivo, usuário ou firmware")
        val age = nowMs - backup.capturedAtMs
        if (age !in 0..MAX_BACKUP_AGE_MS) return no("Backup desatualizado")
        if (app.enabled != expectedEnabled) return no("Estado do pacote mudou; nova leitura necessária")
        if (app.enabled == (operation == RemoteOperation.ENABLE))
            return RemoteVerdict.AlreadyInRequestedState
        return RemoteVerdict.Allowed
    }

    private fun String.validId(): Boolean =
        isNotBlank() && length <= MAX_ID_LENGTH && all {
            it.isLetterOrDigit() || it in "._:-"
        }
}
