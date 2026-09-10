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
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.TargetAction
import viva.la.circle.remap.InterceptedAssistant
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
    private var lastCopilotHammerMs: Long = 0L
    private var sawPowerKeyThisSession = false
    private var loggedMissingPowerKey = false

    @Volatile
    private var foregroundPackage: String? = null

    /** Last non-assistant activity package — the app the user was in before Celia/Copilot. */
    @Volatile
    private var lastUserForegroundPackage: String? = null

    @Volatile
    private var foregroundSinceMs: Long = 0L

    @Volatile
    private var blueLMActionLaunched = false
    private var backPressCount = 0

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
                backPressCount = 0
                InterceptorStateRepository.diag("FG", "screen-off, cleared foreground")
            }
        }
    }

    companion object {
        const val BLUELM_COOLDOWN_MS = 1500L
        const val CAMERA_COOLDOWN_MS = 150L
        const val CAMERA_FOREGROUND_GRACE_MS = 1200L

        /** Hard cap so a stale getWindows() result cannot restart a BACK storm. */
        const val MAX_DISMISS_BACKS = 3

        /**
         * HwCTS ACTION_TILE_TRIGGER already sends GLOBAL_ACTION_BACK (it closes QS).
         * One of ours first, then the tile BACK, while Copilot is still up. Waiting until
         * Copilot is gone makes the tile BACK hit the app and land on the launcher.
         */
        const val HWCTS_DISMISS_BACKS = 1

        /** Poll until Copilot is gone, then fire the TargetAction. */
        const val COPILOT_DISMISS_TIMEOUT_MS = 400L
        const val COPILOT_POLL_MS = 30L

        /** Do not BACK faster than this while dismissing a float overlay. */
        const val COPILOT_HAMMER_MIN_INTERVAL_MS = 40L

        /**
         * How long a single-press action waits before firing, so a double press can cancel it.
         *
         * The system signals a double press with a separate keycode (550 KEYCODE_DOUBLE_CLICK on
         * OriginOS 6) that arrives with the second shutter DOWN — measured 84-107ms after the
         * first shutter UP. 250ms leaves better than 2x margin while keeping single-press latency
         * below the platform's 300ms double-tap timeout.
         */
        const val DOUBLE_PRESS_WINDOW_MS = 250L

        /** Vivo Copilot is bound to long-press power. Fire before the ROM opens it. */
        const val POWER_LONG_PRESS_MS = 500L

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
        fun containsCopilotWindow(packageNames: List<String?>, ownPackage: String): Boolean {
            val top = packageNames.firstOrNull { !it.isNullOrEmpty() }
            return InterceptedAssistant.isInterceptedAssistant(top, null, ownPackage)
        }

        fun isAssistantTopWindow(topPackage: String?, ownPackage: String): Boolean =
            InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)

        /**
         * Stop BACKs once the top window is known and is not the assistant.
         * Covers both "user app restored" and "overshot into launcher".
         */
        fun shouldStopDismissBacks(
            topPackage: String?,
            @Suppress("UNUSED_PARAMETER") preAssistPackage: String?,
            ownPackage: String,
        ): Boolean {
            if (topPackage.isNullOrBlank()) return false
            return !InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)
        }

        fun isDismissOvershoot(
            topPackage: String?,
            preAssistPackage: String?,
            ownPackage: String,
        ): Boolean {
            if (topPackage.isNullOrBlank()) return false
            if (InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)) return false
            if (preAssistPackage != null &&
                topPackage.equals(preAssistPackage, ignoreCase = true)
            ) {
                return false
            }
            return true
        }

        fun copilotDismissBackBudget(
            action: TargetAction,
            profile: VendorProfile = VendorProfile.current(),
        ): Int {
            return if (action == TargetAction.HWCTS) HWCTS_DISMISS_BACKS else profile.maxDismissBacks
        }

        fun assistantDismissTimeoutMs(profile: VendorProfile = VendorProfile.current()): Long =
            profile.dismissTimeoutMs

        fun assistantPollMs(profile: VendorProfile = VendorProfile.current()): Long =
            profile.pollMs

        fun assistantHammerMinIntervalMs(profile: VendorProfile = VendorProfile.current()): Long =
            profile.hammerMinIntervalMs

        fun assistantFocusWaitTimeoutMs(profile: VendorProfile = VendorProfile.current()): Long =
            profile.focusWaitTimeoutMs

        fun assistantFocusPollMs(profile: VendorProfile = VendorProfile.current()): Long =
            profile.focusPollMs

        /** BACK only while the assistant is the top window — never at the app underneath. */
        fun shouldPressDismissBack(topPackage: String?, ownPackage: String): Boolean =
            isAssistantTopWindow(topPackage, ownPackage)

        fun shouldWaitUntilCopilotGone(action: TargetAction): Boolean {
            return action != TargetAction.HWCTS
        }

        fun canDismissCopilotBack(
            actionLaunched: Boolean,
            backPressCount: Int,
            copilotPresent: Boolean,
            elapsedSinceLastBackMs: Long,
            maxBacks: Int = MAX_DISMISS_BACKS,
            minIntervalMs: Long = COPILOT_HAMMER_MIN_INTERVAL_MS,
            /** First BACK after a wake event: presence is already proven by the event. */
            assumePresent: Boolean = false,
        ): Boolean {
            if (actionLaunched) return false
            if (backPressCount >= maxBacks) return false
            if (!assumePresent && !copilotPresent) return false
            if (backPressCount > 0 && elapsedSinceLastBackMs < minIntervalMs) return false
            return true
        }

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

        fun isPowerLongPress(heldMs: Long, thresholdMs: Long = POWER_LONG_PRESS_MS): Boolean {
            return heldMs >= thresholdMs
        }

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
            return false
        }

        fun shouldPassThroughCameraKey(
            skipWhileCameraOpen: Boolean,
            foregroundPackage: String?,
            foregroundSinceMs: Long,
            nowMs: Long,
            ownPackage: String,
        ): Boolean {
            if (!skipWhileCameraOpen) return false
            if (!isCameraApp(foregroundPackage, null, ownPackage)) return false
            return (nowMs - foregroundSinceMs) >= CAMERA_FOREGROUND_GRACE_MS
        }

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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        InterceptorStateRepository.updateRunning(isRunning = false)
        InterceptorStateRepository.diag("SVC", "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        InterceptorStateRepository.updateRunning(isRunning = false)
        unregisterScreenOffReceiver()
        serviceScope.cancel()
        InterceptorStateRepository.diag("SVC", "destroyed")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val state = InterceptorStateRepository.serviceState.value
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

        if (state.cameraAction == TargetAction.NONE) {
            InterceptorStateRepository.diag("KEY", "rejected: cameraAction=NONE")
            return false
        }

        val ownPkg = ownPackageName()
        val now = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return consumingCameraKey
                if (shouldPassThroughCameraKey(state.skipCameraApp, foregroundPackage, foregroundSinceMs, now, ownPkg)) {
                    consumingCameraKey = false
                    InterceptorStateRepository.diag(
                        "KEY",
                        "rejected: camera app foreground for ${now - foregroundSinceMs}ms (grace=${CAMERA_FOREGROUND_GRACE_MS}ms)",
                    )
                    return false
                }
                // A second DOWN while a single-press action is still pending means this is a
                // double press. Cancel the pending action and stay out of the way: the system
                // opens the camera off its own double-click keycode, which we never match.
                val pending = pendingSingleActionJob
                if (pending != null) {
                    pending.cancel()
                    pendingSingleActionJob = null
                    suppressNextUpAction = true
                    InterceptorStateRepository.diag(
                        "KEY",
                        "double press: cancelled pending ${state.cameraAction}, camera left to system",
                    )
                }
                consumingCameraKey = true
                shutterDownEventTime = event.eventTime
                if (!suppressNextUpAction) {
                    InterceptorStateRepository.diag("KEY", "DOWN consumed, wait for UP")
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val wasConsuming = consumingCameraKey
                consumingCameraKey = false
                if (!wasConsuming) {
                    InterceptorStateRepository.diag("KEY", "UP ignored: no matching DOWN")
                    return false
                }
                val heldMs = event.eventTime - shutterDownEventTime
                if (suppressNextUpAction) {
                    suppressNextUpAction = false
                    InterceptorStateRepository.diag("KEY", "double press: no single action, held=${heldMs}ms")
                    return true
                }
                if (now - lastCameraInterceptMs < CAMERA_COOLDOWN_MS) {
                    InterceptorStateRepository.diag("KEY", "rejected: camera cooldown held=${heldMs}ms")
                    return true
                }
                // Defer, so a second press arriving inside DOUBLE_PRESS_WINDOW_MS can cancel this.
                val keyCode = event.keyCode
                pendingSingleActionJob = serviceScope.launch {
                    delay(DOUBLE_PRESS_WINDOW_MS.milliseconds)
                    pendingSingleActionJob = null
                    val firedAt = System.currentTimeMillis()
                    lastCameraInterceptMs = firedAt
                    InterceptorStateRepository.recordInterception("HardwareKey_$keyCode", firedAt)
                    val fired = ActionExecutionEngine.executeAction(
                        context = this@BlueLMInterceptorService,
                        action = state.cameraAction,
                        specificPackage = state.cameraSpecificPackage,
                        service = this@BlueLMInterceptorService,
                    )
                    InterceptorStateRepository.diag(
                        "KEY",
                        "fired ${state.cameraAction} single-press held=${heldMs}ms result=$fired",
                    )
                }
                return true
            }
            else -> return false
        }
    }

    private fun handlePowerKey(event: KeyEvent, state: InterceptorServiceState): Boolean {
        if (state.blueLMAction == TargetAction.NONE) return false
        if (!isScreenInteractive()) {
            InterceptorStateRepository.diag("KEY", "power ignored, screen off")
            return false
        }

        sawPowerKeyThisSession = true
        val keyCode = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    return consumingPowerKey
                }
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
            KeyEvent.ACTION_UP -> {
                if (!consumingPowerKey) return false
                pendingPowerLongJob?.cancel()
                pendingPowerLongJob = null
                consumingPowerKey = false
                val longFired = powerLongFired
                powerLongFired = false
                if (longFired) {
                    InterceptorStateRepository.diag("KEY", "power UP after long-press")
                    return true
                }
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
        val fired = ActionExecutionEngine.executeAction(
            context = this,
            action = current.blueLMAction,
            specificPackage = current.blueLMSpecificPackage,
            service = this,
        )
        InterceptorStateRepository.diag(
            "KEY",
            "fired ${current.blueLMAction} power-long held=${heldMs}ms result=$fired",
        )
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val ownPkg = ownPackageName()
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

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
            if (state.blueLMAction == TargetAction.NONE) {
                InterceptorStateRepository.diag("BLM", "rejected: blueLMAction=NONE pkg=$packageName")
                return
            }

            if (now - lastBlueLMInterceptMs < BLUELM_COOLDOWN_MS) {
                InterceptorStateRepository.diag(
                    "BLM",
                    "cooldown skip pkg=$packageName cls=$className",
                )
                return
            }

            lastBlueLMInterceptMs = now
            blueLMActionLaunched = false
            backPressCount = 0
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
                "copilot wake overlay=${!isLikelyActivityWindow(className)} " +
                    "action=${state.blueLMAction} pkg=$packageName cls=$className " +
                    "preAssist=${preAssistPackage ?: "-"}",
            )

            pendingBlueLMLaunchJob?.cancel()
            pendingBlueLMLaunchJob = serviceScope.launch {
                try {
                    val profile = VendorProfile.current()
                    val backBudget = copilotDismissBackBudget(state.blueLMAction, profile)
                    val pollMs = assistantPollMs(profile)
                    val hammerMs = assistantHammerMinIntervalMs(profile)
                    val focusWaitMs = assistantFocusWaitTimeoutMs(profile)
                    val focusPollMs = assistantFocusPollMs(profile)

                    // Wait until the assistant is top, then BACK. assumePresent still skips a
                    // second full window walk — it must not skip the top-window condition.
                    var assistantBecameTop = false
                    val focusDeadline = System.currentTimeMillis() + focusWaitMs
                    while (System.currentTimeMillis() <= focusDeadline) {
                        val snap = windowSnapshot()
                        InterceptorStateRepository.diag(
                            "BLM",
                            "focus windows=[${snap.packages.joinToString()}] " +
                                "top=${snap.topPackage ?: "-"}",
                        )
                        if (shouldPressDismissBack(snap.topPackage, ownPkg)) {
                            assistantBecameTop = true
                            if (backBudget > 0) {
                                dismissCopilotBack(
                                    reason = "initial",
                                    maxBacks = backBudget,
                                    minIntervalMs = hammerMs,
                                    assumePresent = true,
                                    knownTopPackage = snap.topPackage,
                                    knownPackages = snap.packages,
                                )
                            }
                            break
                        }
                        if (System.currentTimeMillis() + focusPollMs > focusDeadline) break
                        delay(focusPollMs.milliseconds)
                    }
                    if (!assistantBecameTop) {
                        InterceptorStateRepository.diag(
                            "BLM",
                            "assistant never top within ${focusWaitMs}ms, skip BACK",
                        )
                    } else if (shouldWaitUntilCopilotGone(state.blueLMAction)) {
                        val deadline = System.currentTimeMillis() + assistantDismissTimeoutMs(profile)
                        while (System.currentTimeMillis() < deadline) {
                            delay(pollMs.milliseconds)
                            val snap = windowSnapshot()
                            InterceptorStateRepository.diag(
                                "BLM",
                                "poll windows=[${snap.packages.joinToString()}] " +
                                    "top=${snap.topPackage ?: "-"}",
                            )
                            if (isDismissOvershoot(snap.topPackage, preAssistPackage, ownPkg)) {
                                InterceptorStateRepository.diag(
                                    "BLM",
                                    "stop BACK: overshoot top=${snap.topPackage} " +
                                        "preAssist=${preAssistPackage ?: "-"}",
                                )
                                break
                            }
                            if (!shouldPressDismissBack(snap.topPackage, ownPkg)) {
                                InterceptorStateRepository.diag(
                                    "BLM",
                                    "stop BACK: assistant gone top=${snap.topPackage ?: "-"}",
                                )
                                break
                            }
                            dismissCopilotBack(
                                reason = "poll",
                                maxBacks = backBudget,
                                minIntervalMs = hammerMs,
                                assumePresent = true,
                                knownTopPackage = snap.topPackage,
                                knownPackages = snap.packages,
                            )
                        }
                        val settleMs = CircleToSearch.extraSettleMs(state.blueLMAction)
                        if (settleMs > 0) delay(settleMs.milliseconds)
                    }

                    val endSnap = windowSnapshot()
                    if (isAssistantTopWindow(endSnap.topPackage, ownPkg)) {
                        InterceptorStateRepository.diag(
                            "BLM",
                            "assistant still top after $backPressCount backs " +
                                "top=${endSnap.topPackage}",
                        )
                    }
                    fireBlueLMAction(state)
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

    private fun topWindowPackage(): String? = windowSnapshot().topPackage

    private fun dismissCopilotBack(
        reason: String,
        maxBacks: Int = MAX_DISMISS_BACKS,
        minIntervalMs: Long = COPILOT_HAMMER_MIN_INTERVAL_MS,
        assumePresent: Boolean = false,
        knownTopPackage: String? = null,
        knownPackages: List<String?>? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        val snap = when {
            knownTopPackage != null || knownPackages != null -> WindowSnapshot(
                topPackage = knownTopPackage ?: knownPackages?.firstOrNull { !it.isNullOrEmpty() },
                packages = knownPackages.orEmpty(),
            )
            assumePresent -> null
            else -> windowSnapshot()
        }
        val present = assumePresent || isAssistantTopWindow(snap?.topPackage, ownPackageName())
        if (!canDismissCopilotBack(
                actionLaunched = blueLMActionLaunched,
                backPressCount = backPressCount,
                copilotPresent = present,
                elapsedSinceLastBackMs = now - lastCopilotHammerMs,
                maxBacks = maxBacks,
                minIntervalMs = minIntervalMs,
                assumePresent = assumePresent,
            )
        ) {
            if (!present && !assumePresent) {
                InterceptorStateRepository.diag(
                    "BLM",
                    "skip BACK ($reason): assistant not top top=${snap?.topPackage ?: "-"}",
                )
            }
            return false
        }
        lastCopilotHammerMs = now
        backPressCount++
        val backOk = performGlobalAction(GLOBAL_ACTION_BACK)
        val windowsDetail = when {
            assumePresent -> "assumePresent=true"
            snap != null -> "windows=[${snap.packages.joinToString()}] top=${snap.topPackage ?: "-"}"
            else -> ""
        }
        InterceptorStateRepository.diag(
            "BLM",
            "BACK=$backOk reason=$reason count=$backPressCount $windowsDetail".trim(),
        )
        return backOk
    }

    private suspend fun fireBlueLMAction(state: InterceptorServiceState) {
        InterceptorStateRepository.diag(
            "CTS",
            "top=${topWindowPackage()} backs=$backPressCount",
        )
        val fired = ActionExecutionEngine.executeAction(
            context = this,
            action = state.blueLMAction,
            specificPackage = state.blueLMSpecificPackage,
            service = this,
        )
        InterceptorStateRepository.diag("BLM", "launched ${state.blueLMAction} result=$fired")
        if (fired) {
            blueLMActionLaunched = true
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
