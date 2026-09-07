# Huawei — "extra gesture" bug

Interception itself works on `HUAWEI PLR-L29` (Android 12): Celia is detected,
dismissed, and the target assistant launches. The defect is that **dismissal
overshoots and eats the user's app.**

Reported symptom: triggered from a browser → phone dropped to the home screen →
Google Assistant opened. Three visible states where there should be one.

---

## What the log shows

```
19:42:19.127  BLM copilot wake overlay=true pkg=com.huawei.hiassistantoversea cls=android.widget.FrameLayout
19:42:19.198  BLM BACK=true reason=initial count=1
19:42:19.241  BLM cooldown skip pkg=com.huawei.hiassistantoversea cls=android.widget.RelativeLayout
19:42:19.319  BLM BACK=true reason=poll  count=2
19:42:19.366  BLM BACK=true reason=poll  count=3
19:42:19.456  FG  pkg=com.huawei.android.launcher            ← browser is gone
19:42:19.581  CTS top=android backs=3
19:42:19.583  ACT invoked SearchManager.launchAssist
19:42:19.805  FG  pkg=com.google.android.googlequicksearchbox …FloatyActivity
```

Three `GLOBAL_ACTION_BACK` presses in 168 ms. Celia needed fewer; the remainder
landed on the browser and closed it. `MAX_DISMISS_BACKS = 3` is Vivo's value,
copied verbatim into `VendorProfile.HUAWEI` as an admitted guess.

**Every BACK beyond what the assistant needs is destructive** — it navigates or
exits whatever the user was in. That is the whole bug.

---

## Why the loop kept pressing

