# BlueLM Interceptor — Plan V4 (addendum to V3)

Interception now works. `vendorShutterKeyCodes=[552, 529, 535]` resolved at
runtime — `KEYCODE_PRESS` (on-body button) plus `CAMERA_SHUTTER` (529) and
`CAMERA_HANDLE_SHUTTER` (535), so the Seafrogs housing and SmallRig grip are
covered too, with no hardcoded numbers. Fire-on-UP works and reports sane hold
times (86–154 ms).

The `11:31–11:32` log exposes four bugs, all in the camera-app skip path. They
compound: #1 creates the condition, #2 keeps it alive, #3 corrupts key state
while it lasts.

---

## Bug 1 — `com.vivo.smartshot` is the screenshot UI, not a camera app

`com.vivo.smartshot` is Vivo's screenshot / screen-recording overlay. It sits
in `KNOWN_CAMERA_PACKAGES`
([BlueLMInterceptorService.kt:65](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L65))
and again as a substring rule at
[line 127](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L127).

So the `SCREENSHOT` action locks the button out of its own result:

```
11:31:53.103  KEY fired SCREENSHOT on UP held=102ms result=true
11:31:53.195  FG pkg=com.vivo.smartshot                    ← screenshot overlay appears
11:31:54.495  KEY rejected: camera app foreground for 1300ms
11:31:55.135  KEY rejected: camera app foreground for 1940ms
…
11:31:58.319  KEY rejected: camera app foreground for 5124ms   ← five seconds of dead button
```

Six consecutive presses swallowed because taking a screenshot raises a window
the app misclassifies as a camera.

**Fix:** drop `"com.vivo.smartshot"` from `KNOWN_CAMERA_PACKAGES` and drop
`combined.contains("smartshot")` from `isCameraApp()`.

Reconsider `"com.vivo.doubleclickcamera"` and its substring rule at the same
time — that is the double-click-to-launch *handler*, not a camera UI that the
user could be shooting with. It has the same false-positive shape. Keep the
real ones (`com.android.camera`, `com.vivo.camera`, …).

The broad `pkg.contains("camera") || cls.contains("camera")` rules stay risky
for the same reason. Consider matching the exact set only, and letting the user
add packages — the capture UI already establishes that pattern.

---

## Bug 2 — `foregroundPackage` tracks overlays, not activities

`onAccessibilityEvent` updates `foregroundPackage` on **every**
`TYPE_WINDOW_STATE_CHANGED`, whatever the window is. The log shows what leaks
in:

```
FG pkg=com.android.camera          cls=com.android.camera.CameraActivity   ← real activity
FG pkg=com.bbk.launcher2           cls=com.bbk.launcher2.Launcher          ← real activity
FG pkg=com.vivo.smartshot          cls=android.widget.FrameLayout          ← overlay
FG pkg=com.vivo.upslide            cls=android.widget.FrameLayout          ← gesture overlay
```

`com.vivo.upslide` is the navigation-gesture layer; it became "foreground"
mid-press at `11:32:00.443`. Neither it nor smartshot is what the user is
looking at.

**Fix:** only update `foregroundPackage` / `foregroundSinceMs` for windows that
are actually activities — e.g. ignore events whose `className` is a generic
`android.widget.*` / `android.view.*` container, or check the window type via
`event.windowId` against `windows`.

**Important:** apply this filter *only* to foreground tracking. The BlueLM
detection path must keep accepting non-activity windows — `com.vivo.ai.copilot`
arrives as `android.widget.FrameLayout` and that detection currently works.
These are two separate concerns sharing one callback; split them.

---

## Bug 3 — the guard runs on UP as well as DOWN, so a press can be half-consumed

`shouldPassThroughCameraKey` is evaluated at
[line 215](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L215),
*before* the `when (event.action)` block — so it re-runs on `ACTION_UP` with
whatever `foregroundPackage` has become in the meantime:

