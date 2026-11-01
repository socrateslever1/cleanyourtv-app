package tv.cleanyour.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.ops.RemoteRestoreReceipt

/**
 * Records package enabled states, NOT private application data.
 * All import/export uses fixed ZIP entry names and strict size/hash checks.
 */
internal object RemoteStateBackup {
    private const val MAX_SNAPSHOT = 2 * 1024 * 1024
    private const val MAX_ZIP = 4 * 1024 * 1024
    private const val SCHEMA = 1
    private const val SNAPSHOT = "snapshot.json"
    private const val MANIFEST = "manifest.json"
    private val id = Regex("^[a-f0-9]{16}$")
    private val digest = Regex("^[a-f0-9]{64}$")

    data class State(val packageName: String, val enabled: Boolean)
    data class Record(val deviceId: String, val firmwareId: String, val userId: Int,
        val capturedAtMs: Long, val states: List<State>, val sha256: String) {
        fun receipt() = RemoteRestoreReceipt(deviceId, firmwareId, userId, capturedAtMs,
            persisted = true, hashVerified = true)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun snapshot(record: Record): ByteArray {
        val states = JSONArray()
        record.states.sortedBy { it.packageName }.forEach { s ->
            states.put(JSONObject().put("package", s.packageName).put("enabled", s.enabled))
        }
        return JSONObject().put("schema", SCHEMA).put("device", record.deviceId)
            .put("firmware", record.firmwareId).put("user", record.userId)
            .put("capturedAtMs", record.capturedAtMs).put("states", states)
            .toString().encodeToByteArray()
    }

    private fun decode(bytes: ByteArray, declaredHash: String): Record {
        require(bytes.size in 1..MAX_SNAPSHOT) { "Tamanho do backup inválido" }
        require(digest.matches(declaredHash) && sha256(bytes) == declaredHash) {
            "Backup modificado ou corrompido"
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema") == SCHEMA) { "Versão de backup incompatível" }
        val device = json.getString("device")
        require(id.matches(device)) { "Identificador do dispositivo inválido" }
        val firmware = json.getString("firmware")
        require(digest.matches(firmware)) { "Identificador de firmware inválido" }
        val user = json.getInt("user")
        require(user in 0..9999) { "Usuário Android inválido" }
        val captured = json.getLong("capturedAtMs")
        require(captured > 0L) { "Data de backup inválida" }
        val arr = json.getJSONArray("states")
        require(arr.length() in 0..10000) { "Número de pacotes fora do limite" }
        val seen = HashSet<String>()
        val states = ArrayList<State>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val pkg = entry.getString("package")
            require(PackageName(pkg).value == pkg && seen.add(pkg)) { "Pacotes inválidos ou duplicados" }
            states.add(State(pkg, entry.getBoolean("enabled")))
        }
        return Record(device, firmware, user, captured, states, declaredHash)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "remote-state-v1").also {
            require(it.isDirectory || it.mkdirs()) { "Não foi possível criar a pasta privada" }
        }

    private fun file(context: Context, device: String): File {
        require(id.matches(device)) { "Dispositivo inválido" }
        return File(dir(context), "$device.json")
    }

    /** Private app storage, written and fsynced before any device modification. */
    fun save(context: Context, device: String, firmware: String, user: Int,
             states: List<State>, now: Long): Record {
        require(id.matches(device) && digest.matches(firmware) && user in 0..9999)
        require(states.size <= 10000 && states.map { it.packageName }.toSet().size == states.size)
        val temporary = Record(device, firmware, user, now, states, "")
        val payload = snapshot(temporary)
        require(payload.size in 1..MAX_SNAPSHOT) { "Inventário excede o limite de backup" }
        val hash = sha256(payload)
        val envelope = JSONObject().put("sha256", hash)
            .put("snapshot", payload.toString(Charsets.UTF_8)).toString().encodeToByteArray()
        val target = file(context, device)
        val pending = File(target.parentFile, "$device.tmp")
        try {
            pending.outputStream().use { stream -> stream.write(envelope); stream.flush()
                (stream as java.io.FileOutputStream).fd.sync() }
            require(pending.renameTo(target)) { "Falha ao gravar backup permanente" }
            return read(context, device) ?: error("Verificação do backup falhou")
        } finally { pending.delete() }
    }

    fun read(context: Context, device: String): Record? {
        val target = file(context, device)
        if (!target.isFile) return null
        require(target.length() in 1..MAX_SNAPSHOT.toLong() + 4096) { "Arquivo de backup muito grande" }
        val outer = JSONObject(target.readText(Charsets.UTF_8))
        val decoded = decode(outer.getString("snapshot").encodeToByteArray(), outer.getString("sha256"))
        require(decoded.deviceId == device) { "Backup é de outro dispositivo" }
        return decoded
    }

    /** Export creates an independently verifiable ZIP without private app data or credentials. */
    fun export(record: Record, out: OutputStream) {
        val contents = snapshot(record)
        require(sha256(contents) == record.sha256) { "Backup mudou antes da exportação" }
        val manifest = JSONObject().put("schema", SCHEMA)
            .put("kind", "cleanyourtv-remote-state")
            .put("snapshotSha256", record.sha256)
            .put("includesPrivateAppData", false)
            .put("includedApkCount", 0)
            .put("note", "Estado dos pacotes. APKs não extraídos por esta versão.")
            .toString().encodeToByteArray()
        ZipOutputStream(out).use { zip ->
            listOf(MANIFEST to manifest, SNAPSHOT to contents).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    fun import(input: InputStream): Record {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf(MANIFEST, SNAPSHOT) &&
                    entry.name !in files) { "Entrada ZIP não autorizada" }
                val bytes = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_ZIP && bytes.size() + n <= MAX_SNAPSHOT) { "Backup excedeu limites" }
                    bytes.write(buf, 0, n)
                }
                files[entry.name] = bytes.toByteArray()
                zip.closeEntry()
            }
        }
        require(files.size == 2) { "Backup incompleto" }
        val m = JSONObject(files.getValue(MANIFEST).toString(Charsets.UTF_8))
        require(m.getInt("schema") == SCHEMA && m.getString("kind") == "cleanyourtv-remote-state")
        return decode(files.getValue(SNAPSHOT), m.getString("snapshotSha256"))
    }

    /** Imported snapshots are stored only after validation; a new hash is never trusted blindly. */
    fun saveImported(context: Context, record: Record): Record {
        require(sha256(snapshot(record)) == record.sha256) { "Conteúdo importado alterado" }
        return save(context, record.deviceId, record.firmwareId,
            record.userId, record.states, record.capturedAtMs)
    }
}
