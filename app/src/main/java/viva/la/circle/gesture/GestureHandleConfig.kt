package viva.la.circle.gesture

import viva.la.circle.model.TargetAction

/**
 * Defaults and UI rules for the app-drawn Gesture Handle overlay.
 */
object GestureHandleConfig {
    const val DEFAULT_OPACITY_PERCENT = 100
    const val MIN_OPACITY_PERCENT = 0
    const val MAX_OPACITY_PERCENT = 100

    val DEFAULT_TAP_ACTION: TargetAction = TargetAction.NONE
    val DEFAULT_LONG_PRESS_ACTION: TargetAction = TargetAction.CIRCLE_TO_SEARCH
    val DEFAULT_SWIPE_UP_ACTION: TargetAction = TargetAction.HOME

    fun clampOpacity(percent: Int): Int =
        percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)

    /** True when swipe-up is remapped away from Home — UI should warn. */
    fun missingHomeOnSwipe(swipeUpAction: TargetAction): Boolean =
        swipeUpAction != TargetAction.HOME

    fun opacityAlpha(percent: Int): Float =
        clampOpacity(percent) / 100f
}