[BlueLMInterceptorService.kt:566](app/src/main/java/viva/la/circle/service/BlueLMInterceptorService.kt#L566):

```kotlin
dismissCopilotBack("initial", backBudget)          // BACK #1
while (now < deadline) {                            // deadline = +400ms
    delay(COPILOT_POLL_MS.milliseconds)             // 30ms
    if (!copilotWindowPresent()) break
    dismissCopilotBack("poll", backBudget)          // BACK #2, #3…
}
```

`canDismissCopilotBack` only requires `copilotPresent == true` and 40 ms since
the last press. So the loop presses BACK every 40 ms for as long as
`copilotWindowPresent()` keeps answering true.

`copilotWindowPresent()` → `containsCopilotWindow(windowPackageNames(), …)` →
**any** window in the accessibility window list whose root package is Celia.

Two problems with that as a stop condition:

1. **It scans every window, not the top one.** A dismissed overlay stays in the
   window list while it animates out. The app keeps pressing BACK at an app that
   is already closing, and those presses fall through to the browser underneath.
2. **30 ms poll / 40 ms minimum interval is far shorter than a window
   transition.** A typical dismiss animation is 150–300 ms. The loop is
   guaranteed to get several observations of a window that is on its way out.

The log is consistent with this: BACK #2 and #3 fired at +121 ms and +168 ms,
and the browser was gone by +258 ms.

---

## What cannot be determined from this log

`copilotWindowPresent()` returned true at 19:42:19.319 and .366, but the log
never records **what was in the window list**. So it is not currently possible
to distinguish "Celia genuinely still up" from "stale/exiting overlay".

This is the same gap that cost several rounds on the Vivo camera button: the log
records the decision but not the evidence. Fixing it is step 1, not step 5.

---

## Plan

### 1. Log the evidence, not just the verdict

In `dismissCopilotBack` and each poll iteration, emit the window list:

```
BLM poll windows=[com.huawei.hiassistantoversea, com.android.chrome, android] top=…
```

Without this, every number below is guesswork. One run from the device then
answers: how many BACKs Celia actually needs, and how long its window lingers.

### 2. Stop on the *top* window, not any window

`containsCopilotWindow` should become "is the assistant the focused/topmost
window", not "does the assistant appear anywhere in the list". A window that is
animating out is no longer the top window, so the loop stops one press earlier —
which is exactly the press that closed the browser.

`AccessibilityWindowInfo.isFocused` / `isActive`, or the last entry by layer,
rather than scanning all roots. This also cuts cost: `windowPackageNames()`
currently calls `window.root` for **every** window on every 30 ms tick, which is
an expensive binder round trip each time.

### 3. Hard stop when the foreground app changes

Capture the foreground package *before* the assistant opened (the service
already tracks `foregroundPackage`). Then: **never press BACK once the top
window is neither the assistant nor that remembered package.** If the launcher
appears, the dismissal has already overshot — stop immediately rather than
spending the remaining budget.

This is a safety net independent of any timing tuning.

### 4. Slow the loop to match reality

`COPILOT_POLL_MS = 30` and `COPILOT_HAMMER_MIN_INTERVAL_MS = 40` are tuned to
hammer. For a dismissal that must not overshoot, the minimum interval should be
longer than the window animation — start at 150 ms and confirm against step 1's
data. Fewer, better-timed presses.

Keep these per-profile, since Vivo's current behaviour is known-good and must
not regress.

### 5. Drop Huawei's back budget to 1 and measure up

`VendorProfile.HUAWEI.maxDismissBacks = 3` is a copy of Vivo's. Celia is a
single overlay; 1 is the likely correct value. Set it to 1, verify with step 1's
log that Celia actually closes, and only raise it if the evidence says so.

Same for `dismissTimeoutMs = 400`.

### 6. Close the visible gap

Even with the right back count, the launcher is visible from +329 ms to +678 ms
before Google Assistant appears. Once dismissal is correct, consider firing the
target action as soon as the assistant window stops being top, rather than after
the poll loop and `extraSettleMs` complete.

Lower priority — cosmetic once the browser stops closing.

---

## Secondary findings

### `os=unknown` — the vendor profile is not wired into detection

```
device=HUAWEI PLR-L29 sdk=31
os=unknown dismissDelay=100ms
```

`detectedOsLabel` still comes from `OriginOs.detect()`, which reads only
`ro.vivo.os.*`. The new `VendorProfile.current()` is not consulted, so:

- the report says `unknown` instead of `huawei / EMUI`;
- `dismissDelay=100ms` came from `OriginOs.defaultDismissDelayMs(null)`, not
  from `VendorProfile.HUAWEI.defaultDismissDelayMs`.

Wire `VendorProfile` into `InterceptorStateRepository` alongside `OriginOs`, and
add `vendor=` plus the EMUI version (`ro.build.version.emui`) to the report
header. Cheap, and every future Huawei report is otherwise unreadable.

### Celia's trigger produces no key event

Diagnostics were enabled, and unmatched keys are logged in that mode — yet there
is **no `KEY` line at all** before the Celia window appears. So whatever invokes
Celia (likely long-press power) never reaches `onKeyEvent`.

Same situation as Vivo's assistant button. Window detection is the only viable
route; nobody should go hunting for a keycode.

### `vendorShutterKeyCodes=[]` is expected, not a fault

The `PRESS` / `CAMERA_SHUTTER` / `CAMERA_HANDLE_SHUTTER` labels are Vivo-only
and resolve to nothing on Huawei. `camera=NONE` here anyway. If a camera key is
wanted on this device, capture mode is the route — exactly as on Vivo.

### `ACTION_ASSIST` resolves to a chooser

```
resolve ACTION_ASSIST = com.huawei.android.internal.app/HwResolverActivity
```

No default handler for the raw intent. It did not matter — `secure.assistant` is
Google and `SearchManager.launchAssist` worked (`FloatyActivity` opened). Worth
knowing that the `startUnscopedAssistIntent` fallback would pop a Huawei
resolver dialog on this device if it were ever reached.

---

## Order

| # | Work | Needs device |
|---|---|---|
| 1 | Log window list on every dismiss/poll | no |
| 2 | Stop on top window instead of any window | no |
| 3 | Hard stop when foreground app changes | no |
| 4 | Per-profile poll/interval timing, slower | no |
| 5 | Huawei `maxDismissBacks = 1`, then measure | yes, for confirmation |
| 6 | Close the launcher-visible gap | yes |
| — | Wire `VendorProfile` into detection + report | no |

Items 2 and 3 are the actual fix; 1 is what makes 5 verifiable rather than
another guess. All of 1–4 can be done and unit-tested without the phone —
`canDismissCopilotBack` is already a pure function with tests, and the new stop
conditions belong in the same place.

---

# Addendum — Celia still flashes on screen

Reported: Celia is visible for a fraction of a second before it is dismissed.
The log measures it, and most of it is self-inflicted.

## 71 ms of our own latency before the first BACK

```
19:42:19.127  BLM copilot wake overlay=true pkg=com.huawei.hiassistantoversea
19:42:19.198  BLM BACK=true reason=initial count=1      ← +71ms
```

Compare Vivo, where the first dismissal lands in **1 ms**:

```
11:14:43.276  FG  pkg=com.vivo.ai.copilot
11:14:43.277  BLM BACK=true delay=100ms …
```

Seventy-one times slower on the same code path. `notificationTimeout="0"`, so
this is not the system being slow to notify — it is the app.

## Where it goes

`dismissCopilotBack()` calls `copilotWindowPresent()` **before** the first BACK,
and that walks the whole window list calling `window.root` on each entry:

```kotlin
for (window in wins) {
    root = window.root                       // binder round trip, per window
    names.add(root?.packageName?.toString())
}
```

Each `window.root` is a synchronous binder call into the app that owns the
window. On a device with an overlay stack — Celia, browser, status bar, nav bar,
IME — that is easily five or more round trips, and it runs on
`Dispatchers.Main`, blocking the very coroutine that is supposed to press BACK.

**And the check is redundant.** The job was started *by* the accessibility event
that reports Celia's window. Presence is already proven; the app pays ~70 ms to
re-derive a fact it was just handed.

## Fix

1. **Skip the presence check for the initial BACK.** The triggering event is the
   evidence. Press BACK first, then start polling. This alone should take the
   flash from ~71 ms down to Vivo's ~1 ms.

   Restructure so `dismissCopilotBack(reason = "initial")` takes
   `assumePresent = true`, or hoist the first `performGlobalAction` out of the
   guarded path entirely.

2. **Make the poll's presence check cheap.** Combined with fix 2 in the main
   plan (top window instead of any window), the poll needs one
   `AccessibilityWindowInfo`, not `root` for all of them. Prefer
   `window.title` / `window.type` / the top entry's package over fetching every
   root node.

   `AccessibilityWindowInfo.getRoot()` is the expensive part — avoid it in the
   hot loop.

3. **Do not re-scan on the same event.** `copilotWindowPresent()` may be called
   several times per dismissal; cache the window snapshot for the duration of
   one dismissal pass.

## What will remain

Even at 1 ms, Celia's *own* opening animation has already begun by the time its
window-state event fires — the system dispatches the event when the window is
added, not when it becomes visible. So a brief flash is inherent to the
dismiss-after-the-fact strategy, on Huawei exactly as on Vivo.

Two options if it is still objectionable after fix 1, in increasing order of
effort:

- **Cover it.** Launch the replacement assistant earlier so its window paints
  over Celia's tail instead of waiting for the poll loop to finish (main plan,
  item 6). This hides the flash rather than removing it.
