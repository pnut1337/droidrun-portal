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
            return resultData != null && resultCode != 0
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
        return START_STICKY
    }

    fun takeScreenshotBase64(): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        if (!hasMediaProjectionPermission()) {
            future.complete("error: MediaProjection permission not granted. Please request permission first.")
            return future
        }

        try {
            // Initialize MediaProjection if not already done
            if (mediaProjection == null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
                if (mediaProjection == null) {
                    future.complete("error: Failed to create MediaProjection")
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

            // Create ImageReader
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

            // Wait a bit for the display to render
            mainHandler.postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()

                    if (image == null) {
                        Log.e(TAG, "Failed to acquire image")
                        virtualDisplay.release()
                        imageReader.close()
                        future.complete("error: Failed to acquire image from screen")
                        return@postDelayed
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
            }, 100) // Small delay to ensure screen is rendered

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

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            // Crop the bitmap if there's padding
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            croppedBitmap
        }
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
