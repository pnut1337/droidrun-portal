package com.droidrun.portal

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture

class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private var instance: ScreenshotService? = null
        private var mediaProjection: MediaProjection? = null
        private var resultCode: Int = 0
        private var resultData: Intent? = null

        fun getInstance(): ScreenshotService? = instance

        fun setMediaProjectionData(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
        }

        fun hasMediaProjectionPermission(): Boolean {
            // Check if we have permission from dialog
            if (resultData != null && resultCode != 0) {
                return true
            }

            // Also check if permission was granted via appops
            return checkAppOpsPermission()
        }

        private fun checkAppOpsPermission(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec("appops get ${instance?.packageName} PROJECT_MEDIA")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val output = reader.readText()
                reader.close()
                process.waitFor()

                // Check if output contains "allow"
                val hasPermission = output.contains("allow", ignoreCase = true)
                Log.d(TAG, "appops check result: $output, hasPermission: $hasPermission")
                hasPermission
            } catch (e: Exception) {
                Log.e(TAG, "Error checking appops permission", e)
                false
            }
        }
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.d(TAG, "ScreenshotService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ScreenshotService started")

        // Start as foreground service with notification
        startForegroundServiceWithNotification()

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "screenshot_service_channel"
        val notificationId = 2001

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Screenshot Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles screenshot capture via MediaProjection"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Create notification
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
                .setContentTitle("Droidrun Screenshot Service")
                .setContentText("Ready to capture screenshots")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("Droidrun Screenshot Service")
                .setContentText("Ready to capture screenshots")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }

        // Start foreground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(notificationId, notification)
        }

        Log.d(TAG, "ScreenshotService running as foreground service")
    }

    fun takeScreenshotBase64(): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        try {
            // Initialize MediaProjection if not already done
            if (mediaProjection == null) {
                // Must have resultData from dialog to create MediaProjection
                if (resultData != null && resultCode != 0) {
                    Log.d(TAG, "Creating MediaProjection with dialog token")
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
                } else {
                    Log.e(TAG, "No MediaProjection token available")
                    future.complete("error: No MediaProjection token. Please call /screenshot/launch_permission_dialog first to grant permission.")
                    return future
                }

                if (mediaProjection == null) {
                    future.complete("error: Failed to create MediaProjection.")
                    return future
                }
            }

            captureScreen(future)

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")
        }

        return future
    }

    private fun captureScreen(future: CompletableFuture<String>) {
        try {
            // Get screen dimensions
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()

            val width: Int
            val height: Int
            val density: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                width = windowMetrics.bounds.width()
                height = windowMetrics.bounds.height()

                val displayMetrics = resources.displayMetrics
                density = displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                width = metrics.widthPixels
                height = metrics.heightPixels
                density = metrics.densityDpi
            }

            Log.d(TAG, "Screen dimensions: ${width}x${height}, density: $density")

            // Create ImageReader with best available pixel format
            // Use RGBA_8888 for compatibility (hardware will handle color space)
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // Create VirtualDisplay
            val virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                mainHandler
            )

            if (virtualDisplay == null) {
                imageReader.close()
                future.complete("error: Failed to create VirtualDisplay")
                return
            }

            // Wait longer and retry if needed for the display to render
            var retryCount = 0
            val maxRetries = 5

            fun tryAcquireImage() {
                mainHandler.postDelayed({
                    try {
                        val image = imageReader.acquireLatestImage()

                        if (image == null) {
                            retryCount++
                            if (retryCount < maxRetries) {
                                Log.d(TAG, "Image not ready, retry $retryCount/$maxRetries")
                                tryAcquireImage() // Retry
                                return@postDelayed
                            } else {
                                Log.e(TAG, "Failed to acquire image after $maxRetries retries")
                                virtualDisplay.release()
                                imageReader.close()
                                future.complete("error: Failed to acquire image from screen after $maxRetries attempts. VirtualDisplay may not be rendering.")
                                return@postDelayed
                            }
                        }

                        // Convert image to bitmap
                        val bitmap = imageToBitmap(image, width, height)
                        image.close()

                        // Convert bitmap to base64
                        val base64String = bitmapToBase64(bitmap)
                        bitmap.recycle()

                        // Cleanup
                        virtualDisplay.release()
                        imageReader.close()

                        Log.d(TAG, "Screenshot captured successfully via MediaProjection")
                        future.complete(base64String)

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot", e)
                        virtualDisplay.release()
                        imageReader.close()
                        future.complete("error: Failed to process screenshot: ${e.message}")
                    }
                }, if (retryCount == 0) 300 else 200) // First delay 300ms, then 200ms per retry
            }

            tryAcquireImage()

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            future.complete("error: Failed to capture screen: ${e.message}")
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        // Create bitmap with ARGB_8888 and explicit sRGB color space
        val tempBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ - explicitly set sRGB color space
            Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
                true, // hasAlpha
                android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            )
        } else {
            // Android 7.1 and below - defaults to sRGB
            Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
        }

        // Copy pixel data
        tempBitmap.copyPixelsFromBuffer(buffer)

        // Crop if there's padding
        val bitmap = if (rowPadding == 0) {
            tempBitmap
        } else {
            val croppedBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            tempBitmap.recycle()
            croppedBitmap
        }

        // Check if we need to expand limited range (16-235) to full range (0-255)
        // This fixes the issue where G and B only reach 246 instead of 255
        return expandToFullRange(bitmap)
    }

    private fun expandToFullRange(bitmap: Bitmap): Bitmap {
        // Create a mutable copy
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val width = mutableBitmap.width
        val height = mutableBitmap.height
        val pixels = IntArray(width * height)

        // Get all pixels
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Expand from limited range (16-235) to full range (0-255)
        for (i in pixels.indices) {
            val pixel = pixels[i]

            val a = (pixel shr 24) and 0xFF  // Alpha: keep as is
            var r = (pixel shr 16) and 0xFF
            var g = (pixel shr 8) and 0xFF
            var b = pixel and 0xFF

            // Expand limited range to full range: (value - 16) * 255 / (235 - 16)
            // Only if it looks like limited range (check if max values are around 235)
            r = expandChannel(r)
            g = expandChannel(g)
            b = expandChannel(b)

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Set the expanded pixels back
        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Recycle the original if it's different
        if (bitmap != mutableBitmap) {
            bitmap.recycle()
        }

        return mutableBitmap
    }

    private fun expandChannel(value: Int): Int {
        // Expand limited range (16-235) to full range (0-255)
        // Formula: (value - 16) * 255 / 219
        if (value < 16) return 0
        if (value > 235) return 255

        val expanded = ((value - 16) * 255) / 219
        return expanded.coerceIn(0, 255)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        byteArrayOutputStream.close()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun stopProjection() {
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        instance = null
        Log.d(TAG, "ScreenshotService destroyed")
    }
}
