package viva.la.circle.remap

import viva.la.circle.model.VolumeShortAction

/**
 * Volume-key Remap: double-press skip tracks + optional short-press TargetAction.
 *
 * OriginOS/Vivo (Android 15+ volume hardening) silently ignores every programmatic
 * volume API from a non-privileged app — background service *and* foreground activity
 * alike — so the app cannot re-synthesize or undo a volume step. The only volume change
 * a volume key can produce is the one the OS makes itself while handling the key.
 *
 * Therefore for native [VolumeShortAction.Volume] we **do not consume** presses: the OS
 * keeps native volume (tap = one step, hold = system ramp). A skip is a **double-press**
 * (two quick taps): the first tap passes through (one native step), the second is
 * consumed and fires a MediaSession skip — so a skip costs at most one volume step
 * instead of the continuous ramp a long-press would leak.
 *
 * When the short action is a Remap we consume the key (a Remap fires a TargetAction
 * instead of volume), and there a long-press still triggers skip.
 */
object VolumeKeyPolicy {

    const val DEFAULT_LONG_PRESS_MS = 500L
    val TIMEOUT_PRESETS_MS: List<Long> = listOf(300L, 500L, 700L)

    sealed class DownDecision {
        data object PassThrough : DownDecision()
        data object ContinueConsuming : DownDecision()
        /** First native-volume tap: pass through and arm double-press detection. */
        data object PassThroughArmDouble : DownDecision()
        /** Second native-volume tap within the window: consume and fire skip. */
        data object ConsumeFireSkip : DownDecision()
        /** Consume DOWN and schedule long-press skip (short Remap path). */
        data object ConsumeStartSkipJob : DownDecision()
        /** Consume DOWN for short-press Remap only (no skip). */
        data object ConsumeShortOnly : DownDecision()
    }

    sealed class UpDecision {
        data object PassThrough : UpDecision()
        data object AfterSkip : UpDecision()
        data class FireAction(
            val action: viva.la.circle.model.TargetAction,
            val specificPackage: String?,
        ) : UpDecision()
    }

    fun shouldArmKey(
        skipTracksEnabled: Boolean,
        shortRemapEnabled: Boolean,
        shortAction: VolumeShortAction,
        mediaPlaying: Boolean,
        inCall: Boolean,
    ): Boolean {
        if (inCall) return false
        if (skipTracksEnabled && mediaPlaying) return true
        if (shortRemapEnabled && shortAction is VolumeShortAction.Remap) return true
        return false
    }

    fun canSkipOnLongPress(
        skipTracksEnabled: Boolean,
        mediaPlaying: Boolean,
        inCall: Boolean,
    ): Boolean = !inCall && skipTracksEnabled && mediaPlaying

    /**
     * @param passThroughVolume true when the short-press keeps native volume
     *   ([VolumeShortAction.Volume]); presses are never consumed except the second
     *   tap of a recognised double-press.
     * @param secondTapWithinWindow true when this DOWN follows a previous native-volume
     *   tap within the double-press window (service supplies the timing).
     */
    fun onDown(
        armed: Boolean,
        canSkip: Boolean,
        passThroughVolume: Boolean,
        repeatCount: Int,
        alreadyConsuming: Boolean,
        secondTapWithinWindow: Boolean,
    ): DownDecision {
        if (!armed) return DownDecision.PassThrough
        if (passThroughVolume) {
            // Native volume: never consume, except the 2nd tap of a double-press.
            if (!canSkip) return DownDecision.PassThrough
            if (repeatCount > 0) return DownDecision.PassThrough
            return if (secondTapWithinWindow) {
                DownDecision.ConsumeFireSkip
            } else {
                DownDecision.PassThroughArmDouble
            }
        }
        if (repeatCount > 0) {
            return if (alreadyConsuming) DownDecision.ContinueConsuming else DownDecision.PassThrough
        }
        return if (canSkip) DownDecision.ConsumeStartSkipJob else DownDecision.ConsumeShortOnly
    }

    fun onUp(
        wasConsuming: Boolean,
        skipFired: Boolean,
        shortAction: VolumeShortAction,
    ): UpDecision {
        if (!wasConsuming) return UpDecision.PassThrough
        if (skipFired) return UpDecision.AfterSkip
        return when (shortAction) {
            // Volume never reaches the consume path on a short tap; kept defensive.
            is VolumeShortAction.Volume -> UpDecision.PassThrough
            is VolumeShortAction.Remap -> UpDecision.FireAction(
                action = shortAction.action,
                specificPackage = shortAction.specificPackage,
            )
        }
    }

    fun isLongPress(heldMs: Long, thresholdMs: Long): Boolean = heldMs >= thresholdMs

    fun normalizeTimeoutMs(raw: Long): Long =
        TIMEOUT_PRESETS_MS.minByOrNull { kotlin.math.abs(it - raw) } ?: DEFAULT_LONG_PRESS_MS
}