- **Pre-empt the trigger.** Vivo has `POWER_LONG_PRESS_MS = 500`, firing before
  the ROM opens Copilot. That requires seeing the trigger key — and on this
  Huawei there is **no key event at all** before Celia appears (see Secondary
  findings). So pre-emption is not available here unless a trigger signal can be
  found. Do not plan around it without evidence.

Measure fix 1 first: if `BLM BACK` lands within a few ms of `BLM copilot wake`
and the flash is still objectionable, the remaining time is the ROM's animation,
and only the covering approach helps.

---

# Addendum 2 — the BACK is fired before Celia can receive it

Two runs with the fixes in place. They show the overshoot guard working, the
71 ms latency gone (`assumePresent=true` → BACK at +2…4 ms), **and a new failure
that the previous addendum caused.**

## The evidence

**Run A — 20:12, user inside `viva.la.circle`. Works.**

```
20:12:01.440  BLM copilot wake … preAssist=viva.la.circle
20:12:01.444  BLM BACK=true reason=initial count=1 assumePresent=true      +4ms
20:12:01.627  BLM poll windows=[null, android, …, com.huawei.hiassistantoversea, viva.la.circle] top=android
20:12:01.628  BLM stop BACK: overshoot top=android preAssist=viva.la.circle
20:12:01.633  CTS top=android backs=1
```

