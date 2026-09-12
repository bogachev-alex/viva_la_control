package viva.la.circle.service

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.text.TextUtils
import viva.la.circle.engine.OriginOs
import viva.la.circle.engine.VendorProfile
import viva.la.circle.gesture.GestureHandleConfig
import viva.la.circle.gesture.GestureNavEvent
import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.VolumeKeyPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    val volumeSkipTracksEnabled: Boolean = false,
    val volumeShortRemapEnabled: Boolean = false,
    val volumeLongPressMs: Long = VolumeKeyPolicy.DEFAULT_LONG_PRESS_MS,
    val volumeHapticEnabled: Boolean = true,
    /** Runtime status: system volume long-press hook is registered (ADB permission granted). */
    val volumeLongPressListenerActive: Boolean = false,
    val volumeUpShortAction: VolumeShortAction = VolumeShortAction.Volume,
    val volumeDownShortAction: VolumeShortAction = VolumeShortAction.Volume,
    val gestureHandleEnabled: Boolean = false,
    val gestureHandleOpacity: Int = GestureHandleConfig.DEFAULT_OPACITY_PERCENT,
    val gestureHapticTap: Boolean = true,
    val gestureHapticLongPress: Boolean = true,
    val gestureHapticSwipeUp: Boolean = true,
    val gestureHapticSwipeLeft: Boolean = true,
    val gestureHapticSwipeRight: Boolean = true,
    val gestureHapticBack: Boolean = true,
    val gestureHapticRecents: Boolean = true,
    val gestureHandleHideInFullscreen: Boolean = true,
    val gesturePillColorArgb: Int = GestureHandleConfig.DEFAULT_COLOR_ARGB,
    val gesturePillWidthDp: Int = GestureHandleConfig.DEFAULT_WIDTH_DP,
    val gesturePillHeightDp: Int = GestureHandleConfig.DEFAULT_HEIGHT_DP,
    val gestureBottomOffsetDp: Int = GestureHandleConfig.DEFAULT_BOTTOM_OFFSET_DP,
    val gestureTapAction: TargetAction = GestureHandleConfig.DEFAULT_TAP_ACTION,
    val gestureTapSpecificPackage: String? = null,
    val gestureLongPressAction: TargetAction = GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION,
    val gestureLongPressSpecificPackage: String? = null,
    val gestureSwipeUpAction: TargetAction = GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION,
    val gestureSwipeUpSpecificPackage: String? = null,
    val gestureSwipeLeftAction: TargetAction = GestureHandleConfig.DEFAULT_SWIPE_LEFT_ACTION,
    val gestureSwipeLeftSpecificPackage: String? = null,
    val gestureSwipeRightAction: TargetAction = GestureHandleConfig.DEFAULT_SWIPE_RIGHT_ACTION,
    val gestureSwipeRightSpecificPackage: String? = null,
) {
    fun gestureHapticEnabled(event: GestureNavEvent): Boolean = when (event) {
        GestureNavEvent.Tap -> gestureHapticTap
        GestureNavEvent.LongPress -> gestureHapticLongPress
        GestureNavEvent.SwipeUp -> gestureHapticSwipeUp
        GestureNavEvent.SwipeLeft -> gestureHapticSwipeLeft
        GestureNavEvent.SwipeRight -> gestureHapticSwipeRight
        GestureNavEvent.Back -> gestureHapticBack
        GestureNavEvent.Recents -> gestureHapticRecents
    }
}

enum class KeyCaptureTarget { CAMERA }

