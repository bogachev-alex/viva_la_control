# BlueLM Interceptor — Plan V2

Supersedes `OPTIMIZATION_PLAN.md`. Phase 1 (size) and Phase 3 (assistant
selection) from V1 are **done**; this plan covers what is left, plus the
camera-button failure diagnosed against the real device.

## Where we are

Verified by building and by inspecting the connected device
(`V2454A` = X200 Ultra, **OriginOS 6**, `ro.vivo.os.version=16.0`, Android 16 / SDK 36):

| | Before | Now |
|---|---|---|
| Release APK | 25.7 MB (R8 off) | **1.16 MB** |
| Debug APK | 25.7 MB | **9.32 MB** |
| DEX | ~73 MB, 11 files | 1.89 MB, 1 file (release) |
| Dependencies | 31 (13 unused) | 12, all used |
| Native libs | 4 ABIs of CameraX JNI | only `libandroidx.graphics.path.so` |

Confirmed live on device via `dumpsys accessibility`:

```
Service[label=BlueLM Interceptor Service, feedbackType[FEEDBACK_GENERIC],
        capabilities=8, eventTypes=TYPE_WINDOW_STATE_CHANGED, notificationTimeout=200]
```

`capabilities=8` is exactly `CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS`, with
bit 0 (`CAN_RETRIEVE_WINDOW_CONTENT`) absent — so **key filtering is granted**
and the V1 Phase 2.1 memory fix is live. Neither is the cause of the camera bug.

**Size work is essentially finished.** A 1.16 MB release APK for this app is
at the floor; further size effort has poor returns. The remaining work is the
camera bug, observability, and runtime behaviour.

---

## Phase A — Camera button is never intercepted

### A.0 The blocker behind the blocker: logcat is dead on this ROM

```
[log.tag]: [M]        # global log level, non-standard Vivo value
```

The entire logcat buffer held **9 lines**. Restarting the accessibility
service produced **zero** output — not even the `Log.i(TAG, "…connected")` in
`onServiceConnected`. Every `Log.*` call in this codebase is a no-op on the
user's device.

This is why the camera bug is being diagnosed by guesswork. **Fix this first —
everything else in Phase A depends on it.**

Add an in-app diagnostic ring buffer to `InterceptorStateRepository`:

```kotlin
data class DiagEvent(val timeMs: Long, val kind: String, val detail: String)

private val _diagLog = MutableStateFlow<List<DiagEvent>>(emptyList())
val diagLog: StateFlow<List<DiagEvent>> = _diagLog.asStateFlow()

fun diag(kind: String, detail: String) {
    _diagLog.update { (it + DiagEvent(System.currentTimeMillis(), kind, detail))
        .takeLast(200) }        // bounded — see Phase B
}
```

Call it at every decision point in `onKeyEvent` and `onAccessibilityEvent`:
key received, keycode + action + repeatCount + `KeyEvent.keyCodeToString()`,
which guard rejected it, which action ran and whether it returned true.
Render it as a scrollable "Diagnostics" card in `MainScreen` with a share/copy
button. Keep it behind a toggle, default off, so it costs nothing normally.

### A.1 Add a "learn the key" capture mode — *the decisive step*

Rather than guessing keycodes, capture them. Add a mode where `onKeyEvent`
records **every** keycode it sees and returns `false` (consumes nothing):

```kotlin
if (state.captureMode) {
    InterceptorStateRepository.diag(
        "KEY",
        "${event.keyCode} ${KeyEvent.keyCodeToString(event.keyCode)} " +
        "action=${event.action} scan=${event.scanCode} dev=${event.deviceId} " +
        "src=${event.source} flags=${event.flags}",
    )
    return false
}
```

UI: a "Capture next key press" button that arms the mode, shows a live list,
and offers "use this key" to write the captured keycode into preferences.
`cameraKeyCodes` then becomes a **persisted `Set<Int>`**, not a hardcoded
constant.

