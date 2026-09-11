package viva.la.circle.remap

import viva.la.circle.model.VolumeShortAction

/**
 * Volume-key Remap: long-press skip tracks + optional short-press TargetAction.
 *
 * When short-press is [VolumeShortAction.Volume], keys are **not** consumed: the OS keeps
 * native volume (needed on OEMs where AudioManager synth fails). We only observe hold time
 * and fire skip. When short-press is a Remap action, keys are consumed as usual.
 */
object VolumeKeyPolicy {

    const val DEFAULT_LONG_PRESS_MS = 500L
    val TIMEOUT_PRESETS_MS: List<Long> = listOf(300L, 500L, 700L)

    sealed class DownDecision {
        data object PassThrough : DownDecision()
        data object ContinueConsuming : DownDecision()
        /** Pass key to OS (native volume) but schedule long-press skip. */
        data object ObserveStartSkipJob : DownDecision()
        /** After observe-skip fired, swallow further repeats so volume stops ramping. */
        data object SwallowAfterSkip : DownDecision()
        /** Consume DOWN and schedule long-press skip (short Remap path). */
        data object ConsumeStartSkipJob : DownDecision()
        /** Consume DOWN for short-press Remap only (no skip). */
        data object ConsumeShortOnly : DownDecision()
    }

    sealed class UpDecision {
        data object PassThrough : UpDecision()
        data object AfterSkip : UpDecision()
        data object AfterObserve : UpDecision()
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
     * @param passThroughVolume true when short-press should stay native volume (Volume sentinel).
     */
    fun onDown(
        armed: Boolean,
        canSkip: Boolean,
        passThroughVolume: Boolean,
        repeatCount: Int,
        alreadyConsuming: Boolean,
        alreadyObserving: Boolean,
        skipFired: Boolean = false,
    ): DownDecision {
        if (!armed) return DownDecision.PassThrough
        if (passThroughVolume) {
            // Native volume path — never consume until skip has fired.
            if (alreadyObserving && skipFired) return DownDecision.SwallowAfterSkip
            if (!canSkip) return DownDecision.PassThrough
            if (repeatCount > 0) return DownDecision.PassThrough
            if (alreadyObserving) return DownDecision.PassThrough
            return DownDecision.ObserveStartSkipJob
        }
        if (repeatCount > 0) {
            return if (alreadyConsuming) DownDecision.ContinueConsuming else DownDecision.PassThrough
        }
        return if (canSkip) DownDecision.ConsumeStartSkipJob else DownDecision.ConsumeShortOnly
    }

    fun onUp(
        wasConsuming: Boolean,
        wasObserving: Boolean,
        skipFired: Boolean,
        shortAction: VolumeShortAction,
    ): UpDecision {
        if (wasObserving) return UpDecision.AfterObserve
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
