package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.ui.models.CombinedDeviceInfo
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import kotlinx.coroutines.*
import javax.swing.SwingUtilities


class DevicePollingService(private val project: Project) {
    private var pollingJob: Job? = null
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCombinedUpdateCallback: ((List<CombinedDeviceInfo>) -> Unit)? = null
    
    // Сохраняем состояние чекбоксов между обновлениями
    private val selectedDevices = mutableSetOf<String>()

    fun stopDevicePolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * Запускает поллинг устройств с объединением USB и Wi-Fi подключений
     */
    fun startCombinedDevicePolling(onDevicesUpdated: (List<CombinedDeviceInfo>) -> Unit) {
        lastCombinedUpdateCallback = onDevicesUpdated
        stopDevicePolling()
        
        pollingJob = pollingScope.launch {
            try {
                // 1. Мгновенно получить первый список устройств
                val firstDevicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                val firstDevices = firstDevicesRaw.map { DeviceInfo(it, null) }
                val firstCombined = combineDevices(firstDevices)
                SwingUtilities.invokeLater { onDevicesUpdated(firstCombined) }
                
                // 2. Далее — Flow с периодическим обновлением
                AdbServiceAsync.deviceFlow(project, PluginConfig.UI.DEVICE_POLLING_INTERVAL_MS.toLong())
                    .collect { deviceInfos ->
                        val combined = combineDevices(deviceInfos)
                        SwingUtilities.invokeLater { onDevicesUpdated(combined) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginLogger.error("Error in combined device polling", e)
            }
        }
    }
    
    /**
     * Форсирует немедленное обновление списка объединённых устройств
     */
    fun forceCombinedUpdate() {
        lastCombinedUpdateCallback?.let { callback ->
            pollingScope.launch {
                try {
                    val devicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                    val devices = devicesRaw.map { DeviceInfo(it, null) }
                    val combined = combineDevices(devices)
                    SwingUtilities.invokeLater { callback(combined) }
                } catch (e: Exception) {
                    PluginLogger.error("Error in force combined update", e)
                }
            }
        }
    }
    
    /**
     * Объединяет USB и Wi-Fi подключения одного устройства
     */
    private fun combineDevices(devices: List<DeviceInfo>): List<CombinedDeviceInfo> {
        val combinedMap = mutableMapOf<String, CombinedDeviceInfo>()
        
        for (device in devices) {
            val baseSerial = extractBaseSerialNumber(device)
            val existing = combinedMap[baseSerial]
            
            // Получаем текущие параметры экрана
            val currentResolution = device.device?.let { 
                AdbService.getCurrentSize(it).getOrNull() 
            }
            val currentDpi = device.device?.let { 
                AdbService.getCurrentDpi(it).getOrNull() 
            }
            val defaultResolution = device.device?.let { 
                AdbService.getDefaultSize(it).getOrNull() 
            }
            val defaultDpi = device.device?.let { 
                AdbService.getDefaultDpi(it).getOrNull() 
            }
            
            // Получаем IP адрес устройства (даже для USB подключения)
            val ipAddress = device.ipAddress ?: device.device?.let {
                AdbService.getDeviceIpAddress(it).getOrNull()
            }
            
            if (existing != null) {
                // Устройство уже есть, добавляем второе подключение
                val updated = if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) {
                    existing.copy(
                        wifiDevice = device,
                        ipAddress = ipAddress ?: existing.ipAddress,
                        // Обновляем параметры экрана если они не были установлены
                        currentResolution = existing.currentResolution ?: currentResolution,
                        currentDpi = existing.currentDpi ?: currentDpi,
                        defaultResolution = existing.defaultResolution ?: defaultResolution,
                        defaultDpi = existing.defaultDpi ?: defaultDpi,
                        isSelectedForAdb = selectedDevices.contains(baseSerial) // Восстанавливаем состояние чекбокса
                    )
                } else {
                    existing.copy(
                        usbDevice = device,
                        ipAddress = ipAddress ?: existing.ipAddress,
                        currentResolution = currentResolution ?: existing.currentResolution,
                        currentDpi = currentDpi ?: existing.currentDpi,
                        defaultResolution = defaultResolution ?: existing.defaultResolution,
                        defaultDpi = defaultDpi ?: existing.defaultDpi,
                        isSelectedForAdb = selectedDevices.contains(baseSerial) // Восстанавливаем состояние чекбокса
                    )
                }
                combinedMap[baseSerial] = updated
            } else {
                // Новое устройство
                val combined = CombinedDeviceInfo(
                    baseSerialNumber = baseSerial,
                    displayName = device.displayName,
                    androidVersion = device.androidVersion,
                    apiLevel = device.apiLevel,
                    usbDevice = if (!DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) device else null,
                    wifiDevice = if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) device else null,
                    ipAddress = ipAddress,
                    currentResolution = currentResolution,
                    currentDpi = currentDpi,
                    defaultResolution = defaultResolution,
                    defaultDpi = defaultDpi,
                    isSelectedForAdb = selectedDevices.contains(baseSerial) // Восстанавливаем состояние чекбокса
                )
                combinedMap[baseSerial] = combined
            }
        }
        
        return combinedMap.values.toList()
    }
    
    /**
     * Извлекает базовый серийный номер устройства (без IP для Wi-Fi подключений)
     */
    private fun extractBaseSerialNumber(device: DeviceInfo): String {
        // Для Wi-Fi устройств displaySerialNumber содержит реальный серийник
        return if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) {
            device.displaySerialNumber ?: device.logicalSerialNumber
        } else {
            device.logicalSerialNumber
        }
    }
    
    /**
     * Обновляет состояние выбора устройства для ADB команд
     */
    fun updateDeviceSelection(baseSerialNumber: String, isSelected: Boolean) {
        if (isSelected) {
            selectedDevices.add(baseSerialNumber)
        } else {
            selectedDevices.remove(baseSerialNumber)
        }
    }
    
    /**
     * Освобождает все ресурсы и отменяет все корутины
     */
    fun dispose() {
        stopDevicePolling()
        pollingScope.cancel()
    }
}