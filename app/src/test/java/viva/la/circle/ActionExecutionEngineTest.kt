package viva.la.circle

import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.HuaweiPowerManagement
import viva.la.circle.engine.OriginOs
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.TargetAction
import viva.la.circle.service.InterceptorStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionExecutionEngineTest {

    @Before
    fun setUp() {
        InterceptorStateRepository.reset()
    }

    @Test
    fun testTargetActionFromName() {
        assertEquals(TargetAction.NONE, TargetAction.fromName("NONE"))
        assertEquals(TargetAction.ASSISTANT_CHOOSER, TargetAction.fromName("ASSISTANT_CHOOSER"))
        assertEquals(TargetAction.DEFAULT_ASSISTANT, TargetAction.fromName("DEFAULT_ASSISTANT"))
        assertEquals(TargetAction.CIRCLE_TO_SEARCH, TargetAction.fromName("CIRCLE_TO_SEARCH"))
        assertEquals(TargetAction.HWCTS, TargetAction.fromName("HWCTS"))
        assertEquals(TargetAction.SPECIFIC_APP, TargetAction.fromName("SPECIFIC_APP"))
        assertEquals(TargetAction.FLASHLIGHT, TargetAction.fromName("FLASHLIGHT"))
        assertEquals(TargetAction.SCREENSHOT, TargetAction.fromName("SCREENSHOT"))
        assertEquals(TargetAction.MUTE_TOGGLE, TargetAction.fromName("MUTE_TOGGLE"))

        // Fallbacks for null or invalid inputs
        assertEquals(TargetAction.DEFAULT_ASSISTANT, TargetAction.fromName(null))
        assertEquals(TargetAction.DEFAULT_ASSISTANT, TargetAction.fromName("INVALID_ACTION_NAME", TargetAction.DEFAULT_ASSISTANT))
        assertEquals(
            TargetAction.DEFAULT_ASSISTANT,
            TargetAction.fromName("CIRCLE_TO_SEARCH_TYPO", TargetAction.DEFAULT_ASSISTANT),
        )
        val names = TargetAction.entries.map { it.name }
        assertEquals(
            names.indexOf("DEFAULT_ASSISTANT") + 1,
            names.indexOf("CIRCLE_TO_SEARCH"),
        )
    }

    @Test
    fun testTargetActionTitlesAndDescriptions() {
        for (action in TargetAction.entries) {
            assertTrue("Title res should be set for $action", action.titleRes != 0)
            assertTrue("Description res should be set for $action", action.descriptionRes != 0)
        }
    }

    @Test
    fun testActionPersistenceAndStateRepository() {
        val initialState = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.DEFAULT_ASSISTANT, initialState.blueLMAction)
        assertNull(initialState.blueLMSpecificPackage)
        assertEquals(TargetAction.NONE, initialState.cameraAction)
        assertNull(initialState.cameraSpecificPackage)
        assertTrue(initialState.skipCameraApp)

        InterceptorStateRepository.persistChooserSelection(
            context = null,
            packageName = "com.openai.chatgpt",
        )

        val chooserPersisted = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.SPECIFIC_APP, chooserPersisted.blueLMAction)
        assertEquals("com.openai.chatgpt", chooserPersisted.blueLMSpecificPackage)

        InterceptorStateRepository.setBlueLMAction(
            context = null,
            action = TargetAction.SPECIFIC_APP,
            specificPackage = "com.openai.chatgpt"
        )

        val updatedState1 = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.SPECIFIC_APP, updatedState1.blueLMAction)
        assertEquals("com.openai.chatgpt", updatedState1.blueLMSpecificPackage)

        // Set Camera Action
        InterceptorStateRepository.setCameraAction(
            context = null,
            action = TargetAction.FLASHLIGHT
        )

        val updatedState2 = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.FLASHLIGHT, updatedState2.cameraAction)

        // Set Camera Action to NONE
        InterceptorStateRepository.setCameraAction(
            context = null,
            action = TargetAction.NONE
        )

        val updatedState3 = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.NONE, updatedState3.cameraAction)

        InterceptorStateRepository.setSkipCameraApp(context = null, skip = false)
        assertEquals(false, InterceptorStateRepository.serviceState.value.skipCameraApp)

        InterceptorStateRepository.setDismissDelayMs(context = null, delayMs = 0)
        assertEquals(0, InterceptorStateRepository.serviceState.value.dismissDelayMs)
        InterceptorStateRepository.setDismissDelayMs(context = null, delayMs = 400)
        assertEquals(400, InterceptorStateRepository.serviceState.value.dismissDelayMs)
        InterceptorStateRepository.setDismissDelayMs(context = null, delayMs = 900)
        assertEquals(500, InterceptorStateRepository.serviceState.value.dismissDelayMs)

        assertNull(InterceptorStateRepository.sanitizeTestPackage("com.tosharoki.hwcts"))
        assertEquals("com.openai.chatgpt", InterceptorStateRepository.sanitizeTestPackage("com.openai.chatgpt"))

        // Reset
        InterceptorStateRepository.reset()
        val resetState = InterceptorStateRepository.serviceState.value
        assertEquals(TargetAction.DEFAULT_ASSISTANT, resetState.blueLMAction)
        assertEquals(TargetAction.NONE, resetState.cameraAction)
        assertTrue(resetState.skipCameraApp)
    }

    @Test
    fun testParseAssistantComponent() {
        assertEquals(
            "com.google.android.googlequicksearchbox",
            ActionExecutionEngine.parseAssistantComponent(
                "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            ),
        )
        assertEquals(
            "com.openai.chatgpt",
            ActionExecutionEngine.parseAssistantComponent("com.openai.chatgpt"),
        )
        assertNull(ActionExecutionEngine.parseAssistantComponent(null))
        assertNull(ActionExecutionEngine.parseAssistantComponent(""))
    }

    @Test
    fun testDescribeAssistantSurfacePrefersIntentThenVoiceInteraction() {
        assertEquals(
            "yes - via assist intent",
            ActionExecutionEngine.describeAssistantSurface(
                hasAssistIntent = true,
                hasVoiceCommand = true,
                hasVoiceInteraction = true,
            ),
        )
        assertEquals(
            "yes - via voice command",
            ActionExecutionEngine.describeAssistantSurface(
                hasAssistIntent = false,
                hasVoiceCommand = true,
                hasVoiceInteraction = true,
            ),
        )
        assertEquals(
            "yes - VoiceInteractionService",
            ActionExecutionEngine.describeAssistantSurface(
                hasAssistIntent = false,
                hasVoiceCommand = false,
                hasVoiceInteraction = true,
            ),
        )
        assertEquals(
            "no - will try launcher",
            ActionExecutionEngine.describeAssistantSurface(
                hasAssistIntent = false,
                hasVoiceCommand = false,
                hasVoiceInteraction = false,
            ),
        )
    }

    @Test
    fun testAssistDisambiguationDetectsResolverNotAssistant() {
        assertTrue(
            ActionExecutionEngine.isAssistDisambiguation(
                "com.android.intentresolver",
                "com.android.intentresolver.ResolverActivity",
            ),
        )
        assertTrue(
            ActionExecutionEngine.isAssistDisambiguation(
                "com.android.internal.app",
                "com.android.internal.app.ResolverActivity",
            ),
        )
        assertFalse(
            ActionExecutionEngine.isAssistDisambiguation(
                "com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox.GoogleAppImplicitActionAssistGatewayInternal",
            ),
        )
        assertFalse(
            ActionExecutionEngine.isAssistDisambiguation(
                "com.tosharoki.hwcts",
                "com.tosharoki.hwcts.StubAssistantService",
            ),
        )
        assertTrue(
            "EMUI answers unresolved implicit intents with HwResolverActivity",
            ActionExecutionEngine.isAssistDisambiguation(
                "com.huawei.android.internal.app",
                "com.huawei.android.internal.app.HwResolverActivity",
            ),
        )
        assertFalse(
            ActionExecutionEngine.resolvesToRealActivity(
                "com.huawei.android.internal.app",
                "com.huawei.android.internal.app.HwResolverActivity",
            ),
        )
        assertTrue(
            ActionExecutionEngine.resolvesToRealActivity(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        assertFalse(ActionExecutionEngine.resolvesToRealActivity(null, null))
    }

    @Test
    fun hwctsTileActionMatchesQsTileBroadcast() {
        assertEquals("com.tosharoki.hwcts.ACTION_TILE_TRIGGER", ActionExecutionEngine.HWCTS_TILE_ACTION)
        assertEquals("com.tosharoki.hwcts", ActionExecutionEngine.HWCTS_PACKAGE)
    }

    @Test
    fun huaweiPowerManagementDetectsVendor() {
        assertFalse(HuaweiPowerManagement.isHuaweiDevice(VendorProfile.VIVO))
        assertFalse(HuaweiPowerManagement.isHuaweiDevice(VendorProfile.GENERIC))
        assertTrue(HuaweiPowerManagement.isHuaweiDevice(VendorProfile.HUAWEI))
    }

    @Test
    fun hwctsAccessibilityEnabledReadsColonSeparatedList() {
        assertFalse(ActionExecutionEngine.hwctsAccessibilityEnabled(null))
        assertFalse(ActionExecutionEngine.hwctsAccessibilityEnabled(""))
        assertFalse(
            ActionExecutionEngine.hwctsAccessibilityEnabled(
                "com.ivianuu.oneplusgestures/com.ivianuu.vivid.accessibility.VividAccessibilityService",
            ),
        )
        assertTrue(
            ActionExecutionEngine.hwctsAccessibilityEnabled(
                "com.ivianuu.oneplusgestures/com.ivianuu.vivid.accessibility.VividAccessibilityService:" +
                    "com.tosharoki.hwcts/.LensAccessibilityService",
            ),
        )
        assertTrue(
            ActionExecutionEngine.hwctsAccessibilityEnabled(
                "com.tosharoki.hwcts/com.tosharoki.hwcts.LensAccessibilityService",
            ),
        )
    }

    @Test
    fun testKnownAssistantPackagesList() {
        val knownPackages = ActionExecutionEngine.KNOWN_ASSISTANT_PACKAGES
        assertTrue("Known assistant list should not be empty", knownPackages.isNotEmpty())

        val packageNames = knownPackages.map { it.first }
        assertTrue("Should include Google Search Box", packageNames.contains("com.google.android.googlequicksearchbox"))
        assertTrue("Should include ChatGPT", packageNames.contains("com.openai.chatgpt"))
        assertTrue("Should include Claude", packageNames.contains("com.anthropic.claude"))
        assertTrue("Should include Microsoft Copilot", packageNames.contains("com.microsoft.copilot"))

        // KNOWN_ASSISTANT_PACKAGES is the *choosable* list, so Google belongs in it. There is
        // deliberately no auto-fallback priority list any more: guessing an assistant when the
        // system default was unusable is what silently launched Google instead of the user's pick.
        assertFalse(
            "No hardcoded auto-fallback list should exist",
            ActionExecutionEngine::class.java.declaredFields.any {
                it.name.contains("DEFAULT_ASSISTANT_PACKAGE_PRIORITY")
            },
        )
    }

    @Test
    fun testOriginOsParseMajorAndDelay() {
        assertEquals(6, OriginOs.parseMajor("OriginOS 6", "16.0"))
        assertEquals(5, OriginOs.parseMajor("OriginOS 5", "15.0"))
        assertEquals(6, OriginOs.parseMajor(null, "16.0"))
        assertEquals(5, OriginOs.parseMajor(null, "15.0"))
        assertNull(OriginOs.parseMajor(null, null))
        assertEquals(100, OriginOs.defaultDismissDelayMs(6))
        assertEquals(200, OriginOs.defaultDismissDelayMs(5))
        assertEquals(100, OriginOs.defaultDismissDelayMs(null))
        assertEquals("OriginOS 6", OriginOs.label("OriginOS 6", "16.0", 6))
        assertEquals("OriginOS 5", OriginOs.label(null, null, 5))
    }

    @Suppress("unused")
    private class FakeSearchManager {
        fun launchAssist() {}
        fun launchAssist(args: android.os.Bundle) {}
        fun launchAssist(args: android.os.Bundle, userId: Int) {}
        fun other() {}
    }

    @Test
    fun pickLaunchAssistPrefersSingleBundleArg() {
        val method = ActionExecutionEngine.pickLaunchAssistMethod(FakeSearchManager::class.java.methods)
        assertEquals("launchAssist", method?.name)
        assertEquals(1, method?.parameterTypes?.size)
        assertEquals(android.os.Bundle::class.java, method?.parameterTypes?.get(0))
    }
}
