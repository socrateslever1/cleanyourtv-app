package tv.cleanyour.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.core.model.PackageName

class RemoteAndroidPolicyTest {
    private val now = 1000000L
    private val target = RemoteTarget(
        kind = RemoteKind.PHONE,
        deviceId = "sha256:1234",
        firmwareId = "android-17-build1",
        userId = 0,
        sdk = 37,
        authenticated = true,
        capabilities = RemoteCapability.entries.toSet(),
        dynamicProtectionsKnown = true,
        protectedPackages = emptySet(),
    )
    private val app = RemoteApp(PackageName("com.example.game"), true, false, true)
    private val backup = RemoteRestoreReceipt(
        deviceId = target.deviceId,
        firmwareId = target.firmwareId,
        userId = 0,
        capturedAtMs = now - 10000,
        persisted = true,
        hashVerified = true,
    )

    private fun check(
        t: RemoteTarget = target, a: RemoteApp = app,
        b: RemoteRestoreReceipt? = backup,
        op: RemoteOperation = RemoteOperation.DISABLE,
        expected: Boolean = a.enabled,
    ) = RemoteAndroidPolicy.evaluate(t, a, op, b, expected, now)

    @Test fun permitsOnlyVerifiedReversibleThirdPartyChange() {
        assertEquals(RemoteVerdict.Allowed, check())
        assertEquals(RemoteVerdict.AlreadyInRequestedState, check(op = RemoteOperation.ENABLE))
    }

    @Test fun blocksTvAndUnknownPlatformsFromGenericEngine() {
        assertTrue(check(t = target.copy(kind = RemoteKind.TV)) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(kind = RemoteKind.UNKNOWN)) is RemoteVerdict.Rejected)
    }

    @Test fun wearRequiresCertifiedFirmware() {
        assertTrue(check(t = target.copy(kind = RemoteKind.WEAR)) is RemoteVerdict.Rejected)
        assertEquals(RemoteVerdict.Allowed,
            check(t = target.copy(kind = RemoteKind.WEAR, certifiedWearFirmware = true)))
    }

    @Test fun identityAndAuthenticationMustBePresent() {
        assertTrue(check(t = target.copy(authenticated = false)) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(deviceId = "")) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(firmwareId = "")) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(dynamicProtectionsKnown = false)) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(capabilities = setOf(RemoteCapability.PACKAGE_STATE_READ)))
            is RemoteVerdict.Rejected)
    }

    @Test fun backupMustBePersistedVerifiedCurrentAndSameTarget() {
        assertTrue(check(b = null) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(persisted = false)) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(hashVerified = false)) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(deviceId = "other")) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(userId = 10)) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(firmwareId = "other")) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(capturedAtMs = now + 1)) is RemoteVerdict.Rejected)
        assertTrue(check(b = backup.copy(capturedAtMs = now - 16 * 60000L)) is RemoteVerdict.Rejected)
    }

    @Test fun oldBackupMayOnlyAuthorizeExplicitRestoration() {
        val old = backup.copy(capturedAtMs = now - 60 * 60000L)
        assertTrue(check(b = old) is RemoteVerdict.Rejected)
        assertEquals(RemoteVerdict.Allowed, RemoteAndroidPolicy.evaluate(
            target, app, RemoteOperation.DISABLE, old, true, now, restoring = true))
    }

    @Test fun packageMustBeThirdPartyAndNotCritical() {
        assertTrue(check(a = app.copy(userInstalled = false)) is RemoteVerdict.Rejected)
        assertTrue(check(a = app.copy(systemApp = true)) is RemoteVerdict.Rejected)
        for (pkg in listOf("com.android.settings", "com.example.watchface", "com.example.dialer",
                "com.example.healthassistant", "com.tcl.keyhelp", "com.example.wallet")) {
            assertTrue(pkg, check(a = app.copy(packageName = PackageName(pkg))) is RemoteVerdict.Rejected)
        }
        assertTrue(check(t = target.copy(protectedPackages = setOf(app.packageName))) is RemoteVerdict.Rejected)
    }

    @Test fun rejectsStalePackageStateAndMultiuserMismatches() {
        assertTrue(check(expected = false) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(userId = 10)) is RemoteVerdict.Rejected)
        assertTrue(check(t = target.copy(sdk = 25)) is RemoteVerdict.Rejected)
    }
}
