package viva.la.circle.gesture

/**
 * Debounces immersive-hide so a single flaky accessibility window snapshot
 * (common while the gesture overlay is touched) cannot yank the pill off-screen.
 * Leaving immersive publishes immediately.
 */
class GestureImmersiveGate(
    private val hideConfirmMs: Long = HIDE_CONFIRM_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var pendingTrueSinceMs: Long? = null
    private var published: Boolean = false

    /**
     * @return new published immersive state, or null when nothing should change yet.
     * When [sample] is true but confirmation is still pending, returns null and the
     * caller should poll again soon.
     */
    fun onSample(sample: Boolean): Boolean? {
        if (!sample) {
            pendingTrueSinceMs = null
            if (!published) return null
            published = false
            return false
        }
        val since = pendingTrueSinceMs ?: nowMs().also { pendingTrueSinceMs = it }
        if (published) return null
        if (nowMs() - since < hideConfirmMs) return null
        published = true
        return true
    }

    /** True while a hide confirmation timer is running (caller should keep polling). */
    fun isConfirmingHide(): Boolean =
        !published && pendingTrueSinceMs != null

    fun reset() {
        pendingTrueSinceMs = null
        published = false
    }

    companion object {
        const val HIDE_CONFIRM_MS = 500L
    }
}
