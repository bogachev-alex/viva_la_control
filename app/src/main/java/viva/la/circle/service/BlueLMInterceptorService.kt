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
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.engine.CircleToSearch
import viva.la.circle.model.TargetAction
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

    @Volatile
    private var foregroundSinceMs: Long = 0L

    @Volatile
    private var blueLMActionLaunched = false
    private var backPressCount = 0

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                foregroundPackage = null
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

        val KNOWN_PACKAGES = setOf(
            "com.vivo.agent",
            "com.vivo.vpa",
            "com.bbk.voiceassistant",
            "com.vivo.ai.copilot",
            "com.vivo.blue.assistant",
            "com.vivo.bluelm",
        )

        val KNOWN_CAMERA_PACKAGES = setOf(
            "com.vivo.camera",
            "com.android.camera",
            "com.vivo.media.camera",
            "com.android.camera2",
            "com.google.android.googlecamera",
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

        fun isBlueLMOrVivoAssistant(packageName: String?, className: String?, ownPackageName: String = ""): Boolean {
            val pkg = packageName?.lowercase() ?: ""
            if (pkg.isEmpty()) return false

            if (ownPackageName.isNotEmpty() && ((pkg == ownPackageName.lowercase()) || pkg.startsWith("viva.la.circle"))) {
                return false
            }

            if (KNOWN_PACKAGES.contains(pkg)) {
                return true
            }

            return pkg.contains("bluelm") ||
                pkg.contains("jovi") ||
                pkg.contains("vivoassistant") ||
                pkg.contains("bbk.voiceassistant") ||
                (pkg.contains("vivo") && pkg.contains("agent"))
        }

        /**
         * Copilot 5.6.x settings / gallery / circle-to-search must not be remapped.
         * Power-button UI is FloatService overlays plus EmptyLauncher / chat.
         */
        fun isCopilotSecondaryUi(className: String?): Boolean {
            val cls = className.orEmpty()
            if (cls.isEmpty()) return false
            return cls.contains(".settings.") ||
                cls.contains("circletosearch") ||
                cls.contains(".photos.ui") ||
                cls.contains("PrivacyPolicy") ||
                cls.contains("UserPolicy") ||
                cls.contains("AboutActivity") ||
                cls.contains("FeedBackDialog")
        }

        fun isCopilotWakeUi(packageName: String?, className: String?, ownPackageName: String = ""): Boolean {
            if (!isBlueLMOrVivoAssistant(packageName, className, ownPackageName)) return false
            return !isCopilotSecondaryUi(className)
        }

        fun containsCopilotWindow(packageNames: List<String?>, ownPackage: String): Boolean =
            packageNames.any { isBlueLMOrVivoAssistant(it, null, ownPackage) }

        fun copilotDismissBackBudget(action: TargetAction): Int {
            return if (action == TargetAction.HWCTS) HWCTS_DISMISS_BACKS else MAX_DISMISS_BACKS
        }

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
        ): Boolean {
            if (actionLaunched) return false
            if (backPressCount >= maxBacks) return false
            if (!copilotPresent) return false
            if (elapsedSinceLastBackMs < minIntervalMs) return false
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
            return true
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
        if (isLikelyActivityWindow(className) && packageName != foregroundPackage) {
            foregroundPackage = packageName
            foregroundSinceMs = now
            InterceptorStateRepository.diag("FG", "pkg=$packageName cls=$className")
        }

        if (isCopilotWakeUi(packageName, className, ownPkg)) {
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
                    "action=${state.blueLMAction} pkg=$packageName cls=$className",
            )

            pendingBlueLMLaunchJob?.cancel()
            pendingBlueLMLaunchJob = serviceScope.launch {
                try {
                    val backBudget = copilotDismissBackBudget(state.blueLMAction)
                    if (backBudget > 0) {
                        dismissCopilotBack("initial", backBudget)
                    }
                    if (shouldWaitUntilCopilotGone(state.blueLMAction)) {
                        val deadline = System.currentTimeMillis() + COPILOT_DISMISS_TIMEOUT_MS
                        while (System.currentTimeMillis() < deadline) {
                            delay(COPILOT_POLL_MS.milliseconds)
                            if (!copilotWindowPresent()) break
                            dismissCopilotBack("poll", backBudget)
                        }
                        val settleMs = CircleToSearch.extraSettleMs(state.blueLMAction)
                        if (settleMs > 0) delay(settleMs.milliseconds)
                    }
                    fireBlueLMAction(state)
                } finally {
                    pendingBlueLMLaunchJob = null
                }
            }
        }
    }

    private fun copilotWindowPresent(): Boolean =
        containsCopilotWindow(windowPackageNames(), ownPackageName())

    private fun windowPackageNames(): List<String?> {
        val names = mutableListOf<String?>()
        val wins = try {
            windows
        } catch (_: Exception) {
            return names
        }
        for (window in wins) {
            var root: AccessibilityNodeInfo? = null
            try {
                root = window.root
                names.add(root?.packageName?.toString())
            } catch (_: Exception) {
                names.add(null)
            } finally {
                @Suppress("DEPRECATION")
                try {
                    root?.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return names
    }

    private fun topWindowPackage(): String? =
        windowPackageNames().firstOrNull { !it.isNullOrEmpty() }

    private fun dismissCopilotBack(reason: String, maxBacks: Int = MAX_DISMISS_BACKS): Boolean {
        val now = System.currentTimeMillis()
        val present = copilotWindowPresent()
        if (!canDismissCopilotBack(
                actionLaunched = blueLMActionLaunched,
                backPressCount = backPressCount,
                copilotPresent = present,
                elapsedSinceLastBackMs = now - lastCopilotHammerMs,
                maxBacks = maxBacks,
            )
        ) {
            if (!present) {
                InterceptorStateRepository.diag("BLM", "skip BACK ($reason): copilot gone")
            }
            return false
        }
        lastCopilotHammerMs = now
        backPressCount++
        val backOk = performGlobalAction(GLOBAL_ACTION_BACK)
        InterceptorStateRepository.diag("BLM", "BACK=$backOk reason=$reason count=$backPressCount")
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
