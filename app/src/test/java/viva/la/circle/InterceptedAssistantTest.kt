package viva.la.circle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.remap.InterceptedAssistant

class InterceptedAssistantTest {

    private val ownPkg = "viva.la.circle"

    @Test
    fun knownVivoAndHuaweiPackagesAreInterceptedAssistants() {
        listOf(
            "com.vivo.agent",
            "com.vivo.vpa",
            "com.bbk.voiceassistant",
            "com.vivo.ai.copilot",
            "com.vivo.blue.assistant",
            "com.vivo.bluelm",
            "com.huawei.hiassistantoversea",
            "com.huawei.hiassistant",
            "com.huawei.vassistant",
        ).forEach { pkg ->
            assertTrue(pkg, InterceptedAssistant.isInterceptedAssistant(pkg, null, ownPkg))
        }
    }

    @Test
    fun packageHintsAndVivoAgentCompoundMatch() {
        assertTrue(InterceptedAssistant.isInterceptedAssistant("com.vivo.something.bluelm", null, ownPkg))
        assertTrue(InterceptedAssistant.isInterceptedAssistant("com.vivoassistant.app", null, ownPkg))
        assertTrue(InterceptedAssistant.isInterceptedAssistant("com.vivo.jovi.home", null, ownPkg))
        assertTrue(InterceptedAssistant.isInterceptedAssistant("com.vivo.some.agent", null, ownPkg))
    }

    @Test
    fun unrelatedAppsOwnPackageAndBroadSubstringsDoNotMatch() {
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.random.agent", null, ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.other.app", "com.vivo.jovi.VoiceActivity", ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.example.tvparental", null, ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.microsoft.copilot", null, ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.whatsapp", null, ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("com.google.android.apps.bard", null, ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant(ownPkg, "MainActivity", ownPkg))
        assertFalse(InterceptedAssistant.isInterceptedAssistant("viva.la.circle.debug", null, ownPkg))
    }

    @Test
    fun secondaryUiIsNotWakeUi() {
        assertTrue(
            InterceptedAssistant.isSecondaryUi("com.vivo.ai.copilot.settings.MainActivity"),
        )
        assertTrue(
            InterceptedAssistant.isSecondaryUi("com.vivo.ai.copilot.circletosearch.Foo"),
        )
        assertFalse(InterceptedAssistant.isSecondaryUi("com.vivo.ai.copilot.FloatService"))
        assertFalse(
            InterceptedAssistant.isWakeUi(
                "com.vivo.ai.copilot",
                "com.vivo.ai.copilot.settings.activity.AboutActivity",
                ownPkg,
            ),
        )
        assertTrue(
            InterceptedAssistant.isWakeUi("com.vivo.ai.copilot", "android.widget.FrameLayout", ownPkg),
        )
        assertTrue(
            InterceptedAssistant.isWakeUi(
                "com.vivo.ai.copilot",
                "com.vivo.ai.copilot.transfer.EmptyLauncherActivity",
                ownPkg,
            ),
        )
    }

    @Test
    fun knownPackagesUnionsEveryVendorSurface() {
        val known = InterceptedAssistant.knownPackages
        assertTrue(known.contains("com.vivo.ai.copilot"))
        assertTrue(known.contains("com.huawei.hiassistantoversea"))
    }

    @Test
    fun defaultAssistantLoopUsesInterceptedAssistant() {
        assertTrue(
            InterceptedAssistant.wouldLoopToInterceptedAssistant(
                "com.huawei.hiassistantoversea",
                ownPkg,
            ),
        )
        assertTrue(
            InterceptedAssistant.wouldLoopToInterceptedAssistant("com.vivo.ai.copilot", ownPkg),
        )
        assertFalse(
            InterceptedAssistant.wouldLoopToInterceptedAssistant(
                "com.google.android.googlequicksearchbox",
                ownPkg,
            ),
        )
        assertFalse(InterceptedAssistant.wouldLoopToInterceptedAssistant(null, ownPkg))
    }
}
