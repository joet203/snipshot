package com.jt.snipshot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

object PermissionHelpers {
    fun hasMediaImages(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < 33) true
        else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT < 30) true
        else Environment.isExternalStorageManager()

    /**
     * True when the app is exempt from battery optimization. Without this the
     * watcher's JobScheduler trigger can be deferred by App Standby, so the crop
     * overlay may appear late. See ENGINEERING_NOTES "App Standby Buckets".
     */
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
}
