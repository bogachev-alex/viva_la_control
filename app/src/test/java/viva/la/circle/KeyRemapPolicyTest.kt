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
    fun powerShortPressLocks() {
        assertEquals(
            KeyRemapPolicy.PowerDecision.ShortPressLock,
            KeyRemapPolicy.onPowerUp(wasConsuming = true, longFired = false),
        )
        assertTrue(KeyRemapPolicy.isPowerLongPress(500L))
        assertFalse(KeyRemapPolicy.isPowerLongPress(499L))
    }
}
