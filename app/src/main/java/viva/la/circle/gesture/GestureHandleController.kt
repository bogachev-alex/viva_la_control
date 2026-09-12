package viva.la.circle.gesture

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.animation.DecelerateInterpolator
import viva.la.circle.service.InterceptorServiceState
import viva.la.circle.service.InterceptorStateRepository

private enum class PillFollowAxis { LEFT_RIGHT, UP }

/**
 * App-drawn Gesture Handle: compact pill hit-target + short edge Back strips.
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
    private var bottomParams: LayoutParams? = null
    private var leftParams: LayoutParams? = null
    private var rightParams: LayoutParams? = null
    private var attached = false

    private var opacityPercent = GestureHandleConfig.DEFAULT_OPACITY_PERCENT
    private var pillColorArgb = GestureHandleConfig.DEFAULT_COLOR_ARGB
    private var pillWidthDp = GestureHandleConfig.DEFAULT_WIDTH_DP
    private var pillHeightDp = GestureHandleConfig.DEFAULT_HEIGHT_DP
    private var bottomOffsetDp = GestureHandleConfig.DEFAULT_BOTTOM_OFFSET_DP
    private var prefsEnabled = false
    private var hideInFullscreen = true
    private var immersiveFullscreen = false
    /** True while VoiceInteraction / assistant overlay should own Back gestures. */
    private var assistantUiVisible = false
    private var pendingReconcileAfterStroke = false
    /** True while a finger stroke is active on the pill or edge strips. */
    @Volatile
    var isInteracting: Boolean = false
        private set

    /**
     * Called when a pill/edge stroke ends. [completed] is false on CANCEL so
     * deferred assist actions can be dropped instead of firing after a stolen touch.
     */
    var onStrokeFinished: ((completed: Boolean) -> Unit)? = null

    fun sync(state: InterceptorServiceState) {
        mainHandler.post {
            opacityPercent = GestureHandleConfig.clampOpacity(state.gestureHandleOpacity)
            pillColorArgb = GestureHandleConfig.normalizeColorArgb(state.gesturePillColorArgb)
            pillWidthDp = GestureHandleConfig.clampWidthDp(state.gesturePillWidthDp)
            pillHeightDp = GestureHandleConfig.clampHeightDp(state.gesturePillHeightDp)
            bottomOffsetDp = GestureHandleConfig.clampBottomOffsetDp(state.gestureBottomOffsetDp)
            prefsEnabled = state.gestureHandleEnabled
            hideInFullscreen = state.gestureHandleHideInFullscreen
            reconcileAttachment()
        }
    }

    /** Called when accessibility windows suggest immersive video/game UI. */
    fun setImmersiveFullscreen(immersive: Boolean) {
        mainHandler.post {
            if (immersiveFullscreen == immersive) return@post
            immersiveFullscreen = immersive
            reconcileAttachmentSafe()
            if (immersive) {
                InterceptorStateRepository.diag("GH", "hidden (fullscreen/immersive)")
            } else {
                InterceptorStateRepository.diag("GH", "shown (left fullscreen)")
            }
        }
    }

    /**
     * Hide our overlay while an assistant / CTS session is up so edge Back goes to
     * that session (not GLOBAL_ACTION_BACK into the app underneath).
     * Do not remove the windows — OriginOS flashes the launcher on removeView.
     */
    fun setAssistantUiVisible(visible: Boolean) {
        mainHandler.post {
            if (assistantUiVisible == visible) return@post
            assistantUiVisible = visible
            reconcileAttachmentSafe()
            InterceptorStateRepository.diag(
                "GH",
                if (visible) "hidden (assistant UI)" else "shown (left assistant UI)",
            )
        }
    }

    private fun shouldAttachOverlay(): Boolean =
        GestureHandleConfig.shouldAttachOverlay(
            prefsEnabled = prefsEnabled,
            hideInFullscreen = hideInFullscreen,
            immersiveFullscreen = immersiveFullscreen,
        )

    private fun reconcileAttachmentSafe() {
        if (isInteracting) {
            pendingReconcileAfterStroke = true
            return
        }
        reconcileAttachment()
    }

    private fun reconcileAttachment() {
        pendingReconcileAfterStroke = false
        if (shouldAttachOverlay()) {
            ensureAttached()
            if (!assistantUiVisible) {
                applyGeometry()
                bottomView?.setPillSize(pillWidthDp, pillHeightDp)
                bottomView?.setPillColor(pillColorArgb)
                bottomView?.setPillAlpha(GestureHandleConfig.opacityAlpha(opacityPercent))
            }
            setPassThrough(GestureHandleConfig.overlayPassThroughTouches(assistantUiVisible))
        } else {
            detach()
        }
    }

    fun destroy() {
        mainHandler.post {
            isInteracting = false
            assistantUiVisible = false
            pendingReconcileAfterStroke = false
            detach()
        }
    }

    private fun ensureAttached() {
        if (attached) return

        val bottom = BottomHandleView(service).also {
            it.setPillSize(pillWidthDp, pillHeightDp)
            it.setPillColor(pillColorArgb)
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

        val bParams = pillParams()
        val lParams = edgeParams(Gravity.BOTTOM or Gravity.START)
        val rParams = edgeParams(Gravity.BOTTOM or Gravity.END)

        try {
            wm.addView(bottom, bParams)
            wm.addView(left, lParams)
            wm.addView(right, rParams)
            bottomView = bottom
            leftEdge = left
            rightEdge = right
            bottomParams = bParams
            leftParams = lParams
            rightParams = rParams
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
            bottomParams = null
            leftParams = null
            rightParams = null
            attached = false
        }
    }

    private fun applyGeometry() {
        val bottom = bottomView ?: return
        val bParams = bottomParams ?: return
        // Fixed size always — any updateViewLayout during a stroke makes the pill
        // "shoot" on vivo/OriginOS and other OEMs.
        bParams.width = dpPx(GestureHandleConfig.idleHitWidthDp(pillWidthDp))
        bParams.height = dpPx(GestureHandleConfig.hitHeightDp(pillHeightDp))
        bParams.y = dpPx(bottomOffsetDp)
        try {
            wm.updateViewLayout(bottom, bParams)
        } catch (_: Exception) {
        }

        val edgeW = dpPx(GestureHandleConfig.EDGE_WIDTH_DP)
        val edgeH = dpPx(GestureHandleConfig.EDGE_HEIGHT_DP)
        leftParams?.let { params ->
            params.width = edgeW
            params.height = edgeH
            params.y = dpPx(bottomOffsetDp)
            leftEdge?.let { view ->
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
        }
        rightParams?.let { params ->
            params.width = edgeW
            params.height = edgeH
            params.y = dpPx(bottomOffsetDp)
            rightEdge?.let { view ->
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun detach() {
        safeRemove(bottomView)
        safeRemove(leftEdge)
        safeRemove(rightEdge)
        bottomView = null
        leftEdge = null
        rightEdge = null
        bottomParams = null
        leftParams = null
        rightParams = null
        if (attached) {
            InterceptorStateRepository.diag("GH", "overlay detached")
        }
        attached = false
    }

    private fun setPassThrough(passThrough: Boolean) {
        val notTouchable = LayoutParams.FLAG_NOT_TOUCHABLE
        fun apply(view: View?, params: LayoutParams?) {
            if (view == null || params == null) return
            view.visibility = if (passThrough) View.INVISIBLE else View.VISIBLE
            params.flags = if (passThrough) {
                params.flags or notTouchable
            } else {
                params.flags and notTouchable.inv()
            }
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
        apply(bottomView, bottomParams)
        apply(leftEdge, leftParams)
        apply(rightEdge, rightParams)
    }

    private fun dispatch(event: GestureNavEvent) {
        InterceptorStateRepository.diag("GH", "gesture=$event")
        onEvent(event)
    }

    private fun setInteracting(active: Boolean, completed: Boolean = true) {
        val was = isInteracting
        isInteracting = active
        if (!active && pendingReconcileAfterStroke) {
            reconcileAttachment()
        }
        if (was && !active) {
            onStrokeFinished?.invoke(completed)
        }
    }

    private fun safeRemove(view: View?) {
        if (view == null) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun pillParams(): LayoutParams {
        return baseParams(
            width = dpPx(GestureHandleConfig.idleHitWidthDp(pillWidthDp)),
            height = dpPx(GestureHandleConfig.hitHeightDp(pillHeightDp)),
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = dpPx(bottomOffsetDp),
        )
    }

    private fun edgeParams(gravity: Int): LayoutParams {
        return baseParams(
            width = dpPx(GestureHandleConfig.EDGE_WIDTH_DP),
            height = dpPx(GestureHandleConfig.EDGE_HEIGHT_DP),
            gravity = gravity,
            y = dpPx(bottomOffsetDp),
        )
    }

    private fun baseParams(width: Int, height: Int, gravity: Int, y: Int = 0): LayoutParams {
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
                LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun dpPx(value: Int): Int = (value * density).toInt().coerceAtLeast(1)

    private interface StrokeViewListener {
        fun onGesture(event: GestureNavEvent)
    }

    @SuppressLint("ViewConstructor")
    private inner class BottomHandleView(context: Context) : View(context) {
        var listener: StrokeViewListener? = null
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GestureHandleConfig.DEFAULT_COLOR_ARGB
            style = Paint.Style.FILL
        }
        private val pillRect = RectF()
        private var pillWDp = GestureHandleConfig.DEFAULT_WIDTH_DP
        private var pillHDp = GestureHandleConfig.DEFAULT_HEIGHT_DP
        private var baseAlpha = 1f
        private var pillColor = GestureHandleConfig.DEFAULT_COLOR_ARGB
        /** 0 = idle, 1 = fully pressed. Uniform capsule scale. */
        private var pressAmount = 0f
        /** Finger-follow drawn in onDraw only — never View.translation (OEM jump). */
        private var dragOffsetX = 0f
        private var dragOffsetY = 0f
        private var scaleAnimator: ValueAnimator? = null
        private var homeAnimator: ValueAnimator? = null
        private var tracker: GestureStrokeTracker? = null
        /** True from DOWN until UP/CANCEL — must keep ownership so OS nav does not steal the stroke. */
        private var strokeOwned = false
        /** Early LongPress/Recents already fired; ignore further classification until UP. */
        private var gestureEmitted = false
        private val pollLongPress = object : Runnable {
            override fun run() {
                if (!strokeOwned || gestureEmitted) return
                val t = tracker ?: return
                maybeRevealPress()
                val ev = t.onMove(lastRawX, lastRawY)
                if (ev != null) {
                    emitGesture(ev)
                    return
                }
                mainHandler.postDelayed(this, 16L)
            }
        }
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var polling = false
        private var followAxis: PillFollowAxis? = null
        private var returningHome = false
        private var pressShown = false
        private var downAtMs = 0L
        /** Show press scale only after a short hold — pure taps stay inert. */
        private val pressRevealDelayMs = 140L
        /**
         * When false, only the bottom pill band is touchable so the swipe
         * corridor above passes taps through. True for the duration of a stroke.
         */
        private var captureTouches = false
        private val touchableRegion = Region()
        private var insetsListener: Any? = null

        init {
            clipToOutline = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            installInsetsListener()
            post { applyTouchableRegion() }
        }

        override fun onDetachedFromWindow() {
            removeInsetsListener()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyTouchableRegion()
        }

        fun setPillAlpha(alpha: Float) {
            baseAlpha = alpha.coerceIn(0f, 1f)
            applyPaintAlpha()
            invalidate()
        }

        fun setPillColor(colorArgb: Int) {
            pillColor = GestureHandleConfig.normalizeColorArgb(colorArgb)
            pillPaint.color = pillColor
            applyPaintAlpha()
            invalidate()
        }

        fun setPillSize(widthDp: Int, heightDp: Int) {
            pillWDp = GestureHandleConfig.clampWidthDp(widthDp)
            pillHDp = GestureHandleConfig.clampHeightDp(heightDp)
            applyTouchableRegion()
            invalidate()
        }

        private fun pillBandTopPx(): Int {
            val h = dp(pillHDp.toFloat())
            val padTop = dp(GestureHandleConfig.HIT_PADDING_DP.toFloat())
            val padBottom = dp(GestureHandleConfig.PILL_BOTTOM_INSET_DP.toFloat())
            return (height - padBottom - h - padTop).toInt().coerceAtLeast(0)
        }

        /**
         * Idle: touchable = pill band only (corridor above is pass-through).
         * Stroke: touchable = full window so swipe MOVE keeps arriving.
         * No LayoutParams resize — keeps the current animation stable on OEMs.
         */
        private fun applyTouchableRegion() {
            if (width <= 0 || height <= 0) return
            if (captureTouches) {
                touchableRegion.set(0, 0, width, height)
            } else {
                touchableRegion.set(0, pillBandTopPx(), width, height)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rootSurfaceControl?.setTouchableRegion(Region(touchableRegion))
            }
            // Pre-S: insets listener reads [touchableRegion] on next traversal.
            viewTreeObserver.dispatchOnGlobalLayout()
        }

        private fun setCaptureTouches(active: Boolean, completed: Boolean = true) {
            if (captureTouches == active) return
            captureTouches = active
            setInteracting(active, completed)
            applyTouchableRegion()
        }

        /**
         * Hidden ViewTreeObserver.OnComputeInternalInsetsListener via reflection
         * for API < 31. Accessibility overlays are trusted for pass-through.
         */
        private fun installInsetsListener() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            if (insetsListener != null) return
            try {
                val listenerClass = Class.forName(
                    "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
                )
                val infoClass = Class.forName(
                    "android.view.ViewTreeObserver\$InternalInsetsInfo",
                )
                val setTouchableInsets = infoClass.getMethod(
                    "setTouchableInsets",
                    Int::class.javaPrimitiveType,
                )
                val touchableRegionField = infoClass.getField("touchableRegion")
                val touchableInsetsRegion = infoClass.getField("TOUCHABLE_INSETS_REGION")
                    .getInt(null)
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                        val info = args[0]
                        setTouchableInsets.invoke(info, touchableInsetsRegion)
                        (touchableRegionField.get(info) as Region).set(touchableRegion)
                    }
                    null
                }
                ViewTreeObserver::class.java
                    .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                    .invoke(viewTreeObserver, proxy)
                insetsListener = proxy
            } catch (_: Throwable) {
                insetsListener = null
            }
        }

        private fun removeInsetsListener() {
            val listener = insetsListener ?: return
            insetsListener = null
            try {
                val listenerClass = Class.forName(
                    "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
                )
                ViewTreeObserver::class.java
                    .getMethod("removeOnComputeInternalInsetsListener", listenerClass)
                    .invoke(viewTreeObserver, listener)
            } catch (_: Throwable) {
            }
        }

        private fun applyPaintAlpha() {
            // Re-apply base color so Paint.alpha does not stick from a previous frame.
            pillPaint.color = pillColor
            val pressedBoost = if (pressAmount > 0.05f) {
                (baseAlpha + (1f - baseAlpha) * 0.2f * pressAmount).coerceIn(0f, 1f)
            } else {
                baseAlpha
            }
            pillPaint.alpha = (pressedBoost * 255f).toInt().coerceIn(0, 255)
        }

        override fun onDraw(canvas: Canvas) {
            val baseW = dp(pillWDp.toFloat())
            val baseH = dp(pillHDp.toFloat())
            val scale = 1f + GestureHandleConfig.PRESS_SCALE_EXTRA * pressAmount
            val drawW = baseW * scale
            val drawH = baseH * scale
            val radius = drawH / 2f
            val padBottom = dp(GestureHandleConfig.PILL_BOTTOM_INSET_DP.toFloat())
            val maxX = dp(GestureHandleConfig.DRAG_FOLLOW_X_DP)
            val maxUp = dp(GestureHandleConfig.DRAG_FOLLOW_UP_DP)
            val cx = width / 2f + dragOffsetX.coerceIn(-maxX, maxX)
            val cy = height - padBottom - (baseH / 2f) + dragOffsetY.coerceIn(-maxUp, 0f)
            pillRect.set(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)
            canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isInPillBand(event.y)) {
                        return false
                    }
                    homeAnimator?.removeAllListeners()
                    homeAnimator?.cancel()
                    returningHome = false
                    pressShown = false
                    gestureEmitted = false
                    strokeOwned = true
                    downAtMs = android.os.SystemClock.uptimeMillis()
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    followAxis = null
                    tracker = GestureStrokeTracker(
                        zone = GestureZone.BOTTOM,
                        touchSlopPx = dp(GestureStrokeTracker.TOUCH_SLOP_DP),
                        swipeThresholdPx = dp(GestureStrokeTracker.SWIPE_THRESHOLD_DP),
                    ).also { it.onDown(event.rawX, event.rawY) }
                    // Open full corridor for MOVE without resizing the window.
                    setCaptureTouches(true)
                    // No haptic / press scale on DOWN — short taps stay silent.
                    startPoll()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!strokeOwned || returningHome) return false
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    updateDragFollow(event.rawX, event.rawY)
                    maybeRevealPress()
                    if (!gestureEmitted) {
                        val ev = tracker?.onMove(event.rawX, event.rawY)
                        if (ev != null) {
                            emitGesture(ev)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    // Must return true after consuming DOWN — false leaks the stroke to OS nav
                    // (Home/Back flicker under the assistant).
                    if (!strokeOwned) return false
                    stopPoll()
                    if (!gestureEmitted) {
                        val ev = tracker?.onUp(event.rawX, event.rawY)
                        if (ev != null) {
                            emitGesture(ev)
                        } else {
                            if (pressShown) animatePress(pressed = false)
                            animateReturnHome()
                        }
                    } else {
                        if (pressShown) animatePress(pressed = false)
                        animateReturnHome()
                    }
                    endStroke(completed = true)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!strokeOwned) return false
                    stopPoll()
                    tracker?.onCancel()
                    if (pressShown) animatePress(pressed = false)
                    animateReturnHome()
                    endStroke(completed = false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun isInPillBand(y: Float): Boolean {
            return y >= pillBandTopPx().toFloat()
        }

        private fun maybeRevealPress() {
            if (pressShown) return
            val held = android.os.SystemClock.uptimeMillis() - downAtMs
            val dragging = followAxis != null
            if (dragging || held >= pressRevealDelayMs) {
                pressShown = true
                animatePress(pressed = true)
            }
        }

        private fun updateDragFollow(rawX: Float, rawY: Float) {
            val dead = dp(GestureHandleConfig.DRAG_DEADZONE_DP)
            val follow = GestureHandleConfig.DRAG_FOLLOW_FACTOR
            val maxX = dp(GestureHandleConfig.DRAG_FOLLOW_X_DP)
            val maxUp = dp(GestureHandleConfig.DRAG_FOLLOW_UP_DP)
            val rawDx = rawX - downRawX
            val rawDy = rawY - downRawY
            val absDx = kotlin.math.abs(rawDx)
            val absDy = kotlin.math.abs(rawDy)

            if (followAxis == null && (absDx >= dead || absDy >= dead)) {
                followAxis = when {
                    absDx >= absDy -> PillFollowAxis.LEFT_RIGHT
                    rawDy < 0f -> PillFollowAxis.UP
                    else -> null
                }
            }

            when (followAxis) {
                PillFollowAxis.LEFT_RIGHT -> {
                    val signed = if (absDx <= dead) {
                        0f
                    } else {
                        kotlin.math.sign(rawDx) * (absDx - dead) * follow
                    }
                    dragOffsetX = signed.coerceIn(-maxX, maxX)
                    dragOffsetY = 0f
                }
                PillFollowAxis.UP -> {
                    val up = -rawDy
                    val followed = if (up <= dead) 0f else (up - dead) * follow
                    dragOffsetX = 0f
                    dragOffsetY = (-followed).coerceIn(-maxUp, 0f)
                }
                null -> {
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                }
            }
            invalidate()
        }

        private fun emitGesture(event: GestureNavEvent) {
            if (gestureEmitted) return
            gestureEmitted = true
            stopPoll()
            tracker = null
            // Keep captureTouches / strokeOwned until UP so the OS cannot steal the finger.
            if (pressShown) {
                animatePress(pressed = false)
            }
            animateReturnHome()
            listener?.onGesture(event)
        }

        private fun endStroke(completed: Boolean) {
            tracker = null
            gestureEmitted = false
            strokeOwned = false
            setCaptureTouches(false, completed)
        }

        private fun animateReturnHome() {
            followAxis = null
            returningHome = true
            homeAnimator?.removeAllListeners()
            homeAnimator?.cancel()
            val startX = dragOffsetX
            val startY = dragOffsetY
            if (startX == 0f && startY == 0f) {
                returningHome = false
                return
            }
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val k = 1f - t
                    dragOffsetX = startX * k
                    dragOffsetY = startY * k
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        returningHome = false
                        invalidate()
                    }
                })
            }
            homeAnimator = anim
            anim.start()
        }

        private fun animatePress(pressed: Boolean) {
            scaleAnimator?.cancel()
            val start = pressAmount
            val end = if (pressed) 1f else 0f
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (pressed) 100L else 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    pressAmount = start + (end - start) * t
                    applyPaintAlpha()
                    invalidate()
                }
            }
            scaleAnimator = anim
            anim.start()
        }

        private fun startPoll() {
            if (polling) return
            polling = true
            mainHandler.postDelayed(pollLongPress, 16L)
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
                    setInteracting(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.onMove(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val ev = tracker?.onUp(event.x, event.y)
                    tracker = null
                    setInteracting(false, completed = true)
                    if (ev != null) {
                        listener?.onGesture(ev)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracker?.onCancel()
                    tracker = null
                    setInteracting(false, completed = false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
