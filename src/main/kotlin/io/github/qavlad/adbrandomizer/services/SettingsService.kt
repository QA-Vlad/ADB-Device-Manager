// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/SettingsService.kt

package io.github.qavlad.adbrandomizer.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import io.github.qavlad.adbrandomizer.config.PluginConfig

object SettingsService {
    // Ключ, под которым мы будем хранить весь JSON со списком пресетов
    private const val PRESETS_KEY = PluginConfig.SettingsKeys.PRESETS_KEY
    // Ключ для хранения пути к scrcpy
    private const val SCRCPY_PATH_KEY = PluginConfig.SettingsKeys.SCRCPY_PATH_KEY

    private val properties = PropertiesComponent.getInstance()
    private val gson = Gson()

    /**
     * Возвращает список пресетов по умолчанию, если ничего не сохранено.
     */
    private fun getDefaultPresets(): List<DevicePreset> {
        return PluginConfig.DefaultPresets.PRESETS.map { (label, size, dpi) ->
            DevicePreset(label, size, dpi)
        }
    }

    /**
     * Загружает список пресетов из хранилища.
     * Теперь использует PresetListService для поддержки нескольких списков.
     * @return List<DevicePreset> - список сохраненных пресетов.
     */
    fun getPresets(): List<DevicePreset> {
        // Сначала пытаемся получить из нового сервиса
        val presetsFromNewService = PresetListService.getPresetsForCompatibility()
        if (presetsFromNewService.isNotEmpty()) {
            return presetsFromNewService
        }
        
        // Если в новом сервисе пусто, пробуем старый способ для миграции
        val json = properties.getValue(PRESETS_KEY)
        if (json.isNullOrBlank()) {
            return getDefaultPresets()
        }
        return try {
            // Указываем Gson, что мы хотим получить именно List<DevicePreset>
            val type = object : TypeToken<List<DevicePreset>>() {}.type
            val oldPresets: List<DevicePreset> = gson.fromJson(json, type)
            
            // Мигрируем старые пресеты в новую систему
            if (oldPresets.isNotEmpty()) {
                PresetListService.savePresetsForCompatibility(oldPresets)
                // Очищаем старое хранилище
                properties.setValue(PRESETS_KEY, null)
            }
            
            oldPresets
        } catch (_: Exception) {
            // Если JSON некорректен, возвращаем дефолтные значения
            getDefaultPresets()
        }
    }

    /**
     * Сохраняет список пресетов в хранилище.
     * Теперь использует PresetListService для поддержки нескольких списков.
     * @param presets - список пресетов для сохранения.
     */
    fun savePresets(presets: List<DevicePreset>) {
        // Используем новый сервис для сохранения
        PresetListService.savePresetsForCompatibility(presets)
        
        // Очищаем старое хранилище если оно есть
        properties.setValue(PRESETS_KEY, null)
    }

    /**
     * Сохраняет путь к исполняемому файлу scrcpy.
     * @param path - путь к файлу для сохранения.
     */
    fun saveScrcpyPath(path: String) {
        properties.setValue(SCRCPY_PATH_KEY, path)
    }

    /**
     * Загружает сохраненный путь к scrcpy.
     * @return String? - сохраненный путь или null, если ничего не сохранено.
     */
    fun getScrcpyPath(): String? {
        return properties.getValue(SCRCPY_PATH_KEY)
    }
}