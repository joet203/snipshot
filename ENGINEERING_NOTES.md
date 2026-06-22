# Engineering Notes — Snipshot

> Working notes kept during development (originally the project's agent-context file). The "hard-earned gotchas" section documents real Android 14/15/16 bugs hit and fixed.

Project-specific notes for future Claude sessions. Global preferences live at `~/CLAUDE.md`.

## Goal

Sideloaded Android app on Pixel devices. Auto-detects screenshots, shows a marching-ants crop overlay, tap-to-keep-full / drag-to-crop. Not for the Play Store.

## Architecture (detection)

**No long-running service.** Detection uses a **JobScheduler content-URI trigger** on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (`ScreenshotWatcher.scheduleJob`). The OS wakes `ScreenshotJobService` only when images change — no process alive in between — and hands us the changed URIs in `params.triggeredContentUris`. We run them through the dedup chain and show the overlay.

Why not a foreground service: an always-on `dataSync` FGS (the old design) hits Android 14+'s ~6h-per-24h cap and the OS throws `ForegroundServiceDidNotStopInTimeException` (this crashed v0.1.2 on a Pixel 10 Fold / Android 16). There is no "run forever" FGS type for a passive watcher. The overlay is a `SYSTEM_ALERT_WINDOW`, which needs no FGS to show; a visible overlay keeps the process at perceptible priority through crop/save.

**Operational caveat — App Standby Buckets.** The user interacts with the *overlay*, not the app's UI, so Snipshot's process is rarely "launched." Android can decay a rarely-opened app to the `rare`/`restricted` standby bucket, where JobScheduler defers jobs (potentially hours) — which would delay the overlay. A foreground service is exempt from this; a content-trigger job is not. Mitigation: set Snipshot to **Unrestricted** battery usage (Settings → Apps → Snipshot → Battery). If this proves flaky in real-world use, the fallback is a `specialUse` FGS (no 6h timeout, exempt from standby; needs a Play Console justification only for store distribution, irrelevant for sideload). Not yet wired: an in-app `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt.

Key invariants of the job design:
- Content-trigger jobs are **one-shot** — `onStartJob` re-arms immediately (`ScreenshotWatcher.rearm`) before processing, or changes during processing are missed. The re-armed trigger fires only on NEW changes, so it does not loop on the change that woke us.
- Jobs **do not survive reboot** — `BootReceiver` reschedules if the user had watching enabled (a persisted SharedPreferences flag). Scheduling from `BOOT_COMPLETED` is allowed on every API level (unlike starting an FGS), so the old tap-to-resume workaround is gone.
- Detection state (dedup maps, `startMaxId`) is a **process-level singleton** in `ScreenshotWatcher` so it survives across the short-lived job executions that share a process. `startMaxId` is also persisted so the no-URI fallback stays anchored across process death.

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

1. **No foreground-service type works for an always-on watcher.** `mediaProcessing` is silently rejected on Android 15+; `dataSync` is accepted but capped at ~6h/24h on target SDK 35+, after which the OS throws `ForegroundServiceDidNotStopInTimeException` (the v0.1.2 crash). Resolved by dropping the FGS entirely for a JobScheduler content trigger — see the Architecture section. Do not reintroduce a long-running FGS.
2. **`ComposeView` is `final`** — cannot be subclassed to override `dispatchKeyEvent`. The X cancel button is the only dismiss path. (`OverlayManager.OverlayHost.attach`)
3. **`"... LIMIT 1"` in MediaStore `sortOrder` throws on Android 14+.** Removed from `queryUriForFilename` and `queryLatestScreenshot`. Use `moveToFirst()` after `.use {}` instead.
4. **MediaStore observer fires BEFORE screenshot bytes flush** — needed `IS_PENDING` check + retry-decode backoff (50ms → 1.5s) in `CropOverlay.decodeScreenshotWithDiagnostic`.
5. **`IMPORTANCE_MIN` notification channel breaks `startForeground()` on Android 16.** Use `IMPORTANCE_LOW` minimum.
6. **`WindowManager.addView` must be on main thread.** `ScreenshotWatcher` processes job fires on a background `HandlerThread` (MediaStore queries off the main looper), so it uses `mainHandler.post { OverlayManager.show(...) }`.
7. **`BitmapRegionDecoder.newInstance(InputStream)` returns nullable** even though it usually throws — needs `?: return@use null`.
8. **The `current != null` "already showing" guard in `OverlayManager` can permanently block new screenshots** if the previous overlay's dismiss callback never fires. There's a 15-second stale timeout that force-clears; do not remove it.
9. **R8 minification requires explicit `-keep` rules** for `androidx.compose.runtime.**`, `androidx.compose.ui.**`, and `com.jt.snipshot.**` — see `proguard-rules.pro`.
10. **Use formula `openjdk@17`, not cask `temurin@17`** — cask needs sudo password we couldn't provide non-interactively.
11. **Content-trigger jobs don't survive reboot.** `BootReceiver` reschedules on `BOOT_COMPLETED` if watching was enabled. Scheduling a job from boot is allowed on every API level — no `ForegroundServiceStartNotAllowedException`, so the old tap-to-resume notification workaround was deleted in the JobScheduler rewrite.
12. **Never guess "latest screenshot" from a change event.** Took 4 codex review rounds to get right (v0.1.2). The normal path uses the exact `triggeredContentUris` the job delivers. The no-URI fallback (`queryFreshScreenshots`, used when >50 changes collapse to authorities-only) returns ALL fresh candidates (cap 5), each run through `handleNewMedia`'s dedup chain, anchored on a SEED-ONLY `_ID` watermark (`startMaxId`, captured when watching starts, persisted). A dynamic watermark was rejected — bumping it for row X strands a still-pending screenshot with a lower `_ID`. A single-row fallback was rejected — a just-committed B hides behind an already-shown newer A. (The old FileObserver fast-path was dropped: content triggers are MediaStore-only.)

## Diagnostic story

When something doesn't work:
1. **Crash log** persists at `/data/data/com.jt.snipshot/files/last_crash.txt`. Settings screen surfaces it on next launch with a Copy-to-clipboard button.
2. **`ServiceStatus` object** (singleton in `ScreenshotWatcher.kt`) holds live in-memory diagnostics: observer fires count (now job-fire count), last URI, last decision, overlay attempts, overlay result, decode result. Visible in the app via the "Show debug info" toggle.
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
