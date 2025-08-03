package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер для управления состоянием автоповорота устройств.
 * Сохраняет исходное состояние автоповорота и восстанавливает его после операций.
 */
object AutoRotationStateManager {
    
    private data class AutoRotationState(
        val deviceSerial: String,
        val wasEnabled: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val savedStates = ConcurrentHashMap<String, AutoRotationState>()
    
    /**
     * Сохраняет текущее состояние автоповорота для устройства.
     * @return true если автоповорот был включен
     */
    fun saveAutoRotationState(device: IDevice): Boolean {
        val wasEnabled = AdbService.isAutoRotationEnabled(device).getOrNull() ?: false
        val state = AutoRotationState(device.serialNumber, wasEnabled)
        savedStates[device.serialNumber] = state
        
        PluginLogger.debug(LogCategory.DEVICE_STATE, 
            "Saved auto-rotation state for device %s: %s", 
            device.serialNumber, 
            wasEnabled
        )
        
        return wasEnabled
    }
    
    /**
     * Восстанавливает сохранённое состояние автоповорота для устройства.
     * @return true если состояние было успешно восстановлено
     */
    fun restoreAutoRotationState(device: IDevice): Boolean {
        val state = savedStates.remove(device.serialNumber)
        if (state == null) {
            PluginLogger.debug(LogCategory.DEVICE_STATE, 
                "No saved auto-rotation state for device %s", 
                device.serialNumber
            )
            return false
        }
        
        // Проверяем, не слишком ли старое сохранённое состояние (более 10 минут)
        val ageMs = System.currentTimeMillis() - state.timestamp
        if (ageMs > 10 * 60 * 1000) {
            PluginLogger.warn("Saved auto-rotation state for device %s is too old (%d ms), skipping restore", 
                device.serialNumber, 
                ageMs
            )
            return false
        }
        
        // Восстанавливаем состояние только если оно отличается от текущего
        val currentEnabled = AdbService.isAutoRotationEnabled(device).getOrNull() ?: false
        if (currentEnabled != state.wasEnabled) {
            val result = AdbService.setAutoRotation(device, state.wasEnabled)
            if (result.isSuccess()) {
                PluginLogger.debug(LogCategory.DEVICE_STATE, 
                    "Restored auto-rotation state for device %s to %s", 
                    device.serialNumber, 
                    state.wasEnabled
                )
                return true
            } else {
                PluginLogger.warn("Failed to restore auto-rotation state for device %s: %s", 
                    device.serialNumber, 
                    result.getErrorMessage()
                )
            }
        } else {
            PluginLogger.debug(LogCategory.DEVICE_STATE, 
                "Auto-rotation state for device %s already matches saved state (%s), no restore needed", 
                device.serialNumber, 
                state.wasEnabled
            )
        }
        
        return false
    }

}