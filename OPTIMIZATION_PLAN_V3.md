# BlueLM Interceptor — Plan V3

The key capture resolved the camera bug. This supersedes Phase A of
`OPTIMIZATION_PLAN_V2.md`; Phases B–D of V2 still stand except where noted.

---

## What the capture proved — and what it corrects

Three hypotheses from V2 were wrong. Stating them plainly, because they change
what to build:

| V2 claim | Reality |
|---|---|
| **A.2** — `skipCameraApp` guard is the cause | **Wrong.** The guard was never reached: `isCameraShutterKey()` never matched, so the code returned at the `super.onKeyEvent()` line long before the guard. |
| **A.5** — system policy may consume the key before accessibility | **Refuted.** Keys arrive fine. Key filtering works. |
| **A.3** — vendor labels are `CAMERA_HANDLE_SHUTTER` / `CAMERA_SHUTTER` / `CAMERA_VIDEO` | **Wrong for this button.** Those belong to the SmallRig grip and Seafrogs housing. `vendorShutterKeyCodes` as currently written resolves nothing useful for the on-body button. |

`gpio-keys.kl`'s `key 528 FOCUS` / `key 766 CAMERA` was a red herring. The
button does not route through `gpio-keys` at all — it routes through
**`vforce_input`**, a *virtual* device (`SysfsRootPath: /sys/devices/virtual`,
`Sources: KEYBOARD | JOYSTICK`), which has no dedicated `.kl` and so falls back
to Vivo's patched `Generic.kl`.

### What the button actually emits

From the capture (`dev=9` = `vforce_input`, `src=257` = `SOURCE_KEYBOARD`,
`flags=8` = `FLAG_FROM_SYSTEM`):

| scanCode | keyCode | label | emitted when |
|---|---|---|---|
| 746 | **552** | `KEYCODE_PRESS` | **every** press — `DOWN` then `UP` |
| 744 | **550** | `KEYCODE_DOUBLE_CLICK` | on the 2nd press of a double, in the **same millisecond** as that press's 552 `DOWN` |

Neither is `KEYCODE_CAMERA` (27) or `KEYCODE_FOCUS` (80). That, and nothing
else, is why the button was never intercepted.

Vivo's `Generic.kl` defines the whole `vforce` gesture family — scanCode maps
to keyCode at a constant offset of −194 (746→552, 744→550):

```
744 DOUBLE_CLICK   745 TAP            746 PRESS         747 UPSLIDE
748 DOWNSLIDE      749 SLIDE_UP       750 SMART_KEY_FOR_FOLD
751 FACTORY_KEY    752 GAME_PAD_LEFT  753 GAME_PAD_RIGHT
754 DOUBLE_TAP_WAKEUP                 755 VOLUME_LONG_PRESS
756 VOLUME_LONG_PRESS_RELEASE         757 INSIDE_SLIDE_DOWN
758 INSIDE_SLIDE_UP                   759 QUIT_ACTIVE_MODE
770 LONG_PRESS
# elsewhere: 241 CAMERA_TRIPLE_CLICK, 380 CAMERA_DOUBLE_CLICK
```

### Observed gesture semantics

- **Single press** → `552 DOWN`, `552 UP` (~100–210 ms apart). **The OS does
  nothing.** Confirmed four separate times.
- **Long hold** (`11:14:52.480` → `11:14:54.979`, 2.5 s) → still only
  `552 DOWN` / `552 UP`. **No `LONG_PRESS` (770) keycode is emitted** for this
  button — long press must be derived from the DOWN→UP interval.
- **Double press** → `552 D/U`, then `552 DOWN` + `550 DOWN` in the same
  millisecond, `550 UP`, `552 UP` → `com.android.camera` launches ~50 ms later.
  This is `double_click_shutter_start_camera_switch=2` acting on 550.

---

## Fix it right now, with no code change

