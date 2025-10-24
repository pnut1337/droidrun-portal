package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import android.os.Build

class DroidrunAccessibilityService : AccessibilityService(), ConfigManager.ConfigChangeListener {

    companion object {
        private const val TAG = "DroidrunA11yService"
        private var instance: DroidrunAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5

        // Periodic update constants
        private const val REFRESH_INTERVAL_MS = 250L // Update every 250ms
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)

        fun getInstance(): DroidrunAccessibilityService? = instance
    }

    private lateinit var overlayManager: OverlayManager
    private val screenBounds = Rect()
    private lateinit var configManager: ConfigManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socketServer: SocketServer? = null

    // Periodic update state
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""
    private val visibleElements = mutableListOf<ElementNode>()

    override fun onCreate() {
        super.onCreate()

        // NOTE: Accessibility Service should NOT call startForeground()
        // It is managed by the system and doesn't need foreground notification

        Log.d(TAG, "onCreate() called")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected() called")

        try {
            // Initialize OverlayManager
            overlayManager = OverlayManager(this)

            // Get screen bounds compatible with Android 10+
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                screenBounds.set(0, 0, bounds.width(), bounds.height())
            } else {
                // Fallback for Android 10 (API 29)
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(displayMetrics)
                screenBounds.set(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            }

            // Initialize ConfigManager
            configManager = ConfigManager.getInstance(this)
            configManager.addListener(this)

            // Initialize SocketServer
            socketServer = SocketServer(this)

            // Start ScreenshotService for Android 10-13
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val screenshotServiceIntent = android.content.Intent(this, ScreenshotService::class.java)
                startService(screenshotServiceIntent)
                Log.d(TAG, "ScreenshotService started for Android ${Build.VERSION.SDK_INT}")
            }

            isInitialized = true

            // Show overlay
            overlayManager.showOverlay()
            instance = this

            // Configure accessibility service
            serviceInfo = AccessibilityServiceInfo().apply {
                // Listen to all events
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK

                // Monitor all packages
                packageNames = null

                // Set feedback type
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

                // Set flags for better access
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

                // Enable screenshot capability (API 34+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
                }
            }

            // Apply loaded configuration
            applyConfiguration()

            // Start periodic updates
            startPeriodicUpdates()

            // Start socket server if enabled
            startSocketServerIfEnabled()

            Log.d(TAG, "Accessibility service connected and configured")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected: ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""

        // Detect package changes
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }

        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }

        // Trigger update on relevant events
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Let the periodic runnable handle updates
            }
        }
    }

    // Periodic update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized && configManager.overlayVisible) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime

                if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                    refreshVisibleElements()
                    lastUpdateTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "Started periodic updates")
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped periodic updates")
    }

    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }

        try {
            if (currentPackageName.isEmpty()) {
                overlayManager.clearElements()
                overlayManager.refreshOverlay()
                return
            }

            // Clear previous elements
            clearElementList()

            // Get fresh elements
            val elements = getVisibleElementsInternal()

            // Update overlay if visible
            if (configManager.overlayVisible && elements.isNotEmpty()) {
                overlayManager.clearElements()

                elements.forEach { rootElement ->
                    addElementAndChildrenToOverlay(rootElement, 0)
                }

                overlayManager.refreshOverlay()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun resetOverlayState() {
        try {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
            clearElementList()
            Log.d(TAG, "Reset overlay state for package change")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting overlay state: ${e.message}", e)
        }
    }

    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling node: ${e.message}")
            }
        }
        visibleElements.clear()
    }

    private fun applyConfiguration() {
        mainHandler.post {
            try {
                val config = configManager.getCurrentConfiguration()
                if (config.overlayVisible) {
                    overlayManager.showOverlay()
                } else {
                    overlayManager.hideOverlay()
                }

                // Apply offset: auto or manual
                val offsetToApply = if (config.autoOffsetEnabled) {
                    val autoOffset = overlayManager.calculateAutoOffset()
                    // Only update ConfigManager if the auto offset has changed
                    // This prevents overwriting user's manual settings
                    if (autoOffset != config.overlayOffset) {
                        Log.d(TAG, "Auto offset calculated: $autoOffset (was ${config.overlayOffset})")
                        configManager.overlayOffset = autoOffset
                    }
                    autoOffset
                } else {
                    // Use the saved manual offset
                    Log.d(TAG, "Using manual offset: ${config.overlayOffset}")
                    config.overlayOffset
                }

                overlayManager.setPositionOffsetY(offsetToApply)
                Log.d(TAG, "Applied offset: $offsetToApply (auto: ${config.autoOffsetEnabled})")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}", e)
            }
        }
    }

    // Public methods for MainActivity to call directly
    fun setOverlayVisible(visible: Boolean): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized yet")
            return false
        }

        return try {
            configManager.overlayVisible = visible

            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    // Trigger immediate refresh when showing overlay
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }

            Log.d(TAG, "Overlay visibility set to: $visible")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay visibility: ${e.message}", e)
            false
        }
    }

    fun isOverlayVisible(): Boolean {
        if (!isInitialized) return false
        return configManager.overlayVisible
    }

    fun setOverlayOffset(offset: Int): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized yet")
            return false
        }

        return try {
            // When user manually sets offset, disable auto offset
            if (configManager.autoOffsetEnabled) {
                Log.d(TAG, "Disabling auto offset because user set manual offset")
                configManager.autoOffsetEnabled = false
            }

            configManager.overlayOffset = offset

            mainHandler.post {
                overlayManager.setPositionOffsetY(offset)
            }

            Log.d(TAG, "Overlay offset set to: $offset (auto offset disabled)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay offset: ${e.message}", e)
            false
        }
    }

    fun getOverlayOffset(): Int {
        if (!isInitialized) return 0
        return configManager.overlayOffset
    }

    fun getCurrentAppliedOffset(): Int {
        if (!isInitialized) return 0
        return overlayManager.getPositionOffsetY()
    }

    fun setAutoOffsetEnabled(enabled: Boolean): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized yet")
            return false
        }

        return try {
            if (!enabled) {
                // When disabling auto-offset, save the current applied offset
                // as the manual offset so it persists across restarts
                configManager.overlayOffset = overlayManager.getPositionOffsetY()
            }

            configManager.autoOffsetEnabled = enabled

            // Only recalculate when enabling auto-offset
            if (enabled) {
                mainHandler.post {
                    val autoOffset = overlayManager.calculateAutoOffset()
                    // Save the calculated auto offset back to ConfigManager
                    // so MainActivity can read the correct value
                    configManager.overlayOffset = autoOffset
                    overlayManager.setPositionOffsetY(autoOffset)
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto offset: ${e.message}", e)
            false
        }
    }

    fun isAutoOffsetEnabled(): Boolean {
        if (!isInitialized) return false
        return configManager.autoOffsetEnabled
    }

    fun getVisibleElements(): MutableList<ElementNode> {
        return getVisibleElementsInternal()
    }

    private fun getVisibleElementsInternal(): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1) // Start indexing from 1

        val rootNode = rootInActiveWindow ?: return elements
        val rootElement = findAllVisibleElements(rootNode, 0, null, indexCounter)
        rootElement?.let {
            collectRootElements(it, elements)
        }

        // Store the elements for cleanup later
        synchronized(visibleElements) {
            clearElementList()
            visibleElements.addAll(elements)
        }

        return elements
    }

    private fun collectRootElements(element: ElementNode, rootElements: MutableList<ElementNode>) {
        rootElements.add(element)
    }

    private fun findAllVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        indexCounter: IndexCounter
    ): ElementNode? {
        try {

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isInScreen = Rect.intersects(rect, screenBounds)
            val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE

            var currentElement: ElementNode? = null

            if (isInScreen && hasSize) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                val displayText = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                    else -> className.substringAfterLast('.')
                }

                val elementType = if (node.isClickable) {
                    "Clickable"
                } else if (node.isCheckable) {
                    "Checkable"
                } else if (node.isEditable) {
                    "Input"
                } else if (text.isNotEmpty()) {
                    "Text"
                } else if (node.isScrollable) {
                    "Container"
                } else {
                    "View"
                }

                val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)

                // Create a copy of the node using obtain() for Android 10 compatibility
                // Note: AccessibilityNodeInfo(node) constructor doesn't exist in Android 10
                val nodeCopy = AccessibilityNodeInfo.obtain(node)

                currentElement = ElementNode(
                    nodeCopy,
                    Rect(rect),
                    displayText,
                    className.substringAfterLast('.'),
                    windowLayer,
                    System.currentTimeMillis(),
                    id
                )

                // Assign unique index
                currentElement.overlayIndex = indexCounter.getNext()

                // Set parent-child relationship
                parent?.addChild(currentElement)
            }

            // Recursively process children
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                val childElement = findAllVisibleElements(childNode, windowLayer, currentElement, indexCounter)
                // Children are already added to currentElement via parent?.addChild() call above
            }

            return currentElement

        } catch (e: Exception) {
            Log.e(TAG, "Error in findAllVisibleElements: ${e.message}", e)
            return null
        }
    }

     fun getPhoneState(): PhoneState {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val isEditable = focusedNode?.isEditable ?: false
        val keyboardVisible = detectKeyboardVisibility()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val appName = getAppName(currentPackage)

        return PhoneState(focusedNode, keyboardVisible, currentPackage, appName, isEditable)
    }

    private fun detectKeyboardVisibility(): Boolean {
        try {
            val windows = windows
            if (windows != null) {
                val hasInputMethodWindow = windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                windows.forEach { it.recycle() }
                return hasInputMethodWindow
            } else { return false }
        } catch (e: Exception) { return false}
    }

    private fun getAppName(packageName: String?): String? {
        return try {
            if (packageName == null) return null

            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package $packageName: ${e.message}")
            null
        }
    }

    // Helper class to maintain global index counter
    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }

    // Socket server management methods
    private fun startSocketServerIfEnabled() {
        if (configManager.socketServerEnabled) {
            startSocketServer()
        }
    }

    private fun startSocketServer() {
        socketServer?.let { server ->
            if (!server.isRunning()) {
                val port = configManager.socketServerPort
                val success = server.start(port)
                if (success) {
                    Log.i(TAG, "Socket server started on port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on port $port")
                }
            }
        }
    }

    private fun stopSocketServer() {
        socketServer?.let { server ->
            if (server.isRunning()) {
                server.stop()
                Log.i(TAG, "Socket server stopped")
            }
        }
    }

    fun getSocketServerStatus(): String {
        if (!isInitialized) return "Service not initialized"

        return socketServer?.let { server ->
            if (server.isRunning()) {
                "Running on port ${server.getPort()}"
            } else {
                "Stopped"
            }
        } ?: "Not initialized"
    }

    fun getAdbForwardCommand(): String {
        if (!isInitialized) return "Service not initialized"

        val port = configManager.socketServerPort
        return "adb forward tcp:$port tcp:$port"
    }

    // ConfigManager.ConfigChangeListener implementation
    override fun onOverlayVisibilityChanged(visible: Boolean) {
        // Already handled in setOverlayVisible method
    }

    override fun onOverlayOffsetChanged(offset: Int) {
        // Already handled in setOverlayOffset method
    }

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        if (enabled) {
            startSocketServer()
        } else {
            stopSocketServer()
        }
    }

    override fun onSocketServerPortChanged(port: Int) {
        // Restart server with new port if enabled
        socketServer?.let { server ->
            val wasRunning = server.isRunning()
            if (wasRunning) {
                server.stop()
            }

            // Start server on new port if it was running or if socket server is enabled
            if (wasRunning || configManager.socketServerEnabled) {
                val success = server.start(port)
                if (success) {
                    Log.i(TAG, "Socket server started on new port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on new port $port")
                }
            }
        }
    }

    // Screenshot functionality
    fun takeScreenshotBase64(hideOverlay: Boolean = true): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        // Temporarily hide overlay if requested
        val wasOverlayDrawingEnabled = if (hideOverlay) {
            val enabled = overlayManager.isDrawingEnabled()
            overlayManager.setDrawingEnabled(false)
            enabled
        } else {
            true
        }

        try {
            if (hideOverlay) {
                // Small delay to ensure overlay is hidden before screenshot
                mainHandler.postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Use Accessibility Screenshot API for Android 14+
                        performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
                    } else {
                        // Use shell command fallback for Android 10-13
                        performScreenshotViaShell(future, wasOverlayDrawingEnabled, hideOverlay)
                    }
                }, 100)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
                } else {
                    performScreenshotViaShell(future, wasOverlayDrawingEnabled, hideOverlay)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }

        return future
    }

    private fun performScreenshotViaShell(future: CompletableFuture<String>, wasOverlayDrawingEnabled: Boolean, hideOverlay: Boolean) {
        // Use MediaProjection for Android 10-13 to bypass FLAG_SECURE
        try {
            Log.d(TAG, "Taking screenshot via MediaProjection (Android ${Build.VERSION.SDK_INT})")

            // Get or start ScreenshotService
            var screenshotService = ScreenshotService.getInstance()

            if (screenshotService == null) {
                Log.d(TAG, "ScreenshotService not available, starting service...")
                // Try to start the service
                val screenshotServiceIntent = android.content.Intent(this, ScreenshotService::class.java)
                startService(screenshotServiceIntent)

                // Wait a bit for service to start
                Thread.sleep(500)
                screenshotService = ScreenshotService.getInstance()

                if (screenshotService == null) {
                    Log.e(TAG, "ScreenshotService failed to start")
                    future.complete("error: ScreenshotService failed to start. Please try again.")
                    if (hideOverlay) {
                        mainHandler.post { overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled) }
                    }
                    return
                }
            }

            // Take screenshot using MediaProjection
            val screenshotFuture = screenshotService.takeScreenshotBase64()

            // Wait for the screenshot to complete
            Thread {
                try {
                    val result = screenshotFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    future.complete(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for screenshot", e)
                    future.complete("error: Failed to capture screenshot: ${e.message}")
                } finally {
                    // Restore overlay drawing state
                    if (hideOverlay) {
                        mainHandler.post { overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled) }
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot via MediaProjection", e)
            future.complete("error: Failed to take screenshot via MediaProjection: ${e.message}")

            // Restore overlay drawing state
            if (hideOverlay) {
                mainHandler.post { overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled) }
            }
        }
    }

    private fun performScreenshotCapture(future: CompletableFuture<String>, wasOverlayDrawingEnabled: Boolean, hideOverlay: Boolean) {
        // This method should only be called on API 34+, but add safety check
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            future.complete("error: Screenshot API not supported on this Android version")
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainHandler.looper.thread.contextClassLoader?.let {
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                } ?: java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to create bitmap from hardware buffer")
                                screenshotResult.hardwareBuffer.close()
                                future.complete("error: Failed to create bitmap from screenshot data")
                                return
                            }

                            val byteArrayOutputStream = ByteArrayOutputStream()
                            val compressionSuccess = bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)

                            if (!compressionSuccess) {
                                Log.e(TAG, "Failed to compress bitmap to PNG")
                                bitmap.recycle()
                                screenshotResult.hardwareBuffer.close()
                                byteArrayOutputStream.close()
                                future.complete("error: Failed to compress screenshot to PNG format")
                                return
                            }

                            val byteArray = byteArrayOutputStream.toByteArray()
                            val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)

                            bitmap.recycle()
                            screenshotResult.hardwareBuffer.close()
                            byteArrayOutputStream.close()

                            future.complete(base64String)
                            Log.d(TAG, "Screenshot captured successfully, size: ${byteArray.size} bytes")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot", e)
                            try {
                                screenshotResult.hardwareBuffer.close()
                            } catch (closeException: Exception) {
                                Log.e(TAG, "Error closing hardware buffer", closeException)
                            }
                            future.complete("error: Failed to process screenshot: ${e.message}")
                        } finally {
                            // Restore overlay drawing state
                            if (hideOverlay) {
                                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorMessage = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal error occurred"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshot interval too short"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility access"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Secure window cannot be captured"
                            else -> "Unknown error (code: $errorCode)"
                        }
                        Log.e(TAG, "Screenshot failed: $errorMessage")
                        future.complete("error: Screenshot failed: $errorMessage")

                        // Restore overlay drawing state
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        stopPeriodicUpdates()
        stopSocketServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        stopSocketServer()
        clearElementList()
        configManager.removeListener(this)
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    private fun addElementAndChildrenToOverlay(element: ElementNode, depth: Int) {
        overlayManager.addElement(
            text = element.text,
            rect = element.rect,
            type = element.className,
            index = element.overlayIndex
        )

        for (child in element.children) {
            addElementAndChildrenToOverlay(child, depth + 1)
        }
    }
}