```
11:31:54.368  KEY DOWN consumed, wait for UP
11:31:54.495  KEY rejected: camera app foreground for 1300ms (grace=1200ms)
```

`DOWN` was consumed and returned `true`; `UP` returned `false` and was
delivered downstream. **The key goes down and never comes up** — the exact
asymmetric-consumption defect V1 Phase 4.3 set out to fix, reintroduced through
the guard's placement.

It also leaks the latch: the early `return false` skips the `ACTION_UP` branch,
so `consumingCameraKey` is never cleared and stays `true` indefinitely.

The window can genuinely change mid-press — `11:32:00.424` DOWN under
`com.bbk.launcher2`, `11:32:00.541` UP under `com.vivo.upslide`, 117 ms apart.

**Fix:** decide once, at `ACTION_DOWN`, and latch it:

```kotlin
when (event.action) {
    KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount > 0) return consumingCameraKey
        if (shouldPassThroughCameraKey(...)) {      // evaluated here only
            consumingCameraKey = false
            diag("KEY", "rejected: camera app foreground …")
            return false
        }
        consumingCameraKey = true
        shutterDownEventTime = event.eventTime
        return true
    }
    KeyEvent.ACTION_UP -> {
        val wasConsuming = consumingCameraKey
        consumingCameraKey = false                  // always cleared
        if (!wasConsuming) return false
        …fire…
        return true
    }
}
```

Every path through `ACTION_UP` must clear the latch, including the cooldown
rejection (which already returns `true` — correct, since its DOWN was consumed).

---

## Bug 4 — the grace window makes the first press after a screenshot a coin flip

`CAMERA_FOREGROUND_GRACE_MS = 1200` against a window that appeared 1300 ms ago
(`11:31:54.495`) means the same gesture succeeds or fails depending on how fast
the user presses again. Fixing Bugs 1 and 2 removes the smartshot and upslide
cases, which is most of it.

The grace window is still the right mechanism for the real case — "did this
press open the camera, or was the user already shooting?" — so keep it, but
re-test the timing once Bug 1 is out, and consider raising it now that it only
fires for genuine camera packages.

---

## Interaction worth knowing about

`SCREENSHOT` as the camera action is self-interfering by nature: it raises a
system window that changes foreground state right after firing. Once Bugs 1–3
are fixed it behaves, but it is the harshest test case — keep using it while
verifying, then re-check with `DEFAULT_ASSISTANT` and `FLASHLIGHT`.

---

## Verification, after the fixes

1. Set camera action = `SCREENSHOT`. Press five times, ~1 s apart, from the
   launcher. **All five** must fire — no `camera app foreground` rejections.
2. Open `com.android.camera`, wait >1.2 s, press the shutter. The photo must be
   taken, and the log must show `rejected: camera app foreground`.
3. Press once and check the log shows exactly one `DOWN consumed` and one
   `fired … on UP` — never a `DOWN consumed` followed by a `rejected` on UP.
4. Double press: confirm whether `550 KEYCODE_DOUBLE_CLICK` still appears now
   that 552 is consumed. **Unknown** — the log has no double press in it, and
   consuming 552 may suppress the OS's double-click detection entirely. If 550
   no longer fires, the "double press opens camera" behaviour is gone as a side
   effect, which changes the V3 A′.2 design decision.
5. Trigger BlueLM and confirm the assistant path still works — Bug 2's fix
   touches the shared callback.

---

## Still open from V3

- **A′.2** — fire-on-UP is done. The double-press decision is still yours, and
  verification step 4 above may settle it for you.
- **C.1 / C.2** — stale `specific_pkg` prefs still point at `com.tosharoki.hwcts`;
  two rival accessibility services still enabled.
- **C.3** — V2 leftovers: main-thread package queries, triple-built assistant
  list, torch callback, diag ring-buffer allocations, chooser re-entry.
- **Size** — release 1.16 MB. Done.

---

