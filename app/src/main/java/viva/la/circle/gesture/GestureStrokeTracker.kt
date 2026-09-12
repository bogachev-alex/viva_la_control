package viva.la.circle.gesture

/**
 * Pure stroke classifier for one finger on a [GestureZone].
 * Pixel-like bottom: tap / long-press / swipe-up / swipe-up+hold → Recents.
 * Edges: inward horizontal swipe → Back.
 */
class GestureStrokeTracker(
    private val zone: GestureZone,
    private val touchSlopPx: Float,
    private val swipeThresholdPx: Float,
    private val longPressTimeoutMs: Long = LONG_PRESS_TIMEOUT_MS,
    private val recentsHoldMs: Long = RECENTS_HOLD_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var maxUpTravel = 0f
    private var crossedSwipeAtMs = 0L
    private var longPressFired = false
    private var terminalFired = false

    fun onDown(x: Float, y: Float) {
        tracking = true
        downX = x
        downY = y
        downAtMs = nowMs()
        maxUpTravel = 0f
        crossedSwipeAtMs = 0L
        longPressFired = false
        terminalFired = false
    }

    /** Adjust origin after the overlay window grows/shrinks under the finger. */
    fun offsetOrigin(dx: Float, dy: Float) {
        downX += dx
        downY += dy
    }

    /**
     * Call on MOVE (and optionally on a timer tick). May return [GestureNavEvent.LongPress]
     * or [GestureNavEvent.Recents] before finger-up.
     */
    fun onMove(x: Float, y: Float): GestureNavEvent? {
        if (!tracking || terminalFired) return null
        val dx = x - downX
        val dy = y - downY
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        val elapsed = nowMs() - downAtMs

        when (zone) {
            GestureZone.LEFT_EDGE, GestureZone.RIGHT_EDGE -> {
                // Edges only resolve on up (need full swipe direction).
                return null
            }
            GestureZone.BOTTOM -> {
                if (dy < 0f) {
                    maxUpTravel = maxOf(maxUpTravel, -dy)
                }
                if (maxUpTravel >= swipeThresholdPx && crossedSwipeAtMs == 0L) {
                    crossedSwipeAtMs = nowMs()
                }
                if (crossedSwipeAtMs != 0L && nowMs() - crossedSwipeAtMs >= recentsHoldMs) {
                    return fireTerminal(GestureNavEvent.Recents)
                }
                if (!longPressFired &&
                    maxUpTravel < touchSlopPx &&
                    absDx < touchSlopPx &&
                    elapsed >= longPressTimeoutMs
                ) {
                    longPressFired = true
                    return GestureNavEvent.LongPress
                }
                return null
            }
        }
    }

    fun onUp(x: Float, y: Float): GestureNavEvent? {
        if (!tracking || terminalFired) {
            reset()
            return null
        }
        val dx = x - downX
        val dy = y - downY
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        val upTravel = if (dy < 0f) -dy else 0f
        maxUpTravel = maxOf(maxUpTravel, upTravel)

        val result = when (zone) {
            GestureZone.LEFT_EDGE -> {
                if (dx >= swipeThresholdPx && absDx >= absDy) {
                    GestureNavEvent.Back
                } else {
                    null
                }
            }
            GestureZone.RIGHT_EDGE -> {
                if (dx <= -swipeThresholdPx && absDx >= absDy) {
                    GestureNavEvent.Back
                } else {
                    null
                }
            }
            GestureZone.BOTTOM -> {
                when {
                    longPressFired -> null
                    maxUpTravel >= swipeThresholdPx -> GestureNavEvent.SwipeUp
                    absDx >= swipeThresholdPx && absDx > absDy -> {
                        if (dx < 0f) GestureNavEvent.SwipeLeft else GestureNavEvent.SwipeRight
                    }
                    absDx < touchSlopPx && absDy < touchSlopPx -> GestureNavEvent.Tap
                    else -> null
                }
            }
        }
        reset()
        return result
    }

    fun onCancel() {
        reset()
    }

    private fun fireTerminal(event: GestureNavEvent): GestureNavEvent {
        terminalFired = true
        tracking = false
        return event
    }

    private fun reset() {
        tracking = false
        longPressFired = false
        terminalFired = false
        crossedSwipeAtMs = 0L
        maxUpTravel = 0f
    }

    companion object {
        const val LONG_PRESS_TIMEOUT_MS = 500L
        const val RECENTS_HOLD_MS = 200L
        const val TOUCH_SLOP_DP = 16f
        const val SWIPE_THRESHOLD_DP = 48f
        const val EDGE_WIDTH_DP = 28f
        const val BOTTOM_HEIGHT_DP = 72f
        const val PILL_WIDTH_DP = 108f
        const val PILL_HEIGHT_DP = 5f
    }
}
