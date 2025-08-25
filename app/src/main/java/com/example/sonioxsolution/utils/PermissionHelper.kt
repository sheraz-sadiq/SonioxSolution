package com.example.sonioxsolution.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.RECORD_AUDIO
    )

    fun hasPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions(activity: Activity, requestCode: Int = 1234) {
        ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, requestCode)
    }
}