# Addendum — the `11:36` log

Same build (Bugs 1–4 not yet applied). It reconfirms all four and settles V4
verification step 4, with an answer that changes the design.

## Bug 5 — consuming 552 does **not** stop the camera launch

```
11:36:27.098  KEY fired SCREENSHOT on UP held=97ms result=true
11:36:27.167  FG pkg=com.vivo.smartshot
11:36:27.182  KEY DOWN consumed, wait for UP          ← smartshot only 15ms old, under grace
11:36:27.344  KEY rejected: camera cooldown held=92ms ← returns true, so also consumed
11:36:27.348  FG pkg=com.android.camera               ← camera opens anyway, 4ms later
```

Both halves of that press were consumed by the service, and the camera still
launched. The double-click is signalled by a **separate keycode — 550
`KEYCODE_DOUBLE_CLICK`** — which is not in `cameraKeyCodes` or
`vendorShutterKeyCodes` (`[552, 529, 535]`), so it falls straight through
`super.onKeyEvent()` to the system, which acts on it.

This answers V4 verification step 4: **the "double press opens the camera"
behaviour survives consuming 552.** It is not going away on its own, so the V3
A′.2 decision is still live and now has a concrete mechanism:

- **Keep camera-on-double-press** → do nothing; leave 550 unmatched.
- **Take the double press as a second action** → match **550** and consume it.
  Consuming 550 is what suppresses the OS launch; consuming 552 never could.

## Bug 6 — the diagnostics can't see unmatched keys

`onKeyEvent` returns at
[`if (!isCameraShutterKey(...)) return super.onKeyEvent(event)`](app/src/main/java/com/example/bluelm_interceptor/service/BlueLMInterceptorService.kt#L194)
**before** the `diag("KEY", "shutter …")` line. So 550 was firing throughout
these sessions and is completely absent from the log — the evidence for Bug 5
had to be inferred from the camera launching rather than read directly.

Outside capture mode the log shows only keys the app already handles, which is
precisely backwards for debugging. When `diagnosticsEnabled` is on, log
unmatched shutter-family keys too (or all keys, rate-limited), before the gate.

## Bug 7 — the 400 ms cooldown makes a double press strictly worse

At `27.098` the action fired; at `27.344`, 246 ms later, the second press was
rejected as `camera cooldown` — and the camera opened regardless. So a quick
double press currently yields: action fires once, second press swallowed,
camera opens anyway. Worst of the three possible outcomes.

`CAMERA_COOLDOWN_MS = 400` overlaps the double-press window. Either drop it to
~150 ms (long enough to debounce, short enough not to eat a deliberate second
press), or fold it into the double-press timer if 550 handling lands.

## Bug 4 revisited — the grace window fails in both directions

`11:36:23.765`: smartshot 2547 ms old → rejected (too old).
`11:36:27.182`: smartshot 15 ms old → accepted (too new).

Same window, opposite verdicts, seconds apart — the outcome depends entirely on
how fast overlays cycle. The grace window is not a sound mechanism while
smartshot and upslide are still in the foreground set. Fix Bugs 1 and 2 first,
then re-tune; do not tune the grace value on its own.

## Revised order

| # | Fix | Why |
|---|---|---|
| 1 | **Bug 1** — drop `smartshot` / `doubleclickcamera` | Removes the lockout entirely |
| 2 | **Bug 2** — track activities, not overlays | Removes `upslide`; stop applying it to BlueLM detection |
| 3 | **Bug 3** — evaluate the guard at DOWN only, latch it | Fixes half-consumed keys |
| 4 | **Bug 6** — log unmatched keys under diagnostics | Cheap; you're flying blind without it |
| 5 | **Bug 7** — cooldown to ~150 ms | One constant |
| 6 | **Bug 5** — decide on 550 | Design call, now with a known mechanism |
| 7 | **Bug 4** — re-tune grace after 1–3 land | Meaningless before then |
