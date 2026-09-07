# BlueLM Interceptor — Optimization & Bug-Fix Plan

Scope: cut APK size, cut RAM/CPU footprint, and fix two behavioural bugs
(assistant selection ignored; camera button intercepted incorrectly).

Baseline measured on the current tree:

| Metric | Value |
|---|---|
| `app-debug.apk` | **25.7 MB** |
| DEX payload (11 `classes*.dex`) | **~73 MB uncompressed** |
| Source under `app/src/main/java` | ~2,400 lines |
| Declared runtime dependencies | 31 |
| Dependencies with **zero** references in source | **13** |
| Material icons available / used | ~10,000 / **18** |

The app is a ~2,400-line accessibility utility shipping a full
network + database + camera + image-loading stack. Size is the easiest win;
the accessibility-service configuration is the biggest RAM/CPU win; the two
bugs are independent logic defects.

---

## Phase 0 — Measure before changing anything

Record a baseline so each phase can be attributed.

```bash
./gradlew :app:assembleRelease && ls -l app/build/outputs/apk/release/
```

```bash
adb shell dumpsys meminfo com.example.bluelm_interceptor
```

Capture `TOTAL PSS`, `Java Heap`, `Native Heap`, and `Objects → Views/Activities`
in three states: (a) service enabled, app not launched, (b) main screen open,
(c) app-picker dialog open. State (c) is where the regression is expected to be
worst. Keep these numbers; Phase 3 is validated against them.

---

## Phase 1 — APK size

### 1.1 Delete the 13 unused dependencies — *biggest, safest win*

`grep` over `app/src/main/java` returns **zero** matches for every one of these:

| Dependency | Files referencing it |
|---|---|
| `androidx.room.runtime`, `androidx.room.ktx`, `ksp(room-compiler)` | 0 |
| `retrofit`, `converter-moshi` | 0 |
| `moshi-kotlin`, `ksp(moshi-kotlin-codegen)` | 0 |
| `okhttp`, `logging-interceptor` | 0 |
| `androidx.camera.core/camera2/lifecycle/view` | 0 |
| `play-services-location` | 0 |
| `coil-compose` | 0 |
| `androidx.navigation3.runtime`, `navigation3.ui` | 0 |
| `compose.adaptive`, `adaptive-layout`, `adaptive-navigation3` | 0 |
| `androidx.lifecycle.viewmodel.navigation3` | 0 |
| `androidx.datastore.preferences` | 0 |
| `accompanist-permissions` | 0 |
| `kotlinx-serialization-core` | 0 |
| `com.google.android.material` (View-system Material, app is pure Compose) | 0 |

Removing CameraX alone drops `libimage_processing_util_jni.so` for four ABIs
(~148 KB of native code). Removing OkHttp drops the 41 KB
`publicsuffixes.gz` asset. Removing Room and Moshi also removes **both KSP
processors**, which currently run on every build for nothing.

Also drop the now-unneeded plugins from [app/build.gradle.kts](app/build.gradle.kts):
`google.devtools.ksp` and `jetbrains.kotlin.plugin.serialization`.

Also remove the matching `CAMERA` permission and `camera` / `camera.flash`
`uses-feature` entries from [AndroidManifest.xml](app/src/main/AndroidManifest.xml)
— **unless** `TargetAction.FLASHLIGHT` is kept. Torch via
`CameraManager.setTorchMode()` does **not** require the `CAMERA` permission,
so the permission can go either way; the `uses-feature flash` declaration
should stay if the flashlight action stays.

Expected: **-3 to -5 MB**, plus a meaningful build-time drop from killing KSP.

### 1.2 Replace `material-icons-extended` — *single biggest size item*

`androidx.compose.material` occupies ~97 MB in the Gradle cache, almost all of
it `material-icons-extended`: roughly 10,000 generated `ImageVector` classes.
The app uses **18**:

```
AutoMirrored.Rounded.*, Analytics, Apps, AutoAwesome, Block, Check,
CheckCircle, Close, FlashOn, Mic, Screenshot, Search, Settings, Shield,
SmartToy, TouchApp, Tune, Warning
```

Two options, in order of preference:

1. **Vendor the 18 icons.** Export each as a vector drawable into
   `app/src/main/res/drawable/` and switch call sites to
   `painterResource(R.drawable.ic_…)`, or hand-write them as local
   `ImageVector` values in a single `ui/theme/AppIcons.kt`. Then drop both
   `material-icons-extended` **and** `material-icons-core`. This fixes debug
   builds and build time as well as release size.
2. **Rely on R8** (see 1.3). With minification on, R8 does strip unreferenced
   icon objects, so release size largely recovers — but debug APKs stay at
   ~25 MB and every build still compiles the icon library.

Do both: 1 for the durable win, 2 because it is needed anyway.

Expected: **-8 to -15 MB** on debug, and the dominant contributor to the DEX
count dropping from 11 files back toward 1–2.

### 1.3 Turn R8 back on for release

[app/build.gradle.kts](app/build.gradle.kts) currently ships release
**unoptimized**:

```kotlin
buildTypes {
    release {
        optimization {
            enable = false      // ← no shrinking, no obfuscation, no resource shrinking
        }
    }
}
```

Enable full-mode R8 plus resource shrinking. Verify the exact property names
against the AGP 9.4 DSL before committing — this block is new AGP 9 syntax and
the older `isMinifyEnabled` / `isShrinkResources` spelling may still be what
resolves; run `./gradlew :app:assembleRelease` after the edit to confirm.

Keep rules go in [app/src/main/keepRules/rules.keep](app/src/main/keepRules/rules.keep),
which is already wired up. The only entries actually needed:

- `BlueLMInterceptorService` — instantiated by the framework from the manifest.
- `MainActivity`, `AssistantChooserActivity` — manifest-declared.
- Nothing else; there is no reflection, no Gson/Moshi models, no JNI.

Add `android.enableR8.fullMode=true` to [gradle.properties](gradle.properties)
if not already the AGP 9 default.

### 1.4 Ship an ABI/density-split or App Bundle

If distribution is a bare APK, add splits so a device only downloads its own
ABI and density:

```kotlin
splits {
    abi { isEnable = true; reset(); include("arm64-v8a"); isUniversalApk = false }
}
```

The Vivo X200 Ultra is `arm64-v8a` only. If any native library survives Phase
1.1, this removes three unused ABIs. If **no** native libraries remain (the
expected outcome), this step is unnecessary — verify with
`unzip -l app-release.apk | grep '\.so'` first.

### 1.5 Raise `minSdk`

`minSdk = 23` is currently paying for legacy multidex handling and Java-8+
desugaring on a device family that is Android 15+. The app already gates
`GLOBAL_ACTION_TAKE_SCREENSHOT` behind API 28 and `KEYCODE_STEM_PRIMARY`
behind API 24. Raising to `minSdk = 26` (or 28) lets those `Build.VERSION`
branches be deleted and removes desugaring overhead.

Confirm the real deployment target first — this is a behavioural change, not
just an optimization.

**Phase 1 target: 25.7 MB → under 4 MB release, under 8 MB debug.**

---

## Phase 2 — Runtime memory & CPU

### 2.1 Stop asking the system for window *content* — *biggest runtime win*

[accessibility_service_config.xml](app/src/main/res/xml/accessibility_service_config.xml):

```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents"
android:canRetrieveWindowContent="true"
android:notificationTimeout="100"
```

`typeWindowContentChanged` fires on **every content mutation in every
foreground app** — scrolling a list, a blinking cursor, a progress bar.
Combined with `canRetrieveWindowContent="true"` and
`flagRetrieveInteractiveWindows`, the system builds and marshals accessibility
node trees across the binder boundary continuously, and this app's process
allocates for each one. `notificationTimeout="100"` sets the throttle floor to
100 ms, so the ceiling is ~10 deliveries/second, indefinitely.

`onAccessibilityEvent` in
[BlueLMInterceptorService.kt:169](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L169)
reads only `event.packageName` and `event.className`. It needs **none** of
that machinery. Change to:

```xml
android:accessibilityEventTypes="typeWindowStateChanged"
android:accessibilityFlags="flagDefault|flagRequestFilterKeyEvents"
android:canRetrieveWindowContent="false"
android:notificationTimeout="200"
```

