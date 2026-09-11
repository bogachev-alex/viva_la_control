package viva.la.circle.gesture

/** Outcome of a completed (or early-fired) gesture on the Gesture Handle overlay. */
sealed class GestureNavEvent {
    data object Tap : GestureNavEvent()
    data object LongPress : GestureNavEvent()
    data object SwipeUp : GestureNavEvent()
    data object Recents : GestureNavEvent()
    data object Back : GestureNavEvent()
}

enum class GestureZone {
    BOTTOM,
    LEFT_EDGE,
    RIGHT_EDGE,
}
