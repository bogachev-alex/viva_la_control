package viva.la.circle.remap

import viva.la.circle.model.TargetAction

/** Seam for firing a configured TargetAction. */
fun interface FireTargetAction {
    fun fire(action: TargetAction, specificPackage: String?): Boolean
}

data class WindowSnap(
    val topPackage: String?,
    val packages: List<String?>,
)

fun interface RemapWindows {
    fun snapshot(): WindowSnap
}

fun interface RemapBack {
    fun pressBack(): Boolean
}

interface RemapClock {
    fun nowMs(): Long
    suspend fun delayMs(ms: Long)
}

fun interface RemapDiag {
    fun log(kind: String, detail: String)
}

/** Vendor dismiss timings for one Remap session run. */
data class RemapTiming(
    val maxDismissBacks: Int,
    val dismissTimeoutMs: Long,
    val pollMs: Long,
    val hammerMinIntervalMs: Long,
    val focusWaitTimeoutMs: Long,
    val focusPollMs: Long,
) {
    companion object {
        fun fromVendor(
            maxDismissBacks: Int,
            dismissTimeoutMs: Long,
            pollMs: Long,
            hammerMinIntervalMs: Long,
            focusWaitTimeoutMs: Long,
            focusPollMs: Long,
        ) = RemapTiming(
            maxDismissBacks = maxDismissBacks,
            dismissTimeoutMs = dismissTimeoutMs,
            pollMs = pollMs,
            hammerMinIntervalMs = hammerMinIntervalMs,
            focusWaitTimeoutMs = focusWaitTimeoutMs,
            focusPollMs = focusPollMs,
        )
    }
}
