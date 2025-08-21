package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.ui.models.CombinedDeviceInfo
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ScrcpyService
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import kotlinx.coroutines.*
import javax.swing.SwingUtilities


class DevicePollingService(private val project: Project) {
    private var pollingJob: Job? = null
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCombinedUpdateCallback: ((List<CombinedDeviceInfo>) -> Unit)? = null
    
    // Сохраняем состояние чекбоксов между обновлениями
    private val selectedDevices = mutableSetOf<String>()
    
    // Флаг блокировки обновлений во время рестарта ADB сервера
    @Volatile
    private var isAdbRestarting = false
    
    // Хранилище устройств с активным scrcpy по Wi-Fi
    private val devicesWithActiveWifiScrcpy = mutableSetOf<String>()

    fun stopDevicePolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * Устанавливает флаг блокировки обновлений на время рестарта ADB
     */
    fun setAdbRestarting(restarting: Boolean) {
        isAdbRestarting = restarting
        if (restarting) {
            PluginLogger.info("DevicePollingService: ADB restart started, blocking updates")
        } else {
            PluginLogger.info("DevicePollingService: ADB restart completed, resuming updates")
        }
    }
    
    /**
     * Запускает поллинг устройств с объединением USB и Wi-Fi подключений
     */
    fun startCombinedDevicePolling(onDevicesUpdated: (List<CombinedDeviceInfo>) -> Unit) {
        lastCombinedUpdateCallback = onDevicesUpdated
        stopDevicePolling()
        
        pollingJob = pollingScope.launch {
            try {
                // 1. Мгновенно получить первый список устройств (если не рестартит ADB)
                if (!isAdbRestarting) {
                    val firstDevicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                    val firstDevices = firstDevicesRaw.map { DeviceInfo(it, null) }
                    val firstCombined = combineDevices(firstDevices)
                    SwingUtilities.invokeLater { onDevicesUpdated(firstCombined) }
                }
                
                // 2. Далее — Flow с периодическим обновлением
                AdbServiceAsync.deviceFlow(project, PluginConfig.UI.DEVICE_POLLING_INTERVAL_MS.toLong())
                    .collect { deviceInfos ->
                        if (!isAdbRestarting) {
                            val combined = combineDevices(deviceInfos)
                            SwingUtilities.invokeLater { onDevicesUpdated(combined) }
                        }
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
        // Не обновляем во время рестарта ADB
        if (isAdbRestarting) {
            PluginLogger.info("DevicePollingService: Skipping force update - ADB is restarting")
            return
        }
        
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
        // Проверяем активные scrcpy процессы и поддерживаем TCP/IP
        maintainTcpIpForActiveScrcpy(devices)
        
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
            
            // Получаем IP адрес устройства
            val ipAddress = if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) {
                // Для Wi-Fi устройств извлекаем IP из серийного номера (формат: IP:PORT)
                device.logicalSerialNumber.substringBefore(":")
            } else {
                // Для USB устройств пытаемся получить IP адрес
                device.ipAddress ?: device.device?.let {
                    AdbService.getDeviceIpAddress(it).getOrNull()
                }
            }
            
            if (existing != null) {
                // Устройство уже есть, добавляем второе подключение
                val updated = if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) {
                    existing.copy(
                        displayName = device.displayName, // Обновляем displayName для Wi-Fi устройств
                        wifiDevice = device,
                        ipAddress = ipAddress ?: existing.ipAddress,
                        // Всегда обновляем текущие параметры экрана новыми значениями (они могли измениться)
                        currentResolution = currentResolution ?: existing.currentResolution,
                        currentDpi = currentDpi ?: existing.currentDpi,
                        // Дефолтные значения обновляем только если они не были установлены
                        defaultResolution = existing.defaultResolution ?: defaultResolution,
                        defaultDpi = existing.defaultDpi ?: defaultDpi,
                        isSelectedForAdb = selectedDevices.contains(baseSerial) // Восстанавливаем состояние чекбокса
                    )
                } else {
                    existing.copy(
                        displayName = device.displayName, // Обновляем displayName для USB устройств  
                        usbDevice = device,
                        ipAddress = ipAddress ?: existing.ipAddress,
                        // Всегда обновляем текущие параметры экрана новыми значениями (они могли измениться)
                        currentResolution = currentResolution ?: existing.currentResolution,
                        currentDpi = currentDpi ?: existing.currentDpi,
                        // Дефолтные значения обновляем только если они не были установлены
                        defaultResolution = existing.defaultResolution ?: defaultResolution,
                        defaultDpi = existing.defaultDpi ?: defaultDpi,
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
        
        val combinedList = combinedMap.values.toList()
        
        // Сохраняем IP адреса USB устройств в историю Wi-Fi подключений
        saveUsbDeviceIpsToHistory(combinedList)
        
        // Автоматическое включение TCP/IP для новых USB устройств
        enableTcpIpForNewUsbDevices(combinedList)
        
        return combinedList
    }
    
    /**
     * Сохраняет IP адреса USB-подключенных устройств в историю Wi-Fi подключений
     */
    private fun saveUsbDeviceIpsToHistory(devices: List<CombinedDeviceInfo>) {
        for (device in devices) {
            // Проверяем только USB устройства с IP адресом
            if (device.usbDevice != null && device.ipAddress != null) {
                val usbDevice = device.usbDevice
                
                // Создаем запись для истории
                val historyEntry = WifiDeviceHistoryService.WifiDeviceHistoryEntry(
                    ipAddress = device.ipAddress,
                    port = 5555, // Стандартный порт ADB для Wi-Fi
                    displayName = device.displayName,
                    androidVersion = usbDevice.androidVersion,
                    apiLevel = usbDevice.apiLevel,
                    logicalSerialNumber = "${device.ipAddress}:5555", // Логический серийник для Wi-Fi
                    realSerialNumber = device.baseSerialNumber // Реальный серийник устройства
                )
                
                // Проверяем, есть ли уже такая запись в истории
                val currentHistory = WifiDeviceHistoryService.getHistory()
                val existingEntry = currentHistory.find { 
                    it.ipAddress == device.ipAddress && 
                    (it.realSerialNumber == device.baseSerialNumber || 
                     it.logicalSerialNumber == device.baseSerialNumber)
                }
                
                if (existingEntry == null) {
                    // Добавляем новую запись
                    WifiDeviceHistoryService.addOrUpdateDevice(historyEntry)
                    PluginLogger.debug("Added USB device IP to Wi-Fi history: ${device.ipAddress} for ${device.displayName}")
                } else if (existingEntry.displayName != device.displayName || 
                          existingEntry.androidVersion != usbDevice.androidVersion) {
                    // Обновляем существующую запись если изменились данные устройства
                    WifiDeviceHistoryService.addOrUpdateDevice(historyEntry)
                    PluginLogger.debug("Updated USB device IP in Wi-Fi history: ${device.ipAddress} for ${device.displayName}")
                }
            }
        }
    }
    
    /**
     * Автоматически включает TCP/IP режим для новых USB устройств
     */
    private val devicesWithTcpIpEnabled = mutableSetOf<String>()
    
    private fun enableTcpIpForNewUsbDevices(devices: List<CombinedDeviceInfo>) {
        for (device in devices) {
            // Проверяем только USB устройства
            if (device.usbDevice != null && device.usbDevice.device != null) {
                val deviceSerial = device.baseSerialNumber
                
                // Проверяем, не включали ли мы уже TCP/IP для этого устройства
                if (!devicesWithTcpIpEnabled.contains(deviceSerial)) {
                    val usbDevice = device.usbDevice.device
                    
                    // Проверяем текущий статус TCP/IP
                    Thread {
                        try {
                            // Проверяем, включён ли уже TCP/IP
                            val checkResult = AdbService.isTcpIpEnabled(usbDevice)
                            if (checkResult.isSuccess() && checkResult.getOrNull() == false) {
                                PluginLogger.info("Auto-enabling TCP/IP for newly connected USB device: ${device.displayName}")
                                
                                // Включаем TCP/IP
                                val tcpResult = AdbService.enableTcpIp(usbDevice, 5555)
                                if (tcpResult.isSuccess()) {
                                    PluginLogger.info("TCP/IP mode auto-enabled for ${device.displayName}")
                                    devicesWithTcpIpEnabled.add(deviceSerial)
                                    
                                    // Показываем уведомление пользователю
                                    SwingUtilities.invokeLater {
                                        NotificationUtils.showInfo(
                                            project,
                                            "Wi-Fi debugging enabled for ${device.displayName}. You can now disconnect USB and use Wi-Fi."
                                        )
                                    }
                                } else {
                                    PluginLogger.debug("Failed to auto-enable TCP/IP for ${device.displayName}")
                                }
                            } else if (checkResult.isSuccess() && checkResult.getOrNull() == true) {
                                // TCP/IP уже включён, добавляем в список чтобы не проверять снова
                                devicesWithTcpIpEnabled.add(deviceSerial)
                                PluginLogger.debug("TCP/IP already enabled for ${device.displayName}")
                            }
                        } catch (e: Exception) {
                            PluginLogger.debug("Error auto-enabling TCP/IP: ${e.message}")
                        }
                    }.start()
                }
            } else if (device.usbDevice == null && devicesWithTcpIpEnabled.contains(device.baseSerialNumber)) {
                // Устройство отключено от USB, убираем из списка
                devicesWithTcpIpEnabled.remove(device.baseSerialNumber)
                PluginLogger.debug("Device ${device.displayName} disconnected from USB, removing from TCP/IP enabled list")
            }
        }
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
     * Поддерживает TCP/IP режим для устройств с активным scrcpy по Wi-Fi
     */
    private fun maintainTcpIpForActiveScrcpy(devices: List<DeviceInfo>) {
        val scrcpyService = ScrcpyService
        
        for (device in devices) {
            val serialNumber = device.logicalSerialNumber
            
            // Проверяем Wi-Fi устройства с активным scrcpy
            if (DeviceConnectionUtils.isWifiConnection(serialNumber)) {
                if (scrcpyService.isScrcpyActiveForDevice(serialNumber)) {
                    // Запоминаем базовый serial для этого устройства
                    val baseSerial = extractBaseSerialNumber(device)
                    devicesWithActiveWifiScrcpy.add(baseSerial)
                    PluginLogger.debug("Tracking active Wi-Fi scrcpy for device: $baseSerial")
                } else {
                    // Если scrcpy больше не активен, удаляем из списка
                    val baseSerial = extractBaseSerialNumber(device)
                    devicesWithActiveWifiScrcpy.remove(baseSerial)
                }
            }
            // Проверяем USB устройства - не отключаем TCP/IP если есть активный scrcpy
            else if (device.device != null) {
                val baseSerial = extractBaseSerialNumber(device)
                
                // Если для этого устройства есть активный scrcpy по Wi-Fi
                if (devicesWithActiveWifiScrcpy.contains(baseSerial)) {
                    // Проверяем и восстанавливаем TCP/IP если он был отключен
                    Thread {
                        try {
                            val tcpIpEnabled = AdbService.isTcpIpEnabled(device.device)
                            if (tcpIpEnabled.isSuccess() && tcpIpEnabled.getOrNull() == false) {
                                PluginLogger.info(LogCategory.ADB_CONNECTION, 
                                    "[SCRCPY] Re-enabling TCP/IP for USB device %s to maintain Wi-Fi scrcpy", 
                                    device.displayName)
                                
                                // Включаем TCP/IP обратно
                                val result = AdbService.enableTcpIp(device.device, 5555)
                                if (result.isSuccess()) {
                                    PluginLogger.info(LogCategory.ADB_CONNECTION,
                                        "[SCRCPY] TCP/IP re-enabled for %s to maintain active scrcpy session",
                                        device.displayName)
                                }
                            }
                        } catch (e: Exception) {
                            PluginLogger.debug("Error maintaining TCP/IP for scrcpy: ${e.message}")
                        }
                    }.start()
                }
            }
        }
        
        // Очищаем список от устройств без активных Wi-Fi подключений
        val currentWifiDevices = devices
            .filter { DeviceConnectionUtils.isWifiConnection(it.logicalSerialNumber) }
            .map { extractBaseSerialNumber(it) }
            .toSet()
        
        devicesWithActiveWifiScrcpy.retainAll { baseSerial ->
            // Оставляем только те, для которых есть Wi-Fi подключение и активный scrcpy
            if (currentWifiDevices.contains(baseSerial)) {
                val wifiDevice = devices.find { 
                    DeviceConnectionUtils.isWifiConnection(it.logicalSerialNumber) && 
                    extractBaseSerialNumber(it) == baseSerial 
                }
                wifiDevice?.let { 
                    scrcpyService.isScrcpyActiveForDevice(it.logicalSerialNumber) 
                } ?: false
            } else {
                false
            }
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