object InterceptorStateRepository {
    private const val PREFS_NAME = "bluelm_interceptor_prefs"
    private const val KEY_BLUELM_ACTION = "key_bluelm_action"
    private const val KEY_BLUELM_SPECIFIC_PKG = "key_bluelm_specific_pkg"
    private const val KEY_CAMERA_ACTION = "key_camera_action"
    private const val KEY_CAMERA_SPECIFIC_PKG = "key_camera_specific_pkg"
    private const val KEY_SKIP_CAMERA_APP = "key_skip_camera_app"
    private const val KEY_DIAGNOSTICS_ENABLED = "key_diagnostics_enabled"
    private const val KEY_CAMERA_KEY_CODES = "key_camera_key_codes"
    private const val KEY_HUAWEI_APP_LAUNCH_ACKED = "key_huawei_app_launch_acked"
    private const val KEY_VOLUME_SKIP_TRACKS = "key_volume_skip_tracks"
    private const val KEY_VOLUME_SHORT_REMAP = "key_volume_short_remap"
    private const val KEY_VOLUME_LONG_PRESS_MS = "key_volume_long_press_ms"
    private const val KEY_VOLUME_HAPTIC = "key_volume_haptic"
    private const val KEY_VOLUME_UP_SHORT = "key_volume_up_short"
    private const val KEY_VOLUME_UP_SHORT_PKG = "key_volume_up_short_pkg"
    private const val KEY_VOLUME_DOWN_SHORT = "key_volume_down_short"
    private const val KEY_VOLUME_DOWN_SHORT_PKG = "key_volume_down_short_pkg"
    private const val KEY_GESTURE_HANDLE_ENABLED = "key_gesture_handle_enabled"
    private const val KEY_GESTURE_HANDLE_OPACITY = "key_gesture_handle_opacity"
    /** Legacy master switch; migrates into per-gesture keys when those are absent. */
    private const val KEY_GESTURE_HANDLE_HAPTIC = "key_gesture_handle_haptic"
    private const val KEY_GESTURE_HAPTIC_TAP = "key_gesture_haptic_tap"
    private const val KEY_GESTURE_HAPTIC_LONG_PRESS = "key_gesture_haptic_long_press"
    private const val KEY_GESTURE_HAPTIC_SWIPE_UP = "key_gesture_haptic_swipe_up"
    private const val KEY_GESTURE_HAPTIC_SWIPE_LEFT = "key_gesture_haptic_swipe_left"
    private const val KEY_GESTURE_HAPTIC_SWIPE_RIGHT = "key_gesture_haptic_swipe_right"
    private const val KEY_GESTURE_HAPTIC_BACK = "key_gesture_haptic_back"
    private const val KEY_GESTURE_HAPTIC_RECENTS = "key_gesture_haptic_recents"
    private const val KEY_GESTURE_HANDLE_HIDE_FULLSCREEN = "key_gesture_handle_hide_fullscreen"
    private const val KEY_GESTURE_PILL_COLOR = "key_gesture_pill_color"
    private const val KEY_GESTURE_PILL_WIDTH_DP = "key_gesture_pill_width_dp"
    private const val KEY_GESTURE_PILL_HEIGHT_DP = "key_gesture_pill_height_dp"
    private const val KEY_GESTURE_BOTTOM_OFFSET_DP = "key_gesture_bottom_offset_dp"
    private const val KEY_GESTURE_TAP_ACTION = "key_gesture_tap_action"
    private const val KEY_GESTURE_TAP_PKG = "key_gesture_tap_pkg"
    private const val KEY_GESTURE_LONG_PRESS_ACTION = "key_gesture_long_press_action"
    private const val KEY_GESTURE_LONG_PRESS_PKG = "key_gesture_long_press_pkg"
    private const val KEY_GESTURE_SWIPE_UP_ACTION = "key_gesture_swipe_up_action"
    private const val KEY_GESTURE_SWIPE_UP_PKG = "key_gesture_swipe_up_pkg"
    private const val KEY_GESTURE_SWIPE_LEFT_ACTION = "key_gesture_swipe_left_action"
    private const val KEY_GESTURE_SWIPE_LEFT_PKG = "key_gesture_swipe_left_pkg"
    private const val KEY_GESTURE_SWIPE_RIGHT_ACTION = "key_gesture_swipe_right_action"
    private const val KEY_GESTURE_SWIPE_RIGHT_PKG = "key_gesture_swipe_right_pkg"
    private const val STALE_TEST_PACKAGE = "com.tosharoki.hwcts"
    private const val DIAG_CAP = 200

    private val _serviceState = MutableStateFlow(InterceptorServiceState())
    val serviceState: StateFlow<InterceptorServiceState> = _serviceState.asStateFlow()

    private val diagLock = Any()
    private val diagEvents = ArrayDeque<DiagEvent>(DIAG_CAP)
    private val _diagLog = MutableStateFlow<List<DiagEvent>>(emptyList())
    val diagLog: StateFlow<List<DiagEvent>> = _diagLog.asStateFlow()

