package viva.la.circle.gesture

import viva.la.circle.model.TargetAction
import kotlin.math.ceil

/**
 * Defaults and UI rules for the app-drawn Gesture Handle overlay.
 */
object GestureHandleConfig {
    const val DEFAULT_OPACITY_PERCENT = 100
    const val MIN_OPACITY_PERCENT = 0
    const val MAX_OPACITY_PERCENT = 100

    /** Visual pill length (horizontal), dp. */
    const val DEFAULT_WIDTH_DP = 96
    const val MIN_WIDTH_DP = 48
    const val MAX_WIDTH_DP = 280

    /** Visual pill thickness (vertical), dp. */
    const val DEFAULT_HEIGHT_DP = 5
    const val MIN_HEIGHT_DP = 2
    const val MAX_HEIGHT_DP = 16

    /** Offset of the hit window from the physical bottom edge, dp. */
    const val DEFAULT_BOTTOM_OFFSET_DP = 0
    const val MIN_BOTTOM_OFFSET_DP = 0
    const val MAX_BOTTOM_OFFSET_DP = 64

    /** Touch padding around the drawn pill (horizontal + above), dp. */
    const val HIT_PADDING_DP = 8

    /** Visual gap between pill and the physical bottom of the overlay, dp. */
    const val PILL_BOTTOM_INSET_DP = 2

    /**
     * Fixed corridor above the pill so swipe-up can finish without resizing
     * the overlay mid-gesture (resize = OEM "shoot" jump).
     */
    const val SWIPE_TRAVEL_DP = 64

    /** Must match BottomHandleView press scale (1 + PRESS_SCALE_EXTRA). */
    const val PRESS_SCALE_EXTRA = 0.14f

    /**
     * Finger must travel this far (dp) before the pill starts following.
     */
    const val DRAG_DEADZONE_DP = 10f

    /**
     * Visual follow clamps (dp). Window reserves this room so stadium caps
     * are never clipped into flat ends.
     */
    const val DRAG_FOLLOW_X_DP = 16f
    const val DRAG_FOLLOW_UP_DP = 12f

    /** How strongly the pill tracks the finger after the deadzone (0..1). */
    const val DRAG_FOLLOW_FACTOR = 0.35f

    const val EDGE_WIDTH_DP = 14
    /** Edge Back strips only cover this much height from the bottom. */
    const val EDGE_HEIGHT_DP = 120

    /** Opaque ARGB white — opacity is applied separately via the opacity slider. */
    const val DEFAULT_COLOR_ARGB = 0xFFFFFFFF.toInt()

    /** Preset pill colors shown in settings (all opaque). */
    val PRESET_COLORS: IntArray = intArrayOf(
        0xFFFFFFFF.toInt(), // white
        0xFFE5E7EB.toInt(), // gray
        0xFF111827.toInt(), // near black
        0xFF60A5FA.toInt(), // blue
        0xFF2DD4BF.toInt(), // teal
        0xFF4ADE80.toInt(), // green
        0xFFFBBF24.toInt(), // amber
        0xFFFB923C.toInt(), // orange
        0xFFF87171.toInt(), // red
        0xFFF472B6.toInt(), // pink
        0xFFA78BFA.toInt(), // purple
    )

    /** Force opaque ARGB (opacity comes from the opacity slider). */
    fun normalizeColorArgb(argb: Int): Int =
        0xFF000000.toInt() or (argb and 0x00FFFFFF)

    fun clampOpacity(percent: Int): Int =
        percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)

    fun clampWidthDp(dp: Int): Int = dp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)

    fun clampHeightDp(dp: Int): Int = dp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)

    fun clampBottomOffsetDp(dp: Int): Int =
        dp.coerceIn(MIN_BOTTOM_OFFSET_DP, MAX_BOTTOM_OFFSET_DP)

    fun pressGrowXDp(pillWidthDp: Int): Int =
        ceil(clampWidthDp(pillWidthDp) * PRESS_SCALE_EXTRA / 2f).toInt().coerceAtLeast(1)

    fun pressGrowYDp(pillHeightDp: Int): Int =
        ceil(clampHeightDp(pillHeightDp) * PRESS_SCALE_EXTRA / 2f).toInt().coerceAtLeast(1)

    fun idleHitWidthDp(pillWidthDp: Int): Int =
        clampWidthDp(pillWidthDp) +
            (HIT_PADDING_DP * 2) +
            (DRAG_FOLLOW_X_DP.toInt() * 2) +
            (pressGrowXDp(pillWidthDp) * 2)

    /**
     * Single fixed hit height (never resized during a stroke).
     * Pill band at bottom accepts DOWN; corridor above only continues a stroke.
     */
    fun hitHeightDp(pillHeightDp: Int): Int =
        clampHeightDp(pillHeightDp) +
            HIT_PADDING_DP + // above pill
            PILL_BOTTOM_INSET_DP +
            DRAG_FOLLOW_UP_DP.toInt() +
            pressGrowYDp(pillHeightDp) +
            SWIPE_TRAVEL_DP

    fun idleHitHeightDp(pillHeightDp: Int): Int = hitHeightDp(pillHeightDp)

    fun activeHitHeightDp(pillHeightDp: Int): Int = hitHeightDp(pillHeightDp)

    fun activeHitWidthDp(pillWidthDp: Int): Int = idleHitWidthDp(pillWidthDp)

    fun missingHomeOnSwipe(swipeUpAction: TargetAction): Boolean =
        swipeUpAction != TargetAction.HOME

    fun opacityAlpha(percent: Int): Float =
        clampOpacity(percent) / 100f

    val DEFAULT_TAP_ACTION: TargetAction = TargetAction.NONE
    val DEFAULT_LONG_PRESS_ACTION: TargetAction = TargetAction.CIRCLE_TO_SEARCH
    val DEFAULT_SWIPE_UP_ACTION: TargetAction = TargetAction.HOME
    val DEFAULT_SWIPE_LEFT_ACTION: TargetAction = TargetAction.BACK
    val DEFAULT_SWIPE_RIGHT_ACTION: TargetAction = TargetAction.BACK
}
