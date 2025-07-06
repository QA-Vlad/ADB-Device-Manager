package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.ValidationUtils

object DeviceStateService {
    
    data class DeviceDisplayState(
        val width: Int?,
        val height: Int?,
        val dpi: Int?
    )

    data class ActivePresetInfo(
        val activeSizePreset: DevicePreset?,
        val activeDpiPreset: DevicePreset?,
        val originalSizePreset: DevicePreset?,
        val originalDpiPreset: DevicePreset?,
        val resetSizePreset: DevicePreset?,
        val resetDpiPreset: DevicePreset?,
        val resetSizeValue: String?,
        val resetDpiValue: String?
    )
    
    private val deviceStates = mutableMapOf<String, DeviceDisplayState>()
    private var lastAppliedSizePreset: DevicePreset? = null
    private var lastAppliedDpiPreset: DevicePreset? = null
    private var lastResetSizePreset: DevicePreset? = null
    private var lastResetDpiPreset: DevicePreset? = null
    private var lastResetSizeValue: String? = null
    private var lastResetDpiValue: String? = null
    
    fun updateDeviceState(deviceId: String, width: Int?, height: Int?, dpi: Int?) {
        val currentState = deviceStates[deviceId]
        
        // Обновляем состояние, сохраняя существующие значения для null параметров
        deviceStates[deviceId] = DeviceDisplayState(
            width = width ?: currentState?.width,
            height = height ?: currentState?.height,
            dpi = dpi ?: currentState?.dpi
        )
    }

    fun setLastAppliedPresets(sizePreset: DevicePreset?, dpiPreset: DevicePreset?) {
        if (sizePreset != null) {
            lastAppliedSizePreset = sizePreset.copy()
            lastResetSizePreset = null
            lastResetSizeValue = null // Сбрасываем значение
        }
        if (dpiPreset != null) {
            lastAppliedDpiPreset = dpiPreset.copy()
            lastResetDpiPreset = null
            lastResetDpiValue = null // Сбрасываем значение
        }
    }

    fun handleReset(resetSize: Boolean, resetDpi: Boolean) {
        if (resetSize && lastAppliedSizePreset != null) {
            lastResetSizePreset = lastAppliedSizePreset?.copy()
            lastResetSizeValue = lastAppliedSizePreset?.size
            lastAppliedSizePreset = null
        }
        if (resetDpi && lastAppliedDpiPreset != null) {
            lastResetDpiPreset = lastAppliedDpiPreset?.copy()
            lastResetDpiValue = lastAppliedDpiPreset?.dpi
            lastAppliedDpiPreset = null
        }
    }

    
    fun getCurrentActivePresets(): ActivePresetInfo {
        val allPresets = SettingsService.getPresets()
        val connectedDevices = getConnectedDeviceStates()

        if (connectedDevices.isEmpty()) {
            return ActivePresetInfo(null, null, lastAppliedSizePreset, lastAppliedDpiPreset, lastResetSizePreset, lastResetDpiPreset, lastResetSizeValue, lastResetDpiValue)
        }
        
        // Берем состояние первого устройства как референс
        val referenceState = connectedDevices.values.first()
        
        val activeSizePreset = findMatchingPresetBySize(allPresets, referenceState)
        val activeDpiPreset = findMatchingPresetByDpi(allPresets, referenceState)

        return ActivePresetInfo(activeSizePreset, activeDpiPreset, lastAppliedSizePreset, lastAppliedDpiPreset, lastResetSizePreset, lastResetDpiPreset, lastResetSizeValue, lastResetDpiValue)
    }
    
    private fun getConnectedDeviceStates(): Map<String, DeviceDisplayState> {
        return deviceStates.filter { (_, _) ->
            // Проверяем, что устройство все еще подключено
            // Для упрощения возвращаем все сохраненные состояния
            true
        }
    }
    
    private fun findMatchingPresetBySize(presets: List<DevicePreset>, state: DeviceDisplayState): DevicePreset? {
        if (state.width == null || state.height == null) return null
        
        return presets.find { preset ->
            if (preset.size.isBlank()) return@find false
            val presetSize = ValidationUtils.parseSize(preset.size)
            presetSize != null && presetSize.first == state.width && presetSize.second == state.height
        }
    }
    
    private fun findMatchingPresetByDpi(presets: List<DevicePreset>, state: DeviceDisplayState): DevicePreset? {
        if (state.dpi == null) return null
        
        return presets.find { preset ->
            if (preset.dpi.isBlank()) return@find false
            val presetDpi = ValidationUtils.parseDpi(preset.dpi)
            presetDpi != null && presetDpi == state.dpi
        }
    }
    
    fun refreshDeviceStates(@Suppress("UNUSED_PARAMETER") project: Project) {
        try {
            val devices = AdbService.getConnectedDevices()
            
            devices.forEach { device ->
                try {
                    val currentSize = AdbService.getCurrentSize(device)
                    val currentDpi = AdbService.getCurrentDpi(device)
                    
                    updateDeviceState(
                        device.serialNumber,
                        currentSize?.first,
                        currentSize?.second,
                        currentDpi
                    )
                } catch (e: Exception) {
                    println("ADB_Randomizer: Error reading device state for ${device.serialNumber}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Error refreshing device states: ${e.message}")
        }
    }
}