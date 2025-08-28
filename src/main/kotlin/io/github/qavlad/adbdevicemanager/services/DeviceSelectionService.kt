package io.github.qavlad.adbdevicemanager.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent

/**
 * Сервис для сохранения состояния выбора устройств для ADB команд
 */
object DeviceSelectionService {
    private const val SELECTION_KEY = "adbrandomizer.deviceSelections"
    private val gson = Gson()
    
    /**
     * Получает сохранённые состояния выбора устройств
     */
    fun getSelections(): Map<String, Boolean> {
        val properties = PropertiesComponent.getInstance()
        val json = properties.getValue(SELECTION_KEY)
        if (json.isNullOrBlank()) return emptyMap()
        
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Сохраняет состояние выбора для устройства
     */
    fun setSelection(deviceSerialNumber: String, isSelected: Boolean) {
        val current = getSelections().toMutableMap()
        current[deviceSerialNumber] = isSelected
        saveSelections(current)
    }
    
    /**
     * Получает состояние выбора для конкретного устройства
     * По умолчанию возвращает true (устройство выбрано)
     */
    fun isSelected(deviceSerialNumber: String): Boolean {
        return getSelections()[deviceSerialNumber] ?: true
    }
    
    /**
     * Сохраняет все состояния выбора
     */
    private fun saveSelections(selections: Map<String, Boolean>) {
        val properties = PropertiesComponent.getInstance()
        val json = gson.toJson(selections)
        properties.setValue(SELECTION_KEY, json)
    }

}