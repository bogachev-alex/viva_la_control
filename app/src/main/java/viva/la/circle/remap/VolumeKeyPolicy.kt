package viva.la.circle.remap

import viva.la.circle.model.VolumeShortAction

/**
 * Volume-key Remap: long-press skip (when keys are consumed) + optional short-press TargetAction.
 *
 * OriginOS/Vivo ignores programmatic volume APIs from this app, so native
 * [VolumeShortAction.Volume] keys are **never** consumed here — the OS keeps volume.
 * Track skip for those keys comes from [viva.la.circle.media.VolumeLongPressListener]
 * (ADB-granted system long-press), not from accessibility double-tap.
 *
 * When the short action is a Remap we consume the key and long-press still triggers skip.
 */
object VolumeKeyPolicy {

    const val DEFAULT_LONG_PRESS_MS = 500L
    val TIMEOUT_PRESETS_MS: List<Long> = listOf(300L, 500L, 700L)

    sealed class DownDecision {
        data object PassThrough : DownDecision()
        data object ContinueConsuming : DownDecision()
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
        // Native Volume short never arms a11y handling — skip is via system long-press.
        if (shortAction is VolumeShortAction.Volume) {
            return false
        }
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
     * @param passThroughVolume true when short-press keeps native volume — always PassThrough.
     */
    fun onDown(
        armed: Boolean,
        canSkip: Boolean,
        passThroughVolume: Boolean,
        repeatCount: Int,
        alreadyConsuming: Boolean,
    ): DownDecision {
        if (!armed || passThroughVolume) return DownDecision.PassThrough
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
