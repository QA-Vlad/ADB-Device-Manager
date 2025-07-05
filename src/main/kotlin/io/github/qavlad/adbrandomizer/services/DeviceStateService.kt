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
        val resetDpiPreset: DevicePreset?
    )
    
    private val deviceStates = mutableMapOf<String, DeviceDisplayState>()
    private var lastAppliedSizePreset: DevicePreset? = null
    private var lastAppliedDpiPreset: DevicePreset? = null
    private var lastResetSizePreset: DevicePreset? = null
    private var lastResetDpiPreset: DevicePreset? = null
    
    fun updateDeviceState(deviceId: String, width: Int?, height: Int?, dpi: Int?) {
        deviceStates[deviceId] = DeviceDisplayState(width, height, dpi)
    }
    
    fun setLastAppliedPresets(sizePreset: DevicePreset?, dpiPreset: DevicePreset?) {
        // Обновляем только те пресеты, которые действительно применялись
        if (sizePreset != null) {
            lastAppliedSizePreset = sizePreset.copy()
            // Очищаем сброшенный пресет, так как применен новый
            lastResetSizePreset = null
        }
        if (dpiPreset != null) {
            lastAppliedDpiPreset = dpiPreset.copy()
            // Очищаем сброшенный пресет, так как применен новый
            lastResetDpiPreset = null
        }
        // НЕ сбрасываем существующие значения, если параметр не передан
    }
    
    fun handleReset(resetSize: Boolean, resetDpi: Boolean) {
        println("ADB_DEBUG: handleReset вызван: resetSize=$resetSize, resetDpi=$resetDpi")
        println("ADB_DEBUG: До сброса - lastAppliedSizePreset=${lastAppliedSizePreset?.let { "${it.label}(${it.size})" }}")
        println("ADB_DEBUG: До сброса - lastAppliedDpiPreset=${lastAppliedDpiPreset?.let { "${it.label}(${it.dpi})" }}")
        
        // Сохраняем информацию о том, что было сброшено
        if (resetSize && lastAppliedSizePreset != null) {
            lastResetSizePreset = lastAppliedSizePreset?.copy()
            println("ADB_DEBUG: Сохранили lastResetSizePreset=${lastResetSizePreset?.let { "${it.label}(${it.size})" }}")
            lastAppliedSizePreset = null
        }
        if (resetDpi && lastAppliedDpiPreset != null) {
            lastResetDpiPreset = lastAppliedDpiPreset?.copy()
            println("ADB_DEBUG: Сохранили lastResetDpiPreset=${lastResetDpiPreset?.let { "${it.label}(${it.dpi})" }}")
            lastAppliedDpiPreset = null
        }
        
        println("ADB_DEBUG: После сброса - lastAppliedSizePreset=$lastAppliedSizePreset")
        println("ADB_DEBUG: После сброса - lastAppliedDpiPreset=$lastAppliedDpiPreset")
    }
    
    fun getDeviceState(deviceId: String): DeviceDisplayState? {
        return deviceStates[deviceId]
    }
    
    fun getCurrentActivePresets(): ActivePresetInfo {
        val allPresets = SettingsService.getPresets()
        val connectedDevices = getConnectedDeviceStates()
        
        if (connectedDevices.isEmpty()) {
            return ActivePresetInfo(null, null, lastAppliedSizePreset, lastAppliedDpiPreset, lastResetSizePreset, lastResetDpiPreset)
        }
        
        // Берем состояние первого устройства как референс
        val referenceState = connectedDevices.values.first()
        
        val activeSizePreset = findMatchingPresetBySize(allPresets, referenceState)
        val activeDpiPreset = findMatchingPresetByDpi(allPresets, referenceState)
        
        return ActivePresetInfo(activeSizePreset, activeDpiPreset, lastAppliedSizePreset, lastAppliedDpiPreset, lastResetSizePreset, lastResetDpiPreset)
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
    
    fun refreshDeviceStates(project: Project) {
        try {
            val devices = AdbService.getConnectedDevices(project)
            
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