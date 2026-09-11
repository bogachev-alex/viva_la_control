package viva.la.circle.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import viva.la.circle.engine.OriginOs
import viva.la.circle.engine.VendorProfile
import viva.la.circle.gesture.GestureHandleConfig
import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

data class DiagEvent(
    val timeMs: Long,
    val kind: String,
    val detail: String,
)

data class InterceptorServiceState(
    val isRunning: Boolean = false,
    val isEnabledInSettings: Boolean = false,
    val lastInterceptedTime: Long = 0L,
    val interceptedCount: Int = 0,
    val lastInterceptedPackage: String? = null,
    val blueLMAction: TargetAction? = null,
    val blueLMSpecificPackage: String? = null,
    val cameraAction: TargetAction = TargetAction.NONE,
    val cameraSpecificPackage: String? = null,
    val skipCameraApp: Boolean = true,
    val diagnosticsEnabled: Boolean = false,
    val captureMode: Boolean = false,
    val captureTarget: KeyCaptureTarget = KeyCaptureTarget.CAMERA,
    val cameraKeyCodes: Set<Int> = emptySet(),
    val detectedOsLabel: String = "",
    val detectedVendorId: String = "",
    val huaweiAppLaunchAcknowledged: Boolean = false,
    val gestureHandleEnabled: Boolean = false,
    val gestureHandleOpacity: Int = GestureHandleConfig.DEFAULT_OPACITY_PERCENT,
    val gestureTapAction: TargetAction = GestureHandleConfig.DEFAULT_TAP_ACTION,
    val gestureTapSpecificPackage: String? = null,
    val gestureLongPressAction: TargetAction = GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION,
    val gestureLongPressSpecificPackage: String? = null,
    val gestureSwipeUpAction: TargetAction = GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION,
    val gestureSwipeUpSpecificPackage: String? = null,
)

enum class KeyCaptureTarget { CAMERA }

object InterceptorStateRepository {
    private const val PREFS_NAME = "bluelm_interceptor_prefs"
    private const val KEY_BLUELM_ACTION = "key_bluelm_action"
    private const val KEY_BLUELM_SPECIFIC_PKG = "key_bluelm_specific_pkg"
    private const val KEY_CAMERA_ACTION = "key_camera_action"
    private const val KEY_CAMERA_SPECIFIC_PKG = "key_camera_specific_pkg"
    private const val KEY_SKIP_CAMERA_APP = "key_skip_camera_app"
    private const val KEY_CAMERA_KEY_CODES = "key_camera_key_codes"
    private const val KEY_HUAWEI_APP_LAUNCH_ACKED = "key_huawei_app_launch_acked"
    private const val KEY_GESTURE_HANDLE_ENABLED = "key_gesture_handle_enabled"
    private const val KEY_GESTURE_HANDLE_OPACITY = "key_gesture_handle_opacity"
    private const val KEY_GESTURE_TAP_ACTION = "key_gesture_tap_action"
    private const val KEY_GESTURE_TAP_PKG = "key_gesture_tap_pkg"
    private const val KEY_GESTURE_LONG_PRESS_ACTION = "key_gesture_long_press_action"
    private const val KEY_GESTURE_LONG_PRESS_PKG = "key_gesture_long_press_pkg"
    private const val KEY_GESTURE_SWIPE_UP_ACTION = "key_gesture_swipe_up_action"
    private const val KEY_GESTURE_SWIPE_UP_PKG = "key_gesture_swipe_up_pkg"
    private const val STALE_TEST_PACKAGE = "com.tosharoki.hwcts"
    private const val DIAG_CAP = 200

    private val _serviceState = MutableStateFlow(InterceptorServiceState())
    val serviceState: StateFlow<InterceptorServiceState> = _serviceState.asStateFlow()