Celia dropped off the top after one BACK. Guard stopped the loop. Correct.

**Run B — 20:13, screen recorder active. Broken.**

```
20:13:17.056  FG  pkg=com.opera.browser
20:13:19.816  FG  pkg=com.huawei.screenrecorder cls=…ScreenRecordService
20:13:21.407  BLM copilot wake … preAssist=com.huawei.screenrecorder
20:13:21.409  BLM BACK=true reason=initial count=1 assumePresent=true      +2ms
20:13:21.496  FG  pkg=com.huawei.android.launcher                          ← app minimised
20:13:21.604  BLM poll windows=[…hiassistantoversea…] top=com.huawei.hiassistantoversea
20:13:21.785  BLM poll … top=com.huawei.hiassistantoversea
20:13:21.981  BLM poll … top=com.huawei.hiassistantoversea
20:13:22.033  CTS top=com.huawei.hiassistantoversea backs=1                ← Celia never left
```

Celia stayed topmost through **every** poll. The single BACK did not touch it —
it went to whatever was underneath, and that is what minimised the app. Then the
target assistant launched on top of a Celia that was never dismissed.

## Root cause

At +2 ms Celia's window exists but is not yet the focused window. A
`GLOBAL_ACTION_BACK` at that moment is delivered to whatever still has focus —
the user's app. Hence "started minimising".

**This is a regression from Addendum 1.** Removing the presence check to kill
the 71 ms latency also removed the only thing that was accidentally waiting for
Celia to become focusable. The latency fix was right; firing unconditionally was
not.

Anton's read — "just add a small delay before the back gesture" — is correct.
The principled form is not a fixed delay but a **condition**.

## Fix: press BACK only while Celia is the top window

```
wait until top == assistantPackage        (poll ~16-30ms, cap ~250ms)
while top == assistantPackage and budget left:
    performGlobalAction(BACK)
    wait ~120ms                            (longer than the dismiss animation)
```

Why this is better than a fixed delay:

- **No BACK can ever reach the user's app.** While Celia is on top, BACK goes to
  Celia; the moment it is not, the loop stops. Safety is structural, not timed.
- **It self-tunes.** Fast devices proceed at once; slow ones wait as long as they
  need, up to the cap.
- **`maxDismissBacks` stops mattering.** The top-window condition is
  self-limiting, so the budget becomes a pure safety net rather than the primary
  control. Run B shows why a budget alone is not enough: it spent its single
  press on the wrong window and then had nothing left for the right one.

If the wait times out with Celia never on top, press nothing and go straight to
the action — a wasted BACK is worse than no BACK.

## Second defect in Run B: `preAssist` recorded a Service

```
preAssist=com.huawei.screenrecorder     cls=com.huawei.screenrecorder.ScreenRecordService
```

The user was in Opera. `com.huawei.screenrecorder` raised a window whose class is
a **Service**, and it overwrote the remembered foreground package — so the
overshoot guard was protecting the wrong thing.

