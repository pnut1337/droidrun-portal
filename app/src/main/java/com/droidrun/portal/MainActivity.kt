package com.droidrun.portal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.provider.Settings
import android.widget.ImageView
import android.view.View
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.database.Cursor
import android.graphics.Color
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.inputmethod.InputMethodManager

class MainActivity : AppCompatActivity() {

    private lateinit var accessibilityBanner: com.google.android.material.card.MaterialCardView
    private lateinit var accessibilityStatusEnabled: com.google.android.material.card.MaterialCardView
    private lateinit var enableAccessibilityButton: MaterialButton
    private lateinit var keyboardBanner: com.google.android.material.card.MaterialCardView
    private lateinit var enableKeyboardButton: MaterialButton
    private var responseText: String = ""
    private lateinit var versionText: TextView
    private lateinit var logsLink: TextView
    private lateinit var toggleOverlay: SwitchMaterial
    private lateinit var fetchButton: MaterialButton
    private lateinit var offsetSlider: SeekBar
    private lateinit var offsetValueDisplay: TextInputEditText
    private lateinit var offsetValueInputLayout: TextInputLayout

    // Socket server UI elements
    private lateinit var socketPortInput: TextInputEditText
    private lateinit var socketPortInputLayout: TextInputLayout
    private lateinit var socketServerStatus: TextView
    private lateinit var adbForwardCommand: TextView

    // Endpoints collapsible section
    private lateinit var endpointsHeader: View
    private lateinit var endpointsContent: View
    private lateinit var endpointsArrow: ImageView
    private var isEndpointsExpanded = false

    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // SharedPreferences key for tracking keyboard prompt
    private val PREFS_NAME = "DroidrunPrefs"
    private val KEY_KEYBOARD_PROMPT_SHOWN = "keyboard_prompt_shown"
    private var previousAccessibilityState = false

    // Constants for the position offset slider
    companion object {
        private const val DEFAULT_OFFSET = 0
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET

        // Permission request code
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        accessibilityBanner = findViewById(R.id.accessibility_banner)
        accessibilityStatusEnabled = findViewById(R.id.accessibility_status_enabled)
        enableAccessibilityButton = findViewById(R.id.enable_accessibility_button)
        keyboardBanner = findViewById(R.id.keyboard_banner)
        enableKeyboardButton = findViewById(R.id.enable_keyboard_button)
        versionText = findViewById(R.id.version_text)
        logsLink = findViewById(R.id.logs_link)
        fetchButton = findViewById(R.id.fetch_button)
        toggleOverlay = findViewById(R.id.toggle_overlay)
        offsetSlider = findViewById(R.id.offset_slider)
        offsetValueDisplay = findViewById(R.id.offset_value_display)
        offsetValueInputLayout = findViewById(R.id.offset_value_input_layout)

        // Initialize socket server UI elements
        socketPortInput = findViewById(R.id.socket_port_input)
        socketPortInputLayout = findViewById(R.id.socket_port_input_layout)
        socketServerStatus = findViewById(R.id.socket_server_status)
        adbForwardCommand = findViewById(R.id.adb_forward_command)

        // Initialize endpoints collapsible section
        endpointsHeader = findViewById(R.id.endpoints_header)
        endpointsContent = findViewById(R.id.endpoints_content)
        endpointsArrow = findViewById(R.id.endpoints_arrow)

        // Set app version
        setAppVersion()

        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()

        // Configure socket server controls
        setupSocketServerControls()

        // Configure endpoints collapsible section
        setupEndpointsCollapsible()

        fetchButton.setOnClickListener {
            fetchElementData()
        }

        toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }

        // Setup enable accessibility button
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        // Setup enable keyboard button
        enableKeyboardButton.setOnClickListener {
            // Check if keyboard is already enabled but just not active
            if (isDroidrunKeyboardEnabled()) {
                showInputMethodPicker()
            } else {
                promptKeyboardSetup()
            }
        }

        // Setup logs link to show dialog
        logsLink.setOnClickListener {
            showLogsDialog()
        }

        // Request notification permission for Android 13+ (API 33+)
        requestNotificationPermissionIfNeeded()

