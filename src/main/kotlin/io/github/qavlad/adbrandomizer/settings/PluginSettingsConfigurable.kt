package io.github.qavlad.adbrandomizer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.AndroidStudioDetector
import io.github.qavlad.adbrandomizer.utils.FileLogger
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
    
    private val debugModeCheckBox = JBCheckBox("Enable debug mode (writes logs to file)").apply {
        toolTipText = "When enabled, all plugin logs will be written to files in the plugin directory"
    }
    
    private val openLogsButton = JButton("Open Logs Folder").apply {
        toolTipText = "Opens the folder containing debug log files"
        isEnabled = false
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
        
        add(mirroringPanel)
        
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
    }
    
    fun isModified(): Boolean {
        var modified = restartScrcpyCheckBox.isSelected != settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            modified = modified || restartRunningDevicesCheckBox.isSelected != settings.restartRunningDevicesOnResolutionChange
        }
        modified = modified || debugModeCheckBox.isSelected != settings.debugMode
        return modified
    }
    
    fun apply() {
        settings.restartScrcpyOnResolutionChange = restartScrcpyCheckBox.isSelected
        if (AndroidStudioDetector.isAndroidStudio()) {
            settings.restartRunningDevicesOnResolutionChange = restartRunningDevicesCheckBox.isSelected
        }
        
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
        debugModeCheckBox.isSelected = settings.debugMode
        openLogsButton.isEnabled = settings.debugMode
    }
}