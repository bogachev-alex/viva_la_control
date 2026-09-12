package viva.la.circle

import viva.la.circle.engine.CircleToSearch
import viva.la.circle.model.TargetAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleToSearchTest {

    @Test
    fun extraSettleOnlyForCircleToSearch() {
        assertEquals(250, CircleToSearch.extraSettleMs(TargetAction.CIRCLE_TO_SEARCH))
        assertEquals(0, CircleToSearch.extraSettleMs(TargetAction.DEFAULT_ASSISTANT))
        assertEquals(0, CircleToSearch.extraSettleMs(TargetAction.FLASHLIGHT))
    }

    @Test
    fun showSessionFlagsAreAssistScreenshotApplication() {
        assertEquals(7, CircleToSearch.SHOW_SESSION_FLAGS)
    }

    @Test
    fun triggerPrefersVoiceSessionOverLaunchAssist() {
        var sessionCalls = 0
        var assistCalls = 0
        val ok = CircleToSearch.triggerWith(
            entryPoint = 1,
            showSession = {
                sessionCalls++
                true
            },
            launchAssist = {
                assistCalls++
                true
            },
        )
        assertTrue(ok)
        assertEquals(1, sessionCalls)
        assertEquals(0, assistCalls)
    }

    @Test
    fun triggerFallsBackToLaunchAssistWhenSessionFails() {
        var assistCalls = 0
        val ok = CircleToSearch.triggerWith(
            entryPoint = 1,
            showSession = { false },
            launchAssist = {
                assistCalls++
                true
            },
        )
        assertTrue(ok)
        assertEquals(1, assistCalls)
    }

    @Test
    fun triggerFailsWhenBothPathsMiss() {
        assertFalse(
            CircleToSearch.triggerWith(
                entryPoint = 1,
                showSession = { false },
                launchAssist = { false },
            ),
        )
    }

    @Test
    fun isGoogleAssistantSettingReadsComponentAndPackage() {
        assertTrue(
            CircleToSearch.isGoogleAssistantSetting(
                "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService",
            ),
        )
        assertTrue(
            CircleToSearch.isGoogleAssistantSetting("com.google.android.googlequicksearchbox"),
        )
        assertFalse(
            CircleToSearch.isGoogleAssistantSetting(
                "com.vivo.ai.gptagent/com.vivo.ai.gptagent.AssistantService",
            ),
        )
        assertFalse(CircleToSearch.isGoogleAssistantSetting(null))
        assertFalse(CircleToSearch.isGoogleAssistantSetting(""))
    }

    @Test
    fun assistantSettingsCandidatesPreferAssistGestureOnApi29() {
        val api28 = CircleToSearch.assistantSettingsActionCandidates(28)
        assertFalse(api28.contains("android.settings.ASSIST_GESTURE_SETTINGS"))
        assertTrue(api28.contains("android.settings.VOICE_INPUT_SETTINGS"))

        val api29 = CircleToSearch.assistantSettingsActionCandidates(29)
        assertEquals("android.settings.ASSIST_GESTURE_SETTINGS", api29.first())
        assertTrue(api29.contains("android.settings.VOICE_INPUT_SETTINGS"))
        assertTrue(api29.contains("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
    }

    @Test
    fun readinessMatrix() {
        val keys = listOf(null, "", "omni.entry_point")
        for (googleInstalled in listOf(true, false)) {
            for (googleIsAssistant in listOf(true, false)) {
                for (csKey in keys) {
                    for (serviceAvailable in listOf(true, false)) {
                        val readiness = CircleToSearch.Readiness(
                            googleInstalled = googleInstalled,
                            googleIsAssistant = googleIsAssistant,
                            contextualSearchKey = csKey,
                            serviceAvailable = serviceAvailable,
                        )
                        val expectedUsable = serviceAvailable && googleInstalled &&
                            (googleIsAssistant || !csKey.isNullOrEmpty())
                        assertEquals(
                            "usable gInst=$googleInstalled gAsst=$googleIsAssistant cs=$csKey svc=$serviceAvailable",
                            expectedUsable,
                            readiness.usable,
                        )
                        if (expectedUsable) {
                            assertNull(readiness.blocker)
                        } else {
                            val expectedBlocker = when {
                                !serviceAvailable -> "voiceinteraction service unavailable"
                                !googleInstalled -> "Google app not installed"
                                else -> "Google is not the default assistant"
                            }
                            assertEquals(expectedBlocker, readiness.blocker)
                        }
                    }
                }
            }
        }
    }
}