        // Check initial accessibility status and sync UI
        previousAccessibilityState = isAccessibilityServiceEnabled()
        updateAccessibilityStatusIndicator()
        updateKeyboardStatusIndicator()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Only request permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Request the permission
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_POST_NOTIFICATIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("DROIDRUN_MAIN", "Notification permission granted")
                } else {
                    Log.w("DROIDRUN_MAIN", "Notification permission denied")
                    Toast.makeText(
                        this,
                        "Notification permission is required for the accessibility service to run properly",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if accessibility service was just enabled
        val currentAccessibilityState = isAccessibilityServiceEnabled()
        val wasJustEnabled = !previousAccessibilityState && currentAccessibilityState
        previousAccessibilityState = currentAccessibilityState

        // Update the accessibility status indicator when app resumes
        updateAccessibilityStatusIndicator()
        updateKeyboardStatusIndicator()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()

        // If accessibility was just enabled, prompt for keyboard setup
        if (wasJustEnabled && !isDroidrunKeyboardEnabled()) {
            promptKeyboardSetup()
        }
    }

    private fun syncUIWithAccessibilityService() {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // Sync overlay toggle
            toggleOverlay.isChecked = accessibilityService.isOverlayVisible()

            // Sync offset controls - show actual applied offset
            val displayOffset = accessibilityService.getOverlayOffset()
            updateOffsetSlider(displayOffset)
            updateOffsetInputField(displayOffset)
        }
    }

    private fun setupOffsetSlider() {
        // Initialize the slider with the new range
        offsetSlider.max = SLIDER_RANGE

        // Get initial value from service if available, otherwise use default
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        val initialOffset = accessibilityService?.getOverlayOffset() ?: DEFAULT_OFFSET

        // Convert the initial offset to slider position
        val initialSliderPosition = initialOffset - MIN_OFFSET
        offsetSlider.progress = initialSliderPosition

        // Set listener for slider changes
        offsetSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert slider position back to actual offset value (range -256 to +256)
                val offsetValue = progress + MIN_OFFSET

                // Update input field to match slider (only when user is sliding)
                if (fromUser) {
                    updateOffsetInputField(offsetValue)
                    updateOverlayOffset(offsetValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Final update when user stops sliding
                val offsetValue = seekBar?.progress?.plus(MIN_OFFSET) ?: DEFAULT_OFFSET
                updateOverlayOffset(offsetValue)
            }
        })
    }

    private fun setupOffsetInput() {
        // Get initial value from service if available, otherwise use default
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        val initialOffset = accessibilityService?.getOverlayOffset() ?: DEFAULT_OFFSET

        // Set initial value
        isProgrammaticUpdate = true
        offsetValueDisplay.setText(initialOffset.toString())
        isProgrammaticUpdate = false

        // Apply on enter key
        offsetValueDisplay.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }

        // Input validation and auto-apply
        offsetValueDisplay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return

                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value < MIN_OFFSET || value > MAX_OFFSET) {
                            offsetValueInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            offsetValueInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        offsetValueInputLayout.error = "Invalid number"
                    } else {
                        offsetValueInputLayout.error = null
                    }
                } catch (e: Exception) {
                    offsetValueInputLayout.error = "Invalid number"
                }
            }
        })
    }

    private fun applyInputOffset() {
        try {
            val inputText = offsetValueDisplay.text.toString()
            val offsetValue = inputText.toIntOrNull()

            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)

                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    offsetValueDisplay.setText(boundedValue.toString())
                    isProgrammaticUpdate = false
                    Toast.makeText(this, "Value adjusted to valid range", Toast.LENGTH_SHORT).show()
                }

                // Update slider to match and apply the offset
                val sliderPosition = boundedValue - MIN_OFFSET
                offsetSlider.progress = sliderPosition
                updateOverlayOffset(boundedValue)
            } else {
                // Invalid input
                Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error applying input offset: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOffsetSlider(currentOffset: Int) {
        // Ensure the offset is within our new bounds
        val boundedOffset = currentOffset.coerceIn(MIN_OFFSET, MAX_OFFSET)

        // Update the slider to match the current offset from the service
        val sliderPosition = boundedOffset - MIN_OFFSET
        offsetSlider.progress = sliderPosition
    }

    private fun updateOffsetInputField(currentOffset: Int) {
        // Set flag to prevent TextWatcher from triggering
        isProgrammaticUpdate = true

        // Update the text input to match the current offset
        offsetValueDisplay.setText(currentOffset.toString())

        // Reset flag
        isProgrammaticUpdate = false
    }

    private fun updateOverlayOffset(offsetValue: Int) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayOffset(offsetValue)
                if (success) {
                    Log.d("DROIDRUN_MAIN", "Offset updated successfully: $offsetValue")
                } else {
                    Log.e("DROIDRUN_MAIN", "Failed to update offset: $offsetValue")
                }
            } else {
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for offset update")
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating offset: ${e.message}")
        }
    }

    private fun fetchElementData() {
        try {
            // Use ContentProvider to get combined state (a11y tree + phone state)
            val uri = Uri.parse("content://com.droidrun.portal/state")

            val cursor = contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)

                    if (jsonResponse.getString("status") == "success") {
                        val data = jsonResponse.getString("data")
                        responseText = data
                        Toast.makeText(this, "Combined state received successfully!", Toast.LENGTH_SHORT).show()

                        Log.d("DROIDRUN_MAIN", "Combined state data received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        responseText = "Error: $error"
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error fetching combined state data: ${e.message}")
            Toast.makeText(this, "Error fetching data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlayVisibility(visible: Boolean) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayVisible(visible)
                if (success) {
                    Log.d("DROIDRUN_MAIN", "Overlay visibility toggled to: $visible")
                } else {
                    Log.e("DROIDRUN_MAIN", "Failed to toggle overlay visibility")
                }
            } else {
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for overlay toggle")
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error toggling overlay: ${e.message}")
        }
    }

    private fun fetchPhoneStateData() {
        try {
            // Use ContentProvider to get phone state
            val uri = Uri.parse("content://com.droidrun.portal/")
            val command = JSONObject().apply {
                put("action", "phone_state")
            }

            val cursor = contentResolver.query(
                uri,
                null,
                command.toString(),
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val result = it.getString(0)
                    val jsonResponse = JSONObject(result)

                    if (jsonResponse.getString("status") == "success") {
                        val data = jsonResponse.getString("data")
                        // responseText.text = data
                        Toast.makeText(this, "Phone state received successfully!", Toast.LENGTH_SHORT).show()

                        Log.d("DROIDRUN_MAIN", "Phone state received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        // responseText.text = error
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error fetching phone state: ${e.message}")
            Toast.makeText(this, "Error fetching phone state: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Check if the accessibility service is enabled
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = packageName + "/" + DroidrunAccessibilityService::class.java.canonicalName

        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            return enabledServices?.contains(accessibilityServiceName) == true
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking accessibility status: ${e.message}")
            return false
        }
    }

    // Update the accessibility status indicator based on service status
    private fun updateAccessibilityStatusIndicator() {
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            // Show enabled card, hide banner
            accessibilityStatusEnabled.visibility = View.VISIBLE
            accessibilityBanner.visibility = View.GONE
        } else {
            // Show banner, hide enabled card
            accessibilityStatusEnabled.visibility = View.GONE
            accessibilityBanner.visibility = View.VISIBLE
        }
    }

    // Open accessibility settings to enable the service
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please enable Droidrun Portal in Accessibility Services",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening accessibility settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening accessibility settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Check if DroidRun Keyboard is enabled
    private fun isDroidrunKeyboardEnabled(): Boolean {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabledInputMethods = imm.enabledInputMethodList

            val droidrunKeyboardId = "$packageName/.DroidrunKeyboardIME"

            return enabledInputMethods.any { inputMethod ->
                val fullId = "${inputMethod.packageName}/${inputMethod.serviceName}"
                fullId == droidrunKeyboardId
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking keyboard status: ${e.message}")
            return false
        }
    }

    // Check if DroidRun Keyboard is the currently active keyboard
    private fun isDroidrunKeyboardActive(): Boolean {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val currentInputMethod = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )

            val droidrunKeyboardId = "$packageName/.DroidrunKeyboardIME"
            return currentInputMethod == droidrunKeyboardId
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error checking active keyboard: ${e.message}")
            return false
        }
    }

    // Update the keyboard status indicator
    private fun updateKeyboardStatusIndicator() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isKeyboardEnabled = isDroidrunKeyboardEnabled()
        val isKeyboardActive = isDroidrunKeyboardActive()

        // Show keyboard banner if accessibility is enabled but keyboard is not enabled or not active
        if (isAccessibilityEnabled && (!isKeyboardEnabled || !isKeyboardActive)) {
            keyboardBanner.visibility = View.VISIBLE

            // Update banner text based on keyboard state
            val bannerText = findViewById<TextView>(R.id.keyboard_banner_text)
            val bannerButton = findViewById<MaterialButton>(R.id.enable_keyboard_button)

            if (!isKeyboardEnabled) {
                bannerText.text = "Enable Droidrun Keyboard for full functionality"
                bannerButton.text = "Enable Keyboard"
            } else {
                bannerText.text = "Switch to Droidrun Keyboard for full functionality"
                bannerButton.text = "Switch Keyboard"
            }
        } else {
            keyboardBanner.visibility = View.GONE
        }
    }

    // Check if WRITE_SECURE_SETTINGS permission is granted
    private fun hasWriteSecureSettingsPermission(): Boolean {
        return try {
            // Try to write a test value to check if we have permission
            Settings.Secure.putString(
                contentResolver,
                "test_write_permission",
                "test"
            )
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    // Programmatically enable and set DroidRun Keyboard as default
    private fun setDroidrunKeyboardAsDefault(): Boolean {
        if (!hasWriteSecureSettingsPermission()) {
            Log.w("DROIDRUN_MAIN", "WRITE_SECURE_SETTINGS permission not granted")
            return false
        }

        return try {
            val droidrunKeyboardId = "$packageName/.DroidrunKeyboardIME"
            val resolver = contentResolver

            // Get currently enabled input methods
            val enabledInputMethods = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""

            // Add DroidRun Keyboard to enabled list if not already there
            if (!enabledInputMethods.contains(droidrunKeyboardId)) {
                val newEnabledList = if (enabledInputMethods.isEmpty()) {
                    droidrunKeyboardId
                } else {
                    "$enabledInputMethods:$droidrunKeyboardId"
                }

                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_INPUT_METHODS,
                    newEnabledList
                )
                Log.d("DROIDRUN_MAIN", "Added DroidRun Keyboard to enabled input methods")
            }

            // Set DroidRun Keyboard as the default input method
            Settings.Secure.putString(
                resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                droidrunKeyboardId
            )

            Log.d("DROIDRUN_MAIN", "Successfully set DroidRun Keyboard as default")
            true
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error setting DroidRun Keyboard as default: ${e.message}", e)
            false
        }
    }

    // Open input method settings to enable the keyboard
    private fun openInputMethodSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Please enable Droidrun Keyboard in Language & Input settings",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error opening input method settings: ${e.message}")
            Toast.makeText(
                this,
                "Error opening input method settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Prompt user to set up keyboard after enabling accessibility service
    private fun promptKeyboardSetup() {
        try {
            // Check if we have WRITE_SECURE_SETTINGS permission
            if (hasWriteSecureSettingsPermission()) {
                // We can set keyboard automatically!
                AlertDialog.Builder(this)
                    .setTitle("Set Droidrun Keyboard")
                    .setMessage("DroidRun can automatically set its keyboard as the default input method.\n\nWould you like to do this now?")
                    .setPositiveButton("Set Now") { _, _ ->
                        val success = setDroidrunKeyboardAsDefault()
                        if (success) {
                            Toast.makeText(
                                this,
                                "✓ Droidrun Keyboard is now the default keyboard!",
                                Toast.LENGTH_LONG
                            ).show()
                            updateKeyboardStatusIndicator()
                        } else {
                            Toast.makeText(
                                this,
                                "Failed to set keyboard. Please try manually.",
                                Toast.LENGTH_SHORT
                            ).show()
                            showManualKeyboardSetup()
                        }
                    }
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show()
            } else {
                // No permission, show manual setup flow
                showManualKeyboardSetup()
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error showing keyboard setup prompt: ${e.message}")
        }
    }

    // Show manual keyboard setup flow
    private fun showManualKeyboardSetup() {
        AlertDialog.Builder(this)
            .setTitle("Enable Droidrun Keyboard")
            .setMessage("To use full automation features, please enable and switch to Droidrun Keyboard.\n\nWould you like to set it up now?\n\n💡 Tip: For automatic setup, grant permission via ADB:\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            .setPositiveButton("Enable Keyboard") { _, _ ->
                // First, open settings to enable the keyboard
                openInputMethodSettings()

                // Show a follow-up prompt after a delay to switch keyboard
                mainHandler.postDelayed({
                    showKeyboardSwitchPrompt()
                }, 2000)
            }
            .setNeutralButton("Copy ADB Command") { _, _ ->
                val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "ADB command copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    // Show prompt to switch keyboard (after enabling)
    private fun showKeyboardSwitchPrompt() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Switch Keyboard")
                .setMessage("After enabling Droidrun Keyboard in settings, you can switch to it using the keyboard selector.\n\nWould you like to open the keyboard selector now?")
                .setPositiveButton("Switch Now") { _, _ ->
                    showInputMethodPicker()
                }
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error showing keyboard switch prompt: ${e.message}")
        }
    }

    // Show the system's input method picker dialog
    private fun showInputMethodPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            Toast.makeText(
                this,
                "Select 'Droidrun Keyboard' from the list",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error showing input method picker: ${e.message}")
            Toast.makeText(
                this,
                "Could not open keyboard selector. You can switch keyboards from your notification panel.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupSocketServerControls() {
        // Initialize with ConfigManager values
        val configManager = ConfigManager.getInstance(this)

        // Set default port value
        isProgrammaticUpdate = true
        socketPortInput.setText(configManager.socketServerPort.toString())
        isProgrammaticUpdate = false

        // Port input listener
        socketPortInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticUpdate) return

                try {
                    val portText = s.toString()
                    if (portText.isNotEmpty()) {
                        val port = portText.toIntOrNull()
                        if (port != null && port in 1..65535) {
                            socketPortInputLayout.error = null
                            updateSocketServerPort(port)
                        } else {
                            socketPortInputLayout.error = "Port must be between 1-65535"
                        }
                    } else {
                        socketPortInputLayout.error = null
                    }
                } catch (e: Exception) {
                    socketPortInputLayout.error = "Invalid port number"
                }
            }
        })

        // Update initial UI state
        updateSocketServerStatus()
        updateAdbForwardCommand()
    }



    private fun updateSocketServerPort(port: Int) {
        try {
            val configManager = ConfigManager.getInstance(this)
            configManager.setSocketServerPortWithNotification(port)

            updateAdbForwardCommand()

            // Give the server a moment to restart, then update the status
            mainHandler.postDelayed({
                updateSocketServerStatus()
            }, 1000)

            Log.d("DROIDRUN_MAIN", "Socket server port updated: $port")
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating socket server port: ${e.message}")
        }
    }

    private fun updateSocketServerStatus() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val status = accessibilityService.getSocketServerStatus()
                socketServerStatus.text = status
                socketServerStatus.setTextColor(Color.parseColor("#00FFA6"))
            } else {
                socketServerStatus.text = "Service not available"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating socket server status: ${e.message}")
            socketServerStatus.text = "Error"
        }
    }

    private fun updateAdbForwardCommand() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val command = accessibilityService.getAdbForwardCommand()
                adbForwardCommand.text = command
            } else {
                val configManager = ConfigManager.getInstance(this)
                val port = configManager.socketServerPort
                adbForwardCommand.text = "adb forward tcp:$port tcp:$port"
            }
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error updating ADB forward command: ${e.message}")
            adbForwardCommand.text = "Error"
        }
    }

    private fun setupEndpointsCollapsible() {
        endpointsHeader.setOnClickListener {
            isEndpointsExpanded = !isEndpointsExpanded

            if (isEndpointsExpanded) {
                endpointsContent.visibility = View.VISIBLE
                endpointsArrow.rotation = 90f
            } else {
                endpointsContent.visibility = View.GONE
                endpointsArrow.rotation = 0f
            }
        }
    }

    private fun setAppVersion() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val version = packageInfo.versionName
            versionText.text = "Version: $version"
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error getting app version: ${e.message}")
            versionText.text = "Version: N/A"
        }
    }

    private fun showLogsDialog() {
        try {
            val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_item, null)

            // Create a scrollable TextView for the logs
            val scrollView = androidx.core.widget.NestedScrollView(this)
            val textView = TextView(this).apply {
                text = if (responseText.isNotEmpty()) responseText else "No logs available. Fetch data first."
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(40, 40, 40, 40)
                setTextIsSelectable(true)
            }
            scrollView.addView(textView)

            AlertDialog.Builder(this)
                .setTitle("Response Logs")
                .setView(scrollView)
                .setPositiveButton("Close") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Response Logs", responseText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .create()
                .apply {
                    window?.setBackgroundDrawableResource(android.R.color.background_dark)
                }
                .show()
        } catch (e: Exception) {
            Log.e("DROIDRUN_MAIN", "Error showing logs dialog: ${e.message}")
            Toast.makeText(this, "Error showing logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}