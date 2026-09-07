# Huawei Mate 80 — Celia interception plan

Goal: intercept `com.huawei.hiassistantoversea` on Huawei the way the app already
intercepts `com.vivo.ai.copilot` on Vivo, with every existing action working.

---

## Step 0 — Verify the device can run this app at all

**Do this before writing any code. Everything below is wasted if it fails.**

HarmonyOS NEXT (5.x / 6.x) is not Android and does not run APKs. If the Mate 80
in question ships HarmonyOS NEXT, no APK installs and no `AccessibilityService`
exists — the project cannot target it in its current form.

The package name `com.huawei.hiassistantoversea` is an Android package, which
suggests an Android-compatible build, but that must be confirmed on the actual
handset rather than assumed. Checks, in order:

1. Can `app-debug.apk` be sideloaded and opened at all?
2. Does Settings → Accessibility list installed services, and can this one be
   enabled?
3. `adb shell getprop | grep -iE "harmony|emui|ro.build.version"` — record it.

If (1) or (2) fails, stop and report; the rest of this plan does not apply.

---

## Step 1 — Understand what the current code assumes

Vivo is not a data entry in this codebase — it is baked into the control flow.
[BlueLMInterceptorService.kt](app/src/main/java/viva/la/circle/service/BlueLMInterceptorService.kt)
alone carries:

| Symbol | Vivo assumption |
|---|---|
| `KNOWN_PACKAGES` | six `com.vivo.*` / `com.bbk.*` packages |
| `isBlueLMOrVivoAssistant()` | substring rules for `bluelm`, `jovi`, `vivoassistant`, `bbk.voiceassistant`, `vivo`+`agent` |
| `isCopilotSecondaryUi()` | class-name filters for Copilot 5.6.x settings / gallery / circle-to-search |
| `isCopilotWakeUi()`, `containsCopilotWindow()` | named for Copilot |
| `MAX_DISMISS_BACKS = 3`, `COPILOT_DISMISS_TIMEOUT_MS = 400` | tuned against Copilot's window stack |
| `POWER_LONG_PRESS_MS = 500` | "Vivo Copilot is bound to long-press power" |
| `dismissCopilotBack()` | the dismissal routine itself |
| `VENDOR_SHUTTER_LABELS` | resolves to `[552, 529, 535]` on Vivo, `[]` elsewhere |
| `OriginOs` | reads `ro.vivo.os.*` only |

`TargetAction.HWCTS` and `CircleToSearch` add two more device-shaped
dependencies (see Step 5).

So this is not "add one package to a set". It is introducing a vendor seam.

---

## Step 2 — Introduce a vendor profile

Add `engine/VendorProfile.kt` holding everything currently hardcoded:

```kotlin
data class VendorProfile(
    val id: String,                        // "vivo" | "huawei" | "generic"
    val label: String,                     // shown in diagnostics + UI
    val assistantPackages: Set<String>,
    val assistantPackageHints: List<String>,   // substring rules, scoped per vendor
    val secondaryUiClassHints: List<String>,   // windows that must NOT be remapped
    val maxDismissBacks: Int,
    val dismissTimeoutMs: Long,
    val defaultDismissDelayMs: Int,
)
```

Then:

- Rename `isBlueLMOrVivoAssistant` → `isInterceptedAssistant(pkg, cls, ownPkg, profile)`.
- Rename `isCopilotWakeUi` → `isAssistantWakeUi`, `isCopilotSecondaryUi` →
  `isSecondaryUi`, `containsCopilotWindow` → `containsAssistantWindow`,
  `dismissCopilotBack` → `dismissAssistantBack`.
- Generalise `OriginOs` into `DeviceProfile.detect()` that returns
  `(vendor, osLabel, osMajor)` and picks the matching `VendorProfile`.

Keep the existing Vivo values **exactly as they are** in the `vivo` profile.
This step must be a pure refactor — verified by the existing 21 unit tests
staying green with no assertion changes.

Detection props:

| Vendor | Props to read |
|---|---|
| vivo | `ro.vivo.os.build.display.id`, `ro.vivo.os.version` (already implemented) |
| huawei | `ro.build.version.emui`, `ro.build.version.harmonyos`, `hw_sc.build.platform.version`, `ro.product.brand` |

`OriginOs.readProp()` already shells out to `getprop` and caches — reuse it
unchanged, just with more keys.

---

## Step 3 — Add the Huawei profile

```kotlin
val HUAWEI = VendorProfile(
    id = "huawei",
    label = "EMUI / HarmonyOS",
    assistantPackages = setOf(
        "com.huawei.hiassistantoversea",   // Celia, global builds
        "com.huawei.hiassistant",          // Celia, domestic builds
        "com.huawei.vassistant",           // older EMUI voice assistant
    ),
    assistantPackageHints = listOf("hiassistant", "vassistant"),
    secondaryUiClassHints = listOf(),      // unknown until Step 4
    maxDismissBacks = 3,                   // starting guess, tune in Step 4
    dismissTimeoutMs = 400L,
    defaultDismissDelayMs = 100,
)
```

Also add to `<queries>` in
[AndroidManifest.xml](app/src/main/AndroidManifest.xml) — without these,
package visibility hides them on API 30+:

```xml
<package android:name="com.huawei.hiassistantoversea" />
<package android:name="com.huawei.hiassistant" />
<package android:name="com.huawei.vassistant" />
<package android:name="com.huawei.camera" />
```

---

## Step 4 — Capture the real behaviour on the device

The Vivo work was only solved by measuring, never by guessing — the camera
keycode turned out to be `KEYCODE_PRESS` (552) from a virtual `vforce_input`
device, which no amount of reasoning would have produced. Apply the same method.
The diagnostics and capture mode already in the app are the whole toolkit.

Have the Mate 80 owner enable diagnostics and send the **Copy** report after:

1. **Triggering Celia** (however it is bound — long-press power, or a gesture).
   The `FG` lines give the real package **and class name** of the assistant
   window. Fills in `secondaryUiClassHints` and confirms `assistantPackages`.
2. **Arming key capture, then pressing every hardware button.** Gives keycodes,
   scancodes and device ids. On Vivo this is what revealed 552/550; Huawei will
   have its own set, and `VENDOR_SHUTTER_LABELS` almost certainly resolves to
   `[]` there.
3. **Triggering Celia with the interception enabled**, so the `BLM` lines show
   whether `GLOBAL_ACTION_BACK` actually dismisses it and how many attempts it
   takes. That tunes `maxDismissBacks` and `dismissTimeoutMs`.

Open questions only the device can answer:

- Does Celia appear as an Activity or an overlay (`android.widget.FrameLayout`,
  as Copilot does)? This decides whether the `isLikelyActivityWindow` foreground
  filter interferes.
- Does `GLOBAL_ACTION_BACK` close it, or is `GLOBAL_ACTION_HOME` needed? Celia
  may run as a system overlay that BACK does not dismiss — this is the single
  biggest technical risk after Step 0.
- Is Celia bound to long-press power (like Copilot), so `POWER_LONG_PRESS_MS`
  pre-emption applies, or to a gesture that produces no key event at all?

---

## Step 5 — Actions that will not survive the port

Two existing actions are device-specific and must degrade honestly rather than
fail silently:

- **`CIRCLE_TO_SEARCH`** goes through `SearchManager.launchAssist()` and the
  Google app (`com.google.android.googlequicksearchbox`). Huawei ships without
  GMS, so `CircleToSearch.Readiness` will report `Google app not installed`.
  The existing `blocker` string already handles this — verify it surfaces in the
  UI rather than presenting a dead option.
- **`TargetAction.HWCTS`** targets `com.tosharoki.hwcts`, which likely is not
  installed. `HWCTS_DISMISS_BACKS = 1` is tuned for it specifically.

`DEFAULT_ASSISTANT` should be fine: `launchDefaultAssistant` now invokes the
system assist gesture via `invokeSystemAssistGesture` / `startUnscopedAssistIntent`
rather than launching a package, so it routes to whatever Huawei has set —
including Celia itself, which the loop guard must then catch (see below).

**Loop risk:** on Huawei the system default assistant *is* Celia. Intercepting
Celia and then invoking the assist gesture would re-open Celia. The existing
loop guard checks `isBlueLMOrVivoAssistant(systemDefault, …)`; once renamed and
profile-driven per Step 2, it will cover Celia automatically — but this needs an
explicit test, because it is a hard loop, not a cosmetic bug.

---

## Step 6 — Power management

Huawei is the most aggressive OEM about killing background services. The
accessibility service will be stopped unless the app is added to
**Settings → Battery → App launch → manage manually** (allow auto-launch,
secondary launch, run in background).

Add a Huawei-specific onboarding card, shown only when the vendor profile is
`huawei`, linking to that screen. Without it the app will appear to work and
then silently stop, which is the worst failure mode for a button remapper.

---

## Order

| # | Work | Gate |
|---|---|---|
| 0 | Confirm the Mate 80 runs Android APKs and can enable the service | **Blocks everything** |
| 1 | Refactor to `VendorProfile`, Vivo values unchanged, 21 tests green | Pure refactor |
| 2 | Add Huawei profile + manifest `<queries>` | Cheap |
| 3 | Capture diagnostics on the real device | Answers Steps 4 unknowns |
| 4 | Tune dismissal (BACK vs HOME, back count, timeout) | Needs Step 3 |
| 5 | Degrade `CIRCLE_TO_SEARCH` / `HWCTS` visibly; test the Celia loop guard | Needs Step 2 |
| 6 | Huawei battery-whitelist onboarding | Independent |

Steps 1, 2 and 6 can be done without the device. Steps 3–5 cannot — and
attempting them by guesswork is exactly what cost several rounds on the Vivo
camera button.

---

## What is genuinely uncertain

Stated plainly, so it is not discovered late:

1. **Whether the Mate 80 runs Android at all.** Not verifiable from here.
2. **Whether `GLOBAL_ACTION_BACK` dismisses Celia.** If it does not, the whole
   Vivo strategy does not transfer and the dismissal needs a different
   mechanism.
3. **Whether Celia's trigger produces a key event** the service can see. On
   Vivo the assistant button never did — that is why detection goes through
   window events instead. Huawei may be the same, or worse.
4. **Whether Huawei permits this accessibility service** to keep running.

None of these are reasons not to start Steps 1–2, which are useful regardless
and make the codebase honest about being multi-vendor.