Then delete the `typeWindowContentChanged` branch from `onAccessibilityEvent`.
Note that `canRetrieveWindowContent="false"` is compatible with
`performGlobalAction(GLOBAL_ACTION_BACK)` and
`GLOBAL_ACTION_TAKE_SCREENSHOT`, which are the only window operations used.

This is also a **battery and system-responsiveness** fix, not only a memory
one — it removes this app from the hot path of every UI frame on the device.

### 2.2 Stop retaining every installed app's icon as a `Drawable`

`getInstalledLaunchableApps()`
([ActionExecutionEngine.kt:120](app/src/main/java/com/example/bluelm_interceptor/engine/ActionExecutionEngine.kt#L120))
calls `info.loadIcon(pm)` for **every launchable app on the device** and
stores the live `Drawable` in an `AppInfo`. `AppPickerDialog`
([MainScreen.kt:677](app/src/main/java/com/example/bluelm_interceptor/ui/MainScreen.kt#L677))
holds the whole list in `remember { … }` for the composition's lifetime, then
each row calls `app.icon?.toBitmap()` with **no target size** — rasterizing an
adaptive icon at its intrinsic size (commonly 288×288 `ARGB_8888` ≈ 324 KB).

On a phone with 150 apps that is a resident cost in the tens of megabytes,
allocated on the main thread, for a dialog most users open once.

Fix:

- Change `AppInfo` / `AssistantAppInfo` to hold `packageName` + `label` only —
  **no `Drawable` field.** Holding a bound `Drawable` also retains a
  `Callback`, which is a context-leak shape.
- Load icons lazily per visible row, keyed by package, and size them:
  `drawable.toBitmap(width = 48.dp.roundToPx(), height = 48.dp.roundToPx())`.
- Move the `queryIntentActivities` + label loading off the main thread
  (`withContext(Dispatchers.IO)`), behind a `produceState` / `LaunchedEffect`,
  so the dialog opens immediately instead of blocking on `PackageManager`.
- Bound the retained set with a small LRU (e.g. 64 entries), or let the row's
  bitmap be released when it scrolls out of the `LazyColumn`.

### 2.3 Deduplicate the assistant list

`ActionExecutionEngine.getInstalledAssistants(context)` is called from three
places, each building and retaining its own copy with its own icons:

- `InstalledAssistantsCard` ([MainScreen.kt:513](app/src/main/java/com/example/bluelm_interceptor/ui/MainScreen.kt#L513))
- `AssistantChooserSheet` ([AssistantChooserActivity.kt:73](app/src/main/java/com/example/bluelm_interceptor/ui/AssistantChooserActivity.kt#L73))
- `launchDefaultAssistant()` fallback path

Hoist to a single cached source (a `StateFlow` in a repository, or a
`ViewModel`), invalidated on `ACTION_PACKAGE_ADDED` / `_REMOVED` or simply on
`onResume`. Also fixes the inconsistency where the two UIs can disagree.

### 2.4 Make the chooser cheap to open

`AssistantChooserActivity` is a full `ComponentActivity` + Compose runtime +
Material3 `ModalBottomSheet` launched from a background interception. That
inflates the entire Compose stack on the critical path of a button press —
slow and allocation-heavy for what is a list of 3–6 rows.

Given Phase 3.1 makes the chooser the *exception* rather than the default
path, this is lower priority. If it stays, either:

- keep it but pre-warm nothing and accept the cost (it is now rare), or
- replace it with a themed `Dialog` activity using a plain `RecyclerView` /
  `AlertDialog`, which avoids Compose entirely in the service-triggered path.

### 2.5 Fix process-global mutable state in `ActionExecutionEngine`

`isTorchOn` and `lastMusicVolume`
([ActionExecutionEngine.kt:58](app/src/main/java/com/example/bluelm_interceptor/engine/ActionExecutionEngine.kt#L58))
are `object`-level `var`s. `isTorchOn` desynchronizes from reality whenever
anything else toggles the torch (quick settings, camera app, another app), so
the next press is a no-op. Register a
`CameraManager.TorchCallback` and read actual torch state, or query it at
toggle time rather than tracking it.

**Phase 2 target: eliminate the continuous background allocation churn; cut
peak PSS with the app-picker open by 60–80%.**

---

## Phase 3 — Bug: selecting another assistant still opens the chooser menu

### Root cause A — the chooser never persists the selection

[AssistantChooserActivity.kt:35](app/src/main/java/com/example/bluelm_interceptor/ui/AssistantChooserActivity.kt#L35):

```kotlin
onSelectAssistant = { assistant ->
    if (assistant.isInstalled) {
        ActionExecutionEngine.launchSpecificApp(this, assistant.packageName)
    } else {
        ActionExecutionEngine.launchDefaultAssistant(this)
    }
    finish()
}
```

It launches the chosen assistant **once** and exits. It never calls
`InterceptorStateRepository.setBlueLMAction(...)`. The stored `blueLMAction`
stays at its default, `ASSISTANT_CHOOSER`
([InterceptorStateRepository.kt:20](app/src/main/java/com/example/bluelm_interceptor/service/InterceptorStateRepository.kt#L20)),
so the next button press shows the menu again.

**This is the reported symptom exactly.** The user picks an assistant,
expecting it to stick; nothing is written, so it never does.

Fix — add a "remember this choice" path:

```kotlin
onSelectAssistant = { assistant, remember ->
    if (remember) {
        InterceptorStateRepository.setBlueLMAction(
            context = this,
            action = TargetAction.SPECIFIC_APP,
            specificPackage = assistant.packageName,
        )
    }
    ActionExecutionEngine.launchSpecificApp(this, assistant.packageName)
    finish()
}
```

Surface it in the sheet as either a "Set as default" checkbox, a long-press
"always use this" affordance, or — simplest and closest to what the user
expects — make plain selection sticky and add a "Change" entry point in
`MainScreen`. Recommend: **selection is sticky by default**, with the chooser
reachable from the main screen and from a long-press on the button if that is
ever wired up.

Also note the chooser is invoked with `FLAG_ACTIVITY_NEW_TASK or
FLAG_ACTIVITY_SINGLE_TOP` but the activity is `launchMode="singleTop"` and
`excludeFromRecents`; on re-entry `onCreate` will not re-run and the sheet may
not re-show. Add `onNewIntent` handling or switch to `singleInstance`.

### Root cause B — "Default Assistant" ignores the system default

`launchDefaultAssistant()`
([ActionExecutionEngine.kt:283](app/src/main/java/com/example/bluelm_interceptor/engine/ActionExecutionEngine.kt#L283))
walks a hardcoded priority list:

```kotlin
val DEFAULT_ASSISTANT_PACKAGE_PRIORITY = listOf(
    "com.google.android.googlequicksearchbox",   // ← always wins if installed
    "com.google.android.apps.bard",
    …
)
```

So `TargetAction.DEFAULT_ASSISTANT` means "Google, if present" — **not** "the
assistant the user set as default in system settings." Anyone who changes
their system assistant to Gemini/ChatGPT/Claude still gets Google.
`AssistantAppInfo.isDefault` exists as a field
([ActionExecutionEngine.kt:22](app/src/main/java/com/example/bluelm_interceptor/engine/ActionExecutionEngine.kt#L22))
and is **never assigned `true` anywhere in the codebase**.

Fix — read the real default first, fall back to the list only if that fails:

```kotlin
private fun systemDefaultAssistantPackage(context: Context): String? {
    val cr = context.contentResolver
    val raw = Settings.Secure.getString(cr, "assistant")
        ?: Settings.Secure.getString(cr, "voice_interaction_service")
        ?: return null
    return ComponentName.unflattenFromString(raw)?.packageName
        ?: raw.substringBefore('/').takeIf { it.isNotEmpty() }
}
```

On API 29+, prefer `RoleManager.getRoleHolders(RoleManager.ROLE_ASSISTANT)`,
which is the supported API; keep the `Settings.Secure` read as the fallback
for older releases and for OEM skins that do not populate the role.

Then populate `isDefault` in `getInstalledAssistants()` and show a "Default"
badge in both the chooser and `InstalledAssistantsCard`, so the user can see
which one the system considers default.

### Root cause C — `DEFAULT_ASSISTANT` and `ASSISTANT_CHOOSER` are indistinguishable to the user

`ASSISTANT_CHOOSER` is the shipped default `blueLMAction`. Combined with A,
a first-run user has no path to "just always open X" that survives a press.
After fixing A, consider defaulting to `DEFAULT_ASSISTANT` (now correct
thanks to B) and offering the chooser as an explicit opt-in.

**Verification:** set a non-Google system assistant, pick it in the chooser,
press the button twice. Second press must launch it directly with no menu.
Add unit coverage: selecting in the chooser writes `SPECIFIC_APP` + package to
`InterceptorStateRepository`.

---

## Phase 4 — Bug: camera button intercepted incorrectly

All in `onKeyEvent`
([BlueLMInterceptorService.kt:135](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L135)).
Six distinct defects, most of which are individually enough to produce
"intercepts the camera button incorrectly":

### 4.1 `KEYCODE_FOCUS` is the shutter half-press, not a button press

```kotlin
val isCameraKey = keyCode == KeyEvent.KEYCODE_CAMERA ||
    keyCode == KeyEvent.KEYCODE_FOCUS || …
```

`KEYCODE_FOCUS` is emitted on the **half-press** of a two-stage shutter — the
autofocus stage. Consuming it breaks focus-then-shoot in every camera app, and
makes a single physical press fire the action during the half-press, before
the user has fully pressed. **Remove `KEYCODE_FOCUS` from the set.**

### 4.2 `KEYCODE_STEM_PRIMARY` is a Wear OS button

`KEYCODE_STEM_PRIMARY` (API 24+) is the Wear OS crown/stem button. It has
nothing to do with a phone camera key and should not be in this set. On a
device that does map it to something else, this silently hijacks it. **Remove.**

### 4.3 The event is consumed unconditionally, including `ACTION_UP` and cooled-down presses

```kotlin
if (isCameraKey) {
    val state = …
    if (state.cameraAction == TargetAction.NONE) return false
    if (event.action == KeyEvent.ACTION_DOWN) {
        …
        if (now - lastInterceptTimeMillis >= COOLDOWN_MS) { … }
    }
    return true          // ← always, for DOWN and UP, fired or not
}
```

Consuming `ACTION_UP` while having let a `ACTION_DOWN` through (or vice versa)
leaves downstream key-state tracking inconsistent — apps see a key that goes
down and never comes up. And when the 1.5 s cooldown suppresses the action,
the press is *still* swallowed, so the button does nothing at all rather than
falling through to the system.

Fix: consume symmetrically and only when actually acting.

```kotlin
private var consumingCameraKey = false

when (event.action) {
    KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount > 0) return consumingCameraKey  // ignore auto-repeat
        val fired = tryFireCameraAction()
        consumingCameraKey = fired
        return fired
    }
    KeyEvent.ACTION_UP -> {
        val wasConsuming = consumingCameraKey
        consumingCameraKey = false
        return wasConsuming
    }
}
```

Auto-repeat (`repeatCount > 0`) is currently unhandled, so holding the button
re-enters the cooldown check on every repeat.

### 4.4 `isCameraApp()` is dead code — the camera app is never exempted

`isCameraApp()` and `KNOWN_CAMERA_PACKAGES`
([BlueLMInterceptorService.kt:40](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L40))
are fully implemented and **called from nowhere in `app/src/main`** — only
from `BlueLMInterceptorServiceTest`. The intended guard "don't steal the
shutter while the user is in the camera" was written and never wired up.

Result: map the camera button to anything, open the camera app, press the
shutter — the app hijacks it and the photo is never taken.

Fix: track the current foreground package from `TYPE_WINDOW_STATE_CHANGED`
(which the service already receives) and short-circuit:

```kotlin
@Volatile private var foregroundPackage: String? = null   // set in onAccessibilityEvent

// in onKeyEvent, before doing anything:
if (isCameraApp(foregroundPackage, null, packageName)) return false
```

Expose this as a user-visible setting — *"Don't intercept while camera app is
open"*, default **on**.

### 4.5 The cooldown is shared between the two independent triggers

`lastInterceptTimeMillis` is a single field written by both the BlueLM
accessibility path and the camera key path. A BlueLM interception therefore
swallows any camera press in the following 1.5 s, and vice versa — the button
"randomly does nothing." Split into `lastBlueLMInterceptMs` and
`lastCameraInterceptMs`.

(`lastInterceptTimeMillis` is also `public var` on the service purely for test
access; make it private and inject a clock instead.)

### 4.6 Double-press is detected and then discarded

```kotlin
val isDoublePress = (now - lastCameraKeyPressTime) <= DOUBLE_PRESS_THRESHOLD_MS
lastCameraKeyPressTime = now
…
Log.i(TAG, "… (doublePress=$isDoublePress). Executing action: ${state.cameraAction}")
```

`isDoublePress` only reaches a log line. Meanwhile the 500 ms double-press
threshold sits *inside* a 1500 ms cooldown, so the second press of a genuine
double-press is always suppressed — the branch can effectively never do
anything useful.

Either implement per-gesture mapping properly (single / double / long press →
separate `TargetAction`s, which is what
[.agent/plan.md](.agent/plan.md) promises) or delete the dead detection. If
implementing: the single-press action must be *deferred* by the double-press
window, which is a real UX cost — recommend long-press as the secondary
gesture instead, since it needs no deferral.

### 4.7 Bonus: the BlueLM matcher is dangerously broad

Not the camera bug, but the same class of defect and likely a source of
"it triggers when it shouldn't". `isBlueLMOrVivoAssistant()`
([BlueLMInterceptorService.kt:53](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L53))
ends with:

```kotlin
combined.contains("copilot") ||
combined.contains("vpa") ||
(combined.contains("vivo") && combined.contains("agent")) ||
pkg.contains("agent") ||
cls.contains("agent")
```

`pkg.contains("agent")` / `cls.contains("agent")` match **any** package or
class with "agent" in the name, from any vendor. `contains("vpa")` matches any
package containing that three-letter substring. Every match triggers a
`GLOBAL_ACTION_BACK` **plus** an app launch — so an unrelated app gets
dismissed out from under the user.

Fix: drop the bare `agent` / `vpa` substring rules. Keep the explicit
`KNOWN_PACKAGES` set, keep the narrow qualified checks
(`bluelm`, `jovi`, `bbk.voiceassistant`, `vivo`+`agent`), and match on
**package only** — never on `className`, which is attacker-adjacent and
uncontrolled. Consider making the package list user-editable.

**Verification for Phase 4:** with the camera action mapped, (a) open the
camera app and confirm shutter + half-press focus both work normally, (b) from
the home screen confirm one press fires exactly one action, (c) hold the
button and confirm it fires once, not repeatedly, (d) fire a BlueLM
interception then immediately press camera and confirm both act.

---

## Suggested execution order

| # | Work | Risk | Payoff |
|---|---|---|---|
| 1 | Phase 3 — assistant selection persists + real system default | Low | Fixes the user-facing complaint |
| 2 | Phase 4.1–4.5 — camera key correctness | Low | Fixes the second complaint |
| 3 | Phase 1.1 — delete 13 unused deps | Very low | −3–5 MB, faster builds |
| 4 | Phase 2.1 — accessibility config narrowing | Low | Largest RAM/CPU/battery win |
| 5 | Phase 1.2 + 1.3 — icons + R8 | Medium | Largest size win |
| 6 | Phase 2.2–2.3 — icon/app-list memory | Medium | Largest peak-PSS win |
| 7 | Phase 4.6, 4.7, 2.4, 2.5, 1.4, 1.5 | Varies | Polish and hardening |

Items 1 and 2 are independent of everything else and can land first.

## Test debt to clear alongside

- `BlueLMInterceptorServiceTest` asserts `isCameraApp()` behaviour for a
  function production never calls — it will keep passing while the bug ships.
  Add a test that asserts the service *does not* consume a camera key when a
  camera app is in the foreground.
- No test covers "chooser selection persists". Add one against
  `InterceptorStateRepository`.
- `ExampleUnitTest` / `ExampleInstrumentedTest` are template stubs; delete.