    // Mirror of the in-memory log on disk (files/diag.log). This ROM drops third-party logcat
    // output entirely and reading the UI with uiautomator restarts accessibility services, so a
    // file pulled over `adb run-as` is the only way to see what the service did after the fact.
    private const val DIAG_FILE = "diag.log"
    private const val DIAG_FILE_MAX_BYTES = 512 * 1024L
    @Volatile
    private var diagDir: File? = null
    private val diagWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-file") }
    private val diagTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun loadFromPreferences(context: Context) {
        diagDir = context.applicationContext.filesDir
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
            if (blueLMSpecificPkg != rawBlueLMSpecificPkg || cameraSpecificPkg != rawCameraSpecificPkg) {
                prefs.edit()
                    .putString(KEY_BLUELM_SPECIFIC_PKG, blueLMSpecificPkg)
                    .putString(KEY_CAMERA_SPECIFIC_PKG, cameraSpecificPkg)
                    .apply()
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
                    diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, it.diagnosticsEnabled),
                    cameraKeyCodes = cameraKeyCodes,
                    detectedOsLabel = osLabel,
                    detectedVendorId = profile.id,
                    huaweiAppLaunchAcknowledged = prefs.getBoolean(KEY_HUAWEI_APP_LAUNCH_ACKED, false),
                    volumeSkipTracksEnabled = prefs.getBoolean(KEY_VOLUME_SKIP_TRACKS, false),
                    volumeShortRemapEnabled = prefs.getBoolean(KEY_VOLUME_SHORT_REMAP, false),
                    volumeLongPressMs = VolumeKeyPolicy.normalizeTimeoutMs(
                        prefs.getLong(KEY_VOLUME_LONG_PRESS_MS, VolumeKeyPolicy.DEFAULT_LONG_PRESS_MS),
                    ),
                    volumeHapticEnabled = prefs.getBoolean(KEY_VOLUME_HAPTIC, true),
                    volumeUpShortAction = VolumeShortAction.fromStored(
                        prefs.getString(KEY_VOLUME_UP_SHORT, VolumeShortAction.VOLUME_STORED),
                        sanitizeTestPackage(prefs.getString(KEY_VOLUME_UP_SHORT_PKG, null)),
                    ),
                    volumeDownShortAction = VolumeShortAction.fromStored(
                        prefs.getString(KEY_VOLUME_DOWN_SHORT, VolumeShortAction.VOLUME_STORED),
                        sanitizeTestPackage(prefs.getString(KEY_VOLUME_DOWN_SHORT_PKG, null)),
                    ),
                    gestureHandleEnabled = prefs.getBoolean(KEY_GESTURE_HANDLE_ENABLED, false),
                    gestureHandleOpacity = GestureHandleConfig.clampOpacity(
                        prefs.getInt(KEY_GESTURE_HANDLE_OPACITY, GestureHandleConfig.DEFAULT_OPACITY_PERCENT),
                    ),
                    gestureHapticTap = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_TAP,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticLongPress = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_LONG_PRESS,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticSwipeUp = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_SWIPE_UP,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticSwipeLeft = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_SWIPE_LEFT,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticSwipeRight = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_SWIPE_RIGHT,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticBack = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_BACK,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHapticRecents = prefsBoolOr(
                        prefs,
                        KEY_GESTURE_HAPTIC_RECENTS,
                        prefs.getBoolean(KEY_GESTURE_HANDLE_HAPTIC, true),
                    ),
                    gestureHandleHideInFullscreen = prefs.getBoolean(
                        KEY_GESTURE_HANDLE_HIDE_FULLSCREEN,
                        true,
                    ),
                    gesturePillColorArgb = GestureHandleConfig.normalizeColorArgb(
                        prefs.getInt(KEY_GESTURE_PILL_COLOR, GestureHandleConfig.DEFAULT_COLOR_ARGB),
                    ),
                    gesturePillWidthDp = GestureHandleConfig.clampWidthDp(
                        prefs.getInt(KEY_GESTURE_PILL_WIDTH_DP, GestureHandleConfig.DEFAULT_WIDTH_DP),
                    ),
                    gesturePillHeightDp = GestureHandleConfig.clampHeightDp(
                        prefs.getInt(KEY_GESTURE_PILL_HEIGHT_DP, GestureHandleConfig.DEFAULT_HEIGHT_DP),
                    ),
                    gestureBottomOffsetDp = GestureHandleConfig.clampBottomOffsetDp(
                        prefs.getInt(KEY_GESTURE_BOTTOM_OFFSET_DP, GestureHandleConfig.DEFAULT_BOTTOM_OFFSET_DP),
                    ),
                    gestureTapAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_TAP_ACTION, null),
                        GestureHandleConfig.DEFAULT_TAP_ACTION,
                    ),
                    gestureTapSpecificPackage = sanitizeTestPackage(prefs.getString(KEY_GESTURE_TAP_PKG, null)),
                    gestureLongPressAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_LONG_PRESS_ACTION, null),
                        GestureHandleConfig.DEFAULT_LONG_PRESS_ACTION,
                    ),
                    gestureLongPressSpecificPackage = sanitizeTestPackage(prefs.getString(KEY_GESTURE_LONG_PRESS_PKG, null)),
                    gestureSwipeUpAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_SWIPE_UP_ACTION, null),
                        GestureHandleConfig.DEFAULT_SWIPE_UP_ACTION,
                    ),
                    gestureSwipeUpSpecificPackage = sanitizeTestPackage(prefs.getString(KEY_GESTURE_SWIPE_UP_PKG, null)),
                    gestureSwipeLeftAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_SWIPE_LEFT_ACTION, null),
                        GestureHandleConfig.DEFAULT_SWIPE_LEFT_ACTION,
                    ),
                    gestureSwipeLeftSpecificPackage = sanitizeTestPackage(
                        prefs.getString(KEY_GESTURE_SWIPE_LEFT_PKG, null),
                    ),
                    gestureSwipeRightAction = TargetAction.fromName(
                        prefs.getString(KEY_GESTURE_SWIPE_RIGHT_ACTION, null),
                        GestureHandleConfig.DEFAULT_SWIPE_RIGHT_ACTION,
                    ),
                    gestureSwipeRightSpecificPackage = sanitizeTestPackage(
                        prefs.getString(KEY_GESTURE_SWIPE_RIGHT_PKG, null),
                    ),
                )
            }
        } catch (_: Exception) {
            // Context might not be available in test environments
        }
    }

    fun setVolumeSkipTracksEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(volumeSkipTracksEnabled = enabled) }
        persistBoolean(context, KEY_VOLUME_SKIP_TRACKS, enabled)
    }

    fun setVolumeShortRemapEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(volumeShortRemapEnabled = enabled) }
        persistBoolean(context, KEY_VOLUME_SHORT_REMAP, enabled)
    }

    fun setVolumeLongPressListenerActive(active: Boolean) {
        _serviceState.update { it.copy(volumeLongPressListenerActive = active) }
    }

    fun setVolumeLongPressMs(context: Context? = null, timeoutMs: Long) {
        val normalized = VolumeKeyPolicy.normalizeTimeoutMs(timeoutMs)
        _serviceState.update { it.copy(volumeLongPressMs = normalized) }
        if (context != null) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_VOLUME_LONG_PRESS_MS, normalized)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    fun setVolumeHapticEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(volumeHapticEnabled = enabled) }
        persistBoolean(context, KEY_VOLUME_HAPTIC, enabled)
    }

    fun setVolumeUpShortAction(context: Context? = null, action: VolumeShortAction) {
        _serviceState.update { it.copy(volumeUpShortAction = action) }
        persistVolumeShort(context, KEY_VOLUME_UP_SHORT, KEY_VOLUME_UP_SHORT_PKG, action)
    }

    fun setVolumeDownShortAction(context: Context? = null, action: VolumeShortAction) {
        _serviceState.update { it.copy(volumeDownShortAction = action) }
        persistVolumeShort(context, KEY_VOLUME_DOWN_SHORT, KEY_VOLUME_DOWN_SHORT_PKG, action)
    }

    private fun persistVolumeShort(
        context: Context?,
        nameKey: String,
        pkgKey: String,
        action: VolumeShortAction,
    ) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(nameKey, VolumeShortAction.toStoredName(action))
                .putString(pkgKey, VolumeShortAction.toStoredPackage(action))
                .apply()
        } catch (_: Exception) {
        }
    }


    fun setGestureHandleEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHandleEnabled = enabled) }
        persistBoolean(context, KEY_GESTURE_HANDLE_ENABLED, enabled)
    }

    fun setGestureHandleOpacity(context: Context? = null, opacityPercent: Int) {
        val clamped = GestureHandleConfig.clampOpacity(opacityPercent)
        _serviceState.update { it.copy(gestureHandleOpacity = clamped) }
        persistInt(context, KEY_GESTURE_HANDLE_OPACITY, clamped)
    }

    fun setGestureHapticTap(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticTap = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_TAP, enabled)
    }

    fun setGestureHapticLongPress(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticLongPress = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_LONG_PRESS, enabled)
    }

    fun setGestureHapticSwipeUp(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticSwipeUp = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_SWIPE_UP, enabled)
    }

    fun setGestureHapticSwipeLeft(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticSwipeLeft = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_SWIPE_LEFT, enabled)
    }

    fun setGestureHapticSwipeRight(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticSwipeRight = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_SWIPE_RIGHT, enabled)
    }

    fun setGestureHapticBack(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticBack = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_BACK, enabled)
    }

    fun setGestureHapticRecents(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHapticRecents = enabled) }
        persistBoolean(context, KEY_GESTURE_HAPTIC_RECENTS, enabled)
    }

    fun setGestureHandleHideInFullscreen(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(gestureHandleHideInFullscreen = enabled) }
        persistBoolean(context, KEY_GESTURE_HANDLE_HIDE_FULLSCREEN, enabled)
    }

    fun setGesturePillColorArgb(context: Context? = null, colorArgb: Int) {
        val normalized = GestureHandleConfig.normalizeColorArgb(colorArgb)
        _serviceState.update { it.copy(gesturePillColorArgb = normalized) }
        persistInt(context, KEY_GESTURE_PILL_COLOR, normalized)
    }

    fun setGesturePillWidthDp(context: Context? = null, widthDp: Int) {
        val clamped = GestureHandleConfig.clampWidthDp(widthDp)
        _serviceState.update { it.copy(gesturePillWidthDp = clamped) }
        persistInt(context, KEY_GESTURE_PILL_WIDTH_DP, clamped)
    }

    fun setGesturePillHeightDp(context: Context? = null, heightDp: Int) {
        val clamped = GestureHandleConfig.clampHeightDp(heightDp)
        _serviceState.update { it.copy(gesturePillHeightDp = clamped) }
        persistInt(context, KEY_GESTURE_PILL_HEIGHT_DP, clamped)
    }

    fun setGestureBottomOffsetDp(context: Context? = null, offsetDp: Int) {
        val clamped = GestureHandleConfig.clampBottomOffsetDp(offsetDp)
        _serviceState.update { it.copy(gestureBottomOffsetDp = clamped) }
        persistInt(context, KEY_GESTURE_BOTTOM_OFFSET_DP, clamped)
    }

    private fun persistInt(context: Context?, key: String, value: Int) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(key, value)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun setGestureTapAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update { it.copy(gestureTapAction = action, gestureTapSpecificPackage = specificPackage) }
        persistGestureSlot(context, KEY_GESTURE_TAP_ACTION, KEY_GESTURE_TAP_PKG, action, specificPackage)
    }

    fun setGestureLongPressAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update { it.copy(gestureLongPressAction = action, gestureLongPressSpecificPackage = specificPackage) }
        persistGestureSlot(context, KEY_GESTURE_LONG_PRESS_ACTION, KEY_GESTURE_LONG_PRESS_PKG, action, specificPackage)
    }

    fun setGestureSwipeUpAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update { it.copy(gestureSwipeUpAction = action, gestureSwipeUpSpecificPackage = specificPackage) }
        persistGestureSlot(context, KEY_GESTURE_SWIPE_UP_ACTION, KEY_GESTURE_SWIPE_UP_PKG, action, specificPackage)
    }

    fun setGestureSwipeLeftAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update {
            it.copy(gestureSwipeLeftAction = action, gestureSwipeLeftSpecificPackage = specificPackage)
        }
        persistGestureSlot(
            context,
            KEY_GESTURE_SWIPE_LEFT_ACTION,
            KEY_GESTURE_SWIPE_LEFT_PKG,
            action,
            specificPackage,
        )
    }

    fun setGestureSwipeRightAction(context: Context? = null, action: TargetAction, specificPackage: String? = null) {
        _serviceState.update {
            it.copy(gestureSwipeRightAction = action, gestureSwipeRightSpecificPackage = specificPackage)
        }
        persistGestureSlot(
            context,
            KEY_GESTURE_SWIPE_RIGHT_ACTION,
            KEY_GESTURE_SWIPE_RIGHT_PKG,
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
                .edit().putString(actionKey, action.name).putString(pkgKey, specificPackage).apply()
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

    fun setDiagnosticsEnabled(context: Context? = null, enabled: Boolean) {
        _serviceState.update { it.copy(diagnosticsEnabled = enabled) }
        // Persisted: the service is rebound (and this process restarted) often enough on
        // vendor ROMs that an in-memory flag rarely survives until the bug reproduces.
        persistBoolean(context, KEY_DIAGNOSTICS_ENABLED, enabled)
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
        appendDiagToFile(event)
    }

    private fun appendDiagToFile(event: DiagEvent) {
        val dir = diagDir ?: return
        diagWriter.execute {
            try {
                val file = File(dir, DIAG_FILE)
                if (file.length() > DIAG_FILE_MAX_BYTES) {
                    file.renameTo(File(dir, "$DIAG_FILE.1"))
                }
                val line = "${diagTimeFormat.format(Date(event.timeMs))} ${event.kind} ${event.detail}\n"
                file.appendText(line)
            } catch (_: Exception) {
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

    private fun prefsBoolOr(prefs: SharedPreferences, key: String, fallback: Boolean): Boolean =
        if (prefs.contains(key)) prefs.getBoolean(key, fallback) else fallback

    private fun publishDiagSnapshot() {
        synchronized(diagLock) {
            _diagLog.value = diagEvents.toList()
        }
    }
}
