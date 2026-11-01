package tv.cleanyour.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEnabledStateTest {
    private fun dump(enabled: Int) = """
        Packages:
          Package [com.example.reader] (ab12cd):
            userId=10500
            codePath=/data/app/reader/base.apk
            User 0: ceDataInode=42 installed=true hidden=false stopped=false
              enabled=$enabled instant=false
            User 10: installed=true enabled=0
    """.trimIndent()

    @Test fun allFivePackageManagerStatesHaveExactRestorationVerbs() {
        val expected = listOf(
            0 to "default-state",
            1 to "enable",
            2 to "disable",
            3 to "disable-user",
            4 to "disable-until-used",
        )
        for ((code, verb) in expected) {
            val state = RemotePackageStateParser.read(dump(code), "com.example.reader", 0)
            assertEquals(code.toString(), RemoteEnabledState.of(code), state)
            assertEquals(verb, state?.shellVerb)
            assertEquals(if (code == 0) null else code == 1, state?.explicitEnabled)
        }
    }

    @Test fun refusesUnknownTruncatedOrAmbiguousUserState() {
        assertNull(RemotePackageStateParser.read(dump(9), "com.example.reader", 0))
        assertNull(RemotePackageStateParser.read(dump(0), "com.another.reader", 0))
        assertNull(RemotePackageStateParser.read(dump(0), "com.example.reader", 11))
        assertNull(RemotePackageStateParser.read(dump(0).replace("installed=true", "installed=false"),
            "com.example.reader", 0))
        assertNull(RemotePackageStateParser.read(dump(0) + "\n" + dump(0), "com.example.reader", 0))
        assertNull(RemotePackageStateParser.read("Package [com.example.reader]", "com.example.reader", 0))
    }

    @Test fun neverMistakesSystemCopyForLivePackage() {
        val duplicate = dump(3) + "\nHidden system packages:\n" + dump(0)
        assertNull(RemotePackageStateParser.read(duplicate, "com.example.reader", 0))
    }
}
