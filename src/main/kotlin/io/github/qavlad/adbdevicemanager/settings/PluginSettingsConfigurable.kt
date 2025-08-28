package io.github.qavlad.adbdevicemanager.settings

import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.JComponent

class PluginSettingsConfigurable : SearchableConfigurable {
    private var settingsPanel: PluginSettingsPanel? = null
    
    override fun createComponent(): JComponent {
        settingsPanel = PluginSettingsPanel()
        return settingsPanel!!
    }
    
    override fun isModified(): Boolean {
        return settingsPanel?.checkModified() ?: false
    }
    
    override fun apply() {
        settingsPanel?.applySettings()
    }
    
    override fun reset() {
        settingsPanel?.resetSettings()
    }
    
    override fun getDisplayName(): String = "ADB Device Manager"
    
    override fun getId(): String = "adb.screen.randomizer.settings"
    
    override fun disposeUIResources() {
        settingsPanel = null
    }
}