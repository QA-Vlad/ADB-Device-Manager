package io.github.qavlad.adbrandomizer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

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
    
    init {
        createUI()
        reset()
    }
    
    private fun createUI() {
        // Scrcpy настройки
        val scrcpyPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        scrcpyPanel.add(restartScrcpyCheckBox)
        
        add(scrcpyPanel)
    }
    
    fun isModified(): Boolean {
        return restartScrcpyCheckBox.isSelected != settings.restartScrcpyOnResolutionChange
    }
    
    fun apply() {
        settings.restartScrcpyOnResolutionChange = restartScrcpyCheckBox.isSelected
    }
    
    fun reset() {
        restartScrcpyCheckBox.isSelected = settings.restartScrcpyOnResolutionChange
    }
}