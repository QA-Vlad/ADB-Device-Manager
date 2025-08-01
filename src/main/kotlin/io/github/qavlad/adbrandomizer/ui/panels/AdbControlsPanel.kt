package io.github.qavlad.adbrandomizer.ui.panels

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.dialogs.PresetsDialog
import io.github.qavlad.adbrandomizer.ui.dialogs.ScrcpyCompatibilityDialog
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import java.util.Locale
import javax.swing.*
import javax.swing.Timer

class AdbControlsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var lastUsedPreset: DevicePreset? = null
    private var currentPresetIndex: Int = -1
    private var currentHoverState = HoverState.noHover()
    
    private val devicePollingService = DevicePollingService(project)
    private lateinit var deviceListPanel: DeviceListPanel
    private lateinit var buttonPanel: ButtonPanel
    private lateinit var compactActionPanel: CompactActionPanel

    init {
        setupUI()
        startDevicePolling()
    }

    private fun setupUI() {
        buttonPanel = ButtonPanel(
            onRandomAction = { setSize, setDpi -> executeRandomAction(setSize, setDpi) },
            onNextPreset = { navigateToNextPreset() },
            onPreviousPreset = { navigateToPreviousPreset() },
            onResetAction = { resetSize, resetDpi -> executeResetAction(resetSize, resetDpi) },
            onOpenPresetSettings = { openPresetsDialog() }
        )
        
        compactActionPanel = CompactActionPanel(
            onConnectDevice = { promptForManualConnection() },
            onKillAdbServer = { executeKillAdbServer() }
        )
        
        deviceListPanel = DeviceListPanel(
            getHoverState = { currentHoverState },
            setHoverState = { newState -> currentHoverState = newState },
            getAllDevices = { getAllDevicesFromModel() },
            onMirrorClick = { deviceInfo -> startScreenMirroring(deviceInfo) },
            onWifiClick = { device -> connectDeviceViaWifi(device) },
            compactActionPanel = compactActionPanel,
            onForceUpdate = { devicePollingService.forceUpdate() }
        )
        
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, buttonPanel, deviceListPanel).apply {
            border = null
            resizeWeight = 0.5
            SwingUtilities.invokeLater { setDividerLocation(0.5) }
        }
        
        add(splitPane, BorderLayout.CENTER)
    }

    private fun startDevicePolling() {
        devicePollingService.startDevicePolling { devices ->
            deviceListPanel.updateDeviceList(devices)
        }
    }

    private fun getAllDevicesFromModel(): List<DeviceInfo> {
        return deviceListPanel.getDeviceListModel().let { model ->
            (0 until model.size())
                .mapNotNull { model.getElementAt(it) as? DeviceListItem.Device }
                .map { it.info }
        }
    }

    // ==================== VALIDATION AND HELPERS ====================

    private fun validateDevicesAvailable(): Boolean {
        val devicesResult = AdbService.getConnectedDevices()
        val devices = devicesResult.getOrNull() ?: run {
            devicesResult.onError { exception, message ->
                PluginLogger.error("Failed to get connected devices", exception, message ?: "")
            }
            emptyList()
        }
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return false
        }
        return true
    }

    private fun validatePresetsAvailable(): Boolean {
        val presets = getVisiblePresets()
        if (presets.isEmpty()) {
            NotificationUtils.showInfo(project, "No presets found.")
            return false
        }
        return true
    }

    // ==================== PRESET ACTIONS ====================

    private fun executeRandomAction(setSize: Boolean, setDpi: Boolean) {
        println("ADB_DEBUG: executeRandomAction called - setSize: $setSize, setDpi: $setDpi")
        if (!validateDevicesAvailable()) return

        val randomPreset = selectRandomPreset(setSize, setDpi) ?: return
        
        // Позиция будет вычислена динамически после обновления счетчиков
        applyPresetToDevices(randomPreset, setSize, setDpi)
    }

    private fun selectRandomPreset(setSize: Boolean, setDpi: Boolean): DevicePreset? {
        var availablePresets = filterSuitablePresets(setSize, setDpi)

        if (availablePresets.size > 1 && lastUsedPreset != null) {
            availablePresets = availablePresets.filter { it != lastUsedPreset }
        }

        if (availablePresets.isEmpty()) {
            val allSuitablePresets = filterSuitablePresets(setSize, setDpi)
            if (allSuitablePresets.isNotEmpty()) {
                availablePresets = allSuitablePresets
            } else {
                NotificationUtils.showInfo(project, "No suitable presets found.")
                return null
            }
        }

        val randomPreset = availablePresets.random()
        updateCurrentPresetIndex(randomPreset)
        return randomPreset
    }

    private fun filterSuitablePresets(setSize: Boolean, setDpi: Boolean): List<DevicePreset> {
        return getVisiblePresets().filter { preset ->
            (!setSize || preset.size.isNotBlank()) && (!setDpi || preset.dpi.isNotBlank())
        }
    }

    private fun updateCurrentPresetIndex(preset: DevicePreset) {
        val allPresets = getVisiblePresets()
        currentPresetIndex = allPresets.indexOf(preset)
    }
    
    private fun getVisiblePresets(): List<DevicePreset> {
        // Получаем пресеты с учетом текущего режима отображения и сортировки
        val presets = PresetListService.getSortedPresets()
        println("ADB_DEBUG: AdbControlsPanel.getVisiblePresets() - returned ${presets.size} presets")
        if (presets.isEmpty()) {
            println("ADB_DEBUG: AdbControlsPanel.getVisiblePresets() - EMPTY LIST! Show All Mode: ${PresetStorageService.getShowAllPresetsMode()}")
        }
        return presets
    }

    private fun navigateToNextPreset() {
        if (!validatePresetsAvailable()) return

        val presets = getVisiblePresets()
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun navigateToPreviousPreset() {
        if (!validatePresetsAvailable()) return

        val presets = getVisiblePresets()
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        if (!validateDevicesAvailable()) return

        val presets = getVisiblePresets()
        if (index < 0 || index >= presets.size) {
            NotificationUtils.showInfo(project, "Invalid preset index.")
            return
        }

        val preset = presets[index]
        // Позиция будет вычислена динамически после обновления счетчиков
        applyPresetToDevices(preset, setSize = true, setDpi = true)
    }

    private fun applyPresetToDevices(preset: DevicePreset, setSize: Boolean, setDpi: Boolean) {
        lastUsedPreset = preset
        PresetApplicationService.applyPreset(project, preset, setSize, setDpi)
    }

    // ==================== RESET ACTIONS ====================

    private fun executeResetAction(resetSize: Boolean, resetDpi: Boolean) {
        if (!validateDevicesAvailable()) {
            return
        }

        val actionDescription = getResetActionDescription(resetSize, resetDpi)

        object : Task.Backgroundable(project, actionDescription) {
            override fun run(indicator: ProgressIndicator) {
                val devicesResult = AdbService.getConnectedDevices()
                val devices = devicesResult.getOrNull() ?: run {
                    devicesResult.onError { exception, message ->
                        PluginLogger.error("Failed to get devices for reset", exception, message ?: "")
                    }
                    emptyList()
                }
                resetAllDevices(devices, resetSize, resetDpi, indicator)
                
                DeviceStateService.handleReset(resetSize, resetDpi)
                
                // Немедленно уведомляем об обновлении до UI обновлений
                PresetsDialogUpdateNotifier.notifyUpdate()
                
                ApplicationManager.getApplication().invokeLater {
                    showResetResult(devices.size, resetSize, resetDpi)
                }
            }
        }.queue()
    }

    private fun getResetActionDescription(resetSize: Boolean, resetDpi: Boolean): String {
        return when {
            resetSize && resetDpi -> "Resetting screen and DPI"
            resetSize -> "Resetting screen size"
            resetDpi -> "Resetting DPI"
            else -> "No action"
        }
    }

    private fun resetAllDevices(devices: List<IDevice>, resetSize: Boolean, resetDpi: Boolean, indicator: ProgressIndicator) {
        devices.forEach { device ->
            indicator.text = "Resetting ${device.name}..."
            
            // Получаем текущий размер перед сбросом (только если нужно сбрасывать размер)
            val currentSizeBefore = if (resetSize) {
                val size = AdbService.getCurrentSize(device).getOrNull()
                PluginLogger.debug(LogCategory.UI_EVENTS, "Current size before reset for device %s: %s", 
                    device.name, size?.let { "${it.first}x${it.second}" } ?: "null")
                size
            } else null
            
            // Выполняем сброс
            if (resetSize) {
                PluginLogger.debug(LogCategory.UI_EVENTS, "Executing size reset for device %s", device.name)
                AdbService.resetSize(device)
            }
            if (resetDpi) {
                PluginLogger.debug(LogCategory.UI_EVENTS, "Executing DPI reset for device %s", device.name)
                AdbService.resetDpi(device)
            }
            
            // Проверяем, изменился ли размер и нужно ли перезапустить scrcpy
            if (resetSize && currentSizeBefore != null) {
                // Даём время на применение сброса
                Thread.sleep(1000) // Увеличим задержку для надёжности
                
                // Получаем новый размер после сброса
                val currentSizeAfter = AdbService.getCurrentSize(device).getOrNull()
                PluginLogger.debug(LogCategory.UI_EVENTS, "Current size after reset for device %s: %s", 
                    device.name, currentSizeAfter?.let { "${it.first}x${it.second}" } ?: "null")
                
                // Проверяем активность scrcpy
                // Для Wi-Fi устройств нужно использовать логический серийный номер
                val allDevices = getAllDevicesFromModel()
                val deviceInfo = allDevices.find { it.device?.serialNumber == device.serialNumber }
                val serialNumber = deviceInfo?.logicalSerialNumber ?: device.serialNumber
                
                PluginLogger.debug(LogCategory.UI_EVENTS, "Checking scrcpy for device: physical serial=%s, logical serial=%s", 
                    device.serialNumber, serialNumber)
                
                val isScrcpyActive = ScrcpyService.isScrcpyActiveForDevice(serialNumber)
                PluginLogger.debug(LogCategory.UI_EVENTS, "Scrcpy active for device %s: %s", serialNumber, isScrcpyActive)
                
                // Проверяем, нужно ли перезапустить scrcpy
                // Перезапускаем если:
                // 1. Размер изменился после сброса ИЛИ
                // 2. Размер был кастомным (не дефолтным) перед сбросом
                val sizeChanged = currentSizeAfter != null && currentSizeBefore != currentSizeAfter
                
                // Получаем дефолтный размер для сравнения
                val defaultSizeResult = AdbService.getDefaultSize(device)
                val defaultSize = defaultSizeResult.getOrNull()
                val wasCustomSize = defaultSize != null && currentSizeBefore != defaultSize
                
                PluginLogger.debug(LogCategory.UI_EVENTS, 
                    "Reset analysis for device %s: before=%sx%s, after=%sx%s, default=%sx%s, changed=%s, wasCustom=%s", 
                    serialNumber,
                    currentSizeBefore.first, currentSizeBefore.second,
                    currentSizeAfter?.first ?: 0, currentSizeAfter?.second ?: 0,
                    defaultSize?.first ?: 0, defaultSize?.second ?: 0,
                    sizeChanged, wasCustomSize
                )
                
                if (isScrcpyActive && (sizeChanged || wasCustomSize)) {
                    PluginLogger.debug(LogCategory.UI_EVENTS, 
                        "Restarting scrcpy for device %s (sizeChanged=%s, wasCustomSize=%s)", 
                        serialNumber, sizeChanged, wasCustomSize
                    )
                    
                    // Перезапускаем scrcpy в отдельном потоке
                    Thread {
                        Thread.sleep(500) // Даём время на стабилизацию
                        val restartResult = ScrcpyService.restartScrcpyForDevice(serialNumber, project)
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Scrcpy restart result for device %s: %s", 
                            serialNumber, restartResult)
                    }.start()
                } else {
                    PluginLogger.debug(LogCategory.UI_EVENTS, 
                        "Scrcpy restart not needed for device %s (isActive=%s, sizeChanged=%s, wasCustomSize=%s)", 
                        serialNumber, isScrcpyActive, sizeChanged, wasCustomSize
                    )
                }
            }
        }
    }

    private fun showResetResult(deviceCount: Int, resetSize: Boolean, resetDpi: Boolean) {
        val resetItems = mutableListOf<String>()
        if (resetSize) resetItems.add("screen size")
        if (resetDpi) resetItems.add("DPI")

        val resetDescription = resetItems.joinToString(" and ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        NotificationUtils.showSuccess(project, "$resetDescription reset for $deviceCount device(s).")
    }

    // ==================== CONNECTION ACTIONS ====================

    private fun executeKillAdbServer() {
        object : Task.Backgroundable(project, "Killing ADB Server") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Stopping ADB server..."
                
                val success = AdbServerService.killAdbServer()
                
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        NotificationUtils.showSuccess(project, "ADB server killed successfully")
                        // Форсируем обновление списка устройств через небольшую задержку
                        Timer(500) {
                            devicePollingService.forceUpdate()
                        }.apply {
                            isRepeats = false
                            start()
                        }
                    } else {
                        NotificationUtils.showError(project, "Failed to kill ADB server")
                    }
                }
            }
        }.queue()
    }

    private fun promptForManualConnection() {
        val input = Messages.showInputDialog(
            project,
            "Enter device IP address and port (example: 192.168.1.100:5555):",
            "Connect Device via Wi-Fi",
            Messages.getQuestionIcon(),
            "192.168.1.100:5555",
            null
        ) ?: return

        val connectionData = ValidationUtils.parseConnectionString(input)
        if (connectionData == null) {
            Messages.showErrorDialog(project, "Please enter in format: IP:PORT", "Invalid Format")
            return
        }

        val (ip, port) = connectionData
        if (!ValidationUtils.isValidAdbPort(port)) {
            Messages.showErrorDialog(project, "Port must be between 1024 and 65535", "Invalid Port")
            return
        }

        executeManualWifiConnection(ip, port)
    }

    private fun executeManualWifiConnection(ipAddress: String, port: Int) {
        object : Task.Backgroundable(project, "Connecting to $ipAddress:$port") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to $ipAddress:$port..."

                try {
                    val connectResult = AdbService.connectWifi(project, ipAddress, port)
                    val success = connectResult.getOrNull() ?: run {
                        connectResult.onError { exception, _ ->
                            PluginLogger.wifiConnectionFailed(ipAddress, port, exception)
                        }
                        false
                    }
                    handleConnectionResult(success, ipAddress, port)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                    }
                }
            }
        }.queue()
    }

    private fun handleConnectionResult(success: Boolean, ipAddress: String, port: Int) {
        if (success) {
            Thread.sleep(PluginConfig.Network.CONNECTION_VERIFY_DELAY_MS)
            val devicesResult = AdbService.getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: run {
                devicesResult.onError { exception, message ->
                    PluginLogger.error("Failed to verify connection", exception, message ?: "")
                }
                emptyList()
            }
            val connectedDevice = devices.find { it.serialNumber.startsWith(ipAddress) }

            if (connectedDevice != null) {
                // Сохраняем в историю Wi-Fi устройств
                val deviceInfo = DeviceInfo(connectedDevice, ipAddress)
                WifiDeviceHistoryService.addOrUpdateDevice(
                    WifiDeviceHistoryService.WifiDeviceHistoryEntry(
                        ipAddress = ipAddress,
                        port = port,
                        displayName = deviceInfo.displayName,
                        androidVersion = deviceInfo.androidVersion,
                        apiLevel = deviceInfo.apiLevel,
                        logicalSerialNumber = deviceInfo.logicalSerialNumber,
                        realSerialNumber = deviceInfo.displaySerialNumber  // Сохраняем настоящий серийник
                    )
                )
            }

            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showSuccess(project, "Successfully connected to device at $ipAddress:$port")
                // Форсируем обновление списка устройств
                devicePollingService.forceUpdate()
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showError(project, "Failed to connect to $ipAddress:$port. Make sure device is in TCP/IP mode.")
            }
        }
    }

    private fun connectDeviceViaWifi(device: IDevice) {
        object : Task.Backgroundable(project, "Connecting to Device via Wi-Fi") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Getting IP address for ${device.name}..."

                val ipResult = AdbService.getDeviceIpAddress(device)
                val ipAddress = ipResult.getOrNull() ?: run {
                    ipResult.onError { exception, message ->
                        PluginLogger.error("Failed to get IP address for device ${device.name}", exception, message ?: "")
                    }
                    null
                }
                if (ipAddress.isNullOrBlank()) {
                    NotificationUtils.showError(project, "Cannot find IP address for device ${device.name}. Make sure it's connected to Wi-Fi.")
                    return
                }

                indicator.text = "Enabling TCP/IP mode on ${device.name}..."
                try {
                    // Получаем IP до включения TCP/IP, чтобы убедиться что устройство подключено к Wi-Fi
                    val ipBeforeResult = AdbService.getDeviceIpAddress(device)
                    val ipBeforeEnable = ipBeforeResult.getOrNull() ?: run {
                        ipBeforeResult.onError { exception, message ->
                            PluginLogger.error("Failed to verify device IP before TCP/IP enable", exception, message ?: "")
                        }
                        null
                    }
                    if (ipBeforeEnable.isNullOrBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            NotificationUtils.showError(project, "Cannot get device IP. Make sure Wi-Fi is enabled and connected.")
                        }
                        return
                    }
                    
                    // Сначала включаем TCP/IP режим
                    val tcpipResult = AdbService.enableTcpIp(device)
                    tcpipResult.onError { exception, message ->
                        PluginLogger.error("Failed to enable TCP/IP mode", exception, message ?: "")
                    }
                    PluginLogger.info("TCP/IP mode enabled on port 5555")
                    
                    // Даем устройству время на включение TCP/IP
                    Thread.sleep(PluginConfig.Network.TCPIP_ENABLE_DELAY_MS)
                    
                    indicator.text = "Connecting to $ipAddress:5555..."
                    
                    // Первая попытка подключения
                    val firstConnectResult = AdbService.connectWifi(project, ipAddress)
                    var success = firstConnectResult.getOrNull() ?: run {
                        firstConnectResult.onError { exception, _ ->
                            PluginLogger.wifiConnectionFailed(ipAddress, 5555, exception)
                        }
                        false
                    }
                    
                    if (!success) {
                        // Если не удалось, пробуем еще раз с большей задержкой
                        PluginLogger.info("First connection attempt failed, retrying...")
                        Thread.sleep(PluginConfig.Network.WIFI_CONNECTION_VERIFY_DELAY_MS)
                        val retryConnectResult = AdbService.connectWifi(project, ipAddress)
                        success = retryConnectResult.getOrNull() ?: run {
                            retryConnectResult.onError { exception, _ ->
                                PluginLogger.wifiConnectionFailed(ipAddress, 5555, exception)
                            }
                            false
                        }
                    }
                    
                    showWifiConnectionResult(success, device.name, ipAddress)
                } catch (e: Exception) {
                    NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                }
            }
        }.queue()
    }

    private fun showWifiConnectionResult(success: Boolean, deviceName: String, ipAddress: String) {
        if (success) {
            // Попытка найти устройство среди подключённых и добавить в историю
            val devicesResult = AdbService.getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: emptyList()
            val connectedDevice = devices.find { it.serialNumber.startsWith(ipAddress) }
            if (connectedDevice != null) {
                val deviceInfo = DeviceInfo(connectedDevice, ipAddress)
                WifiDeviceHistoryService.addOrUpdateDevice(
                    WifiDeviceHistoryService.WifiDeviceHistoryEntry(
                        ipAddress = ipAddress,
                        port = 5555,
                        displayName = deviceInfo.displayName,
                        androidVersion = deviceInfo.androidVersion,
                        apiLevel = deviceInfo.apiLevel,
                        logicalSerialNumber = deviceInfo.logicalSerialNumber,
                        realSerialNumber = deviceInfo.displaySerialNumber  // Сохраняем настоящий серийник
                    )
                )
            }
            NotificationUtils.showSuccess(project, "Successfully connected to $deviceName at $ipAddress.")
            // Форсируем обновление списка устройств
            devicePollingService.forceUpdate()
        } else {
            val message = """Failed to connect to $deviceName via Wi-Fi.
                |Possible solutions:
                |1. Make sure device is connected to the same Wi-Fi network
                |2. Try unplugging and reconnecting the USB cable
                |3. Check that 'USB debugging' is enabled in Developer Options
                |4. Some devices require 'Wireless debugging' to be enabled separately""".trimMargin()
            NotificationUtils.showError(project, message)
        }
    }

    // ==================== SCREEN MIRRORING ====================

    private fun startScreenMirroring(deviceInfo: DeviceInfo) {
        object : Task.Backgroundable(project, "Starting screen mirroring") {
            private var scrcpyPath: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Searching for scrcpy..."

                scrcpyPath = ScrcpyService.findScrcpyExecutable()

                if (scrcpyPath == null) {
                    handleScrcpyNotFound(deviceInfo)
                    return
                }

                launchScrcpyProcess(deviceInfo, scrcpyPath!!, indicator)
            }
        }.queue()
    }

    private fun handleScrcpyNotFound(deviceInfo: DeviceInfo) {
        ApplicationManager.getApplication().invokeLater {
            NotificationUtils.showInfo(project, "scrcpy executable not found in PATH. Please select the file.")
            showScrcpyCompatibilityDialog(deviceInfo)
        }
    }

    private fun showScrcpyCompatibilityDialog(deviceInfo: DeviceInfo) {
        val dialog = ScrcpyCompatibilityDialog(
            project,
            "Not found",
            deviceInfo.displayName,
            ScrcpyCompatibilityDialog.ProblemType.NOT_FOUND
        )
        dialog.show()

        if (dialog.exitCode == ScrcpyCompatibilityDialog.RETRY_EXIT_CODE) {
            retryScreenMirroring(deviceInfo)
        } else {
            NotificationUtils.showError(project, "scrcpy not found. Could not start mirroring.")
        }
    }

    private fun retryScreenMirroring(deviceInfo: DeviceInfo) {
        val scrcpyPath = ScrcpyService.findScrcpyExecutable()
        if (scrcpyPath != null) {
            object : Task.Backgroundable(project, "Starting screen mirroring") {
                override fun run(indicator: ProgressIndicator) {
                    launchScrcpyProcess(deviceInfo, scrcpyPath, indicator)
                }
            }.queue()
        } else {
            NotificationUtils.showError(project, "scrcpy path not provided. Could not start mirroring.")
        }
    }

    private fun launchScrcpyProcess(deviceInfo: DeviceInfo, scrcpyPath: String, indicator: ProgressIndicator) {
        val serialToUse = deviceInfo.logicalSerialNumber
        indicator.text = "Running: scrcpy -s $serialToUse"

        val success = ScrcpyService.launchScrcpy(scrcpyPath, serialToUse, project)

        ApplicationManager.getApplication().invokeLater {
            if (success) {
                NotificationUtils.showSuccess(project, "Screen mirroring started for ${deviceInfo.displayName}")
            } else {
                NotificationUtils.showError(project, "Failed to start screen mirroring for ${deviceInfo.displayName}")
            }
        }
    }

    // ==================== SETTINGS ====================

    private fun openPresetsDialog() {
        PresetsDialog(project).show()
    }
    
    // ==================== LIFECYCLE ====================
    
    /**
     * Освобождает ресурсы при закрытии панели
     */
    fun dispose() {
        devicePollingService.dispose()
    }
}