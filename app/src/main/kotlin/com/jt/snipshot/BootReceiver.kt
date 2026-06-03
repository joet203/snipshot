package com.jt.snipshot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Only attempt if we already have the permissions to actually run.
        val haveOverlay = Settings.canDrawOverlays(context)
        val haveMedia = PermissionHelpers.hasMediaImages(context)
        val haveNotif = PermissionHelpers.hasPostNotifications(context)
        if (!haveOverlay || !haveMedia || !haveNotif) return

        SnipshotService.start(context)
    }
}
