package viva.la.circle.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.media.AudioManager
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.VendorProfile
import viva.la.circle.gesture.GestureHandleController
import viva.la.circle.gesture.GestureHandleHaptics
import viva.la.circle.gesture.GestureImmersiveDetector
import viva.la.circle.gesture.GestureImmersiveGate
import viva.la.circle.gesture.GestureNavEvent
import viva.la.circle.media.MediaPlaybackGate
import viva.la.circle.media.VolumeHaptics
import viva.la.circle.media.VolumeLongPressListener
import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.TargetActionFire
import viva.la.circle.remap.DeviceRemapProfile
import viva.la.circle.remap.EngineFireTargetAction
import viva.la.circle.remap.InterceptedAssistant
import viva.la.circle.remap.KeyRemapPolicy
import viva.la.circle.remap.RemapBack
import viva.la.circle.remap.RemapClock
import viva.la.circle.remap.RemapDiag
import viva.la.circle.remap.RemapSession
import viva.la.circle.remap.RemapWindows
import viva.la.circle.remap.VolumeKeyPolicy
import viva.la.circle.remap.WindowSnap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class BlueLMInterceptorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastBlueLMInterceptMs: Long = 0L
    /** Skip BlueLM dismiss-BACK Remap until this time (Gesture Handle intentional assist). */
    private var suppressBlueLMRemapUntilMs: Long = 0L
    private var lastCameraInterceptMs: Long = 0L
    private var consumingCameraKey = false
    private var shutterDownEventTime = 0L
    private var screenOffReceiverRegistered = false

    /**
     * Set while a single-press action is waiting out [DOUBLE_PRESS_WINDOW_MS]. A second shutter
     * DOWN inside that window cancels it, so a double press only opens the camera.
     */
    private var pendingSingleActionJob: Job? = null
    private var suppressNextUpAction = false
    private var consumingPowerKey = false
    private var powerLongFired = false
    private var pendingPowerLongJob: Job? = null
    private var pendingBlueLMLaunchJob: Job? = null
    private var sawPowerKeyThisSession = false
    private var loggedMissingPowerKey = false
    private var gestureHandleController: GestureHandleController? = null
    private var gestureHandleCollectJob: Job? = null
    private var immersiveRefreshJob: Job? = null
    private var assistantUiRecoveryJob: Job? = null
    private var lastImmersiveFullscreen: Boolean? = null
    private val immersiveGate = GestureImmersiveGate()

    private val volumeUpGesture = VolumeGestureState()
    private val volumeDownGesture = VolumeGestureState()

    /**
     * System volume long-press hook (needs the granted [VolumeLongPressListener.PERMISSION]).
     * When active, a long-press fires skip with **no** volume change and the accessibility
     * volume path stays pure pass-through; short presses keep native volume.
     */
    private var volumeLongPressListener: VolumeLongPressListener? = null
    private var deferredWakeAction: TargetAction? = null
    private var deferredWakePackage: String? = null
    private var debugTriggerRegistered = false

    private class VolumeGestureState {
        var consuming: Boolean = false
        var skipFired: Boolean = false
        var pendingSkipJob: Job? = null

        fun reset() {
            pendingSkipJob?.cancel()
            pendingSkipJob = null
            consuming = false
            skipFired = false
        }
    }

    @Volatile
    private var foregroundPackage: String? = null

    /** Last non-assistant activity package — the app the user was in before an Intercepted assistant Wake UI. */
    @Volatile
    private var lastUserForegroundPackage: String? = null

    @Volatile
    private var foregroundSinceMs: Long = 0L

    @Volatile
    private var blueLMActionLaunched = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                foregroundPackage = null
                lastUserForegroundPackage = null
                foregroundSinceMs = 0L
                pendingSingleActionJob?.cancel()
                pendingSingleActionJob = null
                suppressNextUpAction = false
                consumingCameraKey = false
                pendingPowerLongJob?.cancel()
                pendingPowerLongJob = null
                consumingPowerKey = false
                powerLongFired = false
                pendingBlueLMLaunchJob?.cancel()
                pendingBlueLMLaunchJob = null
                blueLMActionLaunched = false
                volumeUpGesture.reset()
                volumeDownGesture.reset()
                InterceptorStateRepository.diag("FG", "screen-off, cleared foreground")
            }
        }
    }

    companion object {
        const val BLUELM_COOLDOWN_MS = 1500L
        /** After Gesture Handle intentionally launches assist/CTS, skip BlueLM Remap. */
        const val INTENTIONAL_ASSIST_SUPPRESS_MS = 2500L
        /**
         * After an assist/CTS launch, hide the pill only while that session is
         * actually showing. Poll often so we hide shortly after Lens appears and
         * restore as soon as it is gone — not when the BlueLM remap suppress timer
         * expires (that left a 1–2s hole after Back).
         */
        const val ASSIST_RECOVERY_POLL_MS = 80L
        const val ASSIST_RECOVERY_MAX_MS = 15_000L
        const val DOUBLE_PRESS_WINDOW_MS = KeyRemapPolicy.DOUBLE_PRESS_WINDOW_MS
        const val POWER_LONG_PRESS_MS = KeyRemapPolicy.POWER_LONG_PRESS_MS
        const val CAMERA_COOLDOWN_MS = KeyRemapPolicy.CAMERA_COOLDOWN_MS
        const val CAMERA_FOREGROUND_GRACE_MS = KeyRemapPolicy.CAMERA_FOREGROUND_GRACE_MS
        const val MAX_DISMISS_BACKS = 3
        const val HWCTS_DISMISS_BACKS = RemapSession.HWCTS_DISMISS_BACKS
        const val COPILOT_HAMMER_MIN_INTERVAL_MS = 40L
        const val DEBUG_TRIGGER_ACTION = "viva.la.circle.DEBUG_TRIGGER"

        val KNOWN_CAMERA_PACKAGES = setOf(
            "com.vivo.camera",
            "com.android.camera",
            "com.vivo.media.camera",
            "com.android.camera2",
            "com.google.android.googlecamera",
            "com.huawei.camera",
            "com.meizu.media.camera",
            "com.oppo.camera",
            "com.oneplus.camera",
            "com.sec.android.app.camera",
        )

        val VENDOR_SHUTTER_LABELS = listOf(
            "PRESS",
            "CAMERA_SHUTTER",
            "CAMERA_HANDLE_SHUTTER",
        )

        val vendorShutterKeyCodes: Set<Int> by lazy {
            VENDOR_SHUTTER_LABELS.map { label ->
                try {
                    KeyEvent.keyCodeFromString("KEYCODE_$label")
                } catch (_: Exception) {
                    KeyEvent.KEYCODE_UNKNOWN
                }
            }.filter { it != KeyEvent.KEYCODE_UNKNOWN }.toSet()
        }

        /**
         * True when the *top* window (first non-empty entry) is an intercepted assistant.
         * A closing overlay that is still somewhere in the list must not count — that is what
         * caused extra BACKs to fall through into the user's app on Huawei.
         */
        fun containsCopilotWindow(packageNames: List<String?>, ownPackage: String): Boolean =
            RemapSession.containsAssistantWindow(packageNames, ownPackage)

        fun isAssistantTopWindow(topPackage: String?, ownPackage: String): Boolean =
            RemapSession.isAssistantTopWindow(topPackage, ownPackage)

        fun shouldStopDismissBacks(
            topPackage: String?,
            preAssistPackage: String?,
            ownPackage: String,
        ): Boolean = RemapSession.shouldStopDismissBacks(topPackage, preAssistPackage, ownPackage)

        fun isDismissOvershoot(
            topPackage: String?,
            preAssistPackage: String?,
            ownPackage: String,
        ): Boolean = RemapSession.isDismissOvershoot(topPackage, preAssistPackage, ownPackage)

        fun copilotDismissBackBudget(
            action: TargetAction,
            profile: VendorProfile = DeviceRemapProfile.current(),
        ): Int = RemapSession.dismissBackBudget(action, profile.maxDismissBacks)

        fun shouldPressDismissBack(topPackage: String?, ownPackage: String): Boolean =
            RemapSession.shouldPressDismissBack(topPackage, ownPackage)

        fun shouldWaitUntilCopilotGone(action: TargetAction): Boolean =
            RemapSession.shouldWaitUntilGone(action)

        fun canDismissCopilotBack(
            actionLaunched: Boolean,
            backPressCount: Int,
            copilotPresent: Boolean,
            elapsedSinceLastBackMs: Long,
            maxBacks: Int = MAX_DISMISS_BACKS,
            minIntervalMs: Long = COPILOT_HAMMER_MIN_INTERVAL_MS,
            assumePresent: Boolean = false,
        ): Boolean = RemapSession.canDismissBack(
            actionLaunched = actionLaunched,
            backPressCount = backPressCount,
            assistantPresent = copilotPresent,
            elapsedSinceLastBackMs = elapsedSinceLastBackMs,
            maxBacks = maxBacks,
            minIntervalMs = minIntervalMs,
            assumePresent = assumePresent,
        )

        fun shouldRemapBlueLMWake(
            nowMs: Long,
            lastInterceptMs: Long,
            suppressRemapUntilMs: Long,
            cooldownMs: Long = BLUELM_COOLDOWN_MS,
        ): Boolean {
            if (nowMs < suppressRemapUntilMs) return false
            if (nowMs - lastInterceptMs < cooldownMs) return false
            return true
        }

        /** Actions that call launchAssist / open assistant UI and may show Intercepted wake. */
        fun actionMayOpenInterceptedWake(action: TargetAction): Boolean = when (action) {
            TargetAction.CIRCLE_TO_SEARCH,
            TargetAction.DEFAULT_ASSISTANT,
            TargetAction.ASSISTANT_CHOOSER,
            TargetAction.HWCTS,
            -> true
            else -> false
        }

        /**
         * OriginOS StatusBar.startAssist HOMEs the current task if a pointer is still
         * down in the nav region. Fire assist/CTS only after the pill stroke ends.
         */
        fun shouldDeferWakeActionUntilStrokeEnd(
            action: TargetAction,
            strokeActive: Boolean,
        ): Boolean = strokeActive && actionMayOpenInterceptedWake(action)

        fun isCameraApp(packageName: String?, className: String?, ownPackageName: String = ""): Boolean {
            val pkg = packageName?.lowercase() ?: ""
            if (pkg.isEmpty()) return false

            if (ownPackageName.isNotEmpty() && ((pkg == ownPackageName.lowercase()) || pkg.startsWith("viva.la.circle"))) {
                return false
            }

            return KNOWN_CAMERA_PACKAGES.contains(pkg)
        }

        fun isLikelyActivityWindow(className: String?): Boolean {
            val cls = className?.trim().orEmpty()
            if (cls.isEmpty()) return false
            if (cls.startsWith("android.widget.")) return false
            if (cls.startsWith("android.view.")) return false
            if (cls.startsWith("android.inputmethodservice.")) return false
            if (cls.endsWith("Service")) return false
            return true
        }

        /**
         * Positive foreground test: a real app window, not overlays / recorders / IME.
         * Class-name blocklists lose (upslide, smartshot, screenrecorder).
         */
        fun isForegroundAppWindow(windowType: Int?, isActive: Boolean?): Boolean {
            return windowType == AccessibilityWindowInfo.TYPE_APPLICATION && isActive == true
        }

        fun isCameraShutterKey(keyCode: Int, extraKeyCodes: Set<Int> = emptySet()): Boolean {
            if (keyCode == KeyEvent.KEYCODE_CAMERA) return true
            if (keyCode in extraKeyCodes) return true
            return keyCode in vendorShutterKeyCodes
        }

        fun isPowerKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_POWER

        fun isAssistSessionForeground(packageName: String?, className: String? = null): Boolean {
            val cls = className.orEmpty()
            if (cls.contains("VoiceInteractionWindow")) return true
            val pkg = packageName.orEmpty()
            if (pkg.isEmpty()) return false
            if (pkg == ActionExecutionEngine.HWCTS_PACKAGE) return true
            if (pkg == "com.google.android.googlequicksearchbox") return true
            if (pkg == "com.google.android.apps.googleassistant") return true
            if (pkg == "com.google.android.apps.bard") return true
            if (pkg == "com.google.android.apps.gemini") return true
            // OEM assistants opened via launchAssist also need our edge strips gone.
            if (InterceptedAssistant.isInterceptedAssistant(pkg, cls)) return true
            return false
        }

        fun shouldHideGestureHandleForAssistantUi(
            packageName: String?,
            className: String? = null,
            ownPackage: String = "",
        ): Boolean {
            if (isAssistSessionForeground(packageName, className)) return true
            return InterceptedAssistant.isWakeUi(packageName, className, ownPackage)
        }

        /**
         * Remap suppress must not keep the pill detached. Detaching before CTS is
         * on screen flashes the launcher; keeping it detached after Back leaves a
         * 1–2s hole.
         */
        fun shouldHideGestureHandleDuringAssistSession(
            assistantUiShowing: Boolean,
        ): Boolean = assistantUiShowing

        fun shouldEndAssistPillRecovery(
            assistantUiShowing: Boolean,
            sawAssistantUi: Boolean,
        ): Boolean = sawAssistantUi && !assistantUiShowing

        fun shouldPassThroughCameraKey(
            skipWhileCameraOpen: Boolean,
            foregroundPackage: String?,
            foregroundSinceMs: Long,
            nowMs: Long,
            ownPackage: String,
        ): Boolean = KeyRemapPolicy.shouldPassThroughCameraKey(
            skipWhileCameraOpen = skipWhileCameraOpen,
            foregroundPackage = foregroundPackage,
            foregroundSinceMs = foregroundSinceMs,
            nowMs = nowMs,
            ownPackage = ownPackage,
            isCameraApp = ::isCameraApp,
        )

        fun isPowerLongPress(heldMs: Long, thresholdMs: Long = POWER_LONG_PRESS_MS): Boolean =
            KeyRemapPolicy.isPowerLongPress(heldMs, thresholdMs)

        fun formatKeyEvent(event: KeyEvent): String {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP"
                else -> event.action.toString()
            }
            val name = try {
                KeyEvent.keyCodeToString(event.keyCode)
            } catch (_: Exception) {
                "KEYCODE_${event.keyCode}"
            }
            return "${event.keyCode} $name action=$action scan=${event.scanCode} " +
                "dev=${event.deviceId} src=${event.source} flags=${event.flags} repeat=${event.repeatCount}"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        InterceptorStateRepository.loadFromPreferences(this)
        InterceptorStateRepository.updateRunning(isRunning = true)
        InterceptorStateRepository.updateEnabledStatus(this)
        registerScreenOffReceiver()
        val info = serviceInfo
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 0
            setServiceInfo(info)
        }
        InterceptorStateRepository.diag(
            "SVC",
            "connected os=${InterceptorStateRepository.serviceState.value.detectedOsLabel} " +
                "vendorShutterKeyCodes=$vendorShutterKeyCodes",
            force = true,
        )
        registerVolumeLongPressListener()

        gestureHandleController = GestureHandleController(this) { event ->
            handleGestureNavEvent(event)
        }.also { controller ->
            controller.onStrokeFinished = { completed ->
                flushDeferredWakeAction(completed)
            }
        }
        registerDebugTrigger()
        gestureHandleCollectJob?.cancel()
        gestureHandleCollectJob = serviceScope.launch {
            InterceptorStateRepository.serviceState.collect { state ->
                gestureHandleController?.sync(state)
            }
        }
        scheduleImmersiveRefresh(immediate = true)
    }

    private fun registerVolumeLongPressListener() {
        val listener = volumeLongPressListener ?: VolumeLongPressListener(this) { keyCode ->
            onVolumeLongPress(keyCode)
        }.also { volumeLongPressListener = it }
        val ok = listener.register()
        InterceptorStateRepository.setVolumeLongPressListenerActive(ok)
        InterceptorStateRepository.diag(
            "VOL",
            "long-press listener register ok=$ok granted=${listener.isPermissionGranted()}",
            force = true,
        )
    }

    /** System-routed volume long-press (no volume change). Fires one skip per hold. */
    private fun onVolumeLongPress(keyCode: Int) {
        val state = InterceptorStateRepository.serviceState.value
        if (!state.volumeSkipTracksEnabled) return
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager != null && MediaPlaybackGate.isInCall(audioManager)) return
        val raise = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        fireVolumeSkip(raise = raise, haptic = state.volumeHapticEnabled)
        InterceptorStateRepository.diag(
            "VOL",
            "system long-press skip ${if (raise) "NEXT" else "PREV"} (no volume change)",
        )
    }

    private fun handleGestureNavEvent(event: GestureNavEvent) {
        val state = InterceptorStateRepository.serviceState.value
        val haptic = state.gestureHapticEnabled(event)
        when (event) {
            GestureNavEvent.Back -> {
                if (haptic) GestureHandleHaptics.confirm(this)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            GestureNavEvent.Recents -> {
                if (haptic) GestureHandleHaptics.confirm(this)
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            GestureNavEvent.Tap -> {
                // Short tap with None: no haptic, no action.
                fireGestureHandleAction(
                    action = state.gestureTapAction,
                    specificPackage = state.gestureTapSpecificPackage,
                    haptic = haptic,
                )
            }
            GestureNavEvent.LongPress -> {
                fireGestureHandleAction(
                    action = state.gestureLongPressAction,
                    specificPackage = state.gestureLongPressSpecificPackage,
                    haptic = haptic,
                )
            }
            GestureNavEvent.SwipeUp -> {
                fireGestureHandleAction(
                    action = state.gestureSwipeUpAction,
                    specificPackage = state.gestureSwipeUpSpecificPackage,
                    haptic = haptic,
                )
            }
            GestureNavEvent.SwipeLeft -> {
                fireGestureHandleAction(
                    action = state.gestureSwipeLeftAction,
                    specificPackage = state.gestureSwipeLeftSpecificPackage,
                    haptic = haptic,
                )
            }
            GestureNavEvent.SwipeRight -> {
                fireGestureHandleAction(
                    action = state.gestureSwipeRightAction,
                    specificPackage = state.gestureSwipeRightSpecificPackage,
                    haptic = haptic,
                )
            }
        }
    }

    private fun fireGestureHandleAction(
        action: TargetAction,
        specificPackage: String?,
        haptic: Boolean = false,
    ) {
        if (action == TargetAction.NONE) return
        if (haptic) GestureHandleHaptics.confirm(this)
        if (shouldDeferWakeActionUntilStrokeEnd(
                action,
                gestureHandleController?.isInteracting == true,
            )
        ) {
            deferredWakeAction = action
            deferredWakePackage = specificPackage
            InterceptorStateRepository.diag("GH", "defer $action until stroke end")
            return
        }
        executeGestureHandleAction(action, specificPackage)
    }

    private fun flushDeferredWakeAction(completed: Boolean) {
        val action = deferredWakeAction ?: return
        val pkg = deferredWakePackage
        deferredWakeAction = null
        deferredWakePackage = null
        if (!completed) {
            InterceptorStateRepository.diag("GH", "drop deferred $action (stroke cancelled)")
            return
        }
        executeGestureHandleAction(action, pkg)
    }

    private fun executeGestureHandleAction(
        action: TargetAction,
        specificPackage: String?,
    ) {
        // launchAssist can surface BlueLM/Celia Wake UI. Remap would then hammer BACK
        // (often into the app under the overlay) and re-fire — suppress that window.
        if (actionMayOpenInterceptedWake(action)) {
            suppressBlueLMRemapUntilMs =
                System.currentTimeMillis() + INTENTIONAL_ASSIST_SUPPRESS_MS
            InterceptorStateRepository.diag(
                "GH",
                "suppress BlueLM Remap ${INTENTIONAL_ASSIST_SUPPRESS_MS}ms (intentional $action)",
            )
        }
        ActionExecutionEngine.executeAction(
            context = this,
            action = action,
            specificPackage = specificPackage,
            service = this,
        )
        if (actionMayOpenInterceptedWake(action)) {
            scheduleImmersiveRefresh(immediate = false)
            scheduleAssistantUiRecovery()
        }
    }

    private val debugTriggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val actionName = intent?.getStringExtra("action") ?: TargetAction.CIRCLE_TO_SEARCH.name
            val action = TargetAction.fromName(actionName, TargetAction.CIRCLE_TO_SEARCH)
            InterceptorStateRepository.diag("DBG", "broadcast trigger $action", force = true)
            fireGestureHandleAction(action, null, haptic = false)
        }
    }

    private fun registerDebugTrigger() {
        if (debugTriggerRegistered) return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }
        val filter = IntentFilter(DEBUG_TRIGGER_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugTriggerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(debugTriggerReceiver, filter)
        }
        debugTriggerRegistered = true
    }

    private fun unregisterDebugTrigger() {
        if (!debugTriggerRegistered) return
        debugTriggerRegistered = false
        try {
            unregisterReceiver(debugTriggerReceiver)
        } catch (_: Exception) {
        }
    }

    /**
     * Re-shows the Gesture Handle after an assist/CTS session ends. Polls [isAssistantUiShowingNow]
     * because dismissing CTS often produces no accessibility event our normal path would catch —
     * the underlying app was never really backgrounded — leaving the pill hidden until the user
     * switches apps by hand.
     */
    private fun scheduleAssistantUiRecovery() {
        assistantUiRecoveryJob?.cancel()
        assistantUiRecoveryJob = serviceScope.launch {
            val deadline = System.currentTimeMillis() + ASSIST_RECOVERY_MAX_MS
            var sawAssistantUi = false
            while (System.currentTimeMillis() < deadline) {
                delay(ASSIST_RECOVERY_POLL_MS)
                val controller = gestureHandleController ?: break
                if (!InterceptorStateRepository.serviceState.value.gestureHandleEnabled) break
                if (controller.isInteracting) continue
                refreshGestureHandleImmersive()
                val showing = isAssistantUiShowingNow()
                if (showing) sawAssistantUi = true
                if (shouldEndAssistPillRecovery(showing, sawAssistantUi)) {
                    InterceptorStateRepository.diag("GH", "assistant UI gone — pill restored")
                    break
                }
            }
            assistantUiRecoveryJob = null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        gestureHandleCollectJob?.cancel()
        gestureHandleCollectJob = null
        immersiveRefreshJob?.cancel()
        immersiveRefreshJob = null
        assistantUiRecoveryJob?.cancel()
        assistantUiRecoveryJob = null
        lastImmersiveFullscreen = null
        immersiveGate.reset()
        deferredWakeAction = null
        deferredWakePackage = null
        gestureHandleController?.onStrokeFinished = null
        gestureHandleController?.destroy()
        gestureHandleController = null
        unregisterDebugTrigger()
        InterceptorStateRepository.updateRunning(isRunning = false)
        InterceptorStateRepository.diag("SVC", "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureHandleCollectJob?.cancel()
        gestureHandleCollectJob = null
        immersiveRefreshJob?.cancel()
        immersiveRefreshJob = null
        assistantUiRecoveryJob?.cancel()
        assistantUiRecoveryJob = null
        lastImmersiveFullscreen = null
        immersiveGate.reset()
        deferredWakeAction = null
        deferredWakePackage = null
        gestureHandleController?.onStrokeFinished = null
        gestureHandleController?.destroy()
        gestureHandleController = null
        unregisterDebugTrigger()
        volumeLongPressListener?.unregister()
        volumeLongPressListener = null
        InterceptorStateRepository.setVolumeLongPressListenerActive(false)
        InterceptorStateRepository.updateRunning(isRunning = false)
        unregisterScreenOffReceiver()
        serviceScope.cancel()
        InterceptorStateRepository.diag("SVC", "destroyed")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val state = InterceptorStateRepository.serviceState.value

        // Volume skip/remap must keep working during key capture (capture is for shutter/grip).
        if (isVolumeKey(event.keyCode)) {
            return handleVolumeKey(event, state)
        }

        if (state.captureMode) {
            InterceptorStateRepository.diag("KEY", formatKeyEvent(event))
            return false
        }

        if (isPowerKey(event.keyCode)) {
            return handlePowerKey(event, state)
        }

        if (!isCameraShutterKey(event.keyCode, state.cameraKeyCodes)) {
            if (state.diagnosticsEnabled && event.repeatCount == 0) {
                InterceptorStateRepository.diag("KEY", "unmatched ${formatKeyEvent(event)}")
            }
            return super.onKeyEvent(event)
        }

        InterceptorStateRepository.diag("KEY", "shutter ${formatKeyEvent(event)} fg=$foregroundPackage")

        val ownPkg = ownPackageName()
        val now = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val decision = KeyRemapPolicy.onShutterDown(
                    cameraAction = state.cameraAction,
                    repeatCount = event.repeatCount,
                    skipWhileCameraOpen = state.skipCameraApp,
                    foregroundPackage = foregroundPackage,
                    foregroundSinceMs = foregroundSinceMs,
                    nowMs = now,
                    ownPackage = ownPkg,
                    isCameraApp = ::isCameraApp,
                    hasPendingSingleAction = pendingSingleActionJob != null,
                )
                return applyShutterDown(decision, state, event.eventTime, event.repeatCount)
            }
            KeyEvent.ACTION_UP -> {
                val wasConsuming = consumingCameraKey
                consumingCameraKey = false
                val heldMs = event.eventTime - shutterDownEventTime
                val decision = KeyRemapPolicy.onShutterUp(
                    wasConsuming = wasConsuming,
                    suppressNextUpAction = suppressNextUpAction,
                    nowMs = now,
                    lastCameraInterceptMs = lastCameraInterceptMs,
                    keyCode = event.keyCode,
                    heldMs = heldMs,
                )
                return applyShutterUp(decision, state, heldMs)
            }
            else -> return false
        }
    }

    private fun applyShutterDown(
        decision: KeyRemapPolicy.ShutterDecision,
        state: InterceptorServiceState,
        eventTime: Long,
        repeatCount: Int,
    ): Boolean {
        when (decision) {
            KeyRemapPolicy.ShutterDecision.Ignore -> {
                InterceptorStateRepository.diag("KEY", "rejected: cameraAction=NONE")
                return false
            }
            KeyRemapPolicy.ShutterDecision.PassThrough -> {
                consumingCameraKey = false
                val now = System.currentTimeMillis()
                InterceptorStateRepository.diag(
                    "KEY",
                    "rejected: camera app foreground for ${now - foregroundSinceMs}ms " +
                        "(grace=${CAMERA_FOREGROUND_GRACE_MS}ms)",
                )
                return false
            }
            KeyRemapPolicy.ShutterDecision.CancelPendingDoublePress -> {
                pendingSingleActionJob?.cancel()
                pendingSingleActionJob = null
                suppressNextUpAction = true
                consumingCameraKey = true
                shutterDownEventTime = eventTime
                InterceptorStateRepository.diag(
                    "KEY",
                    "double press: cancelled pending ${state.cameraAction}, camera left to system",
                )
                return true
            }
            KeyRemapPolicy.ShutterDecision.ConsumeDown -> {
                if (repeatCount > 0) return consumingCameraKey
                consumingCameraKey = true
                shutterDownEventTime = eventTime
                if (!suppressNextUpAction) {
                    InterceptorStateRepository.diag("KEY", "DOWN consumed, wait for UP")
                }
                return true
            }
            else -> return false
        }
    }

    private fun applyShutterUp(
        decision: KeyRemapPolicy.ShutterDecision,
        state: InterceptorServiceState,
        heldMs: Long,
    ): Boolean {
        when (decision) {
            KeyRemapPolicy.ShutterDecision.Ignore -> {
                InterceptorStateRepository.diag("KEY", "UP ignored: no matching DOWN")
                return false
            }
            KeyRemapPolicy.ShutterDecision.SuppressUpAfterDoublePress -> {
                suppressNextUpAction = false
                InterceptorStateRepository.diag(
                    "KEY",
                    "double press: no single action, held=${heldMs}ms",
                )
                return true
            }
            KeyRemapPolicy.ShutterDecision.ConsumeUpNoFire -> {
                InterceptorStateRepository.diag(
                    "KEY",
                    "rejected: camera cooldown held=${heldMs}ms",
                )
                return true
            }
            is KeyRemapPolicy.ShutterDecision.ScheduleFire -> {
                val keyCode = decision.keyCode
                pendingSingleActionJob = serviceScope.launch {
                    delay(DOUBLE_PRESS_WINDOW_MS.milliseconds)
                    pendingSingleActionJob = null
                    val firedAt = System.currentTimeMillis()
                    lastCameraInterceptMs = firedAt
                    InterceptorStateRepository.recordInterception("HardwareKey_$keyCode", firedAt)
                    val fired = TargetActionFire.forService(
                        context = this@BlueLMInterceptorService,
                        service = this@BlueLMInterceptorService,
                    ).fire(state.cameraAction, state.cameraSpecificPackage)
                    InterceptorStateRepository.diag(
                        "KEY",
                        "fired ${state.cameraAction} single-press held=${decision.heldMs}ms result=$fired",
                    )
                }
                return true
            }
            else -> return false
        }
    }

    private fun handlePowerKey(event: KeyEvent, state: InterceptorServiceState): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val decision = KeyRemapPolicy.onPowerDown(
                    blueLMAction = state.blueLMAction,
                    screenInteractive = isScreenInteractive(),
                    repeatCount = event.repeatCount,
                    alreadyConsuming = consumingPowerKey,
                )
                if (decision != KeyRemapPolicy.PowerDecision.Ignore) {
                    sawPowerKeyThisSession = true
                }
                return applyPowerDown(decision, event.keyCode)
            }
            KeyEvent.ACTION_UP -> {
                val decision = KeyRemapPolicy.onPowerUp(
                    wasConsuming = consumingPowerKey,
                    longFired = powerLongFired,
                )
                return applyPowerUp(decision)
            }
            else -> return false
        }
    }

    private fun applyPowerDown(decision: KeyRemapPolicy.PowerDecision, keyCode: Int): Boolean {
        when (decision) {
            KeyRemapPolicy.PowerDecision.Ignore -> {
                if (!isScreenInteractive()) {
                    InterceptorStateRepository.diag("KEY", "power ignored, screen off")
                }
                return false
            }
            KeyRemapPolicy.PowerDecision.ContinueConsuming -> return consumingPowerKey
            KeyRemapPolicy.PowerDecision.ConsumeDownStartLongJob -> {
                consumingPowerKey = true
                powerLongFired = false
                pendingPowerLongJob?.cancel()
                pendingPowerLongJob = serviceScope.launch {
                    delay(POWER_LONG_PRESS_MS)
                    if (!consumingPowerKey || powerLongFired) return@launch
                    powerLongFired = true
                    fireBlueLMFromPower(keyCode, POWER_LONG_PRESS_MS)
                }
                InterceptorStateRepository.diag("KEY", "power DOWN consume=true")
                return true
            }
            else -> return false
        }
    }

    private fun applyPowerUp(decision: KeyRemapPolicy.PowerDecision): Boolean {
        when (decision) {
            KeyRemapPolicy.PowerDecision.Ignore -> return false
            KeyRemapPolicy.PowerDecision.UpAfterLong -> {
                pendingPowerLongJob?.cancel()
                pendingPowerLongJob = null
                consumingPowerKey = false
                powerLongFired = false
                InterceptorStateRepository.diag("KEY", "power UP after long-press")
                return true
            }
            KeyRemapPolicy.PowerDecision.ShortPressLock -> {
                pendingPowerLongJob?.cancel()
                pendingPowerLongJob = null
                consumingPowerKey = false
                powerLongFired = false
                val locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
                InterceptorStateRepository.diag("KEY", "power short-press lock=$locked")
                return true
            }
            else -> return false
        }
    }

    private fun fireBlueLMFromPower(keyCode: Int, heldMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastBlueLMInterceptMs < BLUELM_COOLDOWN_MS) {
            InterceptorStateRepository.diag("KEY", "power long-press skipped, cooldown")
            return
        }
        lastBlueLMInterceptMs = now
        InterceptorStateRepository.recordInterception("PowerLong_$keyCode", now)
        val current = InterceptorStateRepository.serviceState.value
        val action = current.blueLMAction ?: return
        val fired = TargetActionFire.forService(
            context = this,
            service = this,
        ).fire(action, current.blueLMSpecificPackage)
        InterceptorStateRepository.diag(
            "KEY",
            "fired $action power-long held=${heldMs}ms result=$fired",
        )
    }


    private fun handleVolumeKey(event: KeyEvent, state: InterceptorServiceState): Boolean {
        val raise = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val gesture = if (raise) volumeUpGesture else volumeDownGesture
        val configuredShort = if (raise) state.volumeUpShortAction else state.volumeDownShortAction
        val effectiveShort = if (state.volumeShortRemapEnabled) {
            configuredShort
        } else {
            VolumeShortAction.Volume
        }

        val audioManager = getSystemService(AudioManager::class.java)
        val inCall = audioManager != null && MediaPlaybackGate.isInCall(audioManager)
        val mediaPlaying = MediaPlaybackGate.isMediaPlaying(this)
        val canSkip = VolumeKeyPolicy.canSkipOnLongPress(
            skipTracksEnabled = state.volumeSkipTracksEnabled,
            mediaPlaying = mediaPlaying,
            inCall = inCall,
        )
        val armed = VolumeKeyPolicy.shouldArmKey(
            skipTracksEnabled = state.volumeSkipTracksEnabled,
            shortRemapEnabled = state.volumeShortRemapEnabled,
            shortAction = effectiveShort,
            mediaPlaying = mediaPlaying,
            inCall = inCall,
        )

        val passThroughVolume = effectiveShort is VolumeShortAction.Volume

        // Native volume short: always OS pass-through. Skip is only via system long-press
        // (VolumeLongPressListener) when the ADB permission is granted.
        if (passThroughVolume) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val decision = VolumeKeyPolicy.onDown(
                    armed = armed,
                    canSkip = canSkip,
                    passThroughVolume = false,
                    repeatCount = event.repeatCount,
                    alreadyConsuming = gesture.consuming,
                )
                return applyVolumeDown(decision, gesture, state, raise)
            }
            KeyEvent.ACTION_UP -> {
                val wasConsuming = gesture.consuming
                val skipFired = gesture.skipFired
                gesture.pendingSkipJob?.cancel()
                gesture.pendingSkipJob = null
                gesture.consuming = false
                gesture.skipFired = false
                val decision = VolumeKeyPolicy.onUp(
                    wasConsuming = wasConsuming,
                    skipFired = skipFired,
                    shortAction = effectiveShort,
                )
                return applyVolumeUp(decision, state, raise)
            }
            else -> return false
        }
    }

    private fun applyVolumeDown(
        decision: VolumeKeyPolicy.DownDecision,
        gesture: VolumeGestureState,
        state: InterceptorServiceState,
        raise: Boolean,
    ): Boolean {
        when (decision) {
            VolumeKeyPolicy.DownDecision.PassThrough -> return false
            VolumeKeyPolicy.DownDecision.ContinueConsuming -> return gesture.consuming
            VolumeKeyPolicy.DownDecision.ConsumeShortOnly -> {
                gesture.consuming = true
                gesture.skipFired = false
                gesture.pendingSkipJob?.cancel()
                gesture.pendingSkipJob = null
                InterceptorStateRepository.diag(
                    "VOL",
                    "DOWN consume short-only ${if (raise) "UP" else "DOWN"}",
                )
                return true
            }
            VolumeKeyPolicy.DownDecision.ConsumeStartSkipJob -> {
                gesture.consuming = true
                gesture.skipFired = false
                gesture.pendingSkipJob?.cancel()
                val timeout = state.volumeLongPressMs
                gesture.pendingSkipJob = serviceScope.launch {
                    delay(timeout)
                    if (!gesture.consuming || gesture.skipFired) return@launch
                    gesture.skipFired = true
                    fireVolumeSkip(raise = raise, haptic = state.volumeHapticEnabled)
                }
                InterceptorStateRepository.diag(
                    "VOL",
                    "DOWN consume skip-job ${if (raise) "UP" else "DOWN"} timeout=${timeout}ms",
                )
                return true
            }
        }
    }

    private fun applyVolumeUp(
        decision: VolumeKeyPolicy.UpDecision,
        state: InterceptorServiceState,
        raise: Boolean,
    ): Boolean {
        when (decision) {
            VolumeKeyPolicy.UpDecision.PassThrough -> return false
            VolumeKeyPolicy.UpDecision.AfterSkip -> {
                InterceptorStateRepository.diag("VOL", "UP after skip")
                return true
            }
            is VolumeKeyPolicy.UpDecision.FireAction -> {
                val fired = TargetActionFire.forService(
                    context = this,
                    service = this,
                ).fire(decision.action, decision.specificPackage)
                if (state.volumeHapticEnabled) {
                    VolumeHaptics.tick(this)
                }
                InterceptorStateRepository.recordInterception(
                    "VolumeShort_${if (raise) "UP" else "DOWN"}",
                )
                InterceptorStateRepository.diag(
                    "VOL",
                    "short action ${decision.action} result=$fired",
                )
                return true
            }
        }
    }

    private fun fireVolumeSkip(raise: Boolean, haptic: Boolean) {
        val ok = if (raise) {
            MediaPlaybackGate.skipNext(this)
        } else {
            MediaPlaybackGate.skipPrevious(this)
        }
        if (haptic) {
            VolumeHaptics.tick(this)
        }
        InterceptorStateRepository.recordInterception(
            if (raise) "VolumeSkip_NEXT" else "VolumeSkip_PREV",
        )
        InterceptorStateRepository.diag(
            "VOL",
            "skip ${if (raise) "NEXT" else "PREV"} ok=$ok",
        )
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun scheduleImmersiveRefresh(immediate: Boolean) {
        immersiveRefreshJob?.cancel()
        immersiveRefreshJob = serviceScope.launch {
            if (!immediate) delay(120)
            refreshGestureHandleImmersive()
        }
    }

    private fun refreshGestureHandleImmersive() {
        val controller = gestureHandleController ?: return
        if (!InterceptorStateRepository.serviceState.value.gestureHandleEnabled) return
        // Overlay touch briefly mutates accessibility windows → skip mid-stroke.
        if (controller.isInteracting) {
            scheduleImmersiveRefresh(immediate = false)
            return
        }
        val assistantUi = shouldHideGestureHandleDuringAssistSession(isAssistantUiShowingNow())
        controller.setAssistantUiVisible(assistantUi)

        val dm = resources.displayMetrics
        val snaps = GestureImmersiveDetector.snapshotWindows(windows)
        val sample = GestureImmersiveDetector.isImmersiveFullscreen(
            windows = snaps,
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
        )
        val decision = immersiveGate.onSample(sample)
        if (decision == null) {
            if (immersiveGate.isConfirmingHide()) {
                scheduleImmersiveRefresh(immediate = false)
            }
            return
        }
        if (lastImmersiveFullscreen == decision) return
        lastImmersiveFullscreen = decision
        controller.setImmersiveFullscreen(decision)
    }

    /** Best-effort: focused/active window belongs to an assistant / CTS session. */
    private fun isAssistantUiShowingNow(): Boolean {
        val wins = try {
            windows
        } catch (_: Exception) {
            return false
        }
        if (wins.isNullOrEmpty()) return false
        val own = ownPackageName()
        val candidates = buildList {
            wins.firstOrNull { it.isFocused }?.let { add(it) }
            wins.firstOrNull { it.isActive }?.let { w ->
                if (none { existing -> existing === w }) add(w)
            }
        }
        for (w in candidates) {
            val root = try {
                w.root
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                val pkg = root.packageName?.toString()
                val cls = root.className?.toString()
                if (shouldHideGestureHandleForAssistantUi(pkg, cls, own)) {
                    return true
                }
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            scheduleImmersiveRefresh(immediate = false)
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val ownPkg = ownPackageName()
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val now = System.currentTimeMillis()
        val eventWin = eventWindow(event)
        val isFgApp = if (eventWin != null) {
            isForegroundAppWindow(eventWin.type, eventWin.isActive)
        } else {
            isLikelyActivityWindow(className)
        }
        if (isFgApp && packageName != foregroundPackage) {
            foregroundPackage = packageName
            foregroundSinceMs = now
            if (!InterceptedAssistant.isInterceptedAssistant(packageName, className, ownPkg)) {
                lastUserForegroundPackage = packageName
            }
            InterceptorStateRepository.diag("FG", "pkg=$packageName cls=$className")
        }

        if (InterceptedAssistant.isWakeUi(packageName, className, ownPkg)) {
            val state = InterceptorStateRepository.serviceState.value
            val blueLMAction = state.blueLMAction
            if (!BlueLMActionConfig.shouldRemap(blueLMAction)) {
                InterceptorStateRepository.diag(
                    "BLM",
                    "rejected: blueLMAction=${blueLMAction ?: "unset"} pkg=$packageName",
                )
                return
            }

            if (!shouldRemapBlueLMWake(
                    nowMs = now,
                    lastInterceptMs = lastBlueLMInterceptMs,
                    suppressRemapUntilMs = suppressBlueLMRemapUntilMs,
                )
            ) {
                val reason = if (now < suppressBlueLMRemapUntilMs) {
                    "intentional-assist suppress"
                } else {
                    "cooldown"
                }
                InterceptorStateRepository.diag(
                    "BLM",
                    "$reason skip pkg=$packageName cls=$className",
                )
                return
            }

            lastBlueLMInterceptMs = now
            blueLMActionLaunched = false
            val preAssistPackage = lastUserForegroundPackage
            InterceptorStateRepository.recordInterception(packageName, now)
            if (!sawPowerKeyThisSession && !loggedMissingPowerKey) {
                loggedMissingPowerKey = true
                InterceptorStateRepository.diag(
                    "KEY",
                    "KEYCODE_POWER not delivered this session; window fallback only",
                )
            }
            InterceptorStateRepository.diag(
                "BLM",
                "wake overlay=${!isLikelyActivityWindow(className)} " +
                    "action=$blueLMAction pkg=$packageName cls=$className " +
                    "preAssist=${preAssistPackage ?: "-"}",
            )

            pendingBlueLMLaunchJob?.cancel()
            pendingBlueLMLaunchJob = serviceScope.launch {
                try {
                    val profile = DeviceRemapProfile.current()
                    val timing = DeviceRemapProfile.timing(profile)
                    val session = RemapSession(
                        windows = RemapWindows { toRemapSnap(windowSnapshot()) },
                        back = RemapBack { performGlobalAction(GLOBAL_ACTION_BACK) },
                        clock = object : RemapClock {
                            override fun nowMs(): Long = System.currentTimeMillis()
                            override suspend fun delayMs(ms: Long) {
                                delay(ms.milliseconds)
                            }
                        },
                        fire = TargetActionFire.forService(
                            context = this@BlueLMInterceptorService,
                            service = this@BlueLMInterceptorService,
                        ),
                        diag = RemapDiag { kind, detail ->
                            InterceptorStateRepository.diag(kind, detail)
                        },
                        ownPackage = ownPkg,
                    )
                    val fired = session.run(
                        action = requireNotNull(blueLMAction),
                        specificPackage = state.blueLMSpecificPackage,
                        preAssistPackage = preAssistPackage,
                        timing = timing,
                    )
                    if (fired) {
                        blueLMActionLaunched = true
                    }
                } finally {
                    pendingBlueLMLaunchJob = null
                }
            }
        }
    }

    private data class WindowSnapshot(
        val topPackage: String?,
        val packages: List<String?>,
    )

    private fun toRemapSnap(snap: WindowSnapshot): WindowSnap =
        WindowSnap(topPackage = snap.topPackage, packages = snap.packages)

    /**
     * Prefer the active/focused window's package (one binder call). Fall back to scanning
     * application windows only — never walk every root on the hot path unless needed for logs.
     */
    private fun windowSnapshot(): WindowSnapshot {
        val wins = try {
            windows
        } catch (_: Exception) {
            return WindowSnapshot(null, emptyList())
        }
        if (wins.isNullOrEmpty()) return WindowSnapshot(null, emptyList())

        val ordered = buildList<AccessibilityWindowInfo> {
            wins.firstOrNull { it.isActive }?.let { add(it) }
            wins.firstOrNull { candidate ->
                candidate.isFocused && none { existing -> existing === candidate }
            }?.let { add(it) }
            wins.forEach { candidate ->
                val interesting =
                    candidate.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        candidate.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        candidate.type == AccessibilityWindowInfo.TYPE_SYSTEM
                if (interesting && none { existing -> existing === candidate }) {
                    add(candidate)
                }
            }
        }

        val packages = mutableListOf<String?>()
        for (window in ordered) {
            packages.add(packageFromWindow(window))
        }
        val top = packages.firstOrNull { !it.isNullOrEmpty() }
        return WindowSnapshot(topPackage = top, packages = packages)
    }

    private fun packageFromWindow(window: AccessibilityWindowInfo?): String? {
        if (window == null) return null
        var root: AccessibilityNodeInfo? = null
        return try {
            root = window.root
            root?.packageName?.toString()
        } catch (_: Exception) {
            null
        } finally {
            @Suppress("DEPRECATION")
            try {
                root?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun eventWindow(event: AccessibilityEvent): AccessibilityWindowInfo? {
        val id = event.windowId
        if (id < 0) return null
        return try {
            windows?.firstOrNull { it.id == id }
        } catch (_: Exception) {
            null
        }
    }

    override fun onInterrupt() {
        InterceptorStateRepository.diag("SVC", "interrupted")
    }

    fun launchGoogleAssistant(context: Context) {
        ActionExecutionEngine.launchDefaultAssistant(context)
    }

    private fun ownPackageName(): String {
        return try {
            applicationContext?.packageName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, filter)
        }
        screenOffReceiverRegistered = true
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        screenOffReceiverRegistered = false
    }
}
