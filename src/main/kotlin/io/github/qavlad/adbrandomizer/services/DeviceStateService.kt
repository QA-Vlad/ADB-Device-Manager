package io.github.qavlad.adbrandomizer.services

import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred

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
    
    suspend fun refreshDeviceStatesAsync() = withContext(Dispatchers.IO) {
        try {
            val devicesResult = AdbService.getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: run {
                devicesResult.onError { exception, message ->
                    PluginLogger.error("Failed to get devices for async state refresh", exception, message ?: "")
                }
                emptyList()
            }
            
            coroutineScope {
                val deferredResults: List<Deferred<Unit>> = devices.map { device ->
                    async {
                        try {
                            val sizeResult = AdbService.getCurrentSize(device)
                            val currentSize = sizeResult.getOrNull()
                            sizeResult.onError { exception, message ->
                                PluginLogger.error("Failed to get size for device ${device.serialNumber}", exception, message ?: "")
                            }
                            
                            val dpiResult = AdbService.getCurrentDpi(device)
                            val currentDpi = dpiResult.getOrNull()
                            dpiResult.onError { exception, message ->
                                PluginLogger.error("Failed to get DPI for device ${device.serialNumber}", exception, message ?: "")
                            }
                            
                            updateDeviceState(
                                device.serialNumber,
                                currentSize?.first,
                                currentSize?.second,
                                currentDpi
                            )
                        } catch (e: Exception) {
                            PluginLogger.error("Error reading device state asynchronously for ${device.serialNumber}", e)
                        }
                    }
                }
                deferredResults.forEach { it.await() }
            }
        } catch (e: Exception) {
            PluginLogger.error("Error in async device state refresh", e)
        }
    }
}