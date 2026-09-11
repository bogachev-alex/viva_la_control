package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.model.TargetAction
import viva.la.circle.remap.KeyRemapPolicy

class KeyRemapPolicyTest {

    private val own = "viva.la.circle"

    @Test
    fun shutterIgnoreWhenNone() {
        val decision = KeyRemapPolicy.onShutterDown(
            cameraAction = TargetAction.NONE,
            repeatCount = 0,
            skipWhileCameraOpen = false,
            foregroundPackage = "com.android.launcher3",
            foregroundSinceMs = 0,
            nowMs = 10_000,
            ownPackage = own,
            isCameraApp = { _, _, _ -> false },
            hasPendingSingleAction = false,
        )
        assertEquals(KeyRemapPolicy.ShutterDecision.Ignore, decision)
    }

    @Test
    fun shutterDoublePressCancelsPending() {
        val decision = KeyRemapPolicy.onShutterDown(
            cameraAction = TargetAction.FLASHLIGHT,
            repeatCount = 0,
            skipWhileCameraOpen = false,
            foregroundPackage = "com.android.launcher3",
            foregroundSinceMs = 0,
            nowMs = 10_000,
            ownPackage = own,
            isCameraApp = { _, _, _ -> false },
            hasPendingSingleAction = true,
        )
        assertEquals(KeyRemapPolicy.ShutterDecision.CancelPendingDoublePress, decision)
    }

    @Test
    fun shutterPassThroughWhenCameraForegroundPastGrace() {
        val now = 10_000L
        val decision = KeyRemapPolicy.onShutterDown(
            cameraAction = TargetAction.FLASHLIGHT,
            repeatCount = 0,
            skipWhileCameraOpen = true,
            foregroundPackage = "com.android.camera",
            foregroundSinceMs = now - KeyRemapPolicy.CAMERA_FOREGROUND_GRACE_MS,
            nowMs = now,
            ownPackage = own,
            isCameraApp = { pkg, _, _ -> pkg == "com.android.camera" },
            hasPendingSingleAction = false,
        )
        assertEquals(KeyRemapPolicy.ShutterDecision.PassThrough, decision)
    }

    @Test
    fun shutterUpSchedulesFire() {
        assertEquals(
            KeyRemapPolicy.ShutterDecision.ScheduleFire(keyCode = 27, heldMs = 40L),
            KeyRemapPolicy.onShutterUp(
                wasConsuming = true,
                suppressNextUpAction = false,
                nowMs = 10_000,
                lastCameraInterceptMs = 0,
                keyCode = 27,
                heldMs = 40L,
            ),
        )
    }

    @Test
    fun shutterUpCooldownConsumesWithoutFire() {
        assertEquals(
            KeyRemapPolicy.ShutterDecision.ConsumeUpNoFire,
            KeyRemapPolicy.onShutterUp(
                wasConsuming = true,
                suppressNextUpAction = false,
                nowMs = 10_000,
                lastCameraInterceptMs = 10_000 - 50,
                keyCode = 27,
                heldMs = 40L,
            ),
        )
    }

    @Test
    fun powerIgnoresUnsetAndNone() {
        assertEquals(
            KeyRemapPolicy.PowerDecision.Ignore,
            KeyRemapPolicy.onPowerDown(
                blueLMAction = null,
                screenInteractive = true,
                repeatCount = 0,
                alreadyConsuming = false,
            ),
        )
        assertEquals(
            KeyRemapPolicy.PowerDecision.Ignore,
            KeyRemapPolicy.onPowerDown(
                blueLMAction = TargetAction.NONE,
                screenInteractive = true,
                repeatCount = 0,
                alreadyConsuming = false,
            ),
        )
    }

    @Test
    fun powerLongPressPathStartsJob() {
        assertEquals(
            KeyRemapPolicy.PowerDecision.ConsumeDownStartLongJob,
            KeyRemapPolicy.onPowerDown(
                blueLMAction = TargetAction.FLASHLIGHT,
                screenInteractive = true,
                repeatCount = 0,
                alreadyConsuming = false,
            ),
        )
    }

    @Test
    fun powerShortPressLocks() {
        assertEquals(
            KeyRemapPolicy.PowerDecision.ShortPressLock,
            KeyRemapPolicy.onPowerUp(wasConsuming = true, longFired = false),
        )
        assertTrue(KeyRemapPolicy.isPowerLongPress(500L))
        assertFalse(KeyRemapPolicy.isPowerLongPress(499L))
    }
}
