package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
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
        
        PluginLogger.warn(LogCategory.DEVICE_POLLING, "Starting combined device polling")
        
        pollingJob = pollingScope.launch {
            try {
                // 1. Пытаемся мгновенно получить первый список устройств
                launch {
                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "Starting initial device scan")
                    // Запускаем в отдельной корутине чтобы не блокировать основной flow
                    for (attempt in 1..5) {
                        if (!isAdbRestarting) {
                            try {
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, "Initial scan attempt %d - getting devices", attempt)
                                val firstDevicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull()
                                if (firstDevicesRaw != null && firstDevicesRaw.isNotEmpty()) {
                                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "Found %d raw devices on attempt %d", firstDevicesRaw.size, attempt)
                                    val firstDevices = firstDevicesRaw.map { DeviceInfo(it, null) }
                                    val firstCombined = combineDevices(firstDevices)
                                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "Combined into %d devices, updating UI", firstCombined.size)
                                    SwingUtilities.invokeLater { 
                                        PluginLogger.warn(LogCategory.DEVICE_POLLING, "UI update callback called with %d devices", firstCombined.size)
                                        onDevicesUpdated(firstCombined) 
                                    }
                                    break
                                } else {
                                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "No devices found on attempt %d", attempt)
                                }
                            } catch (e: Exception) {
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, "Initial device scan attempt %d failed: %s", attempt, e.message)
                            }
                        } else {
                            PluginLogger.warn(LogCategory.DEVICE_POLLING, "Skipping initial scan - ADB is restarting")
                        }
                        if (attempt < 5) {
                            delay(500) // Ждём полсекунды между попытками
                        }
                    }
                }
                
                // 2. Далее — Flow с периодическим обновлением  
                PluginLogger.warn(LogCategory.DEVICE_POLLING, "Starting periodic device flow")
                AdbServiceAsync.deviceFlow(project, PluginConfig.UI.DEVICE_POLLING_INTERVAL_MS.toLong())
                    .collect { deviceInfos ->
                        if (!isAdbRestarting) {
                            PluginLogger.warn(LogCategory.DEVICE_POLLING, "Periodic flow received %d devices", deviceInfos.size)
                            val combined = combineDevices(deviceInfos)
                            SwingUtilities.invokeLater { 
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, "Periodic UI update with %d combined devices", combined.size)
                                onDevicesUpdated(combined) 
                            }
                        } else {
                            PluginLogger.warn(LogCategory.DEVICE_POLLING, "Skipping periodic update - ADB is restarting")
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
    
    // Кэш для параметров устройств, чтобы не запрашивать их каждый раз
    private val deviceParamsCache = mutableMapOf<String, DeviceParamsCache>()
    
    // Флаг для отслеживания первого запуска
    private var isFirstRun = true
    
    private data class DeviceParamsCache(
        val timestamp: Long,
        val currentResolution: Pair<Int, Int>?,
        val currentDpi: Int?,
        val defaultResolution: Pair<Int, Int>?,
        val defaultDpi: Int?
    )
    
    /**
     * Объединяет USB и Wi-Fi подключения одного устройства
     */
    private suspend fun combineDevices(devices: List<DeviceInfo>): List<CombinedDeviceInfo> {
        val startTime = System.currentTimeMillis()
        
        // Проверяем активные scrcpy процессы и поддерживаем TCP/IP
        maintainTcpIpForActiveScrcpy(devices)
        val maintainTime = System.currentTimeMillis() - startTime
        PluginLogger.warn(LogCategory.DEVICE_POLLING, "maintainTcpIpForActiveScrcpy took %d ms", maintainTime)
        
        val combinedMap = mutableMapOf<String, CombinedDeviceInfo>()
        
        // Получаем параметры экрана только если их нет в кэше или они устарели
        val paramsStartTime = System.currentTimeMillis()
        val deviceParams = if (isFirstRun) {
            // При первом запуске используем данные из истории, но не запрашиваем новые
            PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                "First run - using history data only to speed up initial load")
            isFirstRun = false
            devices.map { device ->
                // Пытаемся получить параметры из истории
                val baseSerial = extractBaseSerialNumber(device)
                val historyEntry = WifiDeviceHistoryService.getDeviceBySerialNumber(baseSerial)
                    ?: device.ipAddress?.let { WifiDeviceHistoryService.getDeviceByIpAddress(it) }
                
                if (historyEntry != null) {
                    PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                        "Found history for %s with defaults: %s x %s, DPI: %s",
                        device.displayName,
                        historyEntry.defaultResolutionWidth?.toString() ?: "N/A",
                        historyEntry.defaultResolutionHeight?.toString() ?: "N/A",
                        historyEntry.defaultDpi?.toString() ?: "N/A")
                    
                    val defaultResolution = if (historyEntry.defaultResolutionWidth != null && 
                                               historyEntry.defaultResolutionHeight != null) {
                        Pair(historyEntry.defaultResolutionWidth, historyEntry.defaultResolutionHeight)
                    } else null
                    
                    // На первом запуске текущие параметры оставляем null (будет показано "Loading" в UI)
                    Triple(device, 
                        Pair(null as Pair<Int, Int>?, null as Int?), // Текущие параметры будут загружены асинхронно
                        Pair(defaultResolution, historyEntry.defaultDpi)) // Дефолтные из истории
                } else {
                    PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                        "No history found for %s", device.displayName)
                    Triple(device, 
                        Pair(null as Pair<Int, Int>?, null as Int?), 
                        Pair(null as Pair<Int, Int>?, null as Int?))
                }
            }
        } else {
            coroutineScope {
                devices.map { device ->
                    async {
                        // Пропускаем получение параметров для оффлайн устройств
                        if (device.device == null || !device.device.isOnline) {
                            PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                                "Skipping params for offline device: %s", device.displayName)
                            return@async Triple(device, Pair(null, null), Pair(null, null))
                        }
                        
                        val deviceKey = device.logicalSerialNumber
                        val cached = deviceParamsCache[deviceKey]
                        val now = System.currentTimeMillis()
                        
                        // Используем кэш если он свежий (меньше 60 секунд)
                        if (cached != null && (now - cached.timestamp) < 60000) {
                            PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                                "Using cached params for device: %s (age: %d ms)", 
                                device.displayName, now - cached.timestamp)
                            return@async Triple(device, 
                                Pair(cached.currentResolution, cached.currentDpi),
                                Pair(cached.defaultResolution, cached.defaultDpi))
                        }
                    
                    // Пытаемся получить дефолтные параметры из истории
                    val historyEntry = WifiDeviceHistoryService.getDeviceBySerialNumber(
                        extractBaseSerialNumber(device)
                    ) ?: device.ipAddress?.let { 
                        WifiDeviceHistoryService.getDeviceByIpAddress(it) 
                    }
                    
                    // Получаем все параметры параллельно для каждого устройства
                    val params = coroutineScope {
                        val resolutionDeferred = async { 
                            withTimeoutOrNull(300) { 
                                AdbService.getCurrentSize(device.device).getOrNull() 
                            }
                        }
                        val dpiDeferred = async { 
                            withTimeoutOrNull(300) { 
                                AdbService.getCurrentDpi(device.device).getOrNull() 
                            }
                        }
                        
                        // Используем дефолтные параметры из истории если они есть
                        val defaultResolutionDeferred = if (historyEntry?.defaultResolutionWidth != null && 
                                                           historyEntry.defaultResolutionHeight != null) {
                            async { 
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                                    "Using default resolution from history for %s: %dx%d", 
                                    device.displayName, 
                                    historyEntry.defaultResolutionWidth, 
                                    historyEntry.defaultResolutionHeight)
                                Pair(historyEntry.defaultResolutionWidth, historyEntry.defaultResolutionHeight) 
                            }
                        } else {
                            async { 
                                withTimeoutOrNull(300) { 
                                    AdbService.getDefaultSize(device.device).getOrNull() 
                                }
                            }
                        }
                        
                        val defaultDpiDeferred = if (historyEntry?.defaultDpi != null) {
                            async { 
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                                    "Using default DPI from history for %s: %d", 
                                    device.displayName, 
                                    historyEntry.defaultDpi)
                                historyEntry.defaultDpi 
                            }
                        } else {
                            async { 
                                withTimeoutOrNull(300) { 
                                    AdbService.getDefaultDpi(device.device).getOrNull() 
                                }
                            }
                        }
                        
                        listOf(
                            resolutionDeferred.await(),
                            dpiDeferred.await(),
                            defaultResolutionDeferred.await(),
                            defaultDpiDeferred.await()
                        )
                    }
                    
                    @Suppress("UNCHECKED_CAST")
                    val currentResolution = params[0] as? Pair<Int, Int>
                    val currentDpi = params[1] as? Int
                    @Suppress("UNCHECKED_CAST")
                    val defaultResolution = params[2] as? Pair<Int, Int>
                    val defaultDpi = params[3] as? Int
                    
                    // Сохраняем в кэш
                    deviceParamsCache[deviceKey] = DeviceParamsCache(
                        timestamp = System.currentTimeMillis(),
                        currentResolution = currentResolution,
                        currentDpi = currentDpi,
                        defaultResolution = defaultResolution,
                        defaultDpi = defaultDpi
                    )
                    
                    // Если получили новые дефолтные параметры, сохраняем их в историю
                    if (defaultResolution != null && defaultDpi != null && 
                        (historyEntry == null || 
                         historyEntry.defaultResolutionWidth == null || 
                         historyEntry.defaultDpi == null)) {
                        
                        val baseSerial = extractBaseSerialNumber(device)
                        val existingEntry = WifiDeviceHistoryService.getDeviceBySerialNumber(baseSerial)
                        
                        if (existingEntry != null) {
                            // Обновляем существующую запись с дефолтными параметрами
                            val updatedEntry = existingEntry.copy(
                                defaultResolutionWidth = defaultResolution.first,
                                defaultResolutionHeight = defaultResolution.second,
                                defaultDpi = defaultDpi
                            )
                            WifiDeviceHistoryService.addOrUpdateDevice(updatedEntry)
                            PluginLogger.warn(LogCategory.DEVICE_POLLING,
                                "Saved default params to history for %s: %dx%d, DPI: %d",
                                device.displayName, defaultResolution.first, defaultResolution.second, defaultDpi)
                        }
                    }
                    
                        Triple(device, 
                            Pair(currentResolution, currentDpi), 
                            Pair(defaultResolution, defaultDpi))
                    }
                }.awaitAll()
            }
        }
        val paramsTime = System.currentTimeMillis() - paramsStartTime
        PluginLogger.warn(LogCategory.DEVICE_POLLING, "Getting device params took %d ms for %d devices", paramsTime, devices.size)
        
        // Если это был первый запуск, запускаем асинхронное обновление текущих параметров
        if (paramsTime < 100 && devices.isNotEmpty()) { // Быстрое время означает что мы использовали только историю
            PluginLogger.warn(LogCategory.DEVICE_POLLING, "Scheduling async update of current params after 2s delay")
            pollingScope.launch {
                delay(2000) // Ждем 2 секунды чтобы UI успел отрисоваться
                updateCurrentParamsAsync(devices)
            }
        }
        
        for ((device, currentParams, defaultParams) in deviceParams) {
            val baseSerial = extractBaseSerialNumber(device)
            val existing = combinedMap[baseSerial]
            val (currentResolution, currentDpi) = currentParams
            val (defaultResolution, defaultDpi) = defaultParams
            
            // Получаем IP адрес устройства (уже есть в DeviceInfo)
            val ipAddress = if (DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber)) {
                // Для Wi-Fi устройств извлекаем IP из серийного номера (формат: IP:PORT)
                device.logicalSerialNumber.substringBefore(":")
            } else {
                // Для USB устройств используем уже полученный IP из DeviceInfo
                device.ipAddress
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
        
        // Автоматическое включение TCP/IP для новых USB устройств запускаем асинхронно
        // чтобы не блокировать обновление UI
        pollingScope.launch {
            try {
                enableTcpIpForNewUsbDevices(combinedList)
            } catch (e: Exception) {
                PluginLogger.debug("Error enabling TCP/IP for USB devices: ${e.message}")
            }
        }
        
        return combinedList
    }
    
    /**
     * Асинхронно обновляет текущие параметры устройств в фоне
     */
    private suspend fun updateCurrentParamsAsync(devices: List<DeviceInfo>) {
        PluginLogger.warn(LogCategory.DEVICE_POLLING, "Starting async update of current params for %d devices", devices.size)
        
        coroutineScope {
            // Запускаем все запросы параллельно
            devices.map { device ->
                async {
                    if (device.device != null && device.device.isOnline) {
                        try {
                            // Параллельно получаем оба параметра
                            val resolutionDeferred = async {
                                withTimeoutOrNull(500) {
                                    AdbService.getCurrentSize(device.device).getOrNull()
                                }
                            }
                            val dpiDeferred = async {
                                withTimeoutOrNull(500) {
                                    AdbService.getCurrentDpi(device.device).getOrNull()
                                }
                            }
                            
                            val currentResolution = resolutionDeferred.await()
                            val currentDpi = dpiDeferred.await()
                            
                            if (currentResolution != null || currentDpi != null) {
                                val deviceKey = device.logicalSerialNumber
                                val cached = deviceParamsCache[deviceKey]
                                
                                // Обновляем кэш с новыми текущими параметрами
                                deviceParamsCache[deviceKey] = DeviceParamsCache(
                                    timestamp = System.currentTimeMillis(),
                                    currentResolution = currentResolution ?: cached?.currentResolution,
                                    currentDpi = currentDpi ?: cached?.currentDpi,
                                    defaultResolution = cached?.defaultResolution,
                                    defaultDpi = cached?.defaultDpi
                                )
                                
                                PluginLogger.warn(LogCategory.DEVICE_POLLING, 
                                    "Updated current params for %s: %s, DPI: %s",
                                    device.displayName,
                                    currentResolution?.toString() ?: "unchanged",
                                    currentDpi?.toString() ?: "unchanged")
                            }
                        } catch (e: Exception) {
                            PluginLogger.debug("Failed to update current params for %s: %s", 
                                device.displayName, e.message)
                        }
                    }
                }
            }.awaitAll()
        }
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
                    realSerialNumber = device.baseSerialNumber, // Реальный серийник устройства
                    defaultResolutionWidth = device.defaultResolution?.first,
                    defaultResolutionHeight = device.defaultResolution?.second,
                    defaultDpi = device.defaultDpi
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
                          existingEntry.androidVersion != usbDevice.androidVersion ||
                          (existingEntry.defaultResolutionWidth == null && device.defaultResolution != null) ||
                          (existingEntry.defaultDpi == null && device.defaultDpi != null)) {
                    // Обновляем существующую запись если изменились данные устройства или появились дефолтные параметры
                    // Но сохраняем старые дефолтные параметры если они были
                    val updatedEntry = historyEntry.copy(
                        defaultResolutionWidth = existingEntry.defaultResolutionWidth ?: historyEntry.defaultResolutionWidth,
                        defaultResolutionHeight = existingEntry.defaultResolutionHeight ?: historyEntry.defaultResolutionHeight,
                        defaultDpi = existingEntry.defaultDpi ?: historyEntry.defaultDpi
                    )
                    WifiDeviceHistoryService.addOrUpdateDevice(updatedEntry)
                    PluginLogger.debug("Updated USB device IP in Wi-Fi history: ${device.ipAddress} for ${device.displayName}")
                }
            }
        }
    }
    
    /**
     * Автоматически включает TCP/IP режим для новых USB устройств
     */
    private val devicesWithTcpIpEnabled = mutableSetOf<String>()
    
    private suspend fun enableTcpIpForNewUsbDevices(devices: List<CombinedDeviceInfo>) {
        for (device in devices) {
            // Проверяем только USB устройства
            if (device.usbDevice != null && device.usbDevice.device != null) {
                val deviceSerial = device.baseSerialNumber
                
                // Проверяем, не включали ли мы уже TCP/IP для этого устройства
                if (!devicesWithTcpIpEnabled.contains(deviceSerial)) {
                    val usbDevice = device.usbDevice.device
                    
                    // Проверяем текущий статус TCP/IP асинхронно
                    try {
                        // Проверяем, включён ли уже TCP/IP
                        val checkResult = withTimeoutOrNull(1000) {
                            AdbService.isTcpIpEnabled(usbDevice)
                        } ?: Result.Error(Exception("Timeout checking TCP/IP status"))
                        
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
                    // Проверяем и восстанавливаем TCP/IP асинхронно
                    pollingScope.launch {
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
                    }
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