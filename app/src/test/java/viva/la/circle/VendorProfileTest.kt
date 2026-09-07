package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.TargetAction
import viva.la.circle.service.BlueLMInterceptorService

class VendorProfileTest {

    @Test
    fun huaweiCeliaPackagesAreIntercepted() {
        val ownPkg = "viva.la.circle"
        listOf(
            "com.huawei.hiassistantoversea", // RU / global builds
            "com.huawei.hiassistant", // domestic builds
            "com.huawei.vassistant", // older EMUI
        ).forEach { pkg ->
            assertTrue(
                "$pkg should be intercepted",
                BlueLMInterceptorService.isBlueLMOrVivoAssistant(pkg, null, ownPkg),
            )
        }
    }

    @Test
    fun vivoDetectionIsUnchangedByTheVendorSeam() {
        val ownPkg = "viva.la.circle"
        listOf(
            "com.vivo.ai.copilot",
            "com.vivo.agent",
            "com.bbk.voiceassistant",
            "com.vivo.bluelm",
        ).forEach { pkg ->
            assertTrue(pkg, BlueLMInterceptorService.isBlueLMOrVivoAssistant(pkg, null, ownPkg))
        }
        // The compound vivo+agent rule must survive.
        assertTrue(BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.vivo.some.agent", null, ownPkg))
    }

    @Test
    fun unrelatedAppsAndOwnPackageAreNotIntercepted() {
        val ownPkg = "viva.la.circle"
        assertFalse(BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.whatsapp", null, ownPkg))
        assertFalse(BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.google.android.apps.bard", null, ownPkg))
        assertFalse(BlueLMInterceptorService.isBlueLMOrVivoAssistant(ownPkg, null, ownPkg))
    }

    @Test
    fun profileIsChosenFromBuildProps() {
        assertEquals(
            VendorProfile.HUAWEI,
            VendorProfile.forProps("HUAWEI", null, "EmotionUI_14.0.0", null),
        )
        assertEquals(
            VendorProfile.HUAWEI,
            VendorProfile.forProps("HONOR", null, null, null),
        )
        // Manufacturer missing, but an EMUI prop is present.
        assertEquals(
            VendorProfile.HUAWEI,
            VendorProfile.forProps(null, null, "EmotionUI_14.0.0", null),
        )
        // Brand alone is enough (some builds leave manufacturer blank).
        assertEquals(
            VendorProfile.HUAWEI,
            VendorProfile.forProps(null, null, null, null, brand = "HUAWEI"),
        )
        assertEquals(
            VendorProfile.HUAWEI,
            VendorProfile.forProps(null, null, null, null, hwPlatformVersion = "3.0"),
        )
        assertEquals(
            VendorProfile.VIVO,
            VendorProfile.forProps("vivo", "OriginOS 6", null, null),
        )
        assertEquals(
            VendorProfile.GENERIC,
            VendorProfile.forProps("Google", null, null, null),
        )
    }

    @Test
    fun knownPackagesUnionsEveryProfile() {
        val known = BlueLMInterceptorService.KNOWN_PACKAGES
        assertTrue(known.contains("com.vivo.ai.copilot"))
        assertTrue(known.contains("com.huawei.hiassistantoversea"))
    }

    @Test
    fun vivoSecondaryUiStillSuppressesRemapping() {
        // Copilot settings / circle-to-search screens must not be treated as the wake UI.
        assertTrue(BlueLMInterceptorService.isCopilotSecondaryUi("com.vivo.ai.copilot.settings.MainActivity"))
        assertTrue(BlueLMInterceptorService.isCopilotSecondaryUi("com.vivo.ai.copilot.circletosearch.Foo"))
        assertFalse(BlueLMInterceptorService.isCopilotSecondaryUi("com.vivo.ai.copilot.FloatService"))
    }

    @Test
    fun celiaAsSystemDefaultWouldLoop() {
        val ownPkg = "viva.la.circle"
        // On Huawei the system default assistant *is* Celia. Intercept + DEFAULT_ASSISTANT
        // must refuse, otherwise dismiss → assist gesture → Celia again.
        assertTrue(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(
                "com.huawei.hiassistantoversea",
                ownPkg,
            ),
        )
        assertTrue(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(
                "com.huawei.hiassistant",
                ownPkg,
            ),
        )
        assertTrue(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(
                "com.vivo.ai.copilot",
                ownPkg,
            ),
        )
        assertFalse(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(
                "com.google.android.googlequicksearchbox",
                ownPkg,
            ),
        )
        assertFalse(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(null, ownPkg),
        )
    }

    @Test
    fun huaweiProfileExposesDismissTuning() {
        assertEquals(1, VendorProfile.HUAWEI.maxDismissBacks)
        assertEquals(120L, VendorProfile.HUAWEI.pollMs)
        assertEquals(120L, VendorProfile.HUAWEI.hammerMinIntervalMs)
        assertEquals(250L, VendorProfile.HUAWEI.focusWaitTimeoutMs)
        assertEquals(16L, VendorProfile.HUAWEI.focusPollMs)
        assertEquals(400L, VendorProfile.HUAWEI.dismissTimeoutMs)
        assertEquals(
            VendorProfile.HUAWEI.maxDismissBacks,
            BlueLMInterceptorService.copilotDismissBackBudget(
                TargetAction.FLASHLIGHT,
                VendorProfile.HUAWEI,
            ),
        )
        assertEquals(
            400L,
            BlueLMInterceptorService.assistantDismissTimeoutMs(VendorProfile.HUAWEI),
        )
        assertEquals(30L, VendorProfile.VIVO.pollMs)
        assertEquals(40L, VendorProfile.VIVO.hammerMinIntervalMs)
    }

    @Test
    fun osLabelUsesVendorProfileOnHuawei() {
        assertEquals(
            "EMUI / HarmonyOS (EmotionUI_14.0.0)",
            VendorProfile.osLabel(
                profile = VendorProfile.HUAWEI,
                vivoLabel = "unknown",
                emuiVersion = "EmotionUI_14.0.0",
                harmonyVersion = null,
            ),
        )
        assertEquals(
            "EMUI / HarmonyOS",
            VendorProfile.osLabel(
                profile = VendorProfile.HUAWEI,
                vivoLabel = "unknown",
                emuiVersion = null,
                harmonyVersion = null,
            ),
        )
        assertEquals(
            "OriginOS 6",
            VendorProfile.osLabel(
                profile = VendorProfile.VIVO,
                vivoLabel = "OriginOS 6",
                emuiVersion = null,
                harmonyVersion = null,
            ),
        )
    }
}
