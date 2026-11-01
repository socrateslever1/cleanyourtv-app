package tv.cleanyour.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteStateBackupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val firmware = "c".repeat(64)
    private val device = "abcdef0123456789"
    private val states = listOf(
        RemoteStateBackup.State("com.example.game", true),
        RemoteStateBackup.State("org.example.reader", false)
    )

    @Test fun persistedAndExportedBackupVerifiesHashes() {
        val saved = RemoteStateBackup.save(context, device, firmware, 0, states, 1000000L)
        assertEquals(64, saved.sha256.length)
        assertEquals(states, RemoteStateBackup.read(context, device)?.states)
        val zip = ByteArrayOutputStream()
        RemoteStateBackup.export(saved, zip)
        val imported = RemoteStateBackup.import(ByteArrayInputStream(zip.toByteArray()))
        assertEquals(device, imported.deviceId)
        assertEquals(firmware, imported.firmwareId)
        assertEquals(states, imported.states)
        assertEquals(saved.sha256, RemoteStateBackup.saveImported(context, imported).sha256)
    }

    @Test fun forbidsUnexpectedZipEntriesAndDuplicates() {
        for (entry in listOf("../snapshot.json", "/etc/passwd", "apks/secret.apk")) {
            val zip = ByteArrayOutputStream()
            ZipOutputStream(zip).use {
                it.putNextEntry(ZipEntry(entry))
                it.write(byteArrayOf(1))
                it.closeEntry()
            }
            var rejected = false
            try { RemoteStateBackup.import(ByteArrayInputStream(zip.toByteArray())) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Must reject $entry", rejected)
        }
    }

    @Test fun missingBackupIsNotMistakenForValidRestorePoint() {
        assertEquals(null, RemoteStateBackup.read(context, "1111111111111111"))
    }
}
