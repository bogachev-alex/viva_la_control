package viva.la.circle

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.gesture.GestureHandleConfig
import viva.la.circle.gesture.GestureImmersiveDetector
import viva.la.circle.gesture.GestureImmersiveGate
import viva.la.circle.gesture.GestureNavEvent
import viva.la.circle.gesture.GestureStrokeTracker
import viva.la.circle.gesture.GestureZone
import viva.la.circle.model.TargetAction
import viva.la.circle.service.InterceptorServiceState

class GestureHandleConfigTest {

    @Test
    fun defaultsMatchContract() {
        assertEquals(TargetAction.NONE, GestureHandleConfig.DEFAULT_TAP_ACTION)
        assertEquals(TargetAction.CIRCLE_TO_SEARCH, GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION)
        assertEquals(TargetAction.HOME, GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION)
        assertEquals(TargetAction.BACK, GestureHandleConfig.DEFAULT_SWIPE_LEFT_ACTION)
        assertEquals(TargetAction.BACK, GestureHandleConfig.DEFAULT_SWIPE_RIGHT_ACTION)
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

    @Test
    fun geometryClampsAndHitZone() {
        assertEquals(48, GestureHandleConfig.clampWidthDp(10))
        assertEquals(280, GestureHandleConfig.clampWidthDp(999))
        assertEquals(2, GestureHandleConfig.clampHeightDp(0))
        assertEquals(16, GestureHandleConfig.clampHeightDp(99))
        assertEquals(0, GestureHandleConfig.clampBottomOffsetDp(-1))
        assertEquals(64, GestureHandleConfig.clampBottomOffsetDp(100))
        assertEquals(
            GestureHandleConfig.DEFAULT_WIDTH_DP +
                GestureHandleConfig.HIT_PADDING_DP * 2 +
                GestureHandleConfig.DRAG_FOLLOW_X_DP.toInt() * 2 +
                GestureHandleConfig.pressGrowXDp(GestureHandleConfig.DEFAULT_WIDTH_DP) * 2,
            GestureHandleConfig.idleHitWidthDp(GestureHandleConfig.DEFAULT_WIDTH_DP),
        )
        assertEquals(
            GestureHandleConfig.DEFAULT_HEIGHT_DP +
                GestureHandleConfig.HIT_PADDING_DP +
                GestureHandleConfig.PILL_BOTTOM_INSET_DP +
                GestureHandleConfig.DRAG_FOLLOW_UP_DP.toInt() +
                GestureHandleConfig.pressGrowYDp(GestureHandleConfig.DEFAULT_HEIGHT_DP) +
                GestureHandleConfig.SWIPE_TRAVEL_DP,
            GestureHandleConfig.hitHeightDp(GestureHandleConfig.DEFAULT_HEIGHT_DP),
        )
        assertEquals(
            GestureHandleConfig.hitHeightDp(5),
            GestureHandleConfig.activeHitHeightDp(5),
        )
        assertEquals(
            GestureHandleConfig.idleHitWidthDp(96),
            GestureHandleConfig.activeHitWidthDp(96),
        )
    }

    @Test
    fun colorNormalizedOpaque() {
        assertEquals(
            GestureHandleConfig.DEFAULT_COLOR_ARGB,
            GestureHandleConfig.normalizeColorArgb(0x80FFFFFF.toInt()),
        )
        assertEquals(
            0xFF60A5FA.toInt(),
            GestureHandleConfig.normalizeColorArgb(0x0060A5FA),
        )
        assertTrue(GestureHandleConfig.PRESET_COLORS.isNotEmpty())
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
        // Finger drift after long-press must not become horizontal Back/swipe.
        assertNull(t.onUp(160f, 100f))
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
    fun bottomSwipeLeftOrRight() {
        val left = tracker(GestureZone.BOTTOM)
        left.onDown(100f, 200f)
        now = 40L
        assertEquals(GestureNavEvent.SwipeLeft, left.onUp(40f, 200f))

        val right = tracker(GestureZone.BOTTOM)
        right.onDown(100f, 200f)
        now = 40L
        assertEquals(GestureNavEvent.SwipeRight, right.onUp(160f, 205f))
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
        assertFalse(TargetAction.triggerEntries().contains(TargetAction.BACK))
        assertTrue(TargetAction.gestureHandleEntries().contains(TargetAction.HOME))
        assertTrue(TargetAction.gestureHandleEntries().contains(TargetAction.BACK))
    }
}

class GestureHapticRoutingTest {

    @Test
    fun mapsEachNavEventToItsFlag() {
        val allOn = InterceptorServiceState()
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.Tap))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.LongPress))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.SwipeUp))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.SwipeLeft))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.SwipeRight))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.Back))
        assertTrue(allOn.gestureHapticEnabled(GestureNavEvent.Recents))

        val tapOnly = InterceptorServiceState(
            gestureHapticTap = true,
            gestureHapticLongPress = false,
            gestureHapticSwipeUp = false,
            gestureHapticSwipeLeft = false,
            gestureHapticSwipeRight = false,
            gestureHapticBack = false,
            gestureHapticRecents = false,
        )
        assertTrue(tapOnly.gestureHapticEnabled(GestureNavEvent.Tap))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.LongPress))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.SwipeUp))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.SwipeLeft))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.SwipeRight))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.Back))
        assertFalse(tapOnly.gestureHapticEnabled(GestureNavEvent.Recents))
    }
}

