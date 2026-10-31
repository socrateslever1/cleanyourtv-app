package tv.cleanyour.app

import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.trust.DownloadHosts

// The full build (GitHub and F-Droid, STORES-PLAN 1): everything the app did before the store
// flavours, unchanged.
object Store : StorePorts {
    override val flavour = "full"
    override val hosts: Set<String> = DownloadHosts.ALL
    override val rules = StoreRules.FULL
    override val updatesMayGoOff = rules.updatesOff
    override val releasePage: String = RELEASE_PAGE

    // Owned GitHub releases are numbered ptbr-r<workflow-run>-<git-sha>.
    override fun updateChecker(http: HttpFetcher) = UpdateChecker { current ->
        val r = http.get(RELEASES_URL, null, null)
        if (r.code != 200) {
            r.body?.close()
            null
        } else {
            val body = r.body?.use { it.readBytes().decodeToString() } ?: return@UpdateChecker null
            val tag = runCatching { org.json.JSONObject(body).optString("tag_name") }
                .getOrDefault("")
            if (offered(tag, current)) tag else ""
        }
    }

    internal fun offered(tag: String, current: String): Boolean {
        val remote = Regex("""^ptbr-r([0-9]+)-[a-f0-9]{8}$""").matchEntire(tag)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return false
        val installed = Regex("""-ptbr\.r([0-9]+)$""")
            .find(current)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return remote > installed
    }

    override fun addOnFetcher(make: () -> ArtifactFetcher): ArtifactFetcher = make()

    const val RELEASES_URL = "https://api.github.com/repos/socrateslever1/cleanyourtv-app/releases/latest"
    const val RELEASE_PAGE = "https://github.com/socrateslever1/cleanyourtv-app/releases"
}
