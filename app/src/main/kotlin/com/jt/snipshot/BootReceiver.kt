package com.jt.snipshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Content-trigger jobs do not survive a reboot, so re-arm the watcher on boot if
 * the user had it enabled. Unlike the old foreground-service design, scheduling a
 * JobScheduler job from BOOT_COMPLETED is allowed on every API level — no
 * ForegroundServiceStartNotAllowedException, so no tap-to-resume workaround needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Only re-arm if we still hold the permissions needed to actually run.
        if (!Settings.canDrawOverlays(context)) return
        if (!PermissionHelpers.hasMediaImages(context)) return

        ScreenshotWatcher.resumeIfEnabled(context)
    }
}