Have the user run this and press: (a) the on-body shutter half-press, (b) the
full press, (c) the accessory grip shutter if used. That single run answers
every open question below.

### A.2 The self-defeating guard — *most likely proximate cause*

Device evidence:

```
/system/usr/keylayout/gpio-keys.kl
    key 528   FOCUS
    key 766   CAMERA
```

```
settings secure: camera_button_start_camera_switch=1
                 quick_start_camera=1
                 quick_start_camera_switch=100
                 double_click_shutter_start_camera_switch=2
```

The camera package on this device is `com.android.camera`, which **is** in
`KNOWN_CAMERA_PACKAGES`. Current prefs:

```xml
<string name="key_camera_action">MUTE_TOGGLE</string>   <!-- not NONE, so that guard passes -->
<!-- key_skip_camera_app absent → getBoolean(..., true) → true -->
```

Now trace one press with the current code:

1. Half-press → `KEYCODE_FOCUS` (80). V1 Phase 4.1 removed FOCUS from
   `isCameraShutterKey()`, so it falls to `super.onKeyEvent()` → **not consumed**.
2. The OS acts on it — `camera_button_start_camera_switch=1` — and launches
   `com.android.camera`.
3. `TYPE_WINDOW_STATE_CHANGED` fires → `foregroundPackage = "com.android.camera"`.
4. Full press → `KEYCODE_CAMERA` (27) → reaches `onKeyEvent` →
   `shouldPassThroughCameraKey(skipCameraApp = true, "com.android.camera", …)`
   → `isCameraApp()` → **true → `return false`**.

**Never intercepted. Every press. By design.**

The two V1 fixes — "drop FOCUS" (4.1) and "don't steal the shutter inside the
camera app" (4.4) — are individually correct but combine into a deadlock on a
device whose OS opens the camera from that same button.

Fix: the guard must distinguish *"the camera app was already open when the
user pressed"* from *"this very press is what opened the camera app."*

```kotlin
// Only skip if the camera app has been foreground for a while —
// i.e. the user was already shooting, not arriving via this press.
private const val CAMERA_FOREGROUND_GRACE_MS = 1200L

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
```

Record `foregroundSinceMs` alongside `foregroundPackage`, and only update it
when the package actually *changes*. Also clear both on screen-off.

**Immediate workaround the user can apply right now, before any code change:**
turn off "Don't intercept while camera app is open" in the app. If the button
then works, A.2 is confirmed as the cause.

### A.3 `isCameraShutterKey()` cannot match Vivo's accessory keycodes

Vivo ships extra key layouts with **non-AOSP** keycode labels:

```
/system/usr/keylayout/SmallRig_WR-04.kl          # the Photographer Kit grip
    key 59 CAMERA_HANDLE_SHUTTER_HALF
    key 60 CAMERA_HANDLE_SHUTTER
    key 61 CAMERA_HANDLE_ZOOM_IN
    key 62 CAMERA_HANDLE_ZOOM_OUT
    key 63 CAMERA_HANDLE_WHEEL_UP
    key 64 CAMERA_HANDLE_WHEEL_DOWN

/system/usr/keylayout/Seafrogs-vivo_Keyboard.kl  # underwater housing
    key 2  CAMERA_SHUTTER
    key 3  CAMERA_ZOOM_IN
    key 4  CAMERA_ZOOM_OUT
    key 5  CAMERA_POWER
    key 6  CAMERA_VIDEO
```

None of these are `KeyEvent.KEYCODE_CAMERA`, so
`isCameraShutterKey(keyCode) = keyCode == KEYCODE_CAMERA` can never match a
press on the grip.

I could **not** confirm their numeric values: `input keyevent <name>` on this
ROM prints nothing for a deliberately bogus name either, so that test was
inconclusive. Do not hardcode guessed numbers. Resolve them by label at
runtime, which yields `KEYCODE_UNKNOWN` (0) on devices that don't define them:

```kotlin
private val VENDOR_SHUTTER_LABELS = listOf(
    "CAMERA_HANDLE_SHUTTER", "CAMERA_SHUTTER", "CAMERA_VIDEO",
)

private val vendorShutterKeyCodes: Set<Int> by lazy {
    VENDOR_SHUTTER_LABELS
        .map { KeyEvent.keyCodeFromString("KEYCODE_$it") }
        .filter { it != KeyEvent.KEYCODE_UNKNOWN }
        .toSet()
}
```

Then `isCameraShutterKey` = `keyCode == KEYCODE_CAMERA ||
keyCode in vendorShutterKeyCodes || keyCode in userCapturedKeyCodes`.

A.1's capture mode makes this belt-and-braces rather than load-bearing —
whatever the grip actually sends gets learned and persisted.

### A.4 Handle the half-press without breaking autofocus

V1 dropped `KEYCODE_FOCUS` to avoid breaking focus-then-shoot. That is right
**inside the camera app** but wrong outside it, where FOCUS is the only signal
that arrives before the OS opens the camera.

Resolution — make it contextual:

- Camera app foreground (past the A.2 grace window) → never touch FOCUS or
  CAMERA. Shooting works normally.
- Camera app **not** foreground → treat `KEYCODE_FOCUS` as the trigger and
  consume it, which also suppresses the OS's own camera launch. Then consume
  the matching `KEYCODE_CAMERA` and its `ACTION_UP` so no half-event escapes.

Track this with a small state machine rather than the single
`consumingCameraKey` flag, since one physical press now spans two keycodes:

```
IDLE --FOCUS(down, not in camera)--> ARMED [fire action, consume]
ARMED --CAMERA(down|up) / FOCUS(up)--> ARMED [consume, no re-fire]
ARMED --(no key for 800ms)--> IDLE
```

### A.5 If the key genuinely never arrives, fall back to the window path

Android 16 routes hardware keys through `KeyGestureController`, and this ROM
has explicit camera-button gestures registered
(`camera_button_start_camera_switch`, `double_click_shutter_start_camera_switch`).
It is possible the key is consumed by system policy before the accessibility
input filter ever sees it — in which case **no** accessibility service can
intercept it, and A.1's capture mode will show nothing at all.

That outcome is not a dead end: it is exactly the situation the BlueLM path
already solves. The original author never used `onKeyEvent` for the assistant
button either — they let it launch, detected the window, and pressed BACK.

Apply the same trick: when `TYPE_WINDOW_STATE_CHANGED` reports a camera
package **and** the camera action is set **and** the app was not launched by
the user from the launcher, dismiss it with `GLOBAL_ACTION_BACK` and run the
camera action. Gate it behind an explicit "Camera button uses window
detection (fallback)" setting, because it necessarily makes the camera app
harder to open deliberately — offer a "long-press to open camera normally"
escape hatch.

Decide between A.4 and A.5 **after** running A.1. Do not build both blind.

### A.6 Verification checklist

Run after A.1 lands, using the in-app diagnostics (not logcat):

1. Capture mode on → press the shutter half-way → record what appears.
2. Capture mode on → full press → record.
3. Capture mode on → grip shutter (if the Photographer Kit is used) → record.
4. If (1)–(3) show **nothing**, key filtering never receives it → go to A.5.
5. With the fix in: from the home screen, one press fires exactly one action.
6. Open the camera app deliberately, wait 2 s, press shutter → photo is taken,
   app does not interfere.
7. Fire a BlueLM interception, then press camera within 1 s → both act
   (separate cooldowns, already fixed in V1).

---

## Phase B — Assistant dismissal latency per OriginOS version

Requested: **OriginOS 6 keeps the current 100 ms; OriginOS 5 must close almost
instantly**, because on OriginOS 5 the assistant's launch animation is
different and is currently visible before the dismissal lands.

Current code, `BlueLMInterceptorService.onAccessibilityEvent`:

