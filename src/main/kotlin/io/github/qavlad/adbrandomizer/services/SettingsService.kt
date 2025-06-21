// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/SettingsService.kt

package io.github.qavlad.adbrandomizer.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent

object SettingsService {
    // Ключ, под которым мы будем хранить весь JSON со списком пресетов
    private const val PRESETS_KEY = "ADB_RANDOMIZER_PRESETS_JSON"

    private val properties = PropertiesComponent.getInstance()
    private val gson = Gson()

    /**
     * Возвращает список пресетов по умолчанию, если ничего не сохранено.
     */
    private fun getDefaultPresets(): List<DevicePreset> {
        return listOf(
            DevicePreset("Pixel 5", "1080x2340", "432"),
            DevicePreset("Pixel 3a", "1080x2220", "441"),
            DevicePreset("Generic Tablet", "1200x1920", "240")
        )
    }

    /**
     * Загружает список пресетов из хранилища.
     * @return List<DevicePreset> - список сохраненных пресетов.
     */
    fun getPresets(): List<DevicePreset> {
        val json = properties.getValue(PRESETS_KEY)
        if (json.isNullOrBlank()) {
            return getDefaultPresets()
        }
        return try {
            // Указываем Gson, что мы хотим получить именно List<DevicePreset>
            val type = object : TypeToken<List<DevicePreset>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            // Если JSON некорректен, возвращаем дефолтные значения
            getDefaultPresets()
        }
    }

    /**
     * Сохраняет список пресетов в хранилище.
     * @param presets - список пресетов для сохранения.
     */
    fun savePresets(presets: List<DevicePreset>) {
        val json = gson.toJson(presets)
        properties.setValue(PRESETS_KEY, json)
    }
}