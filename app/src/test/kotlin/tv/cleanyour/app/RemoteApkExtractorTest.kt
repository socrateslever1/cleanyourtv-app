package tv.cleanyour.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteApkExtractorTest {
    @Test fun onlyStandaloneBaseApkUnderDataAppIsEligible() {
        assertEquals("/data/app/~~AbCdEF/com.example.game-xyz/base.apk",
            RemoteApkExtractor.eligibleSource(
                "package:/data/app/~~AbCdEF/com.example.game-xyz/base.apk"))
        assertNull(RemoteApkExtractor.eligibleSource(
            "package:/data/app/~~xyz/com.app/base.apk\npackage:/data/app/~~xyz/com.app/split_config.arm64_v8a.apk"))
        assertNull(RemoteApkExtractor.eligibleSource("package:/data/data/com.app/secrets.db"))
        assertNull(RemoteApkExtractor.eligibleSource(
            "package:/data/app/com.app/../../system/priv-app/base.apk"))
        assertNull(RemoteApkExtractor.eligibleSource(
            "package:/data/app/com.app';reboot/base.apk"))
        assertNull(RemoteApkExtractor.eligibleSource("package:/sdcard/Download/fake.apk"))
    }
}