```kotlin
performGlobalAction(GLOBAL_ACTION_BACK)
serviceScope.launch {
    delay(100.milliseconds)          // ← fixed, OS-agnostic
    ActionExecutionEngine.executeAction(...)
}
```

### B.1 Detect the OriginOS major version

On the connected device:

```
[ro.vivo.os.build.display.id]: [OriginOS 6]
[ro.vivo.os.name]:            [Funtouch]      # global ROMs say Funtouch
[ro.vivo.os.version]:         [16.0]          # tracks the Android version
```

`ro.vivo.os.name` is unreliable (reports `Funtouch` on a device displaying
OriginOS 6). Use `ro.vivo.os.build.display.id`, falling back to
`ro.vivo.os.version`, and finally to `Build.VERSION.SDK_INT`:

```kotlin
// OriginOS 6 → os.version 16.0 → Android 16 (SDK 36)
// OriginOS 5 → os.version 15.0 → Android 15 (SDK 35)
private fun originOsMajor(): Int? {
    readProp("ro.vivo.os.build.display.id")            // "OriginOS 6"
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?.let { return it }
    readProp("ro.vivo.os.version")                     // "16.0"
        ?.substringBefore('.')?.toIntOrNull()
        ?.let { return it - 10 }                       // 16 → 6, 15 → 5
    return null
}
```

`android.os.SystemProperties` is a blocked hidden API on modern Android; read
these with `Runtime.exec("getprop <key>")` **once** at `onServiceConnected`
and cache the result. Never call it on the key path.

The SDK-level mapping is a proxy, not a guarantee — which is why B.2 exists.

### B.2 Make the delay a persisted, per-OS setting

Do not let correct behaviour hinge on getting detection right:

```kotlin
val dismissToLaunchDelayMs: Int =
    when (originOsMajor()) {
        6    -> 100    // OriginOS 6 — current behaviour, keep as-is
        5    -> 0      // OriginOS 5 — fire on the next frame
        else -> 100    // unknown ROM — match OriginOS 6
    }
```

Store it in `InterceptorStateRepository` as `dismissDelayMs`, seed it from
detection on first run, and expose a slider (0–300 ms) in the Target Action
card showing the detected OS. The user can then tune it on any device without
a rebuild.

Guard the launch coroutine so `0` really means immediate:

```kotlin
serviceScope.launch {
    val d = state.dismissDelayMs
    if (d > 0) delay(d.milliseconds)
    ActionExecutionEngine.executeAction(...)
}
```

At `d = 0` this still yields to the dispatcher once, so the BACK action is
already queued — which is the "almost instant" behaviour wanted, without
racing ahead of the dismissal.

### B.3 Cut the visible animation at the source

The 100 ms delay sits between BACK and the target launch, so shortening it
alone only makes the *replacement* appear sooner. If the assistant's own
opening animation is still visible on OriginOS 5, also attack the detection
latency:

- `notificationTimeout="200"` in
  [accessibility_service_config.xml](app/src/main/res/xml/accessibility_service_config.xml)
  throttles event delivery by up to 200 ms — the single largest contributor to
  "I can see it opening." V1 raised this from 100 to 200 for battery reasons.
  For `TYPE_WINDOW_STATE_CHANGED` only, the event rate is low; **set it back to
  0** and take the detection latency win. This costs almost nothing now that
  `typeWindowContentChanged` is gone.
- Add `FLAG_ACTIVITY_NO_ANIMATION` to the launch intents in
  `launchSpecificApp` / `launchDefaultAssistant`, so the replacement app does
  not play its own entry animation on top.
- Consider `performGlobalAction(GLOBAL_ACTION_BACK)` twice, ~50 ms apart, only
  on OriginOS 5, if a single BACK lands before the assistant window is
  focusable.

Measure with the A.0 diagnostics: record the delta between the window event
timestamp and the BACK call. If that delta is large, the problem is detection
latency, not the 100 ms.

---

## Phase C — Remaining runtime work from V1

