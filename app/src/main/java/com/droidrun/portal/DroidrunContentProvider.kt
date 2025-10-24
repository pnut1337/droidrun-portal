package com.droidrun.portal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import androidx.core.net.toUri
import android.os.Bundle
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

class DroidrunContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "DroidrunContentProvider"
        private const val AUTHORITY = "com.droidrun.portal"
        private const val A11Y_TREE = 1
        private const val PHONE_STATE = 2
        private const val PING = 3
        private const val KEYBOARD_ACTIONS = 4
        private const val STATE = 5
        private const val OVERLAY_OFFSET = 6
        private const val PACKAGES = 7
        private const val SCREENSHOT = 8
        private const val SCREENSHOT_PERMISSION_STATUS = 9
        private const val SCREENSHOT_LAUNCH_DIALOG = 10

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "a11y_tree", A11Y_TREE)
            addURI(AUTHORITY, "phone_state", PHONE_STATE)
            addURI(AUTHORITY, "ping", PING)
            addURI(AUTHORITY, "keyboard/*", KEYBOARD_ACTIONS)
            addURI(AUTHORITY, "state", STATE)
            addURI(AUTHORITY, "overlay_offset", OVERLAY_OFFSET)
            addURI(AUTHORITY, "packages", PACKAGES)
            addURI(AUTHORITY, "screenshot", SCREENSHOT)
            addURI(AUTHORITY, "screenshot/permission_status", SCREENSHOT_PERMISSION_STATUS)
            addURI(AUTHORITY, "screenshot/launch_permission_dialog", SCREENSHOT_LAUNCH_DIALOG)
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "DroidrunContentProvider created")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val cursor = MatrixCursor(arrayOf("result"))

        try {
            val result = when (uriMatcher.match(uri)) {
                A11Y_TREE -> getAccessibilityTree()
                PHONE_STATE -> getPhoneState()
                PING -> createSuccessResponse("pong")
                STATE -> getCombinedState()
                PACKAGES -> getInstalledPackagesJson()
                SCREENSHOT -> getScreenshot()
                SCREENSHOT_PERMISSION_STATUS -> getScreenshotPermissionStatus()
                SCREENSHOT_LAUNCH_DIALOG -> launchScreenshotPermissionDialog()
                else -> createErrorResponse("Unknown endpoint: ${uri.path}")
            }

            cursor.addRow(arrayOf(result))

        } catch (e: Exception) {
            Log.e(TAG, "Query execution failed", e)
            cursor.addRow(arrayOf(createErrorResponse("Execution failed: ${e.message}")))
        }

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return when (uriMatcher.match(uri)) {
            KEYBOARD_ACTIONS -> executeKeyboardAction(uri, values)
            OVERLAY_OFFSET -> updateOverlayOffset(uri, values)
            else -> "content://$AUTHORITY/result?status=error&message=${Uri.encode("Unsupported insert endpoint: ${uri.path}")}".toUri()
        }
    }

    private fun executeKeyboardAction(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) {
            return "content://$AUTHORITY/result?status=error&message=No values provided".toUri()
        }

        try {
            val action = uri.lastPathSegment ?: return "content://$AUTHORITY/result?status=error&message=No action specified".toUri()

            val result = when (action) {
                "input" -> performKeyboardInputBase64(values)
                "clear" -> performKeyboardClear()
                "key" -> performKeyboardKey(values)
                else -> "error: Unknown keyboard action: $action"
            }

            // Encode result in URI
            return if (result.startsWith("success")) {
                "content://$AUTHORITY/result?status=success&message=${Uri.encode(result)}".toUri()
            } else {
                "content://$AUTHORITY/result?status=error&message=${Uri.encode(result)}".toUri()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Keyboard action execution failed", e)
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Execution failed: ${e.message}")}".toUri()
        }
    }

    private fun updateOverlayOffset(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) {
            return "content://$AUTHORITY/result?status=error&message=No values provided".toUri()
        }

        try {
            val offset = values.getAsInteger("offset")
                ?: return "content://$AUTHORITY/result?status=error&message=No offset provided".toUri()

            val accessibilityService = DroidrunAccessibilityService.getInstance()
                ?: return "content://$AUTHORITY/result?status=error&message=Accessibility service not available".toUri()

            val success = accessibilityService.setOverlayOffset(offset)

            return if (success) {
                "content://$AUTHORITY/result?status=success&message=${Uri.encode("Overlay offset updated to $offset")}".toUri()
            } else {
                "content://$AUTHORITY/result?status=error&message=Failed to update overlay offset".toUri()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay offset", e)
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Execution failed: ${e.message}")}".toUri()
        }
    }

    private fun getAccessibilityTree(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")
        return try {

            val treeJson = accessibilityService.getVisibleElements().map { element ->
                buildElementNodeJson(element)
            }

            createSuccessResponse(treeJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility tree", e)
            createErrorResponse("Failed to get accessibility tree: ${e.message}")
        }
    }

    private fun buildElementNodeJson(element: ElementNode): JSONObject {
        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put("bounds", "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}")

            // Recursively build children JSON
            val childrenArray = org.json.JSONArray()
            element.children.forEach { child ->
                childrenArray.put(buildElementNodeJson(child))
            }
            put("children", childrenArray)
        }
    }


    private fun getPhoneState(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")
        return try {
            val phoneState = buildPhoneStateJson(accessibilityService.getPhoneState())
            createSuccessResponse(phoneState.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility tree", e)
            createErrorResponse("Failed to get accessibility tree: ${e.message}")
        }
    }

    private fun buildPhoneStateJson(phoneState: PhoneState) =
        JSONObject().apply {
            put("currentApp", phoneState.appName)
            put("packageName", phoneState.packageName)
            put("keyboardVisible", phoneState.keyboardVisible)
            put("isEditable", phoneState.isEditable)
            put("focusedElement", JSONObject().apply {
                val rect = Rect()
                put("text", phoneState.focusedElement?.text)
                put("className", phoneState.focusedElement?.className)
                put("resourceId", phoneState.focusedElement?.viewIdResourceName ?: "")
            }
            )
        }

    private fun getCombinedState(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")

        return try {
            // Get accessibility tree
            val treeJson = accessibilityService.getVisibleElements().map { element ->
                buildElementNodeJson(element)
            }

            // Get phone state
            val phoneStateJson = buildPhoneStateJson(accessibilityService.getPhoneState())

            // Combine both in a single response
            val combinedState = JSONObject().apply {
                put("a11y_tree", org.json.JSONArray(treeJson))
                put("phone_state", phoneStateJson)
            }

            createSuccessResponse(combinedState.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get combined state", e)
            createErrorResponse("Failed to get combined state: ${e.message}")
        }
    }

    // private fun performTextInput(values: ContentValues): String {
    //     val accessibilityService = DroidrunAccessibilityService.getInstance()
    //         ?: return "error: Accessibility service not available"
    //     // Get the hex-encoded text
    //     val hexText = values.getAsString("hex_text")
    //         ?: return "error: No hex_text provided"

    //     // Check if we should append (default is false = replace)
    //     val append = values.getAsBoolean("append") ?: false

    //     // Decode hex to actual text
    //     val text = try {
    //         hexText.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    //     } catch (e: Exception) {
    //         return "error: Invalid hex encoding: ${e.message}"
    //     }

    //     // Find the currently focused element
    //     val focusedNode = accessibilityService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    //         ?: return "error: No focused input element found"

    //     return try {
    //         val finalText = if (append) {
    //             // Get existing text and append to it
    //             val existingText = focusedNode.text?.toString() ?: ""
    //             existingText + text
    //         } else {
    //             // Just use the new text (replace)
    //             text
    //         }

    //         // Set the text using ACTION_SET_TEXT
    //         val arguments = Bundle().apply {
    //             putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
    //         }
    //         val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    //         focusedNode.recycle()

    //         if (result) {
    //             val mode = if (append) "appended" else "set"
    //             "success: Text $mode - '$text'"
    //         } else {
    //             "error: Text input failed"
    //         }
    //     } catch (e: Exception) {
    //         focusedNode.recycle()
    //         "error: Text input exception: ${e.message}"
    //     }
    // }

    private fun performKeyboardInputBase64(values: ContentValues): String {
        val base64Text = values.getAsString("base64_text") ?: return "error: no text provided"
        val clear = values.getAsBoolean("clear") ?: true

        return if (DroidrunKeyboardIME.getInstance() != null) {
            val ok = DroidrunKeyboardIME.getInstance()!!.inputB64Text(base64Text, clear)
            if (ok) "success: input done (clear=$clear)" else "error: input failed"
        } else {
            "error: IME not active"
        }
    }


    private fun performKeyboardClear(): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        return if (keyboardIME.clearText()) {
            "success: Text cleared via keyboard"
        } else {
            "error: Failed to clear text via keyboard"
        }
    }

    private fun performKeyboardKey(values: ContentValues): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        val keyCode = values.getAsInteger("key_code")
            ?: return "error: No key_code provided"

        return if (keyboardIME.sendKeyEventDirect(keyCode)) {
            "success: Key event sent via keyboard - code: $keyCode"
        } else {
            "error: Failed to send key event via keyboard"
        }
    }


    private fun getInstalledPackagesJson(): String {
        val pm = context?.packageManager ?: return createErrorResponse("PackageManager unavailable")

        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolvedApps: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val arr = JSONArray()

            for (resolveInfo in resolvedApps) {
                val pkgInfo = try {
                    pm.getPackageInfo(resolveInfo.activityInfo.packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    continue
                }

                val appInfo = resolveInfo.activityInfo.applicationInfo
                val obj = JSONObject()

                obj.put("packageName", pkgInfo.packageName)
                obj.put("label", resolveInfo.loadLabel(pm).toString())
                obj.put("versionName", pkgInfo.versionName ?: JSONObject.NULL)

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkgInfo.versionCode.toLong()
                }
                obj.put("versionCode", versionCode)

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                obj.put("isSystemApp", isSystem)

                arr.put(obj)
            }

            val root = JSONObject()
            root.put("status", "success")
            root.put("count", arr.length())
            root.put("packages", arr)

            root.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate launchable apps", e)
            createErrorResponse("Failed to enumerate launchable apps: ${e.message}")
        }
    }

    private fun createSuccessResponse(data: String): String {
        return JSONObject().apply {
            put("status", "success")
            put("data", data)
        }.toString()
    }

    private fun createErrorResponse(error: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("error", error)
        }.toString()
    }

    private fun getScreenshot(): String {
        return try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
                ?: return createErrorResponse("Accessibility service not available")

            Log.d(TAG, "Taking screenshot via ContentProvider...")
            val screenshotFuture = accessibilityService.takeScreenshotBase64(hideOverlay = true)

            // Wait for screenshot with timeout (10 seconds)
            val screenshotBase64 = screenshotFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)

            if (screenshotBase64.startsWith("error:")) {
                Log.e(TAG, "Screenshot failed: $screenshotBase64")
                createErrorResponse(screenshotBase64.substring(7)) // Remove "error:" prefix
            } else {
                Log.d(TAG, "Screenshot captured successfully, base64 length: ${screenshotBase64.length}")
                createSuccessResponse(screenshotBase64)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "Screenshot timeout", e)
            createErrorResponse("Screenshot timeout - operation took too long")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screenshot", e)
            createErrorResponse("Failed to get screenshot: ${e.message}")
        }
    }

    private fun getScreenshotPermissionStatus(): String {
        return try {
            val androidVersion = Build.VERSION.SDK_INT
            val versionName = Build.VERSION.RELEASE

            val status = if (androidVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+
                JSONObject().apply {
                    put("hasPermission", true)
                    put("method", "Accessibility Screenshot API")
                    put("androidVersion", versionName)
                    put("sdkInt", androidVersion)
                    put("message", "Android 14+ - using native Accessibility Screenshot API (bypasses FLAG_SECURE)")
                }
            } else {
                // Android 10-13
                val hasPermission = ScreenshotService.hasMediaProjectionPermission()
                JSONObject().apply {
                    put("hasPermission", hasPermission)
                    put("method", "MediaProjection API")
                    put("androidVersion", versionName)
                    put("sdkInt", androidVersion)
                    if (hasPermission) {
                        put("message", "MediaProjection permission granted - can bypass FLAG_SECURE")
                    } else {
                        put("message", "MediaProjection permission not granted")
                        put("action", "Call /screenshot/launch_permission_dialog to grant permission")
                    }
                }
            }

            createSuccessResponse(status.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screenshot permission status", e)
            createErrorResponse("Failed to get screenshot permission status: ${e.message}")
        }
    }

    private fun launchScreenshotPermissionDialog(): String {
        return try {
            Log.d(TAG, "Launch permission dialog requested via ContentProvider")

            // Check Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return createSuccessResponse("Android 14+ detected - no additional permission needed. Screenshot API available directly.")
            }

            val accessibilityService = DroidrunAccessibilityService.getInstance()
                ?: return createErrorResponse("Accessibility service not available")

            // Launch the permission activity that shows the MediaProjection dialog
            val intent = android.content.Intent(accessibilityService, ScreenshotPermissionActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            accessibilityService.startActivity(intent)

            createSuccessResponse("MediaProjection permission dialog launched. Please grant permission to enable screenshot.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch permission dialog", e)
            createErrorResponse("Failed to launch permission dialog: ${e.message}")
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
