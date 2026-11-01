package tv.cleanyour.app

/**
 * Android's actual per-user application enabled state, not a derived boolean.
 *
 * Commands are fixed and must be combined only with a validated PackageName.
 * These are not the same as package installed/hidden/suspended state.
 */
internal enum class RemoteEnabledState(val code: Int, val shellVerb: String) {
    DEFAULT(0, "default-state"),
    ENABLED(1, "enable"),
    DISABLED(2, "disable"),
    DISABLED_USER(3, "disable-user"),
    DISABLED_UNTIL_USED(4, "disable-until-used");

    val effectivelyEnabled: Boolean get() = this == DEFAULT || this == ENABLED

    companion object {
        fun of(code: Int): RemoteEnabledState? = entries.firstOrNull { it.code == code }
        fun ofName(value: String): RemoteEnabledState? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Extracts an EXACT package's user-state from dumpsys package <package>.
 * On ambiguous/missing output, return null and reject any write/backup.
 * Never infer DEFAULT from pm list packages -e or -d.
 */
internal object RemotePackageStateParser {
    private val packageHeader = Regex("""(?m)^\s*Package \[([A-Za-z][A-Za-z0-9_.]+)\] \([0-9a-fA-F]+\):\s*$""")
    private val userHeader = Regex("""(?m)^\s*User (\d+):\s*""")
    private val enabledField = Regex("""\benabled=([0-4])\b""")
    private val installedField = Regex("""\binstalled=true\b""")

    fun read(output: String, packageName: String, userId: Int): RemoteEnabledState? {
        if (output.isEmpty() || output.length > 131_072 || userId !in 0..9999) return null
        val matches = packageHeader.findAll(output)
            .filter { it.groupValues[1] == packageName }.take(2).toList()
        // Duplicate package entries can describe a hidden factory system app:
        // refuse to make a wrong restore point.
        if (matches.size != 1) return null
        val start = matches.single().range.last + 1
        val next = packageHeader.find(output, start)?.range?.first ?: output.length
        val section = output.substring(start, next)
        val users = userHeader.findAll(section)
            .filter { it.groupValues[1].toIntOrNull() == userId }.take(2).toList()
        if (users.size != 1) return null
        val userStart = users.single().range.first
        val userEnd = userHeader.find(section, users.single().range.last + 1)?.range?.first
            ?: section.length
        val userSection = section.substring(userStart, userEnd)
        // Refuse partial or structurally invalid records.
        if (!installedField.containsMatchIn(userSection)) return null
        val fields = enabledField.findAll(userSection).take(2).toList()
        if (fields.size != 1) return null
        return fields.first().groupValues[1].toIntOrNull()?.let(RemoteEnabledState::of)
    }
}
