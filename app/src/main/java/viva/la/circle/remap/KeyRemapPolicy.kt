package viva.la.circle.remap

import viva.la.circle.model.TargetAction

/**
 * Key Remap policy for Shutter and power: decide consume / Pass-through / schedule / cancel / lock.
 */
object KeyRemapPolicy {

    const val DOUBLE_PRESS_WINDOW_MS = 250L
    const val POWER_LONG_PRESS_MS = 500L
    const val CAMERA_COOLDOWN_MS = 150L
    const val CAMERA_FOREGROUND_GRACE_MS = 1200L

    sealed class ShutterDecision {
        data object Ignore : ShutterDecision()
        data object PassThrough : ShutterDecision()
        data object ConsumeDown : ShutterDecision()
        data object CancelPendingDoublePress : ShutterDecision()
        data class ScheduleFire(val keyCode: Int, val heldMs: Long) : ShutterDecision()
        data object SuppressUpAfterDoublePress : ShutterDecision()
        data object ConsumeUpNoFire : ShutterDecision()
    }

    sealed class PowerDecision {
        data object Ignore : PowerDecision()
        data object ConsumeDownStartLongJob : PowerDecision()
        data object ContinueConsuming : PowerDecision()
        data object LongPressFire : PowerDecision()
        data object ShortPressLock : PowerDecision()
        data object UpAfterLong : PowerDecision()
    }

    fun onShutterDown(
        cameraAction: TargetAction,
        repeatCount: Int,
        skipWhileCameraOpen: Boolean,
        foregroundPackage: String?,
        foregroundSinceMs: Long,
        nowMs: Long,
        ownPackage: String,
        isCameraApp: (String?, String?, String) -> Boolean,
        hasPendingSingleAction: Boolean,
    ): ShutterDecision {
        if (cameraAction == TargetAction.NONE) return ShutterDecision.Ignore
        if (repeatCount > 0) return ShutterDecision.ConsumeDown
        if (shouldPassThroughCameraKey(
                skipWhileCameraOpen,
                foregroundPackage,
                foregroundSinceMs,
                nowMs,
                ownPackage,
                isCameraApp,
            )
        ) {
            return ShutterDecision.PassThrough
        }
        if (hasPendingSingleAction) return ShutterDecision.CancelPendingDoublePress
        return ShutterDecision.ConsumeDown
    }

    fun onShutterUp(
        wasConsuming: Boolean,
        suppressNextUpAction: Boolean,
        nowMs: Long,
        lastCameraInterceptMs: Long,
        keyCode: Int,
        heldMs: Long,
    ): ShutterDecision {
        if (!wasConsuming) return ShutterDecision.Ignore
        if (suppressNextUpAction) return ShutterDecision.SuppressUpAfterDoublePress
        if (nowMs - lastCameraInterceptMs < CAMERA_COOLDOWN_MS) {
            return ShutterDecision.ConsumeUpNoFire
        }
        return ShutterDecision.ScheduleFire(keyCode = keyCode, heldMs = heldMs)
    }

    fun onPowerDown(
        blueLMAction: TargetAction,
        screenInteractive: Boolean,
        repeatCount: Int,
        alreadyConsuming: Boolean,
    ): PowerDecision {
        if (blueLMAction == TargetAction.NONE) return PowerDecision.Ignore
        if (!screenInteractive) return PowerDecision.Ignore
        if (repeatCount > 0) {
            return if (alreadyConsuming) PowerDecision.ContinueConsuming else PowerDecision.Ignore
        }
        return PowerDecision.ConsumeDownStartLongJob
    }

    fun onPowerUp(wasConsuming: Boolean, longFired: Boolean): PowerDecision {
        if (!wasConsuming) return PowerDecision.Ignore
        if (longFired) return PowerDecision.UpAfterLong
        return PowerDecision.ShortPressLock
    }

    fun shouldPassThroughCameraKey(
        skipWhileCameraOpen: Boolean,
        foregroundPackage: String?,
        foregroundSinceMs: Long,
        nowMs: Long,
        ownPackage: String,
        isCameraApp: (String?, String?, String) -> Boolean,
    ): Boolean {
        if (!skipWhileCameraOpen) return false
        if (!isCameraApp(foregroundPackage, null, ownPackage)) return false
        return (nowMs - foregroundSinceMs) >= CAMERA_FOREGROUND_GRACE_MS
    }

    fun isPowerLongPress(heldMs: Long, thresholdMs: Long = POWER_LONG_PRESS_MS): Boolean =
        heldMs >= thresholdMs
}
