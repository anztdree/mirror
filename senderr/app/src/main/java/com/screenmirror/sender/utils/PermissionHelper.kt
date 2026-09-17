package com.screenmirror.sender.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Handles runtime permission requests for Sender app.
 *
 * Sender requires:
 * - CAMERA (front camera remote access)
 * - RECORD_AUDIO (mic for screen + camera mode)
 * - FOREGROUND_SERVICE (long-running screen capture)
 * - On Android 10+: Background activity / body sensors not needed
 */
object PermissionHelper {

    const val REQ_CODE_PERMISSIONS = 1001
    const val REQ_CODE_MEDIA_PROJECTION = 1002

    val REQUIRED_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    fun hasAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions(activity: Activity) {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_CODE_PERMISSIONS)
        }
    }
}
