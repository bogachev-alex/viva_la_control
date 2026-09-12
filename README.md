# Viva La Control

Remaps the hardware buttons on Vivo and Huawei phones that the vendor
does not let you change, and replaces the gesture navigation pill with
a configurable one.

No root. Works through an accessibility service; one optional
permission is granted once over ADB.

## What it does

**Assistant button / long-press Power**
On Vivo (OriginOS) a long power press opens BlueLM; on Huawei
(EMUI / HarmonyOS) it opens Celia. Neither ROM lets you change that.
The app watches for the assistant's wake UI, dismisses it and fires
the action you picked instead. Short-press Power is untouched.

**Shutter (camera button)**
Half-press, full press and camera grips arrive as different keycodes.
The app learns them via key capture and remaps the whole trigger to
one action. Optional: pass-through while a camera app is in the
foreground, so double-press to open the camera keeps working.

**Volume keys**
- Long-press Vol+ / Vol− skips to next / previous track while media
  plays. With the ADB permission below it works with the screen off
  and without changing the volume.
- Short-press can be remapped per key; keys left on "Volume" behave
  exactly like stock.

**Gesture Handle**
Replaces the system navigation pill with an app-drawn one. Tap,
long-press, swipe up / left / right are remappable; edge swipe is
Back and swipe-up-and-hold is Recents. Adjustable size, position,
colour, opacity, per-gesture haptics, auto-hide in fullscreen.

### Actions

Assistant chooser · system default assistant · Circle to Search ·
HwCTS (Huawei) · launch a specific app · flashlight · screenshot ·
mute / unmute · Home · Back · pass-through.

## Requirements

- Android 6.0+ (API 23). Built for Vivo OriginOS and Huawei
  EMUI / HarmonyOS.
- Accessibility service enabled for the app.
- Notification access (optional) for more accurate media detection.
- `SET_VOLUME_KEY_LONG_PRESS_LISTENER` (optional, Vivo) for
  screen-off long-press skip. The app copies the exact command to
  your clipboard; it is:

  ```bash
  adb shell pm grant viva.la.circle android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
  ```

## Install

Download `viva_la_control.apk` from
[Releases](https://github.com/bogachev-alex/viva_la_control/releases)
and sideload it.

Then, in the app:

1. **Enable in Accessibility Settings** → Downloaded apps →
   Viva La Control → on.
2. Pick an action for each trigger you want remapped. Triggers with
   no action stay pass-through.
3. **Huawei only:** Settings → Battery → App launch → Viva La Control
   → Manage manually, allow Auto-launch, Secondary launch and Run in
   background. Otherwise the ROM kills the service.
4. **Gesture Handle only:** hide the system gesture indicator in the
   phone's navigation settings first, or you get two pills.

## Privacy

The app has no network permission and no analytics. The accessibility
service reads window state to recognise the assistant overlay; the
in-app diagnostics log is kept in memory only and is cleared when the
process dies. Settings are stored in private app preferences.

## Build

```bash
./gradlew :app:assembleDebug
```

Release builds are signed in CI from repository secrets
(`ANDROID_KEYSTORE_*`); without them `assembleRelease` produces an
unsigned APK. See [`.github/workflows/ship.yml`](.github/workflows/ship.yml).

Kotlin · Jetpack Compose · Material 3 · no third-party runtime
dependencies beyond AndroidX.
