package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.model.TargetAction
import viva.la.circle.remap.CtsReadiness
import viva.la.circle.remap.RemapConfig

class RemapConfigTest {

    private val own = "viva.la.circle"
    private val labels = mapOf(
        "com.openai.chatgpt" to "ChatGPT",
        "com.vivo.agent" to "BlueLM",
        "com.google.android.googlequicksearchbox" to "Google",
    )
    private val appLabel: (String) -> String = { pkg -> labels[pkg] ?: pkg }

    @Test
    fun firePreview_unsetAndPassThrough() {
        assertEquals(
            RemapConfig.FirePreview.Unset,
            RemapConfig.firePreview(null, null, null, own, true, appLabel),
        )
        assertEquals(
            RemapConfig.FirePreview.PassThrough,
            RemapConfig.firePreview(TargetAction.NONE, null, null, own, true, appLabel),
        )
    }

    @Test
    fun firePreview_defaultAssistantLoopBlocked() {
        assertEquals(
            RemapConfig.FirePreview.DefaultBlocked("com.vivo.agent"),
            RemapConfig.firePreview(
                action = TargetAction.DEFAULT_ASSISTANT,
                specificPackage = null,
                systemDefaultPackage = "com.vivo.agent",
                ownPackage = own,
                hwctsInstalled = true,
                appLabel = appLabel,
            ),
        )
        assertEquals(
            "com.vivo.agent",
            RemapConfig.defaultAssistantLoopPackage(
                TargetAction.DEFAULT_ASSISTANT,
                "com.vivo.agent",
                own,
            ),
        )
    }

    @Test
    fun firePreview_specificAppUsesLabel() {
        assertEquals(
            RemapConfig.FirePreview.SpecificApp("ChatGPT"),
            RemapConfig.firePreview(
                action = TargetAction.SPECIFIC_APP,
                specificPackage = "com.openai.chatgpt",
                systemDefaultPackage = null,
                ownPackage = own,
                hwctsInstalled = true,
                appLabel = appLabel,
            ),
        )
        assertEquals(
            RemapConfig.FirePreview.SpecificUnset,
            RemapConfig.firePreview(
                action = TargetAction.SPECIFIC_APP,
                specificPackage = null,
                systemDefaultPackage = null,
                ownPackage = own,
                hwctsInstalled = true,
                appLabel = appLabel,
            ),
        )
    }

    @Test
    fun hwctsAndHuaweiGates() {
        assertEquals(RemapConfig.HwctsGate.NOT_INSTALLED, RemapConfig.hwctsGate(false, false))
        assertEquals(RemapConfig.HwctsGate.ACCESSIBILITY_OFF, RemapConfig.hwctsGate(true, false))
        assertEquals(RemapConfig.HwctsGate.READY, RemapConfig.hwctsGate(true, true))
        assertTrue(RemapConfig.needsHuaweiAppLaunchCard(isHuaweiDevice = true, acknowledged = false))
        assertFalse(RemapConfig.needsHuaweiAppLaunchCard(isHuaweiDevice = true, acknowledged = true))
        assertFalse(RemapConfig.needsHuaweiAppLaunchCard(isHuaweiDevice = false, acknowledged = false))
    }

    @Test
    fun ctsGate_mapsReadiness() {
        assertEquals(RemapConfig.CtsGate.Checking, RemapConfig.ctsGate(null, false))
        assertEquals(
            RemapConfig.CtsGate.ReadyGoogle,
            RemapConfig.ctsGate(
                CtsReadiness(
                    usable = true,
                    googleInstalled = true,
                    googleIsAssistant = true,
                    contextualSearchKey = null,
                    blockerText = null,
                ),
                hasAssistantSettingsIntent = true,
            ),
        )
        val blocked = RemapConfig.ctsGate(
            CtsReadiness(
                usable = false,
                googleInstalled = true,
                googleIsAssistant = false,
                contextualSearchKey = null,
                blockerText = "not ready",
            ),
            hasAssistantSettingsIntent = true,
        ) as RemapConfig.CtsGate.Blocked
        assertEquals("not ready", blocked.blockerText)
        assertTrue(blocked.showOpenAssistantSettings)
        assertFalse(blocked.showAssistantSettingsPath)
        assertNull(
            RemapConfig.defaultAssistantLoopPackage(TargetAction.FLASHLIGHT, "com.vivo.agent", own),
        )
    }
}
