package tv.cleanyour.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvToolsTest {
    @Test fun parsesRecentTasksWithoutDuplicatesOrSystemPackages() {
        val text = """
            Recent #0: realActivity=com.netflix.ninja/.Home
            Recent #1: baseIntent=Intent { cmp=com.youtube.tv/.Home }
            Recent #2: realActivity=com.google.android.tv.settings/.Settings
            Recent #3: realActivity=com.netflix.ninja/.Home
            Recent #4: realActivity=com.android.settings/.Settings
        """.trimIndent()
        assertEquals(listOf("com.netflix.ninja", "com.youtube.tv"), TvTools.recentFrom(text))
    }

    @Test fun rejectsUnsafeShellPackageNames() {
        assertTrue(TvTools.validPackage("com.netflix.ninja"))
        assertFalse(TvTools.validPackage("com.example;reboot"))
        assertFalse(TvTools.validPackage("com.example\nreboot"))
        assertFalse(TvTools.validPackage("com.example && reboot"))
        assertFalse(TvTools.validPackage(""))
    }

    @Test fun readsStorageOnlyWhenDataIsValid() {
        assertEquals(100000L to 21000L, TvTools.parseDisk(
            "Filesystem 1K-blocks Used Available Use% Mounted\n/dev/block 100000 79000 21000 80% /data"))
        assertNull(TvTools.parseDisk("Filesystem\n/dev/block no 79000 21000 80% /data"))
        assertNull(TvTools.parseDisk("Filesystem\n/dev/block 100 70 200 70% /data"))
    }

    @Test fun storageGuidanceDoesNotClaimAutomaticOptimization() {
        assertEquals(30, PhoneDiagnostics.Snapshot(70, 100, 0, 100).usedPercent)
        assertTrue(PhoneDiagnostics.Snapshot(1, 100, 0, 100).guidance().contains("Pouco espaço"))
    }
}
