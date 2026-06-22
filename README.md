# Snipshot

Auto-crop screenshots on Pixel. The moment a screenshot is captured, a marching-ants crop overlay appears over whatever's on screen. Tap = keep full. Drag = crop. Do nothing = full screen saved after 3 seconds.

Sideload-only — designed for personal Pixel devices, not Play Store.

## Demo

> _Demo GIF coming soon — recorded on-device (`adb screenrecord`): screenshot captured → marching-ants overlay → drag-crop → saved._

<!-- ![Snipshot demo](docs/demo.gif) -->
<!-- ![Overlay](docs/overlay.png) ![Settings](docs/settings.png) -->

For the deep technical story — the Android 14/15/16 bugs hit and fixed during development — see **[ENGINEERING_NOTES.md](ENGINEERING_NOTES.md)**.

## What's in the box

- Kotlin + Jetpack Compose, ~1300 LOC
- 22 unit tests covering the crop geometry math
- JobScheduler **content-URI trigger** on MediaStore images for screenshot detection — no long-running service
- `SYSTEM_ALERT_WINDOW` overlay hosting a Compose UI with marching-ants animation
- `BitmapRegionDecoder` for memory-efficient cropping (skips loading the whole bitmap)
- Re-armed on `BOOT_COMPLETED` (content-trigger jobs don't survive reboot)
- Persistent crash logger (writes stack trace + `ServiceStatus` snapshot to disk so post-crash diagnosis works without adb)

## Build

Requires JDK 17 and Android SDK 35 + build-tools 35.

```bash
# Install toolchain (macOS)
brew install openjdk@17 gradle
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

# Build
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug     # 53 MB debug APK
./gradlew assembleRelease   # 5 MB R8-minified APK, debug-signed for sideload
./gradlew testDebugUnitTest # 22 geometry tests

# APK output
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

## Install (sideload)

1. Get the APK to your phone (USB via `adb install`, Drive, email, etc.).
2. Open the file — Android will prompt to "Allow from this source." Allow.
3. Tap Install → Open.
4. Grant the two required permissions: Read screenshots / Draw over other apps.
5. (Optional) Grant "Manage all files" if you want originals deleted after cropping.
6. Tap **Start watching**.
7. Take a screenshot — the overlay should appear.

## Workflow per screenshot

- **Tap anywhere on the overlay** → keep the original screenshot, dismiss overlay
- **Drag a rectangle** → save the cropped region as a new `Snipshot_<timestamp>.png` in `Pictures/Screenshots/`
- **Do nothing for 3 seconds** → same as tap (keep full, dismiss)
- **Tap the X in the top-right** → cancel, save nothing new

## Project layout

```
app/src/main/kotlin/com/jt/snipshot/
├── SnipshotApplication.kt  Process-wide CrashLogger install
├── MainActivity.kt         Settings screen, permission grants, crash panel
├── ScreenshotWatcher.kt    JobScheduler content trigger + detection/dedup + ScreenshotJobService
├── OverlayManager.kt       SYSTEM_ALERT_WINDOW host + Compose lifecycle wiring
├── CropOverlay.kt          Compose UI with marching-ants + drag detection
├── CropGeometry.kt         Pure math (testable). Letterbox + view↔image mapping
├── Cropper.kt              BitmapRegionDecoder crop + MediaStore save
├── BootReceiver.kt         BOOT_COMPLETED re-arm of the content trigger
├── CrashLogger.kt          Uncaught exception → disk
├── Prefs.kt                DataStore (keep-original toggle)
└── PermissionHelpers.kt    Permission state checks

app/src/test/kotlin/com/jt/snipshot/
└── CropGeometryTest.kt     22 tests
```
