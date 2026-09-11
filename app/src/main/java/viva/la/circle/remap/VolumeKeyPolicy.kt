package viva.la.circle.remap

import viva.la.circle.model.VolumeShortAction

/**
 * Volume-key Remap: long-press skip tracks + optional short-press TargetAction.
 * Arming is per-key (see [shouldArmKey]).
 */
object VolumeKeyPolicy {

    const val DEFAULT_LONG_PRESS_MS = 500L
    val TIMEOUT_PRESETS_MS: List<Long> = listOf(300L, 500L, 700L)

    sealed class DownDecision {
        data object PassThrough : DownDecision()
        data object ContinueConsuming : DownDecision()
        /** Consume DOWN and schedule long-press skip job. */
        data object ConsumeStartSkipJob : DownDecision()
        /** Consume DOWN for short-press only (no skip). */
        data object ConsumeShortOnly : DownDecision()
    }

    sealed class UpDecision {
        data object PassThrough : UpDecision()
        data object AfterSkip : UpDecision()
        data object AdjustVolume : UpDecision()
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

    fun onDown(
        armed: Boolean,
        canSkip: Boolean,
        repeatCount: Int,
        alreadyConsuming: Boolean,
    ): DownDecision {
        if (!armed) return DownDecision.PassThrough
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
            is VolumeShortAction.Volume -> UpDecision.AdjustVolume
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