The capture-and-learn flow is already fully wired
([MainScreen.kt:984](app/src/main/java/com/example/bluelm_interceptor/ui/MainScreen.kt#L984)
→ `onUseCapturedKey` → `InterceptorStateRepository.addCameraKeyCode`).

In the diagnostics list, tap **"Use this key (552)"** on any
`KEY 552 KEYCODE_PRESS` row. That writes 552 into the persisted
`cameraKeyCodes`, and `isCameraShutterKey()` will match it from the next press
on. Add **550** as well only if you want the double-press to fire your action
instead of opening the camera.

Everything below is about making that work without the user having to do it.

---

## Phase A′ — Camera key, remaining work

### A′.1 Seed the right keycodes by default

Replace the current `VENDOR_SHUTTER_LABELS` in
[BlueLMInterceptorService.kt:72](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L72)
— those three labels don't resolve to this button — with the labels that do,
keeping the runtime-resolution approach so no magic numbers ship:

```kotlin
private val VENDOR_SHUTTER_LABELS = listOf(
    "PRESS",                   // vivo vforce shutter → 552 on OriginOS 6
    "CAMERA_SHUTTER",          // Seafrogs housing
    "CAMERA_HANDLE_SHUTTER",   // SmallRig WR-04 grip
)
```

`keyCodeFromString("KEYCODE_PRESS")` returns 552 on this ROM and
`KEYCODE_UNKNOWN` on devices that don't define it, so the existing filter
already handles portability. Verify the resolution actually works at runtime —
log the resolved set once at `onServiceConnected` via `diag("SVC", …)`, since
`keyCodeFromString` on a vendor label is the one thing here not yet proven
end-to-end.

**Caveat worth designing around:** `KEYCODE_PRESS` is a *generic* Vivo gesture
code on a *virtual* input device — it is not camera-specific, and other
pressure or side-key gestures may plausibly emit it too. If false positives
appear, narrow the match to keyCode 552 **and** `scanCode == 746` **and** the
`vforce_input` device id. The capture already records all three, so this is a
one-line tightening if needed.

### A′.2 Fire on UP, and decide what to do about the double press

`onKeyEvent` currently fires on `ACTION_DOWN`
([line 214](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L214)).
Given the observed pattern, a double press fires your single-press action on
press 1 and *then* the OS opens the camera on 550 — two things happen from one
gesture.

Recommended default: **fire on `552 ACTION_UP`**, leave 550 alone. Single press
works; double press keeps its familiar camera-launch behaviour. Latency cost is
the press duration (~100–210 ms observed), which is acceptable for launching an
assistant.

Optional, behind a setting — "Double press action":
- On `552 UP`, start a ~300 ms timer instead of firing.
- If `550 DOWN` arrives, cancel the timer, fire the double action, and consume
  550 (which suppresses the OS camera launch).
- Otherwise fire the single action when the timer expires.

Long press is free once you fire on UP: measure `UP.eventTime - DOWN.eventTime`
and branch above ~500 ms. Use `KeyEvent.getEventTime()`, not
`System.currentTimeMillis()`, so it isn't skewed by dispatch delay.

### A′.3 The 1500 ms cooldown is too long

In the capture, presses at `38.494` and `38.697` are **203 ms** apart — the
second is inside `COOLDOWN_MS = 1500`. That is right for suppressing the double
press, but it also means two deliberate presses (flashlight on, then off) are
impossible for a second and a half.

Drop the camera cooldown to ~400 ms, or make it exactly the double-press window
when A′.2's double support is enabled. Keep the BlueLM cooldown at 1500 —
the capture shows it correctly absorbing a duplicate copilot window event
79 ms after the first (`11:14:43.355 BLM rejected: cooldown`).

### A′.4 `skipCameraApp` is now live for the first time — verify it

The guard has never actually executed, because nothing ever matched. Once 552
is learned it becomes load-bearing: the shutter inside `com.android.camera`
almost certainly also emits 552, and the 1200 ms grace window is what stops the
app from stealing it.

Test explicitly: open the camera, wait >1.2 s, press the shutter — the photo
must be taken and the app must log
`rejected: camera app foreground for …ms`. Then press from the home screen —
the action must fire. Both paths need to pass before this ships.

### A′.5 Consuming 552 is safe

Proven: a single 552 press produces no OS-level behaviour. Consuming it costs
nothing and prevents other apps from seeing it. Do **not** consume 550 unless
double-press support is switched on — consuming it suppresses the camera
launch, which is a surprise if the user didn't ask for it.

---

## Phase B — Dismissal latency (V2 Phase B, now measured)

The capture gives real numbers for the BlueLM path:

```
11:14:43.276  FG  pkg=com.vivo.ai.copilot
11:14:43.277  BLM BACK=true delay=100ms action=DEFAULT_ASSISTANT   ← 1 ms after detection
11:14:43.410  BLM launched DEFAULT_ASSISTANT result=true           ← 134 ms after detection
```

Detection → `GLOBAL_ACTION_BACK` is **1 ms**. So V2's B.3 worry was unfounded:
`notificationTimeout="0"` is already doing its job and detection latency is not
what makes the animation visible. The only remaining gap is the configured
delay itself.

- **OriginOS 6** (this device, `ro.vivo.os.build.display.id=OriginOS 6`,
  `ro.vivo.os.version=16.0`): keep **100 ms** — measured end-to-end at 134 ms,
  which is the current, wanted behaviour.
- **OriginOS 5**: set **0 ms**. The launch will land ~34 ms after BACK instead
  of ~134 ms, which is the "almost instant closure" you asked for.

Both are already implemented (`dismissDelayMs`, seeded from
`detectedOsLabel`, with the 0–300 ms slider). Nothing further to build —
just confirm on an OriginOS 5 device that 0 actually hides the animation, and
if it does not, the remaining lever is `FLAG_ACTIVITY_NO_ANIMATION` on the
launch intent (V2 B.3), not a smaller delay.

---

## Phase C — Cleanup

### C.1 Stale test data in preferences

```xml
<string name="key_bluelm_specific_pkg">com.tosharoki.hwcts</string>
<string name="key_camera_specific_pkg">com.tosharoki.hwcts</string>
```

Both target packages point at the HwCTS accessibility app, left over from
testing. Harmless while the actions are `DEFAULT_ASSISTANT` / `MUTE_TOGGLE`,
but selecting **Specific App** would launch that instead of an assistant.
Clear or re-pick them.

### C.2 Three accessibility services are competing

Enabled on the device: ours, `com.tosharoki.hwcts/LensAccessibilityService`,
and `com.ivianuu.oneplusgestures/VividAccessibilityService`. All three watch
`TYPE_WINDOW_STATE_CHANGED`; the capture shows HwCTS surfacing right after our
assistant launch (`11:14:44.261 FG pkg=com.tosharoki.hwcts`).

Per `dumpsys accessibility`, only ours has the key-filter bit
(`capabilities=8`; HwCTS is 161, Vivid is 0), so there is no contention on key
events. But if either of the others also remaps this button through its own
mechanism, behaviour will look nondeterministic. Worth disabling them while
testing A′.

### C.3 V2 Phase C items still open

Unchanged and still worth doing, in this order:

- **C.1 (V2)** — `getInstalledLaunchableApps()` / `getInstalledAssistants()`
  still run `queryIntentActivities` + `loadLabel` synchronously in composition.
  Move to `Dispatchers.IO` behind `produceState`.
- **C.2 (V2)** — the assistant list is still built three times independently.
  Hoist to one cached `StateFlow`.
- **C.3 (V2)** — confirm `CameraManager.TorchCallback` is actually registered,
  or `isTorchOn` desyncs the moment quick settings touches the torch.
- **C.4 (V2)** — the diag ring buffer uses list copies; with capture armed and
  a key repeating this allocates steadily. Switch to a capped `ArrayDeque`.
- **C.5 (V2)** — `AssistantChooserActivity` re-entry under
  `singleTop` + `NEW_TASK|SINGLE_TOP` may not re-show the sheet.

### C.4 Size — nothing left worth doing

Release is **1.16 MB**, one 1.89 MB dex, `resources.arsc` 120 KB. The only
remaining items are ~27 KB of extra-ABI `libandroidx.graphics.path.so` and
~50 KB of unused Material3 translations. Not worth the risk. **Done.**

---

## Order

| # | Work | Notes |
|---|---|---|
| 0 | Tap **"Use this key (552)"** | Zero code. Confirms the whole diagnosis in one press |
| 1 | **A′.1** seed `KEYCODE_PRESS` by default | So no user has to do step 0 |
| 2 | **A′.4** verify `skipCameraApp` both ways | It has never once executed |
| 3 | **A′.3** cut camera cooldown to ~400 ms | One constant |
| 4 | **A′.2** fire on UP; decide on double-press | Behavioural, needs your call |
| 5 | **C.1 / C.2** clear stale prefs, disable rival services while testing | Cheap |
| 6 | **C.3** V2 leftovers | No user-visible urgency |

Step 4 is the only one with a real design decision in it: whether a double
press should keep opening the camera, or become a second assignable action.
