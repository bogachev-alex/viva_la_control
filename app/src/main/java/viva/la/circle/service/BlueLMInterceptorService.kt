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
import viva.la.circle.engine.VendorProfile
import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
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
                InterceptorStateRepository.diag("FG", "screen-off, cleared foreground")
            }
        }
    }

    companion object {
        const val BLUELM_COOLDOWN_MS = 1500L
        const val DOUBLE_PRESS_WINDOW_MS = KeyRemapPolicy.DOUBLE_PRESS_WINDOW_MS
        const val POWER_LONG_PRESS_MS = KeyRemapPolicy.POWER_LONG_PRESS_MS
        const val CAMERA_COOLDOWN_MS = KeyRemapPolicy.CAMERA_COOLDOWN_MS
        const val CAMERA_FOREGROUND_GRACE_MS = KeyRemapPolicy.CAMERA_FOREGROUND_GRACE_MS
        const val MAX_DISMISS_BACKS = 3
        const val HWCTS_DISMISS_BACKS = RemapSession.HWCTS_DISMISS_BACKS
        const val COPILOT_HAMMER_MIN_INTERVAL_MS = 40L

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
            return false
        }

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
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return true
        return pm.isInteractive
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

            if (now - lastBlueLMInterceptMs < BLUELM_COOLDOWN_MS) {
                InterceptorStateRepository.diag(
                    "BLM",
                    "cooldown skip pkg=$packageName cls=$className",
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
