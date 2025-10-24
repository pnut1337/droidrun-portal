package com.droidrun.portal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts

class ScreenshotPermissionActivity : Activity() {
    companion object {
        private const val TAG = "ScreenshotPermActivity"
        const val ACTION_REQUEST_PERMISSION = "com.droidrun.portal.REQUEST_SCREENSHOT_PERMISSION"
    }

    private val mediaProjectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection permission granted")

            // Save the permission data
            ScreenshotService.setMediaProjectionData(result.resultCode, result.data)

            // Start the screenshot service
            val serviceIntent = Intent(this, ScreenshotService::class.java)
            startService(serviceIntent)

            // Notify success
            sendBroadcast(Intent("com.droidrun.portal.SCREENSHOT_PERMISSION_GRANTED"))

        } else {
            Log.e(TAG, "MediaProjection permission denied")
            sendBroadcast(Intent("com.droidrun.portal.SCREENSHOT_PERMISSION_DENIED"))
        }

        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "ScreenshotPermissionActivity created")

        // Check if already has permission
        if (ScreenshotService.hasMediaProjectionPermission()) {
            Log.d(TAG, "Already has MediaProjection permission")
            sendBroadcast(Intent("com.droidrun.portal.SCREENSHOT_PERMISSION_GRANTED"))
            finish()
            return
        }

        // Request MediaProjection permission
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()

        Log.d(TAG, "Requesting MediaProjection permission")
        mediaProjectionPermissionLauncher.launch(permissionIntent)
    }
}
