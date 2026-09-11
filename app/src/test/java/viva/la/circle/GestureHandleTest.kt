package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.gesture.GestureHandleConfig
import viva.la.circle.gesture.GestureNavEvent
import viva.la.circle.gesture.GestureStrokeTracker
import viva.la.circle.gesture.GestureZone
import viva.la.circle.model.TargetAction

class GestureHandleConfigTest {

    @Test
    fun defaultsMatchContract() {
        assertEquals(TargetAction.NONE, GestureHandleConfig.DEFAULT_TAP_ACTION)
        assertEquals(TargetAction.CIRCLE_TO_SEARCH, GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION)
        assertEquals(TargetAction.HOME, GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION)
        assertEquals(100, GestureHandleConfig.DEFAULT_OPACITY_PERCENT)
    }

    @Test
    fun missingHomeWarnsWhenSwipeRemapped() {
        assertFalse(GestureHandleConfig.missingHomeOnSwipe(TargetAction.HOME))
        assertTrue(GestureHandleConfig.missingHomeOnSwipe(TargetAction.CIRCLE_TO_SEARCH))
    }

    @Test
    fun opacityClampedAndAlpha() {
        assertEquals(0, GestureHandleConfig.clampOpacity(-5))
        assertEquals(100, GestureHandleConfig.clampOpacity(140))
        assertEquals(0.5f, GestureHandleConfig.opacityAlpha(50), 0.001f)
    }
}

class GestureStrokeTrackerTest {

    private var now = 0L

    private fun tracker(
        zone: GestureZone,
        slop: Float = 10f,
        swipe: Float = 50f,
    ) = GestureStrokeTracker(
        zone = zone,
        touchSlopPx = slop,
        swipeThresholdPx = swipe,
        longPressTimeoutMs = 500L,
        recentsHoldMs = 200L,
        nowMs = { now },
    )

    @Test
    fun bottomTap() {
        val t = tracker(GestureZone.BOTTOM)
        t.onDown(100f, 100f)
        now = 100L
        assertNull(t.onMove(101f, 101f))
        assertEquals(GestureNavEvent.Tap, t.onUp(102f, 102f))
    }

    @Test
    fun bottomLongPress() {
        val t = tracker(GestureZone.BOTTOM)
        t.onDown(100f, 100f)
        now = 500L
        assertEquals(GestureNavEvent.LongPress, t.onMove(100f, 100f))
        assertNull(t.onUp(100f, 100f))
    }

    @Test
    fun bottomSwipeUp() {
        val t = tracker(GestureZone.BOTTOM)
        t.onDown(100f, 200f)
        now = 50L
        assertNull(t.onMove(100f, 140f))
        assertEquals(GestureNavEvent.SwipeUp, t.onUp(100f, 120f))
    }

    @Test
    fun bottomSwipeUpHoldRecents() {
        val t = tracker(GestureZone.BOTTOM)
        t.onDown(100f, 200f)
        now = 10L
        assertNull(t.onMove(100f, 140f)) // crossed swipe
        now = 220L
        assertEquals(GestureNavEvent.Recents, t.onMove(100f, 130f))
    }

    @Test
    fun leftEdgeBack() {
        val t = tracker(GestureZone.LEFT_EDGE)
        t.onDown(5f, 400f)
        now = 40L
        assertEquals(GestureNavEvent.Back, t.onUp(80f, 400f))
    }

    @Test
    fun rightEdgeBack() {
        val t = tracker(GestureZone.RIGHT_EDGE)
        t.onDown(20f, 400f)
        now = 40L
        assertEquals(GestureNavEvent.Back, t.onUp(-40f, 400f))
    }

    @Test
    fun homeExcludedFromTriggerEntries() {
        assertFalse(TargetAction.triggerEntries().contains(TargetAction.HOME))
        assertTrue(TargetAction.gestureHandleEntries().contains(TargetAction.HOME))
    }
}