class GestureImmersiveDetectorTest {

    private fun win(
        type: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        active: Boolean = false,
        focused: Boolean = false,
    ) = GestureImmersiveDetector.WindowBounds(
        type = type,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        isActive = active,
        isFocused = focused,
    )

    @Test
    fun statusBarPresentIsNotImmersive() {
        val windows = listOf(
            win(AccessibilityWindowInfo.TYPE_SYSTEM, 0, 0, 1080, 80),
            win(AccessibilityWindowInfo.TYPE_APPLICATION, 0, 80, 1080, 2400, focused = true),
        )
        assertFalse(
            GestureImmersiveDetector.isImmersiveFullscreen(windows, 1080, 2400),
        )
    }

    @Test
    fun fullscreenAppWithoutStatusBarIsImmersive() {
        val windows = listOf(
            win(AccessibilityWindowInfo.TYPE_APPLICATION, 0, 0, 1080, 2400, focused = true),
        )
        assertTrue(
            GestureImmersiveDetector.isImmersiveFullscreen(windows, 1080, 2400),
        )
    }

    @Test
    fun navBarPresentIsNotImmersive() {
        val windows = listOf(
            win(AccessibilityWindowInfo.TYPE_SYSTEM, 0, 2320, 1080, 2400),
            win(AccessibilityWindowInfo.TYPE_APPLICATION, 0, 0, 1080, 2400, focused = true),
        )
        assertFalse(
            GestureImmersiveDetector.isImmersiveFullscreen(windows, 1080, 2400),
        )
    }

    @Test
    fun partialAppWindowIsNotImmersive() {
        val windows = listOf(
            win(AccessibilityWindowInfo.TYPE_APPLICATION, 0, 200, 1080, 1400, focused = true),
        )
        assertFalse(
            GestureImmersiveDetector.isImmersiveFullscreen(windows, 1080, 2400),
        )
    }
}

class GestureImmersiveGateTest {

    @Test
    fun flakyTrueSampleDoesNotHideImmediately() {
        var now = 0L
        val gate = GestureImmersiveGate(hideConfirmMs = 500L, nowMs = { now })
        assertNull(gate.onSample(true))
        assertTrue(gate.isConfirmingHide())
        now = 200L
        assertNull(gate.onSample(true))
        now = 499L
        assertNull(gate.onSample(true))
    }

    @Test
    fun sustainedTruePublishesHide() {
        var now = 0L
        val gate = GestureImmersiveGate(hideConfirmMs = 500L, nowMs = { now })
        assertNull(gate.onSample(true))
        now = 500L
        assertEquals(true, gate.onSample(true))
        assertNull(gate.onSample(true))
    }

    @Test
    fun briefTrueThenFalseNeverHides() {
        var now = 0L
        val gate = GestureImmersiveGate(hideConfirmMs = 500L, nowMs = { now })
        assertNull(gate.onSample(true))
        now = 100L
        assertNull(gate.onSample(false))
        assertFalse(gate.isConfirmingHide())
        now = 700L
        assertNull(gate.onSample(false))
    }

    @Test
    fun leaveImmersivePublishesImmediately() {
        var now = 0L
        val gate = GestureImmersiveGate(hideConfirmMs = 500L, nowMs = { now })
        assertNull(gate.onSample(true))
        now = 500L
        assertEquals(true, gate.onSample(true))
        now = 600L
        assertEquals(false, gate.onSample(false))
    }
}
