# AlarmOff

AlarmOff lets you select several saved OnePlus Clock alarms and turn their switches off or on together. It runs on Android 13 or later. The phone must be unlocked, with Clock visible during a read or change.

## Install

1. Open [GitHub Actions](../../actions/workflows/android.yml) and choose a successful run. Under **Artifacts**, download **AlarmOff-debug**. GitHub may ask you to sign in.
2. Extract the ZIP and install **AlarmOff-debug.apk**. Android may ask you to allow the app opening the APK to install it. For USB installation, use `adb install -r AlarmOff-debug.apk`. The ZIP includes a SHA-256 checksum.
3. Open **AlarmOff → Review access**. Read the disclosure and select **Agree and open settings**. Under your device’s downloaded accessibility services, enable **AlarmOff Clock access**, then return to AlarmOff. On some sideloaded installations Android requires **App info → ⋮ → Allow restricted settings** first. Menu names vary by OxygenOS release.
4. Tap **Read alarms**. If Clock opens a different tab, open **Alarm**. Keep Clock visible until the scan finishes. If OxygenOS keeps Clock in front afterward, switch back to AlarmOff through Recents.

The artifact is a debug build. Builds signed by different debug keys require uninstalling the previous copy before installing the replacement. This clears the app’s consent setting. Alarm schedules live in OnePlus Clock.

## Use

- **Refresh** reads the foreground list again. The counts and timestamp describe the last complete scan. A dash means AlarmOff has not read the list yet.
- Check individual alarms, or use **Select all** and **Deselect all**. **Disable selected** and **Enable selected** show the exact targets for review. Alarms already in the requested state are skipped.
- **Disable all alarms** reviews the active alarms from the last complete scan. Check the listed times and confirm. The app then opens Clock, compares the live list with your review, presses each matching switch, and checks the result. It refreshes the entire list at the end.
- Add **AlarmOff** from the launcher’s **Widgets** menu. One tap on **Disable all alarms** starts a new scan and opens a confirmation showing the currently active alarms. The next tap confirms the changes. With zero active alarms it only reports that result.
- If interrupted, read the verified count and check the indicated alarm in Clock. A bulk operation can stop partway. Completed changes are kept. Refresh before making another selection.
- Use **Clock access → Disconnect** to revoke in-app consent, stop the service and clear the in-memory list. Android Accessibility settings also let you turn the service off.

## Why foreground Clock access is needed

| Interface investigated | What it provides | How AlarmOff uses it |
| --- | --- | --- |
| Android `AlarmClock` intents | `ACTION_SHOW_ALARMS` opens the list. `ACTION_DISMISS_ALARM` dismisses an occurrence and has different behavior for repeating alarms. `ACTION_SET_ALARM` may create an alarm. These intents do not return all saved alarm records or set their permanent enabled flags. | Opens the supported Clock package’s Alarm view. Falls back to its launcher if necessary. |
| Android `AlarmManager` | `getNextAlarmClock()` exposes the next scheduled alarm-clock event. `cancelAll()` concerns alarms scheduled by the calling app. | Neither method can supply the inventory or control OnePlus Clock’s saved switches. |
| OnePlus / OxygenOS | The OnePlus manual documents using the Clock UI. Our review did not identify a published third-party provider contract, read/write permission, or supported bulk-toggle intent for its alarm database. An internal provider on a particular release would not establish a supported public contract. | No provider probing, private URIs, copied databases, vendor permissions, root, or hidden APIs. |
| Android accessibility | With explicit system access, a service can inspect a visible node tree, use scroll actions and click an exposed switch. | Runs deterministic operations only after a user request, checks the window and reads back switch state. |

Sources: [Android AlarmClock](https://developer.android.com/reference/android/provider/AlarmClock), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager#getNextAlarmClock()), [accessibility services](https://developer.android.com/guide/topics/ui/accessibility/views/service), [node actions](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction), [OxygenOS 14 user manual, Clock](https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf), [Android restricted settings](https://support.google.com/android/answer/12623953).

## OxygenOS compatibility and privacy

- The distributed app looks for a preinstalled or system-updated Clock package using the OnePlus, OPlus or ColorOS package IDs configured in `app/src/main/res/values/strings.xml`. These are candidate packages. The package name alone does not guarantee compatible row semantics.
- AlarmOff reads visible **Alarm** rows. It needs an identifiable time, a checkable switch and a verifiable list boundary. Long lists need stable row positions or a reliable total. It stops on ambiguous layouts, changed rosters, missing rows, verification failures or OEM prompts. It never chooses a screen coordinate, deletes an alarm, guesses a switch state, or presses an OEM “skip next” dialog.
- The app preserves recognizable 12/24-hour time and exposed names and repeat rules. Missing metadata is marked in the UI. English text fallbacks supplement resource IDs. Other languages, merged descriptions, new Clock layouts and custom holiday or sleep schedules may require manual handling. Give identical alarms distinct labels in Clock before bulk-selecting them.
- If your version is unsupported, use **Open Clock** in the result and change its switches directly. To check a new OxygenOS build, create two disposable alarms with different labels, include a repeating alarm, cancel one review, then disable and re-enable them. Check the actual switches in Clock after each action.
- AlarmOff declares no Internet, exact-alarm, overlay or storage permission. Android alone binds the service using `BIND_ACCESSIBILITY_SERVICE`. Alarm details, selection and pending operations live only in memory. Only the consent flag is stored in private preferences. The service has no idle scanning, analytics, network upload or restartable automation.
- This is a sideloadable utility. Its service declares `isAccessibilityTool=false`. Google Play distribution would require the relevant accessibility declaration and review. [Google Play policy](https://support.google.com/googleplay/android-developer/answer/10964491)

## Build and test

Use JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME` or configure `sdk.dir` in `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build uses Kotlin, Jetpack Compose, Material 3, AndroidX ViewModel and Gradle Kotlin DSL. The small launcher widget uses Android RemoteViews. The accessibility adapter is separate from the UI-independent parser and bulk engine.

CI builds and verifies the signed debug APK. JVM tests cover parsing, identity collisions, verified pagination, stale selections, retries and partial failures. A separate QA build and fixture APK exercise the Android accessibility service, persistent fixture switches, Compose review UI and an actual AppWidgetHost on API 33 and 35. The distributed debug APK can only target supported system Clock packages. Fixture results establish behavior on the tested Android systems. Physical OnePlus/OxygenOS compatibility needs device-specific verification.

To reproduce the end-to-end check, start a disposable emulator and run:

```sh
./gradlew :app:assembleQa :clock-fixture:assembleDebug :clock-fixture:assembleDebugAndroidTest
python3 testing/device_smoke.py
```

The harness clears only the two test apps, configures accessibility and widget binding on that emulator, and saves its result under `evidence/`. It refuses to target a physical phone.
