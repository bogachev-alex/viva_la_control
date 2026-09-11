package viva.la.circle.gesture

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.service.InterceptorStateRepository

/**
 * App-drawn Gesture Handle: bottom pill + edge Back strips via TYPE_ACCESSIBILITY_OVERLAY.
 */
class GestureHandleController(
    private val service: AccessibilityService,
    private val onEvent: (GestureNavEvent) -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density

    private var bottomView: BottomHandleView? = null
    private var leftEdge: EdgeStripView? = null
    private var rightEdge: EdgeStripView? = null
    private var attached = false
    private var opacityPercent = GestureHandleConfig.DEFAULT_OPACITY_PERCENT

    fun sync(state: InterceptorServiceState) {
        mainHandler.post {
            opacityPercent = GestureHandleConfig.clampOpacity(state.gestureHandleOpacity)
            if (state.gestureHandleEnabled) {
                ensureAttached()
                bottomView?.setPillAlpha(GestureHandleConfig.opacityAlpha(opacityPercent))
            } else {
                detach()
            }
        }
    }

    fun destroy() {
        mainHandler.post { detach() }
    }

    private fun ensureAttached() {
        if (attached) return
        val edgeW = dp(GestureStrokeTracker.EDGE_WIDTH_DP).toInt().coerceAtLeast(1)
        val bottomH = dp(GestureStrokeTracker.BOTTOM_HEIGHT_DP).toInt().coerceAtLeast(1)

        val bottom = BottomHandleView(service).also {
            it.setPillAlpha(GestureHandleConfig.opacityAlpha(opacityPercent))
            it.listener = object : StrokeViewListener {
                override fun onGesture(event: GestureNavEvent) = dispatch(event)
            }
        }
        val left = EdgeStripView(service, GestureZone.LEFT_EDGE).also {
            it.listener = object : StrokeViewListener {
                override fun onGesture(event: GestureNavEvent) = dispatch(event)
            }
        }
        val right = EdgeStripView(service, GestureZone.RIGHT_EDGE).also {
            it.listener = object : StrokeViewListener {
                override fun onGesture(event: GestureNavEvent) = dispatch(event)
            }
        }

        try {
            wm.addView(bottom, baseParams(
                width = LayoutParams.MATCH_PARENT,
                height = bottomH,
                gravity = Gravity.BOTTOM,
            ))
            wm.addView(left, baseParams(
                width = edgeW,
                height = LayoutParams.MATCH_PARENT,
                gravity = Gravity.START,
            ))
            wm.addView(right, baseParams(
                width = edgeW,
                height = LayoutParams.MATCH_PARENT,
                gravity = Gravity.END,
            ))
            bottomView = bottom
            leftEdge = left
            rightEdge = right
            attached = true
            InterceptorStateRepository.diag("GH", "overlay attached", force = true)
        } catch (e: Exception) {
            InterceptorStateRepository.diag("GH", "attach failed: ${e.message}", force = true)
            safeRemove(bottom)
            safeRemove(left)
            safeRemove(right)
            bottomView = null
            leftEdge = null
            rightEdge = null
            attached = false
        }
    }

    private fun detach() {
        safeRemove(bottomView)
        safeRemove(leftEdge)
        safeRemove(rightEdge)
        bottomView = null
        leftEdge = null
        rightEdge = null
        if (attached) {
            InterceptorStateRepository.diag("GH", "overlay detached")
        }
        attached = false
    }

    private fun dispatch(event: GestureNavEvent) {
        InterceptorStateRepository.diag("GH", "gesture=$event")
        onEvent(event)
    }

    private fun safeRemove(view: View?) {
        if (view == null) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun baseParams(width: Int, height: Int, gravity: Int): LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        return LayoutParams(
            width,
            height,
            type,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Float): Float = value * density

    private interface StrokeViewListener {
        fun onGesture(event: GestureNavEvent)
    }

    @SuppressLint("ViewConstructor")
    private inner class BottomHandleView(context: Context) : View(context) {
        var listener: StrokeViewListener? = null
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val pillRect = RectF()
        private var tracker: GestureStrokeTracker? = null
        private val pollLongPress = object : Runnable {
            override fun run() {
                val t = tracker ?: return
                val ev = t.onMove(lastX, lastY)
                if (ev != null) {
                    listener?.onGesture(ev)
                    if (ev is GestureNavEvent.Recents) {
                        tracker = null
                        return
                    }
                }
                mainHandler.postDelayed(this, 50L)
            }
        }
        private var lastX = 0f
        private var lastY = 0f
        private var polling = false

        fun setPillAlpha(alpha: Float) {
            pillPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = dp(GestureStrokeTracker.PILL_WIDTH_DP)
            val h = dp(GestureStrokeTracker.PILL_HEIGHT_DP)
            val cx = width / 2f
            val cy = height * 0.55f
            pillRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.drawRoundRect(pillRect, h, h, pillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    tracker = GestureStrokeTracker(
                        zone = GestureZone.BOTTOM,
                        touchSlopPx = dp(GestureStrokeTracker.TOUCH_SLOP_DP),
                        swipeThresholdPx = dp(GestureStrokeTracker.SWIPE_THRESHOLD_DP),
                    ).also { it.onDown(event.x, event.y) }
                    startPoll()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = event.x
                    lastY = event.y
                    val ev = tracker?.onMove(event.x, event.y)
                    if (ev != null) {
                        listener?.onGesture(ev)
                        if (ev is GestureNavEvent.Recents) {
                            stopPoll()
                            tracker = null
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    stopPoll()
                    val ev = tracker?.onUp(event.x, event.y)
                    tracker = null
                    if (ev != null) listener?.onGesture(ev)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopPoll()
                    tracker?.onCancel()
                    tracker = null
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun startPoll() {
            if (polling) return
            polling = true
            mainHandler.postDelayed(pollLongPress, 50L)
        }

        private fun stopPoll() {
            polling = false
            mainHandler.removeCallbacks(pollLongPress)
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class EdgeStripView(
        context: Context,
        private val zone: GestureZone,
    ) : View(context) {
        var listener: StrokeViewListener? = null
        private var tracker: GestureStrokeTracker? = null

        init {
            // Transparent hit target — no draw.
            setBackgroundColor(0x00000000)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracker = GestureStrokeTracker(
                        zone = zone,
                        touchSlopPx = dp(GestureStrokeTracker.TOUCH_SLOP_DP),
                        swipeThresholdPx = dp(GestureStrokeTracker.SWIPE_THRESHOLD_DP),
                    ).also { it.onDown(event.x, event.y) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.onMove(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val ev = tracker?.onUp(event.x, event.y)
                    tracker = null
                    if (ev != null) listener?.onGesture(ev)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracker?.onCancel()
                    tracker = null
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
