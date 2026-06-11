package com.jt.snipshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Only attempt if we already have the permissions to actually run.
        val haveOverlay = Settings.canDrawOverlays(context)
        val haveMedia = PermissionHelpers.hasMediaImages(context)
        val haveNotif = PermissionHelpers.hasPostNotifications(context)
        if (!haveOverlay || !haveMedia || !haveNotif) return

        if (Build.VERSION.SDK_INT < 35) {
            SnipshotService.start(context)
        } else {
            // Android 15+ forbids starting a dataSync FGS from BOOT_COMPLETED
            // (ForegroundServiceStartNotAllowedException). Post a tap-to-resume
            // notification instead; MainActivity starts the service from foreground.
            postResumeNotification(context)
        }
    }

    private fun postResumeNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_resume_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_AUTO_START, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_resume_title))
            .setContentText(context.getString(R.string.notif_resume_text))
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID_RESUME, notif) }
    }

    companion object {
        private const val CHANNEL_ID = "snipshot_resume"
        const val NOTIF_ID_RESUME = 1002
    }
}
