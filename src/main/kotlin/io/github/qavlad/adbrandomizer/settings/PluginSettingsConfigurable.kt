package io.github.qavlad.adbrandomizer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.AndroidStudioDetector
import io.github.qavlad.adbrandomizer.utils.FileLogger
import io.github.qavlad.adbrandomizer.services.PluginResetService
import java.awt.Desktop
import java.awt.FlowLayout
import javax.swing.*

class PluginSettingsConfigurable : Configurable {
    private var settingsPanel: PluginSettingsPanel? = null
    
    override fun createComponent(): JComponent {
        settingsPanel = PluginSettingsPanel()
        return settingsPanel!!
    }
    
    override fun isModified(): Boolean {
        return settingsPanel?.isModified() ?: false
    }
    
    override fun apply() {
        settingsPanel?.apply()
    }
    
    override fun reset() {
        settingsPanel?.reset()
    }
    
    override fun getDisplayName(): String = "ADB Screen Randomizer"
    
    override fun disposeUIResources() {
        settingsPanel = null
    }
}

class PluginSettingsPanel : JBPanel<PluginSettingsPanel>(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val settings = PluginSettings.instance
    
    private val restartScrcpyCheckBox = JBCheckBox("Automatically restart scrcpy when screen resolution changes").apply {
        toolTipText = "When enabled, scrcpy will automatically restart when screen resolution is changed via presets or reset"
    }
    
    private val restartRunningDevicesCheckBox = JBCheckBox("Automatically restart Running Devices mirroring when screen resolution changes").apply {
        toolTipText = "When enabled, Android Studio's Running Devices will automatically restart when screen resolution is changed via presets or reset"
    }
    
    private val restartActiveAppCheckBox = JBCheckBox("Automatically restart active app when screen resolution changes").apply {
        toolTipText = "When enabled, the currently active app will be restarted after resolution change (excluding system apps and launchers)"
    }
    
    private val autoSwitchWifiCheckBox = JBCheckBox("Automatically switch device to PC's Wi-Fi network when connecting (root only)").apply {
        toolTipText = "When enabled, the device will automatically switch to the same Wi-Fi network as your PC before establishing Wi-Fi connection. REQUIRES ROOT ACCESS on the device. Non-root devices will see instructions for manual switching."
    }
    
    private val debugModeCheckBox = JBCheckBox("Enable debug mode (writes logs to file)").apply {
        toolTipText = "When enabled, all plugin logs will be written to files in the plugin directory"
    }
    
    private val openLogsButton = JButton("Open Logs Folder").apply {
        toolTipText = "Opens the folder containing debug log files"
        isEnabled = false
    }
    
    private val resetAllButton = JButton("Reset All Plugin Data").apply {
        toolTipText = "Resets all plugin settings, presets, and cached data to default values"
        foreground = JBColor.RED
    }
    
    init {
        createUI()
        setupListeners()
        reset()
    }
    
    private fun createUI() {
        // Screen mirroring settings
        val mirroringPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        mirroringPanel.add(restartScrcpyCheckBox)
        
        // Show Running Devices option only in Android Studio
        if (AndroidStudioDetector.isAndroidStudio()) {
            mirroringPanel.add(restartRunningDevicesCheckBox)
        }
        
        mirroringPanel.add(restartActiveAppCheckBox)
        
        add(mirroringPanel)
        
        // Network settings
        val networkPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        networkPanel.add(autoSwitchWifiCheckBox)
        
        add(networkPanel)
        
        // Debug settings
        val debugPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        debugPanel.add(debugModeCheckBox)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = JBUI.Borders.emptyLeft(20)
            add(openLogsButton)
        }
        debugPanel.add(buttonPanel)
        
        add(debugPanel)
        
        // Reset section
        val resetPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val resetSeparator = JSeparator()
        resetPanel.add(resetSeparator)
        
        val resetLabel = JLabel("Danger Zone").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(10, 0, 5, 0)
        }
        resetPanel.add(resetLabel)
        
        val resetButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(resetAllButton)
        }
        resetPanel.add(resetButtonPanel)
        
        add(resetPanel)
    }
    
    private fun setupListeners() {
        // Enable/disable open logs button based on debug mode checkbox
        debugModeCheckBox.addChangeListener {
            openLogsButton.isEnabled = debugModeCheckBox.isSelected
        }
        
        // Open logs folder when button clicked
        openLogsButton.addActionListener {
            try {
                val logsDir = FileLogger.getLogDirectory().toFile()
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                }
                Desktop.getDesktop().open(logsDir)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to open logs folder: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        
        // Reset all plugin data when button clicked
        resetAllButton.addActionListener {
            val result = JOptionPane.showConfirmDialog(
                this,
                """This will reset ALL plugin data including:

• All plugin settings
• All custom presets  
• WiFi device history
• Log files
• All cached data

⚠️ This action cannot be undone!

Are you sure you want to continue?""",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            
            if (result == JOptionPane.YES_OPTION) {
                try {
                    PluginResetService.resetAllPluginData()
                    
                    // Reset UI to reflect changes
                    reset()
                    
                    JOptionPane.showMessageDialog(
                        this,
                        "Plugin data has been reset to defaults.\nPlease restart the IDE for all changes to take effect.",
                        "Reset Complete",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to reset plugin data: ${e.message}",
                        "Reset Failed",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
    
    fun isModified(): Boolean {
        var modified = restartScrcpyCheckBox.isSelected != settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            modified = modified || restartRunningDevicesCheckBox.isSelected != settings.restartRunningDevicesOnResolutionChange
        }
        modified = modified || restartActiveAppCheckBox.isSelected != settings.restartActiveAppOnResolutionChange
        modified = modified || autoSwitchWifiCheckBox.isSelected != settings.autoSwitchToHostWifi
        modified = modified || debugModeCheckBox.isSelected != settings.debugMode
        return modified
    }
    
    fun apply() {
        settings.restartScrcpyOnResolutionChange = restartScrcpyCheckBox.isSelected
        if (AndroidStudioDetector.isAndroidStudio()) {
            settings.restartRunningDevicesOnResolutionChange = restartRunningDevicesCheckBox.isSelected
        }
        settings.restartActiveAppOnResolutionChange = restartActiveAppCheckBox.isSelected
        settings.autoSwitchToHostWifi = autoSwitchWifiCheckBox.isSelected
        
        val debugModeChanged = settings.debugMode != debugModeCheckBox.isSelected
        settings.debugMode = debugModeCheckBox.isSelected
        
        // Reinitialize FileLogger if debug mode changed
        if (debugModeChanged) {
            FileLogger.reinitialize()
        }
    }
    
    fun reset() {
        restartScrcpyCheckBox.isSelected = settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            restartRunningDevicesCheckBox.isSelected = settings.restartRunningDevicesOnResolutionChange
        }
        restartActiveAppCheckBox.isSelected = settings.restartActiveAppOnResolutionChange
        autoSwitchWifiCheckBox.isSelected = settings.autoSwitchToHostWifi
        debugModeCheckBox.isSelected = settings.debugMode
        openLogsButton.isEnabled = settings.debugMode
    }
}