### C.1 App/assistant list still loads on the main thread

V1 Phase 2.2's `Drawable` retention is fixed — `AppInfo` and
`AssistantAppInfo` no longer carry icons, and `PackageIcon.kt` now loads them
per row. Still outstanding:

- `getInstalledLaunchableApps()` and `getInstalledAssistants()` run
  `queryIntentActivities` + `loadLabel` synchronously on the caller's thread,
  from `remember { }` in composition. On a device with ~150 apps this blocks
  the first frame of the picker. Move to `Dispatchers.IO` behind
  `produceState`, with a loading state in the dialog.
- Verify `PackageIcon` sizes its bitmaps (`toBitmap(w, h)`) rather than
  rasterizing at intrinsic size, and that it caches by package with a bounded
  LRU.

### C.2 Assistant list is still built three times

V1 Phase 2.3 is not applied: `getInstalledAssistants(context)` is still called
independently from `InstalledAssistantsCard`, `AssistantChooserSheet`, and
`launchDefaultAssistant`'s fallback. Hoist to one cached `StateFlow`,
invalidated on `ACTION_PACKAGE_ADDED` / `_REMOVED` or on `onResume`.

### C.3 Torch state still tracked, not observed

`ActionExecutionEngine` now has `torchCameraId` and `torchCallbackRegistered`
fields but confirm a `CameraManager.TorchCallback` is actually registered and
that `isTorchOn` is updated from it. Otherwise the torch desyncs the moment
quick settings or the camera app touches it, and the next press is a no-op.

### C.4 Bound the diagnostics buffer

The A.0 ring buffer is the one new allocation source this plan introduces.
`takeLast(200)` copies the list on every event; with capture mode on and a key
repeating this allocates steadily. Use an `ArrayDeque` with a fixed cap and
emit an immutable snapshot only when the UI is actually collecting.

### C.5 `AssistantChooserActivity` re-entry

Still `launchMode="singleTop"` + `excludeFromRecents`, launched with
`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP`. On re-entry `onCreate`
does not run, so the sheet may not re-show. Add `onNewIntent` handling or
switch to `singleInstance`. Lower priority now that `DEFAULT_ASSISTANT` is the
shipped default and the chooser persists its pick.

---

## Phase D — Size, what little is left

Release is **1.16 MB**. Only two items are still worth anything:

- **`libandroidx.graphics.path.so`** ships for 4 ABIs (~37 KB total) and comes
  from `compose.ui.graphics`. An `abiFilters` / splits restriction to
  `arm64-v8a` saves ~27 KB. Marginal; do it only if you are already adding
  splits.
- **`resources.arsc` is 120 KB** against 1.89 MB of DEX. Adding
  `resConfigs("en", "ru")` and a density filter would trim the unused Material3
  translations. Worth maybe 40–60 KB.

Neither justifies risk. **Consider size done.**

---

## Execution order

| # | Work | Why first |
|---|---|---|
| 1 | **A.0** in-app diagnostics | Nothing else is verifiable without it — logcat is dead on this ROM |
| 2 | **A.1** key capture mode | Turns every camera guess into a measurement |
| 3 | **A.2** grace-window guard | Highest-probability cause; also testable today by toggling the setting off |
| 4 | **B.1–B.2** per-OS dismiss delay | Self-contained, user-requested |
| 5 | **A.3 / A.4 or A.5** | Chosen by what A.1 actually captures |
| 6 | **B.3** detection latency | Only if B.2 alone doesn't hide the animation |
| 7 | **C.1–C.5** | Cleanup, no user-visible urgency |

Steps 1–3 are independent of the unknown in A.1 and can land immediately.

## One thing to try before writing any code

Open the app and turn **off** "Don't intercept while camera app is open", then
press the camera button from the home screen. If the mute toggle fires, A.2 is
confirmed and the fix is small. If nothing happens, the key is not reaching the
service at all and A.5 becomes the likely path.
