package tv.cleanyour.feature.setup

import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceImpactCatalogTest {
    @Test fun tclReminderExplainsLossOfRemindersNotJustAdvertising() {
        val impact = ServiceImpactCatalog.explain("com.tcl.notereminder")
        assertTrue(impact.purpose.contains("lembretes"))
        assertTrue(impact.disabling.contains("não funcionar"))
        assertTrue(impact.caution.contains("mantenha ativo"))
    }

    @Test fun unknownPackageIsNeverDeclaredSafeWithoutEvidence() {
        val impact = ServiceImpactCatalog.explain("com.vendor.unknownservice")
        assertTrue(impact.purpose.contains("não confirmada"))
        assertTrue(impact.caution.contains("Risco desconhecido"))
        assertTrue(ServiceImpactCatalog.recovery.contains("ponto de restauração"))
    }

    @Test fun castingGroupWarnsAboutChromecast() {
        assertTrue(ServiceImpactCatalog.groupNote("casting-optional").contains("Chromecast"))
    }
}
