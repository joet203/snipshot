# Engineering Notes — Snipshot

> Working notes kept during development (originally the project's agent-context file). The "hard-earned gotchas" section documents real Android 14/15/16 bugs hit and fixed.

Project-specific notes for future Claude sessions. Global preferences live at `~/CLAUDE.md`.

## Goal

Sideloaded Android app on Pixel devices. Auto-detects screenshots, shows a marching-ants crop overlay, tap-to-keep-full / drag-to-crop. Not for the Play Store.

## Build environment

This Mac has the toolchain installed via Homebrew. Always export both before any gradle call:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"
```

Then `./gradlew assembleDebug testDebugUnitTest` from `<repo-root>/`.


## Hard-earned gotchas (do not re-learn)

These are real bugs that bit us during development. Each is fixed in the current code — preserve the fix.

1. **`foregroundServiceType="mediaProcessing"` is silently rejected on Android 15+** for long-running watchers. Use `"dataSync"`. (`AndroidManifest.xml`, `SnipshotService.startInForeground`)
2. **`ComposeView` is `final`** — cannot be subclassed to override `dispatchKeyEvent`. The X cancel button is the only dismiss path. (`OverlayManager.OverlayHost.attach`)
3. **`"... LIMIT 1"` in MediaStore `sortOrder` throws on Android 14+.** Removed from `queryUriForFilename` and `queryLatestScreenshot`. Use `moveToFirst()` after `.use {}` instead.
4. **MediaStore observer fires BEFORE screenshot bytes flush** — needed `IS_PENDING` check + retry-decode backoff (50ms → 1.5s) in `CropOverlay.decodeScreenshotWithDiagnostic`.
5. **`IMPORTANCE_MIN` notification channel breaks `startForeground()` on Android 16.** Use `IMPORTANCE_LOW` minimum.
6. **`WindowManager.addView` must be on main thread.** ContentObserver runs on its HandlerThread, so the service uses `mainHandler.post { OverlayManager.show(...) }`.
7. **`BitmapRegionDecoder.newInstance(InputStream)` returns nullable** even though it usually throws — needs `?: return@use null`.
8. **The `current != null` "already showing" guard in `OverlayManager` can permanently block new screenshots** if the previous overlay's dismiss callback never fires. There's a 15-second stale timeout that force-clears; do not remove it.
9. **R8 minification requires explicit `-keep` rules** for `androidx.compose.runtime.**`, `androidx.compose.ui.**`, and `com.jt.snipshot.**` — see `proguard-rules.pro`.
10. **Use formula `openjdk@17`, not cask `temurin@17`** — cask needs sudo password we couldn't provide non-interactively.
11. **Android 15+ forbids starting a `dataSync` FGS from `BOOT_COMPLETED`** — it throws `ForegroundServiceStartNotAllowedException`, which `onCreate`'s catch turns into a silent stop. `BootReceiver` direct-starts only on SDK < 35; on 35+ it posts a tap-to-resume notification that routes through `MainActivity` (`EXTRA_AUTO_START`), which can start the FGS from foreground.
12. **Never guess "latest screenshot" from an observer event.** Took 4 codex review rounds to get right (v0.1.2). The FileObserver path retries the exact-filename lookup with backoff (never falls back to latest). The ContentObserver no-URI fallback (`queryFreshScreenshots`) returns ALL fresh candidates (cap 5), each run through `handleNewMedia`'s dedup chain, anchored on a SEED-ONLY `_ID` watermark (`startMaxId`, set once at service start). A dynamic watermark was rejected — bumping it for row X strands a still-pending screenshot with a lower `_ID`. A single-row fallback was rejected — a just-committed B hides behind an already-shown newer A.

## Diagnostic story

When something doesn't work:
1. **Crash log** persists at `/data/data/com.jt.snipshot/files/last_crash.txt`. Settings screen surfaces it on next launch with a Copy-to-clipboard button.
2. **`ServiceStatus` object** (singleton in `SnipshotService.kt`) holds live in-memory diagnostics: observer fires count, last URI, last decision, overlay attempts, overlay result, decode result. Visible in the app via the "Show debug info" toggle.
3. **The Cropper rejects very small crops** (< 80px) and the overlay rejects drags under 40dp slop — these are intentional, prevents a thumb-graze from producing tiny white screenshots.

## Codex review pattern

Codex is the primary review loop. Standard invocation when reviewing changes:

```bash
codex exec --sandbox workspace-write --skip-git-repo-check \
  -o /tmp/codex-review.md \
  -C <repo-root> "<prompt>"
```

`--skip-git-repo-check` is mandatory — without it codex hangs on a stdin trust prompt forever. Codex caught 11+ real bugs during development that source-only review missed.

## Tests

`./gradlew testDebugUnitTest` — 22 tests in `CropGeometryTest.kt` covering `normalize`, `fitRect`, `viewToImageRect` for letterbox, fold-screen mapping, clamping. Run before every build. **No on-device instrumented tests** — Codex audited and we deferred Robolectric as not worth the dep weight.

## What's NOT in scope (deferred)

- Corner handles for refining the crop rectangle after initial drag (codex flagged it; the maintainer preferred to ship the simple tap/drag UX first)
- Quick Settings tile for one-tap toggle
- Refined-after-drag UX (drag-to-move the box, drag corner handles)
- Play Store distribution (sideload-only by design)
