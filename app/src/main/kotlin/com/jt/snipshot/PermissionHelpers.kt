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
}
