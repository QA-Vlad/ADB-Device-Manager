package io.github.qavlad.adbdevicemanager.services

import com.intellij.ide.util.PropertiesComponent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory

/**
 * Сервис для управления порядком устройств в списке
 */
object DeviceOrderService {
    private const val DEVICE_ORDER_KEY = "adbrandomizer.deviceOrder"
    private val gson = Gson()
    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance()
    
    /**
     * Сохраняет порядок устройств
     */
    fun saveDeviceOrder(deviceSerials: List<String>) {
        try {
            val json = gson.toJson(deviceSerials)
            properties.setValue(DEVICE_ORDER_KEY, json)
            PluginLogger.debug(LogCategory.DEVICE_STATE, "Saved device order: $deviceSerials")
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.DEVICE_STATE, "Failed to save device order", e)
        }
    }
    
    /**
     * Загружает сохраненный порядок устройств
     */
    fun loadDeviceOrder(): List<String> {
        return try {
            val json = properties.getValue(DEVICE_ORDER_KEY)
            if (json != null) {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.DEVICE_STATE, "Failed to load device order", e)
            emptyList()
        }
    }

}