package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.TargetAction
import viva.la.circle.remap.InterceptedAssistant
import viva.la.circle.service.BlueLMInterceptorService

class VendorProfileTest {

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

    @Test
    fun engineLoopGuardDelegatesToInterceptedAssistant() {
        val ownPkg = "viva.la.circle"
        assertEquals(
            InterceptedAssistant.wouldLoopToInterceptedAssistant("com.huawei.hiassistantoversea", ownPkg),
            ActionExecutionEngine.wouldLoopToInterceptedAssistant("com.huawei.hiassistantoversea", ownPkg),
        )
        assertFalse(
            ActionExecutionEngine.wouldLoopToInterceptedAssistant(
                "com.google.android.googlequicksearchbox",
                ownPkg,
            ),
        )
    }
}