`isLikelyActivityWindow` currently rejects only `android.widget.*`,
`android.view.*` and `android.inputmethodservice.*`. It passes anything else,
including `…ScreenRecordService`.

This is the third time this class of bug has appeared (`com.vivo.upslide`,
`com.vivo.smartshot`, now `com.huawei.screenrecorder`). Substring blocklists keep
losing. Prefer a positive test instead: accept a window as the foreground app
only when it is `AccessibilityWindowInfo.TYPE_APPLICATION` **and** active, rather
than guessing from the class name.

As a stopgap, also reject class names ending in `Service`.

## Third: launching over an undismissed Celia

Run B fired `SearchManager.launchAssist` while `top=com.huawei.hiassistantoversea`.
Google's Floaty opened over Celia, leaving Celia alive underneath. Once the fix
above lands this should not happen, but the guard is worth having: if the wait
loop ends with Celia still on top, log it loudly — `assistant still top after N
backs` — so the case is visible rather than silent.

## Revised order

| # | Work | Note |
|---|---|---|
| 1 | Wait for `top == assistant` before the first BACK | fixes the minimising |
| 2 | Keep pressing only while `top == assistant`, ~120 ms apart | replaces the budget as primary control |
| 3 | `preAssist` from window type, not class name | fixes the wrong-app guard |
| 4 | Log `assistant still top` when the loop gives up | visibility |
| 5 | Re-measure; only then revisit `maxDismissBacks` / `dismissTimeoutMs` | numbers stay guesses until 1-3 land |

Items 1–4 need no device. The `assumePresent` shortcut from Addendum 1 should be
**kept** — the latency win was real; it is the unconditional press that must go.

---

# Addendum 3 — "Открыть настройки запуска" leads nowhere

Tapping the Huawei battery card's button shows the system chooser with
**"Действие не поддерживается ни в одном приложении"** — nothing handles the
intent. Two separate causes, and the second one is not limited to this button.

## Cause 1 — `com.huawei.systemmanager` is not in `<queries>`

