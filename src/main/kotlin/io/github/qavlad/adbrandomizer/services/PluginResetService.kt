package io.github.qavlad.adbrandomizer.services

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.utils.FileLogger
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.logging.LoggingConfiguration
import java.nio.file.Files

/**
 * Сервис для полного сброса всех данных плагина на значения по умолчанию
 */
object PluginResetService {
    
    /**
     * Сбрасывает все данные плагина на значения по умолчанию
     */
    fun resetAllPluginData() {
        PluginLogger.info(LogCategory.GENERAL, "Starting full plugin reset")
        
        ApplicationManager.getApplication().runWriteAction {
            try {
                // 1. Сброс настроек плагина
                resetPluginSettings()
                
                // 2. Удаление всех сохранённых пресетов
                clearAllPresets()
                
                // 3. Очистка истории WiFi устройств
                clearWifiDeviceHistory()
                
                // 4. Сброс настроек логирования
                resetLoggingConfiguration()
                
                // 5. Удаление временных файлов и логов
                clearTemporaryFiles()
                
                // 6. Очистка прочих данных
                clearMiscellaneousData()
                
                // 7. Принудительное сохранение состояния настроек
                PluginSettings.instance.loadState(PluginSettings())
                
                PluginLogger.info(LogCategory.GENERAL, "Plugin reset completed successfully")
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.GENERAL, "Failed to reset plugin data", e)
                throw RuntimeException("Failed to reset plugin data: ${e.message}", e)
            }
        }
    }
    
    /**
     * Сбрасывает основные настройки плагина
     */
    private fun resetPluginSettings() {
        val settings = PluginSettings.instance
        settings.restartScrcpyOnResolutionChange = true
        settings.restartRunningDevicesOnResolutionChange = true
        settings.debugMode = false
        
        PluginLogger.info(LogCategory.GENERAL, "Plugin settings reset to defaults")
    }
    
    /**
     * Удаляет все сохранённые пресеты
     */
    private fun clearAllPresets() {
        val properties = PropertiesComponent.getInstance()
        
        // Удаляем старый формат пресетов
        properties.unsetValue(PluginConfig.SettingsKeys.PRESETS_KEY)
        
        // Удаляем новый формат (списки пресетов)
        properties.unsetValue("ADB_RANDOMIZER_ACTIVE_LIST_ID")
        properties.unsetValue("ADB_RANDOMIZER_LISTS_METADATA")
        
        // Удаляем файлы пресетов
        val presetsDir = PresetListService.getPresetsDirectory()
        if (presetsDir.exists()) {
            presetsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    file.delete()
                }
            }
        }
        
        // Удаляем настройки показа всех пресетов
        properties.unsetValue("ADB_RANDOMIZER_SHOW_ALL_PRESETS_MODE")
        properties.unsetValue("ADB_RANDOMIZER_SHOW_ALL_PRESETS_ORDER")
        properties.unsetValue("ADB_RANDOMIZER_HIDE_DUPLICATES_MODE")
        
        PluginLogger.info(LogCategory.GENERAL, "All presets cleared")
    }
    
    /**
     * Очищает историю WiFi устройств
     */
    private fun clearWifiDeviceHistory() {
        val properties = PropertiesComponent.getInstance()
        properties.unsetValue("adbrandomizer.wifiDeviceHistory")
        
        PluginLogger.info(LogCategory.GENERAL, "WiFi device history cleared")
    }
    
    /**
     * Сбрасывает настройки логирования
     */
    private fun resetLoggingConfiguration() {
        val loggingConfig = service<LoggingConfiguration>()
        loggingConfig.resetToDefaults()
        
        PluginLogger.info(LogCategory.GENERAL, "Logging configuration reset")
    }
    
    /**
     * Удаляет временные файлы и логи
     */
    private fun clearTemporaryFiles() {
        // Удаляем логи
        val logDir = FileLogger.getLogDirectory()
        if (Files.exists(logDir)) {
            logDir.toFile().listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("adb-randomizer-") && file.name.endsWith(".log")) {
                    file.delete()
                }
            }
        }
        
        PluginLogger.info(LogCategory.GENERAL, "Temporary files and logs cleared")
    }
    
    /**
     * Очищает прочие данные плагина
     */
    private fun clearMiscellaneousData() {
        val properties = PropertiesComponent.getInstance()
        
        // Удаляем путь к scrcpy
        properties.unsetValue(PluginConfig.SettingsKeys.SCRCPY_PATH_KEY)
        
        // Удаляем настройки сортировки таблиц
        properties.unsetValue("ADB_RANDOMIZER_TABLE_SORTING_COLUMN")
        properties.unsetValue("ADB_RANDOMIZER_TABLE_SORTING_ORDER")
        
        // Удаляем любые другие ключи, которые могут остаться
        val allKeys = listOf(
            "ADB_RANDOMIZER_DIALOG_WIDTH",
            "ADB_RANDOMIZER_DIALOG_HEIGHT",
            "ADB_RANDOMIZER_DIALOG_X",
            "ADB_RANDOMIZER_DIALOG_Y",
            "ADB_RANDOMIZER_ORIENTATION",
            "ADB_RANDOMIZER_LAST_SELECTED_ROW",
            "ADB_RANDOMIZER_LAST_MIRROR_DEVICE",
            "ADB_RANDOMIZER_PRESET_ORDER"
        )
        
        allKeys.forEach { key ->
            properties.unsetValue(key)
        }
        
        PluginLogger.info(LogCategory.GENERAL, "Miscellaneous data cleared")
    }
    
}