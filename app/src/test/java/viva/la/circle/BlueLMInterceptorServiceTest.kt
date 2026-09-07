package viva.la.circle

import android.view.KeyEvent
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.TargetAction
import viva.la.circle.service.BlueLMInterceptorService
import viva.la.circle.service.InterceptorStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlueLMInterceptorServiceTest {

    @Before
    fun setUp() {
        InterceptorStateRepository.reset()
    }

    @Test
    fun testDetectKnownPackages() {
        val knownPackages = listOf(
            "com.vivo.agent",
            "com.vivo.vpa",
            "com.bbk.voiceassistant",
            "com.vivo.ai.copilot",
            "com.vivo.blue.assistant",
            "com.vivo.bluelm"
        )

        for (pkg in knownPackages) {
            assertTrue("Package $pkg should be detected", BlueLMInterceptorService.isBlueLMOrVivoAssistant(pkg, null))
        }
    }

    @Test
    fun testDetectKeywordMatches() {
        val matchingPackages = listOf(
            "com.vivo.something.bluelm",
            "com.vivoassistant.app",
            "com.vivo.jovi.home",
        )

        for (pkg in matchingPackages) {
            assertTrue("Package $pkg should be detected", BlueLMInterceptorService.isBlueLMOrVivoAssistant(pkg, "MainActivity"))
        }
    }

    @Test
    fun testIgnoreBroadSubstringMatches() {
        assertFalse(
            "Bare agent substring must not match",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.random.agent", "AgentActivity"),
        )
        assertFalse(
            "Class-name jovi must not match",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.other.app", "com.vivo.jovi.VoiceActivity"),
        )
        assertFalse(
            "vpa substring in unrelated package must not match",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.example.tvparental", null),
        )
        assertFalse(
            "Microsoft Copilot must not be treated as Vivo BlueLM",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.microsoft.copilot", null),
        )
    }

    @Test
    fun testIgnoreOwnPackage() {
        val ownPkg = "viva.la.circle"
        assertFalse(
            "Own package should be ignored",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant(ownPkg, "MainActivity", ownPkg)
        )
    }

    @Test
    fun testIgnoreNormalApps() {
        val normalApps = listOf(
            "com.android.settings",
            "com.google.android.youtube",
            "com.whatsapp"
        )

        for (pkg in normalApps) {
            assertFalse("App $pkg should NOT be detected", BlueLMInterceptorService.isBlueLMOrVivoAssistant(pkg, "MainActivity"))
        }
    }

    @Test
    fun testDetectCameraApps() {
        val cameraPackages = listOf(
            "com.vivo.camera",
            "com.android.camera",
            "com.android.camera2",
            "com.google.android.GoogleCamera",
            "com.vivo.media.camera",
        )

        for (pkg in cameraPackages) {
            assertTrue("Camera package $pkg should be detected", BlueLMInterceptorService.isCameraApp(pkg, null))
        }

        assertFalse(
            "smartshot is a screenshot overlay, not a camera app",
            BlueLMInterceptorService.isCameraApp("com.vivo.smartshot", "android.widget.FrameLayout"),
        )
        assertFalse(
            "doubleclickcamera is a launch handler, not a camera UI",
            BlueLMInterceptorService.isCameraApp("com.vivo.doubleclickcamera", null),
        )
        assertFalse(
            "Substring camera in an unrelated package must not match",
            BlueLMInterceptorService.isCameraApp("com.custom.app", "CameraActivity"),
        )
        assertFalse(
            "upslide overlay must not match",
            BlueLMInterceptorService.isCameraApp("com.vivo.upslide", "android.widget.FrameLayout"),
        )

        val ownPkg = "viva.la.circle"
        assertFalse("Own package should be ignored for camera", BlueLMInterceptorService.isCameraApp(ownPkg, "CameraHelper", ownPkg))
        assertFalse("Non-camera app should not be detected", BlueLMInterceptorService.isCameraApp("com.whatsapp", "ChatActivity"))
    }

    @Test
    fun overlayWindowsAreNotForegroundActivities() {
        assertTrue(
            BlueLMInterceptorService.isLikelyActivityWindow("com.android.camera.CameraActivity"),
        )
        assertTrue(
            BlueLMInterceptorService.isLikelyActivityWindow("com.bbk.launcher2.Launcher"),
        )
        assertFalse(
            BlueLMInterceptorService.isLikelyActivityWindow("android.widget.FrameLayout"),
        )
        assertFalse(
            BlueLMInterceptorService.isLikelyActivityWindow("android.view.View"),
        )
        assertFalse(BlueLMInterceptorService.isLikelyActivityWindow(""))
        assertFalse(BlueLMInterceptorService.isLikelyActivityWindow(null))
        assertFalse(
            "Recorder / overlay services must not steal preAssist",
            BlueLMInterceptorService.isLikelyActivityWindow(
                "com.huawei.screenrecorder.ScreenRecordService",
            ),
        )
        assertTrue(BlueLMInterceptorService.isForegroundAppWindow(1, true))
        assertFalse(BlueLMInterceptorService.isForegroundAppWindow(1, false))
        assertFalse(BlueLMInterceptorService.isForegroundAppWindow(3, true))
        assertTrue(
            "Copilot overlay class must still be detected as BlueLM by package",
            BlueLMInterceptorService.isBlueLMOrVivoAssistant("com.vivo.ai.copilot", "android.widget.FrameLayout"),
        )
        assertFalse(
            "Copilot overlay class is not an activity for FG tracking",
            BlueLMInterceptorService.isLikelyActivityWindow("android.widget.FrameLayout"),
        )
        assertTrue(
            BlueLMInterceptorService.isCopilotWakeUi("com.vivo.ai.copilot", "android.widget.FrameLayout"),
        )
        assertTrue(
            BlueLMInterceptorService.isCopilotWakeUi(
                "com.vivo.ai.copilot",
                "com.vivo.ai.copilot.transfer.EmptyLauncherActivity",
            ),
        )
        assertFalse(
            "Settings must not be remapped",
            BlueLMInterceptorService.isCopilotWakeUi(
                "com.vivo.ai.copilot",
                "com.vivo.ai.copilot.settings.activity.AboutActivity",
            ),
        )
    }

    @Test
    fun containsCopilotWindowDetectsOverlayPackage() {
        val own = "viva.la.circle"
        // Top window only — a stale Celia/Copilot entry lower in the list must not count.
        assertTrue(
            BlueLMInterceptorService.containsCopilotWindow(
                listOf("com.vivo.ai.copilot", "org.mozilla.firefox"),
                own,
            ),
        )
        assertFalse(
            "Stale assistant under the browser must not keep the dismiss loop alive",
            BlueLMInterceptorService.containsCopilotWindow(
                listOf("org.mozilla.firefox", "com.vivo.ai.copilot"),
                own,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.containsCopilotWindow(
                listOf("org.mozilla.firefox", "com.bbk.launcher2"),
                own,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.containsCopilotWindow(emptyList(), own),
        )
        assertFalse(
            "Own package must not count as Copilot",
            BlueLMInterceptorService.containsCopilotWindow(listOf(own), own),
        )
        assertFalse(
            BlueLMInterceptorService.containsCopilotWindow(listOf(null, ""), own),
        )
    }

    @Test
    fun dismissStopsWhenTopIsNotAssistant() {
        val own = "viva.la.circle"
        val browser = "com.android.chrome"
        assertFalse(
            BlueLMInterceptorService.shouldStopDismissBacks(
                topPackage = "com.huawei.hiassistantoversea",
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertTrue(
            "User app restored — stop BACKs",
            BlueLMInterceptorService.shouldStopDismissBacks(
                topPackage = browser,
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertTrue(
            "Launcher means overshoot — stop BACKs",
            BlueLMInterceptorService.shouldStopDismissBacks(
                topPackage = "com.huawei.android.launcher",
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertTrue(
            BlueLMInterceptorService.isDismissOvershoot(
                topPackage = "com.huawei.android.launcher",
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.isDismissOvershoot(
                topPackage = browser,
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.shouldStopDismissBacks(
                topPackage = null,
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
    }

    @Test
    fun dismissBackOnlyWhileAssistantIsTop() {
        val own = "viva.la.circle"
        assertTrue(
            BlueLMInterceptorService.shouldPressDismissBack(
                "com.huawei.hiassistantoversea",
                own,
            ),
        )
        assertFalse(
            "BACK must not fire at the app under an unfocused overlay",
            BlueLMInterceptorService.shouldPressDismissBack("com.opera.browser", own),
        )
        assertFalse(BlueLMInterceptorService.shouldPressDismissBack(null, own))
        assertFalse(BlueLMInterceptorService.shouldPressDismissBack("android", own))
    }

    @Test
    fun dismissBackStopsAtThreeAndWhenCopilotGone() {
        assertEquals(3, BlueLMInterceptorService.MAX_DISMISS_BACKS)
        assertTrue(
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 0,
                copilotPresent = true,
                elapsedSinceLastBackMs = 100L,
            ),
        )
        assertTrue(
            "assumePresent skips a second window walk after top == assistant is already known",
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 0,
                copilotPresent = false,
                elapsedSinceLastBackMs = 0L,
                assumePresent = true,
            ),
        )
        assertFalse(
            "No BACK after Copilot has left the window list",
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 1,
                copilotPresent = false,
                elapsedSinceLastBackMs = 100L,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 3,
                copilotPresent = true,
                elapsedSinceLastBackMs = 100L,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = true,
                backPressCount = 0,
                copilotPresent = true,
                elapsedSinceLastBackMs = 100L,
            ),
        )
        assertFalse(
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 1,
                copilotPresent = true,
                elapsedSinceLastBackMs = 10L,
            ),
        )
        assertTrue(
            BlueLMInterceptorService.isAssistSessionForeground(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.lens.LensientActivity",
            ),
        )
    }

    @Test
    fun hwctsLeavesCopilotUpForTileBack() {
        assertEquals(1, BlueLMInterceptorService.copilotDismissBackBudget(TargetAction.HWCTS))
        assertEquals(
            3,
            BlueLMInterceptorService.copilotDismissBackBudget(
                TargetAction.CIRCLE_TO_SEARCH,
                VendorProfile.VIVO,
            ),
        )
        assertEquals(
            1,
            BlueLMInterceptorService.copilotDismissBackBudget(
                TargetAction.FLASHLIGHT,
                VendorProfile.HUAWEI,
            ),
        )
        assertFalse(BlueLMInterceptorService.shouldWaitUntilCopilotGone(TargetAction.HWCTS))
        assertTrue(BlueLMInterceptorService.shouldWaitUntilCopilotGone(TargetAction.CIRCLE_TO_SEARCH))
        assertTrue(
            "HwCTS still gets one of our BACKs while Copilot is up",
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 0,
                copilotPresent = true,
                elapsedSinceLastBackMs = 100L,
                maxBacks = BlueLMInterceptorService.HWCTS_DISMISS_BACKS,
            ),
        )
        assertFalse(
            "Second of ours would make the tile BACK hit the app",
            BlueLMInterceptorService.canDismissCopilotBack(
                actionLaunched = false,
                backPressCount = 1,
                copilotPresent = true,
                elapsedSinceLastBackMs = 100L,
                maxBacks = BlueLMInterceptorService.HWCTS_DISMISS_BACKS,
            ),
        )
    }

    @Test
    fun cameraKeyNotConsumedWhenCameraAppIsForeground() {
        val now = 10_000L
        assertTrue(
            "Camera open past grace window must pass through",
            BlueLMInterceptorService.shouldPassThroughCameraKey(
                skipWhileCameraOpen = true,
                foregroundPackage = "com.android.camera",
                foregroundSinceMs = now - BlueLMInterceptorService.CAMERA_FOREGROUND_GRACE_MS,
                nowMs = now,
                ownPackage = "viva.la.circle",
            ),
        )
        assertFalse(
            "Camera just opened by this press must still be intercepted",
            BlueLMInterceptorService.shouldPassThroughCameraKey(
                skipWhileCameraOpen = true,
                foregroundPackage = "com.android.camera",
                foregroundSinceMs = now - 200L,
                nowMs = now,
                ownPackage = "viva.la.circle",
            ),
        )
        assertFalse(
            BlueLMInterceptorService.shouldPassThroughCameraKey(
                skipWhileCameraOpen = true,
                foregroundPackage = "com.android.launcher3",
                foregroundSinceMs = now - 5_000L,
                nowMs = now,
                ownPackage = "viva.la.circle",
            ),
        )
        assertFalse(
            "Pass-through must be off when the setting is disabled",
            BlueLMInterceptorService.shouldPassThroughCameraKey(
                skipWhileCameraOpen = false,
                foregroundPackage = "com.vivo.camera",
                foregroundSinceMs = now - 5_000L,
                nowMs = now,
                ownPackage = "viva.la.circle",
            ),
        )
    }

    @Test
    fun cameraShutterKeyExcludesFocusAndWearStem() {
        assertTrue(BlueLMInterceptorService.isCameraShutterKey(KeyEvent.KEYCODE_CAMERA))
        assertFalse(BlueLMInterceptorService.isCameraShutterKey(KeyEvent.KEYCODE_FOCUS))
        assertFalse(BlueLMInterceptorService.isCameraShutterKey(KeyEvent.KEYCODE_STEM_PRIMARY))
        assertFalse(BlueLMInterceptorService.isCameraShutterKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(
            "Learned extra keycodes must match",
            BlueLMInterceptorService.isCameraShutterKey(1234, extraKeyCodes = setOf(1234)),
        )
        assertTrue(BlueLMInterceptorService.VENDOR_SHUTTER_LABELS.contains("PRESS"))
        assertFalse(BlueLMInterceptorService.VENDOR_SHUTTER_LABELS.contains("CAMERA_VIDEO"))
        assertFalse(
            "DOUBLE_CLICK must not be a default shutter key",
            BlueLMInterceptorService.VENDOR_SHUTTER_LABELS.contains("DOUBLE_CLICK"),
        )
        val pressCode = try {
            KeyEvent.keyCodeFromString("KEYCODE_PRESS")
        } catch (_: Exception) {
            KeyEvent.KEYCODE_UNKNOWN
        }
        if (pressCode != KeyEvent.KEYCODE_UNKNOWN) {
            assertTrue(
                "KEYCODE_PRESS must match once the ROM defines it",
                BlueLMInterceptorService.isCameraShutterKey(pressCode),
            )
        }
        assertEquals(150L, BlueLMInterceptorService.CAMERA_COOLDOWN_MS)
        assertEquals(1500L, BlueLMInterceptorService.BLUELM_COOLDOWN_MS)
    }

    @Test
    fun powerLongPressThreshold() {
        assertTrue(BlueLMInterceptorService.isPowerKey(KeyEvent.KEYCODE_POWER))
        assertFalse(BlueLMInterceptorService.isPowerKey(KeyEvent.KEYCODE_CAMERA))
        assertFalse(BlueLMInterceptorService.isPowerKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(
            "Short press must not count as Copilot hold",
            BlueLMInterceptorService.isPowerLongPress(499L),
        )
        assertTrue(BlueLMInterceptorService.isPowerLongPress(500L))
        assertTrue(BlueLMInterceptorService.isPowerLongPress(800L))
        assertEquals(500L, BlueLMInterceptorService.POWER_LONG_PRESS_MS)
    }

    @Test
    fun extraSettleIsOnlyForCircleToSearch() {
        assertEquals(250, CircleToSearch.extraSettleMs(TargetAction.CIRCLE_TO_SEARCH))
        assertEquals(0, CircleToSearch.extraSettleMs(TargetAction.FLASHLIGHT))
        assertTrue(
            BlueLMInterceptorService.isAssistSessionForeground(
                "com.tosharoki.hwcts",
                "android.service.voice.VoiceInteractionWindow",
            ),
        )
        assertFalse(
            BlueLMInterceptorService.isAssistSessionForeground(
                "viva.la.circle",
                "viva.la.circle.MainActivity",
            ),
        )
    }

    @Test
    fun diagIsSilentUntilEnabled() {
        InterceptorStateRepository.diag("KEY", "should not record")
        assertTrue(InterceptorStateRepository.diagSnapshot().isEmpty())

        InterceptorStateRepository.setDiagnosticsEnabled(true)
        InterceptorStateRepository.diag("KEY", "27 KEYCODE_CAMERA action=DOWN")
        val snapshot = InterceptorStateRepository.diagSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals("KEY", snapshot[0].kind)
        assertTrue(snapshot[0].detail.contains("27"))
    }

    @Test
    fun captureModeLearnsKeyCodes() {
        InterceptorStateRepository.setCaptureMode(true)
        InterceptorStateRepository.addCameraKeyCode(null, 80)
        assertTrue(InterceptorStateRepository.serviceState.value.cameraKeyCodes.contains(80))
        InterceptorStateRepository.removeCameraKeyCode(null, 80)
        assertFalse(InterceptorStateRepository.serviceState.value.cameraKeyCodes.contains(80))
    }

    @Test
    fun testStateRepository() {
        val initialState = InterceptorStateRepository.serviceState.value
        assertFalse(initialState.isRunning)
        assertEquals(0, initialState.interceptedCount)
        assertNull(initialState.lastInterceptedPackage)
        assertEquals(TargetAction.NONE, initialState.cameraAction)
        assertTrue(initialState.skipCameraApp)

        InterceptorStateRepository.updateRunning(true)
        assertTrue(InterceptorStateRepository.serviceState.value.isRunning)

        val now = 1000000L
        InterceptorStateRepository.recordInterception("com.vivo.agent", now)

        val updatedState = InterceptorStateRepository.serviceState.value
        assertEquals(1, updatedState.interceptedCount)
        assertEquals(now, updatedState.lastInterceptedTime)
        assertEquals("com.vivo.agent", updatedState.lastInterceptedPackage)
    }
}