    private val diagLock = Any()
    private val diagEvents = ArrayDeque<DiagEvent>(DIAG_CAP)
    private val _diagLog = MutableStateFlow<List<DiagEvent>>(emptyList())
    val diagLog: StateFlow<List<DiagEvent>> = _diagLog.asStateFlow()

    fun loadFromPreferences(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val blueLMActionStr = prefs.getString(KEY_BLUELM_ACTION, null)
            val rawBlueLMSpecificPkg = prefs.getString(KEY_BLUELM_SPECIFIC_PKG, null)
            val cameraActionStr = prefs.getString(KEY_CAMERA_ACTION, TargetAction.NONE.name)
            val rawCameraSpecificPkg = prefs.getString(KEY_CAMERA_SPECIFIC_PKG, null)
            val skipCameraApp = prefs.getBoolean(KEY_SKIP_CAMERA_APP, true)
            val cameraKeyCodes = parseKeyCodes(prefs.getString(KEY_CAMERA_KEY_CODES, null))
            val blueLMSpecificPkg = sanitizeTestPackage(rawBlueLMSpecificPkg)
            val cameraSpecificPkg = sanitizeTestPackage(rawCameraSpecificPkg)
            val blueLMAction = BlueLMActionConfig.decodeStored(blueLMActionStr)
            val prefsEditor = prefs.edit()
            var prefsDirty = false
            if (blueLMSpecificPkg != rawBlueLMSpecificPkg || cameraSpecificPkg != rawCameraSpecificPkg) {
                prefsEditor
                    .putString(KEY_BLUELM_SPECIFIC_PKG, blueLMSpecificPkg)
                    .putString(KEY_CAMERA_SPECIFIC_PKG, cameraSpecificPkg)
                prefsDirty = true
            }
            if (BlueLMActionConfig.shouldMigrateDefaultAssistant(blueLMActionStr)) {
                prefsEditor.remove(KEY_BLUELM_ACTION)
                prefsDirty = true
            }
            if (prefsDirty) {
                prefsEditor.apply()
            }
            val profile = VendorProfile.current()
            val detection = OriginOs.detect()
            val emuiVersion = OriginOs.readProp("ro.build.version.emui")
            val harmonyVersion = OriginOs.readProp("ro.build.version.harmonyos")
            val osLabel = VendorProfile.osLabel(
                profile = profile,
                vivoLabel = detection.label,
                emuiVersion = emuiVersion,
                harmonyVersion = harmonyVersion,
            )

            _serviceState.update {
                it.copy(
                    blueLMAction = blueLMAction,
                    blueLMSpecificPackage = blueLMSpecificPkg,
                    cameraAction = TargetAction.fromName(cameraActionStr, TargetAction.NONE),
                    cameraSpecificPackage = cameraSpecificPkg,
                    skipCameraApp = skipCameraApp,
                    cameraKeyCodes = cameraKeyCodes,
                    detectedOsLabel = osLabel,
                    detectedVendorId = profile.id,
                    huaweiAppLaunchAcknowledged = prefs.getBoolean(KEY_HUAWEI_APP_LAUNCH_ACKED, false),
                    gestureHandleEnabled = prefs.getBoolean(KEY_GESTURE_HANDLE_ENABLED, false),
                    gestureHandleOpacity = GestureHandleConfig.clampOpacity(
                        prefs.getInt(
                            KEY_GESTURE_HANDLE_OPACITY,
                            GestureHandleConfig.DEFAULT_OPACITY_PERCENT,
                        ),
                    ),
                    gestureTapAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_TAP_ACTION, null),
                        GestureHandleConfig.DEFAULT_TAP_ACTION,
                    ),
                    gestureTapSpecificPackage = sanitizeTestPackage(
                        prefs.getString(KEY_GESTURE_TAP_PKG, null),
                    ),
                    gestureLongPressAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_LONG_PRESS_ACTION, null),
                        GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION,
                    ),
                    gestureLongPressSpecificPackage = sanitizeTestPackage(
                        prefs.getString(KEY_GESTURE_LONG_PRESS_PKG, null),
                    ),
                    gestureSwipeUpAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_SWIPE_UP_ACTION, null),
                        GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION,
                    ),
                    gestureSwipeUpSpecificPackage = sanitizeTestPackage(
                        prefs.getString(KEY_GESTURE_SWIPE_UP_PKG, null),
                    ),
                )
            }
        } catch (_: Exception) {
            // Context might not be available in test environments
        }
    }

    fun setGestureHandleEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHandleEnabled = enabled) }
        persistBoolean(context, KEY_GESTURE_HANDLE_ENABLED, enabled)
    }

    fun setGestureHandleOpacity(context: Context? = null, opacityPercent: Int) {
        val clamped = GestureHandleConfig.clampOpacity(opacityPercent)
        _serviceState.update { it.copy(gestureHandleOpacity = clamped) }
        if (context != null) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_GESTURE_HANDLE_OPACITY, clamped)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    fun setGestureTapAction(
        context: Context? = null,
        action: TargetAction,
        specificPackage: String? = null,
    ) {
        _serviceState.update {
            it.copy(gestureTapAction = action, gestureTapSpecificPackage = specificPackage)
        }
        persistGestureSlot(context, KEY_GESTURE_TAP_ACTION, KEY_GESTURE_TAP_PKG, action, specificPackage)
    }

    fun setGestureLongPressAction(
        context: Context? = null,
        action: TargetAction,
        specificPackage: String? = null,
    ) {
        _serviceState.update {
            it.copy(
                gestureLongPressAction = action,
                gestureLongPressSpecificPackage = specificPackage,
            )
        }
        persistGestureSlot(
            context,
            KEY_GESTURE_LONG_PRESS_ACTION,
            KEY_GESTURE_LONG_PRESS_PKG,
            action,
            specificPackage,
        )
    }

    fun setGestureSwipeUpAction(
        context: Context? = null,
        action: TargetAction,
        specificPackage: String? = null,
    ) {
        _serviceState.update {
            it.copy(
                gestureSwipeUpAction = action,
                gestureSwipeUpSpecificPackage = specificPackage,
            )
        }
        persistGestureSlot(
            context,
            KEY_GESTURE_SWIPE_UP_ACTION,
            KEY_GESTURE_SWIPE_UP_PKG,
            action,
            specificPackage,
        )
    }

    private fun persistGestureSlot(
        context: Context?,
        actionKey: String,
        pkgKey: String,
        action: TargetAction,
        specificPackage: String?,
    ) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(actionKey, action.name)
                .putString(pkgKey, specificPackage)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun setBlueLMAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update {
            it.copy(
                blueLMAction = action,
                blueLMSpecificPackage = specificPackage,
            )
        }
        if (context != null) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_BLUELM_ACTION, action.name)
                    .putString(KEY_BLUELM_SPECIFIC_PKG, specificPackage)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    fun persistChooserSelection(context: Context? = null, packageName: String) {
        setBlueLMAction(
            context = context,
            action = TargetAction.SPECIFIC_APP,
            specificPackage = packageName,
        )
    }

    fun setSkipCameraApp(context: Context? = null, skip: Boolean) {
        _serviceState.update { it.copy(skipCameraApp = skip) }
        persistBoolean(context, KEY_SKIP_CAMERA_APP, skip)
    }

    fun setDiagnosticsEnabled(enabled: Boolean) {
        _serviceState.update { it.copy(diagnosticsEnabled = enabled) }
        if (enabled) publishDiagSnapshot()
    }

    fun setCaptureMode(enabled: Boolean, target: KeyCaptureTarget = KeyCaptureTarget.CAMERA) {
        _serviceState.update { current ->
            current.copy(
                captureMode = enabled,
                captureTarget = if (enabled) target else current.captureTarget,
                diagnosticsEnabled = if (enabled) true else current.diagnosticsEnabled,
            )
        }
        if (enabled) publishDiagSnapshot()
        val targetLabel = "shutter / grip"
        diag("CAPTURE", if (enabled) "armed for $targetLabel — press it now" else "disarmed")
    }

    fun setHuaweiAppLaunchAcknowledged(context: Context? = null, acknowledged: Boolean) {
        _serviceState.update { it.copy(huaweiAppLaunchAcknowledged = acknowledged) }
        persistBoolean(context, KEY_HUAWEI_APP_LAUNCH_ACKED, acknowledged)
    }

    fun addCameraKeyCode(context: Context? = null, keyCode: Int) {
        val nextCamera = _serviceState.value.cameraKeyCodes + keyCode
        _serviceState.update { it.copy(cameraKeyCodes = nextCamera) }
        persistCameraKeyCodes(context, nextCamera)
        diag("CAPTURE", "learned camera key $keyCode")
    }

    fun removeCameraKeyCode(context: Context? = null, keyCode: Int) {
        val next = _serviceState.value.cameraKeyCodes - keyCode
        _serviceState.update { it.copy(cameraKeyCodes = next) }
        persistCameraKeyCodes(context, next)
    }

    fun setCameraAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update {
            it.copy(
                cameraAction = action,
                cameraSpecificPackage = specificPackage,
            )
        }
        if (context != null) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CAMERA_ACTION, action.name)
                    .putString(KEY_CAMERA_SPECIFIC_PKG, specificPackage)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    fun updateRunning(isRunning: Boolean) {
        _serviceState.update { it.copy(isRunning = isRunning) }
    }

    fun updateEnabledStatus(context: Context) {
        loadFromPreferences(context)
        val enabled = isAccessibilityServiceEnabled(context)
        _serviceState.update { it.copy(isEnabledInSettings = enabled) }
    }

    fun recordInterception(packageName: String, timeMillis: Long = System.currentTimeMillis()) {
        _serviceState.update {
            it.copy(
                lastInterceptedTime = timeMillis,
                interceptedCount = it.interceptedCount + 1,
                lastInterceptedPackage = packageName,
            )
        }
    }

    fun diag(kind: String, detail: String, force: Boolean = false) {
        val state = _serviceState.value
        if (!force && !state.diagnosticsEnabled && !state.captureMode) return
        val event = DiagEvent(System.currentTimeMillis(), kind, detail)
        synchronized(diagLock) {
            if (diagEvents.size >= DIAG_CAP) {
                diagEvents.removeFirst()
            }
            diagEvents.addLast(event)
            if (_diagLog.subscriptionCount.value > 0) {
                _diagLog.value = diagEvents.toList()
            }
        }
    }

    fun diagSnapshot(): List<DiagEvent> {
        synchronized(diagLock) {
            return diagEvents.toList()
        }
    }

    fun clearDiag() {
        synchronized(diagLock) {
            diagEvents.clear()
            _diagLog.value = emptyList()
        }
    }

    fun reset() {
        _serviceState.value = InterceptorServiceState()
        clearDiag()
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedService = ComponentName(context, BlueLMInterceptorService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedService.flattenToString(), ignoreCase = true) ||
                componentName.equals(expectedService.flattenToShortString(), ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun parseKeyCodes(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    fun sanitizeTestPackage(pkg: String?): String? {
        return pkg?.takeUnless { it == STALE_TEST_PACKAGE }
    }

    private fun persistCameraKeyCodes(context: Context?, codes: Set<Int>) {
        persistKeyCodes(context, KEY_CAMERA_KEY_CODES, codes)
    }

    private fun persistKeyCodes(context: Context?, key: String, codes: Set<Int>) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, codes.sorted().joinToString(","))
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun persistBoolean(context: Context?, key: String, value: Boolean) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, value)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun publishDiagSnapshot() {
        synchronized(diagLock) {
            _diagLog.value = diagEvents.toList()
        }
    }
}
