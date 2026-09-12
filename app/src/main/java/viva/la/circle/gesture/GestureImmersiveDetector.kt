package viva.la.circle.gesture

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Detects immersive / fullscreen UI (video, games) from accessibility windows.
 * When system status bar is gone and the app fills the screen, the gesture pill
 * should hide so it does not sit on top of content.
 */
object GestureImmersiveDetector {

    data class WindowBounds(
        val type: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    /**
     * @return true when the foreground app appears to be in immersive fullscreen.
     */
    fun isImmersiveFullscreen(
        windows: List<WindowBounds>,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): Boolean {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return false

        val statusBarMaxH = (screenHeightPx * 0.12f).toInt().coerceIn(24, 200)
        val hasStatusBar = windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                w.top <= 2 &&
                w.height in 1..statusBarMaxH &&
                w.width >= (screenWidthPx * 0.5f).toInt()
        }
        if (hasStatusBar) return false

        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (appWindows.isEmpty()) return false

        val focused = appWindows.firstOrNull { it.isFocused || it.isActive } ?: appWindows.maxByOrNull { it.height }
            ?: return false

        val fillsWidth = focused.width >= (screenWidthPx * 0.92f).toInt()
        val fillsHeight = focused.height >= (screenHeightPx * 0.92f).toInt()
        val startsAtTop = focused.top <= 4
        return fillsWidth && fillsHeight && startsAtTop
    }

    fun snapshotWindows(windows: List<AccessibilityWindowInfo>?): List<WindowBounds> {
        if (windows.isNullOrEmpty()) return emptyList()
        val out = ArrayList<WindowBounds>(windows.size)
        val rect = Rect()
        for (w in windows) {
            try {
                w.getBoundsInScreen(rect)
                out.add(
                    WindowBounds(
                        type = w.type,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        isActive = w.isActive,
                        isFocused = w.isFocused,
                    ),
                )
            } catch (_: Exception) {
            }
        }
        return out
    }
}