[HuaweiPowerManagement.kt:38](app/src/main/java/viva/la/circle/engine/HuaweiPowerManagement.kt#L38)
targets two explicit components:

```
com.huawei.systemmanager/…startupmgr.ui.StartupNormalAppListActivity
com.huawei.systemmanager/…appcontrol.activity.StartupAppControlActivity
```

The manifest `<queries>` block lists the assistants and cameras added for
Huawei, but **not `com.huawei.systemmanager`**. On API 30+ package visibility
therefore hides it, `resolveActivity` answers null for both components, and they
are skipped before they can be tried.

Fix: add to `<queries>`.

```xml
<package android:name="com.huawei.systemmanager" />
```

## Cause 2 — `resolveActivity() != null` is not a valid guard on this ROM

This is the more interesting one. The report already showed it:

```
resolve ACTION_ASSIST        = com.huawei.android.internal.app/…HwResolverActivity
resolve ACTION_VOICE_COMMAND = com.huawei.android.internal.app/…HwResolverActivity
```

EMUI answers unresolved **implicit** intents with `HwResolverActivity` instead of
null. So `intent.resolveActivity(pm) != null` passes, `startActivity` launches
the resolver, and the resolver then reports that nothing supports the action —
which is exactly the dialog in the screenshot. The first candidate,
`Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER")`, is implicit, so it takes
this path and the loop `return`s true before ever reaching the guaranteed
`ACTION_APPLICATION_DETAILS_SETTINGS` fallback.

Fix, in `HuaweiPowerManagement.openAppLaunchSettings`:

- Treat a resolution as valid only when the resolved `activityInfo.packageName`
  is the package actually intended — reject anything in
  `com.huawei.android.internal.app`, or any component whose name contains
  `ResolverActivity`.
- For explicit `ComponentName` intents, drop the pre-check entirely and rely on
  `try { startActivity() } catch (ActivityNotFoundException)`. That is both
  simpler and immune to this ROM quirk.
- Only then fall through to `ACTION_APPLICATION_DETAILS_SETTINGS`, which always
  exists and is a genuinely useful landing page.

### The same guard is used in six other places

```
CircleToSearch.kt:116
ActionExecutionEngine.kt:325, 377, 391, 596, 667, 673
```

Most are scoped with `setPackage(...)`, where the resolver cannot match, so they
are safe. The ones to check are any **unscoped implicit** intents — notably
`startUnscopedAssistIntent`, which on this device would pop the Huawei resolver
rather than failing over. Audit them for the same pattern; add a shared helper:

```kotlin
fun Intent.resolvesToRealActivity(pm: PackageManager): Boolean {
    val info = resolveActivity(pm) ?: return false
    return info.packageName != "com.huawei.android.internal.app" &&
        !info.className.contains("ResolverActivity")
}
```

## Cause 3 — the card cannot tell whether the setting is already correct

There is no readable API for Huawei's "App launch" state, so the card is purely
advisory and shows on every Huawei device forever, even when the user has
already granted everything.

Options, cheapest first:

- Add a "Уже настроил / скрыть" dismissal persisted in preferences.
- Infer indirectly: if the accessibility service has been continuously connected
  for a long period across reboots, stop showing it. Weak, but no API exists.

Do not attempt to detect it through `PowerManager.isIgnoringBatteryOptimizations`
— that is the AOSP doze whitelist, a different thing from Huawei's App launch
manager, and it will read "true" while EMUI still kills the service.

## Also worth adding while in this file

EMUI 15 / HarmonyOS renames these screens often. Add the known alternates to
`appLaunchIntents` before the details-page fallback:

```
com.huawei.systemmanager/.optimize.process.ProtectActivity
com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity
com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity
huawei.intent.action.HSM_BOOTAPP_MANAGER          (implicit, guard per Cause 2)
```

Order matters: explicit components first (try/catch), implicit action next
(with the real-activity guard), details page last.

## Order

| # | Work | Note |
|---|---|---|
| 1 | `<package android:name="com.huawei.systemmanager" />` in `<queries>` | one line |
| 2 | `resolvesToRealActivity` helper; use it in `openAppLaunchSettings` | fixes the dead button |
| 3 | try/catch `startActivity` for explicit components instead of pre-checking | simpler and quirk-proof |
| 4 | Audit the other six `resolveActivity != null` sites for unscoped intents | `startUnscopedAssistIntent` first |
| 5 | Dismissable battery card | UX |

None of this needs the device; item 2 is verifiable by a unit test over a fake
`PackageManager` that returns `HwResolverActivity`.

---

# Addendum 4 — closing Celia without a simulated gesture

`GLOBAL_ACTION_BACK` is global: it goes to whatever has focus, not to a window we
name. Every bug in Addendum 2 follows from that one property. Four alternatives,
best first.

## Option A — become the assistant, so Celia never opens

The most reliable fix is to not have anything to dismiss.

Register our own `VoiceInteractionService`. When the user sets **Viva la Circle**
as the digital assistant, the long-press-power gesture is delivered to us
directly; Celia is never invoked. No BACK, no race, no flash, nothing to
overshoot, and the user's app is never touched.

This is not speculative — it is exactly what HwCTS does, and we already proved it
works on a shipping device:

```
secure.assistant = com.tosharoki.hwcts/.StubAssistantService
cmd role get-role-holders android.app.role.ASSISTANT → com.tosharoki.hwcts
query-activities -a android.intent.action.ASSIST | grep hwcts → (empty)
```

A role holder with no assist activity at all, purely to receive the gesture. The
codebase already understands this shape — `hasVoiceInteractionService()` at
[ActionExecutionEngine.kt:610](app/src/main/java/viva/la/circle/engine/ActionExecutionEngine.kt#L610)
exists to recognise it in *other* apps. We consume the pattern without providing
it.

What it needs:

- `VoiceInteractionService` + `VoiceInteractionSessionService` +
  `VoiceInteractionSession`, declared with
  `android.permission.BIND_VOICE_INTERACTION` and an
  `<meta-data android:name="android.voice_interaction">` resource.
- `onShow()` fires the configured `TargetAction` and immediately calls
  `hide()` / `finish()` — the session never draws UI.
- Onboarding: we cannot set the role ourselves; the user picks it in
  Settings → Digital assistant app. Deep-link there
  (`Settings.ACTION_VOICE_INPUT_SETTINGS`, falling back to the assistant picker).

Caveats to verify on the device, not assume:

1. **Is the Huawei power-long-press actually bound to the assist role, or
   hardcoded to Celia?** If Huawei hardcodes it, Option A does nothing there.
   The HwCTS evidence is from Vivo. Test by setting HwCTS (already installed on
   the Vivo) or any third-party assistant as default on the Huawei and seeing
   whether long-press still opens Celia.
2. Becoming the assistant means `DEFAULT_ASSISTANT` now resolves to **us** — the
   existing loop guard must catch it, or the app calls itself.
3. Some ROMs restrict which apps may hold the role.

Even where it works, keep the accessibility path as a fallback for users who
would rather not hand over the assistant role.

## Option B — `ACTION_DISMISS` on Celia's own window

Targeted instead of global, so it structurally cannot hit the user's app.

`canRetrieveWindowContent="true"` is already set, so the root node of Celia's
window is reachable. If that node advertises
`AccessibilityNodeInfo.ACTION_DISMISS`, performing it closes that window and
nothing else — no focus race, no overshoot, no dependency on Celia being topmost.

First step is a probe, not an implementation: for the window whose package is
`com.huawei.hiassistantoversea`, log `root.actionList`. One run says whether this
is available.

```
BLM celia window actions=[ACTION_DISMISS, ACTION_ACCESSIBILITY_FOCUS, …]
```

If `ACTION_DISMISS` is absent, the same probe often reveals a close button that
`ACTION_CLICK` can hit — more fragile, but still targeted rather than global.

## Option C — do not dismiss at all; just cover it

Possibly the cheapest correct answer, and it is untested.

The whole BACK machinery assumes Celia must be removed. But launching the target
assistant brings a new task to the front, and an assistant overlay that loses
focus normally closes itself. In Run B the target launched while Celia was still
top:

```
20:13:22.033  CTS top=com.huawei.hiassistantoversea backs=1
20:13:22.036  ACT invoked SearchManager.launchAssist
20:13:22.191  FG  pkg=com.google.android.googlequicksearchbox …FloatyActivity
```

The log simply does not record what happened to Celia afterwards. Add a poll a
few hundred ms **after** the launch: if Celia is gone on its own, the entire
dismissal path can be skipped on Huawei — set `maxDismissBacks = 0` and be done.

One measurement decides this. Do it before building Option A or B.

## Option D — keep BACK, but aim it

If A–C all fail, the fallback is Addendum 2's design: press only while
`top == assistant`, spaced beyond the animation. Reliable enough, but it stays a
timing game, which is why it ranks last.

Note that `GLOBAL_ACTION_HOME` and `killBackgroundProcesses` are **not**
alternatives worth pursuing — HOME is as destructive as an extra BACK (it drops
the user to the launcher), and `killBackgroundProcesses` cannot touch a visible
process and would be the wrong tool if it could.

## Order

| # | Work | Cost | Decides |
|---|---|---|---|
| 1 | Poll for Celia ~300 ms after the target launches | trivial | whether Option C already solves it |
| 2 | Log `root.actionList` for Celia's window | trivial | whether Option B is available |
| 3 | Implement whichever of C / B the probes support | small | — |
| 4 | `VoiceInteractionService` (Option A) | large | removes the problem class entirely |

Steps 1 and 2 are two diagnostic lines and one device run. Neither should be
skipped in favour of guessing — that is the lesson from the camera keycode and
from the BACK timing regression alike.
