package com.droidrun.portal

import android.content.Context
import android.content.Intent
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
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var versionText: TextView
    private lateinit var toggleOverlay: SwitchMaterial
    private lateinit var toggleAutoOffset: SwitchMaterial
    private lateinit var fetchButton: MaterialButton
    private lateinit var offsetSlider: SeekBar
    private lateinit var offsetInput: TextInputEditText
    private lateinit var offsetInputLayout: TextInputLayout
    private lateinit var accessibilityIndicator: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilityStatusContainer: View
    private lateinit var accessibilityStatusCard: com.google.android.material.card.MaterialCardView
    
    // Socket server UI elements
    private lateinit var socketPortInput: TextInputEditText
    private lateinit var socketPortInputLayout: TextInputLayout
    private lateinit var socketServerStatus: TextView
    private lateinit var adbForwardCommand: TextView
    
    // Flag to prevent infinite update loops
    private var isProgrammaticUpdate = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Constants for the position offset slider
    companion object {
        private const val DEFAULT_OFFSET = 0
        private const val MIN_OFFSET = -256
        private const val MAX_OFFSET = 256
        private const val SLIDER_RANGE = MAX_OFFSET - MIN_OFFSET
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI elements
        statusText = findViewById(R.id.status_text)
        responseText = findViewById(R.id.response_text)
        versionText = findViewById(R.id.version_text)
        fetchButton = findViewById(R.id.fetch_button)
        toggleOverlay = findViewById(R.id.toggle_overlay)
        toggleAutoOffset = findViewById(R.id.toggle_auto_offset)
        offsetSlider = findViewById(R.id.offset_slider)
        offsetInput = findViewById(R.id.offset_input)
        offsetInputLayout = findViewById(R.id.offset_input_layout)
        accessibilityIndicator = findViewById(R.id.accessibility_indicator)
        accessibilityStatusText = findViewById(R.id.accessibility_status_text)
        accessibilityStatusContainer = findViewById(R.id.accessibility_status_container)
        accessibilityStatusCard = findViewById(R.id.accessibility_status_card)
        
        // Initialize socket server UI elements
        socketPortInput = findViewById(R.id.socket_port_input)
        socketPortInputLayout = findViewById(R.id.socket_port_input_layout)
        socketServerStatus = findViewById(R.id.socket_server_status)
        adbForwardCommand = findViewById(R.id.adb_forward_command)
        
        // Set app version
        setAppVersion()
        
        // Configure the offset slider and input
        setupOffsetSlider()
        setupOffsetInput()
        
        // Configure socket server controls
        setupSocketServerControls()
        
        fetchButton.setOnClickListener {
            fetchElementData()
        }

        toggleOverlay.setOnCheckedChangeListener { _, isChecked ->
            toggleOverlayVisibility(isChecked)
        }

        toggleAutoOffset.setOnCheckedChangeListener { _, isChecked ->
            toggleAutoOffset(isChecked)
        }

        // Setup accessibility status container
        accessibilityStatusContainer.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // Check initial accessibility status and sync UI
        updateAccessibilityStatusIndicator()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
    }
    
    override fun onResume() {
        super.onResume()
        // Update the accessibility status indicator when app resumes
        updateAccessibilityStatusIndicator()
        syncUIWithAccessibilityService()
        updateSocketServerStatus()
    }
    
    private fun syncUIWithAccessibilityService() {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // Sync overlay toggle
            toggleOverlay.isChecked = accessibilityService.isOverlayVisible()

            // Sync auto offset toggle
            val autoOffsetEnabled = accessibilityService.isAutoOffsetEnabled()
            toggleAutoOffset.isChecked = autoOffsetEnabled

            // Sync offset controls - show actual applied offset
            val displayOffset = if (autoOffsetEnabled) {
                // Show the actual calculated auto-offset
                accessibilityService.getCurrentAppliedOffset()
            } else {
                // Show the saved manual offset
                accessibilityService.getOverlayOffset()
            }
            updateOffsetSlider(displayOffset)
            updateOffsetInputField(displayOffset)

            // Disable manual offset controls if auto-offset is enabled
            updateOffsetControlsEnabled(!autoOffsetEnabled)

            statusText.text = "Connected to accessibility service"
        } else {
            statusText.text = "Accessibility service not available"
        }
    }
    
    private fun setupOffsetSlider() {
        // Initialize the slider with the new range
        offsetSlider.max = SLIDER_RANGE
        
        // Convert the default offset to slider position
        val initialSliderPosition = DEFAULT_OFFSET - MIN_OFFSET
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
        // Set initial value
        isProgrammaticUpdate = true
        offsetInput.setText(DEFAULT_OFFSET.toString())
        isProgrammaticUpdate = false
        
        // Apply on enter key
        offsetInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputOffset()
                true
            } else {
                false
            }
        }
        
        // Input validation and auto-apply
        offsetInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                // Skip processing if this is a programmatic update
                if (isProgrammaticUpdate) return
                
                try {
                    val value = s.toString().toIntOrNull()
                    if (value != null) {
                        if (value < MIN_OFFSET || value > MAX_OFFSET) {
                            offsetInputLayout.error = "Value must be between $MIN_OFFSET and $MAX_OFFSET"
                        } else {
                            offsetInputLayout.error = null
                            // Auto-apply if value is valid and complete
                            if (s.toString().length > 1 || (s.toString().length == 1 && !s.toString().startsWith("-"))) {
                                applyInputOffset()
                            }
                        }
                    } else if (s.toString().isNotEmpty() && s.toString() != "-") {
                        offsetInputLayout.error = "Invalid number"
                    } else {
                        offsetInputLayout.error = null
                    }
                } catch (e: Exception) {
                    offsetInputLayout.error = "Invalid number"
                }
            }
        })
    }
    
    private fun applyInputOffset() {
        try {
            val inputText = offsetInput.text.toString()
            val offsetValue = inputText.toIntOrNull()
            
            if (offsetValue != null) {
                // Ensure the value is within bounds
                val boundedValue = offsetValue.coerceIn(MIN_OFFSET, MAX_OFFSET)
                
                if (boundedValue != offsetValue) {
                    // Update input if we had to bound the value
                    isProgrammaticUpdate = true
                    offsetInput.setText(boundedValue.toString())
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
        offsetInput.setText(currentOffset.toString())
        
        // Reset flag
        isProgrammaticUpdate = false
    }
    
    private fun updateOverlayOffset(offsetValue: Int) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayOffset(offsetValue)
                if (success) {
                    statusText.text = "Element offset updated to: $offsetValue"
                    Log.d("DROIDRUN_MAIN", "Offset updated successfully: $offsetValue")
                } else {
                    statusText.text = "Failed to update offset"
                    Log.e("DROIDRUN_MAIN", "Failed to update offset: $offsetValue")
                }
            } else {
                statusText.text = "Accessibility service not available"
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for offset update")
            }
        } catch (e: Exception) {
            statusText.text = "Error updating offset: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error updating offset: ${e.message}")
        }
    }
    
    private fun fetchElementData() {
        try {
            statusText.text = "Fetching combined state data..."
            
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
                        responseText.text = data
                        statusText.text = "Combined state data received: ${data.length} characters"
                        Toast.makeText(this, "Combined state received successfully!", Toast.LENGTH_SHORT).show()
                        
                        Log.d("DROIDRUN_MAIN", "Combined state data received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        statusText.text = "Error: $error"
                        responseText.text = error
                    }
                }
            }
            
        } catch (e: Exception) {
            statusText.text = "Error fetching data: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error fetching combined state data: ${e.message}")
        }
    }
    
    private fun toggleOverlayVisibility(visible: Boolean) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setOverlayVisible(visible)
                if (success) {
                    statusText.text = "Visualization overlays ${if (visible) "enabled" else "disabled"}"
                    Log.d("DROIDRUN_MAIN", "Overlay visibility toggled to: $visible")
                } else {
                    statusText.text = "Failed to toggle overlay"
                    Log.e("DROIDRUN_MAIN", "Failed to toggle overlay visibility")
                }
            } else {
                statusText.text = "Accessibility service not available"
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for overlay toggle")
            }
        } catch (e: Exception) {
            statusText.text = "Error changing visibility: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error toggling overlay: ${e.message}")
        }
    }

    private fun toggleAutoOffset(enabled: Boolean) {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val success = accessibilityService.setAutoOffsetEnabled(enabled)
                if (success) {
                    // Disable/enable manual offset controls based on auto-offset state
                    updateOffsetControlsEnabled(!enabled)

                    // Display the current applied offset (doesn't reset when disabling)
                    mainHandler.postDelayed({
                        val displayOffset = accessibilityService.getCurrentAppliedOffset()
                        updateOffsetSlider(displayOffset)
                        updateOffsetInputField(displayOffset)

                        statusText.text = "Auto-offset ${if (enabled) "enabled" else "disabled"} (offset: $displayOffset)"
                        Log.d("DROIDRUN_MAIN", "Auto-offset toggled to: $enabled, displaying offset: $displayOffset")
                    }, 100) // Small delay to ensure offset is calculated and applied
                } else {
                    statusText.text = "Failed to toggle auto-offset"
                    Log.e("DROIDRUN_MAIN", "Failed to toggle auto-offset")
                }
            } else {
                statusText.text = "Accessibility service not available"
                Log.e("DROIDRUN_MAIN", "Accessibility service not available for auto-offset toggle")
            }
        } catch (e: Exception) {
            statusText.text = "Error changing auto-offset: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error toggling auto-offset: ${e.message}")
        }
    }

    private fun updateOffsetControlsEnabled(enabled: Boolean) {
        offsetSlider.isEnabled = enabled
        offsetInput.isEnabled = enabled
        offsetInputLayout.isEnabled = enabled

        // Visual feedback: reduce alpha when disabled
        val alpha = if (enabled) 1.0f else 0.5f
        offsetSlider.alpha = alpha
        offsetInput.alpha = alpha
    }

    private fun fetchPhoneStateData() {
        try {
            statusText.text = "Fetching phone state..."
            
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
                        responseText.text = data
                        statusText.text = "Phone state received: ${data.length} characters"
                        Toast.makeText(this, "Phone state received successfully!", Toast.LENGTH_SHORT).show()
                        
                        Log.d("DROIDRUN_MAIN", "Phone state received: ${data.substring(0, Math.min(100, data.length))}...")
                    } else {
                        val error = jsonResponse.getString("error")
                        statusText.text = "Error: $error"
                        responseText.text = error
                    }
                }
            }
            
        } catch (e: Exception) {
            statusText.text = "Error fetching phone state: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error fetching phone state: ${e.message}")
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
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_green)
            accessibilityStatusText.text = "ENABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
        } else {
            accessibilityIndicator.setBackgroundResource(R.drawable.circle_indicator_red)
            accessibilityStatusText.text = "DISABLED"
            accessibilityStatusCard.setCardBackgroundColor(resources.getColor(R.color.droidrun_secondary, null))
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
            configManager.socketServerPort = port
            
            statusText.text = "Socket server port updated to: $port"
            updateAdbForwardCommand()
            
            Log.d("DROIDRUN_MAIN", "Socket server port updated: $port")
        } catch (e: Exception) {
            statusText.text = "Error updating socket server port: ${e.message}"
            Log.e("DROIDRUN_MAIN", "Error updating socket server port: ${e.message}")
        }
    }
    
    private fun updateSocketServerStatus() {
        try {
            val accessibilityService = DroidrunAccessibilityService.getInstance()
            if (accessibilityService != null) {
                val status = accessibilityService.getSocketServerStatus()
                socketServerStatus.text = status
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
} 