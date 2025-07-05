// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/AdbControlsPanel.kt

package io.github.qavlad.adbrandomizer.ui

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.Locale
import javax.swing.*

class AdbControlsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var lastUsedPreset: DevicePreset? = null
    private var currentPresetIndex: Int = -1
    private val deviceListModel = DefaultListModel<DeviceInfo>()
    private val deviceList = JBList(deviceListModel)
    private val deviceIpCache = mutableMapOf<String, String?>()

    // Состояние hover эффектов
    private var currentHoverState = HoverState.noHover()

    init {
        setupUI()
        startDevicePolling()
    }

    // ==================== UI SETUP ====================

    private fun setupUI() {
        val buttonsPanel = createButtonsPanel()
        val devicesPanel = createDevicesPanel()
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, buttonsPanel, devicesPanel).apply {
            border = null
            resizeWeight = 0.5
            SwingUtilities.invokeLater { setDividerLocation(0.5) }
        }
        add(splitPane, BorderLayout.CENTER)
    }

    private fun createButtonsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Controls")

        // Группируем кнопки логически
        panel.add(createCenteredButton("RANDOM SIZE AND DPI") { executeRandomAction(setSize = true, setDpi = true) })
        panel.add(createCenteredButton("RANDOM SIZE ONLY") { executeRandomAction(setSize = true, setDpi = false) })
        panel.add(createCenteredButton("RANDOM DPI ONLY") { executeRandomAction(setSize = false, setDpi = true) })

        panel.add(createCenteredButton("NEXT PRESET") { navigateToNextPreset() })
        panel.add(createCenteredButton("PREVIOUS PRESET") { navigateToPreviousPreset() })

        panel.add(createCenteredButton("Reset size and DPI to default") { executeResetAction(resetSize = true, resetDpi = true) })
        panel.add(createCenteredButton("RESET SIZE ONLY") { executeResetAction(resetSize = true, resetDpi = false) })
        panel.add(createCenteredButton("RESET DPI ONLY") { executeResetAction(resetSize = false, resetDpi = true) })

        panel.add(createCenteredButton("PRESETS") { openPresetSettings() })
        panel.add(createCenteredButton("CONNECT DEVICE") { promptForManualConnection() })

        return panel
    }

    private fun createDevicesPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Connected Devices")

        setupDeviceList()
        setupDeviceListInteractions()

        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun setupDeviceList() {
        deviceList.cellRenderer = DeviceListRenderer(
            getHoverState = { currentHoverState },
            getAllDevices = { getAllDevicesFromModel() },
            onMirrorClick = { deviceInfo -> startScreenMirroring(deviceInfo) },
            onWifiClick = { device -> connectDeviceViaWifi(device) }
        )

        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices connected"
        deviceList.clearSelection()
        deviceList.selectionModel.clearSelection()
    }

    private fun setupDeviceListInteractions() {
        deviceList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                handleMouseMovement(e)
            }
        })

        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                handleMouseClick(e)
            }

            override fun mouseExited(event: MouseEvent?) {
                resetHoverState()
            }
        })
    }

    private fun createCenteredButton(text: String, action: () -> Unit): JButton {
        val button = JButton(text)
        button.alignmentX = CENTER_ALIGNMENT
        ButtonUtils.addHoverEffect(button)
        button.addActionListener { action() }
        return button
    }

    // ==================== DEVICE MANAGEMENT ====================

    private fun startDevicePolling() {
        val timer = Timer(5000) { updateDeviceList() }
        timer.start()
        updateDeviceList()
    }

    private fun updateDeviceList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbService.getConnectedDevices(project).filter { it.isOnline }
            val deviceInfos = devices.map { device -> createDeviceInfo(device) }

            updateDeviceIpCache(deviceInfos)

            ApplicationManager.getApplication().invokeLater {
                synchronizeDeviceListModel(deviceInfos)
            }
        }
    }

    private fun createDeviceInfo(device: IDevice): DeviceInfo {
        val nameParts = device.name.replace("_", " ").split('-')
        val manufacturer = nameParts.getOrNull(0)?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: ""
        val model = nameParts.getOrNull(1)?.uppercase(Locale.getDefault()) ?: ""
        val displayName = "$manufacturer $model".trim()

        val logicalSerial = device.serialNumber
        val displaySerial = getDisplaySerialNumber(device, logicalSerial)
        val androidVersion = device.getProperty(IDevice.PROP_BUILD_VERSION) ?: "Unknown"

        return DeviceInfo(
            device = device,
            displayName = displayName,
            displaySerialNumber = displaySerial,
            logicalSerialNumber = logicalSerial,
            androidVersion = androidVersion,
            apiLevel = device.version.apiLevel.toString(),
            ipAddress = AdbService.getDeviceIpAddress(device)
        )
    }

    private fun getDisplaySerialNumber(device: IDevice, logicalSerial: String): String {
        return if (DeviceConnectionUtils.isWifiConnection(logicalSerial)) {
            getDeviceRealSerial(device) ?: logicalSerial
        } else {
            logicalSerial
        }
    }

    private fun getDeviceRealSerial(device: IDevice): String? {
        val properties = listOf("ro.serialno", "ro.boot.serialno", "gsm.sn1", "ril.serialnumber")

        for (property in properties) {
            try {
                val serial = device.getProperty(property)
                if (!serial.isNullOrBlank() && serial != "unknown" && serial.length > 3) {
                    return serial
                }
            } catch (_: Exception) {
                // Игнорируем ошибки и пробуем следующее свойство
            }
        }
        return null
    }

    private fun updateDeviceIpCache(deviceInfos: List<DeviceInfo>) {
        deviceInfos.forEach { deviceInfo ->
            val cachedIp = deviceIpCache[deviceInfo.logicalSerialNumber]
            if (cachedIp != deviceInfo.ipAddress) {
                deviceIpCache[deviceInfo.logicalSerialNumber] = deviceInfo.ipAddress
                if (deviceInfo.ipAddress != null) {
                    println("ADB_Randomizer: Device ${deviceInfo.displayName} IP: ${deviceInfo.ipAddress}")
                }
            }
        }
    }

    private fun synchronizeDeviceListModel(deviceInfos: List<DeviceInfo>) {
        val selectedValue = deviceList.selectedValue
        val currentSerials = (0 until deviceListModel.size()).map { deviceListModel.getElementAt(it).logicalSerialNumber }.toSet()
        val newSerials = deviceInfos.map { it.logicalSerialNumber }.toSet()

        // Удаляем исчезнувшие устройства
        removeDisconnectedDevices(currentSerials, newSerials)

        // Добавляем или обновляем устройства
        updateConnectedDevices(deviceInfos)

        // Восстанавливаем выделение
        restoreSelection(selectedValue)
    }

    private fun removeDisconnectedDevices(currentSerials: Set<String>, newSerials: Set<String>) {
        (currentSerials - newSerials).forEach { serialToRemove ->
            val elementToRemove = (0 until deviceListModel.size()).find {
                deviceListModel.getElementAt(it).logicalSerialNumber == serialToRemove
            }
            if (elementToRemove != null) {
                deviceListModel.removeElementAt(elementToRemove)
            }
            deviceIpCache.remove(serialToRemove)
        }
    }

    private fun updateConnectedDevices(deviceInfos: List<DeviceInfo>) {
        deviceInfos.forEach { deviceInfo ->
            val existingIndex = (0 until deviceListModel.size()).find {
                deviceListModel.getElementAt(it).logicalSerialNumber == deviceInfo.logicalSerialNumber
            }

            if (existingIndex != null) {
                deviceListModel.set(existingIndex, deviceInfo)
            } else {
                deviceListModel.addElement(deviceInfo)
            }
        }
    }

    private fun restoreSelection(selectedValue: DeviceInfo?) {
        if (selectedValue != null && deviceListModel.contains(selectedValue)) {
            deviceList.setSelectedValue(selectedValue, true)
        }
    }

    private fun getAllDevicesFromModel(): List<DeviceInfo> {
        return (0 until deviceListModel.size()).map {
            deviceListModel.getElementAt(it)
        }
    }

    // ==================== MOUSE INTERACTIONS ====================

    private fun handleMouseMovement(e: MouseEvent) {
        val index = deviceList.locationToIndex(e.point)
        var newButtonType: String? = null

        if (index != -1 && index < deviceListModel.size()) {
            val bounds = deviceList.getCellBounds(index, index)
            val deviceInfo = deviceListModel.getElementAt(index)
            val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                bounds,
                DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
            )

            newButtonType = when {
                buttonLayout.mirrorButtonRect.contains(e.point) -> HoverState.BUTTON_TYPE_MIRROR
                buttonLayout.wifiButtonRect?.contains(e.point) == true -> HoverState.BUTTON_TYPE_WIFI
                else -> null
            }
        }

        updateCursorAndHoverState(index, newButtonType)
    }

    private fun updateCursorAndHoverState(index: Int, newButtonType: String?) {
        deviceList.cursor = if (newButtonType != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }

        val newHoverState = if (index != -1 && newButtonType != null) {
            HoverState.deviceHovering(index, newButtonType)
        } else {
            HoverState.noHover()
        }

        if (currentHoverState != newHoverState) {
            currentHoverState = newHoverState
            deviceList.repaint()
        }
    }

    private fun handleMouseClick(e: MouseEvent) {
        val index = deviceList.locationToIndex(e.point)
        if (index != -1 && index < deviceListModel.size()) {
            val bounds = deviceList.getCellBounds(index, index)
            val deviceInfo = deviceListModel.getElementAt(index)
            val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                bounds,
                DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
            )

            when {
                buttonLayout.mirrorButtonRect.contains(e.point) -> startScreenMirroring(deviceInfo)
                buttonLayout.wifiButtonRect?.contains(e.point) == true -> connectDeviceViaWifi(deviceInfo.device)
            }
        }

        deviceList.clearSelection()
    }

    private fun resetHoverState() {
        if (currentHoverState.hasActiveDeviceHover()) {
            currentHoverState = HoverState.noHover()
            deviceList.cursor = Cursor.getDefaultCursor()
            deviceList.repaint()
        }
    }

    // ==================== VALIDATION AND HELPERS ====================

    private fun validateDevicesAvailable(): Boolean {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return false
        }
        return true
    }

    private fun validatePresetsAvailable(): Boolean {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            NotificationUtils.showInfo(project, "No presets found in settings.")
            return false
        }
        return true
    }

    // ==================== PRESET ACTIONS ====================

    private fun executeRandomAction(setSize: Boolean, setDpi: Boolean) {
        if (!validateDevicesAvailable()) return

        val randomPreset = selectRandomPreset(setSize, setDpi) ?: return
        applyPresetToDevices(randomPreset, setSize, setDpi)
    }

    private fun selectRandomPreset(setSize: Boolean, setDpi: Boolean): DevicePreset? {
        var availablePresets = filterSuitablePresets(setSize, setDpi)

        // Исключаем последний использованный пресет, если есть альтернативы
        if (availablePresets.size > 1 && lastUsedPreset != null) {
            availablePresets = availablePresets.filter { it != lastUsedPreset }
        }

        if (availablePresets.isEmpty()) {
            val allSuitablePresets = filterSuitablePresets(setSize, setDpi)
            if (allSuitablePresets.isNotEmpty()) {
                availablePresets = allSuitablePresets
            } else {
                NotificationUtils.showInfo(project, "No suitable presets found in settings.")
                return null
            }
        }

        val randomPreset = availablePresets.random()
        updateCurrentPresetIndex(randomPreset)
        return randomPreset
    }

    private fun filterSuitablePresets(setSize: Boolean, setDpi: Boolean): List<DevicePreset> {
        return SettingsService.getPresets().filter { preset ->
            (!setSize || preset.size.isNotBlank()) && (!setDpi || preset.dpi.isNotBlank())
        }
    }

    private fun updateCurrentPresetIndex(preset: DevicePreset) {
        val allPresets = SettingsService.getPresets()
        currentPresetIndex = allPresets.indexOf(preset)
    }


    private fun navigateToNextPreset() {
        if (!validatePresetsAvailable()) return

        val presets = SettingsService.getPresets()
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun navigateToPreviousPreset() {
        if (!validatePresetsAvailable()) return

        val presets = SettingsService.getPresets()
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        if (!validateDevicesAvailable()) return

        val presets = SettingsService.getPresets()
        if (index < 0 || index >= presets.size) {
            NotificationUtils.showInfo(project, "Invalid preset index.")
            return
        }

        applyPresetToDevices(presets[index], setSize = true, setDpi = true)
    }

    private fun applyPresetToDevices(preset: DevicePreset, setSize: Boolean, setDpi: Boolean) {
        lastUsedPreset = preset
        
        // Используем PresetApplicationService для применения пресета
        PresetApplicationService.applyPreset(project, preset, setSize, setDpi)
    }


    // ==================== RESET ACTIONS ====================

    private fun executeResetAction(resetSize: Boolean, resetDpi: Boolean) {
        if (!validateDevicesAvailable()) return

        val actionDescription = getResetActionDescription(resetSize, resetDpi)

        object : Task.Backgroundable(project, actionDescription) {
            override fun run(indicator: ProgressIndicator) {
                val devices = AdbService.getConnectedDevices(project)
                resetAllDevices(devices, resetSize, resetDpi, indicator)
                
                // Обновляем состояние в DeviceStateService для показа серых индикаторов
                DeviceStateService.handleReset(resetSize, resetDpi)
                
                ApplicationManager.getApplication().invokeLater {
                    showResetResult(devices.size, resetSize, resetDpi)
                    
                    // Уведомляем все открытые диалоги настроек об обновлении
                    SettingsDialogUpdateNotifier.notifyUpdate()
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
            if (resetSize) AdbService.resetSize(device)
            if (resetDpi) AdbService.resetDpi(device)
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
                    val success = AdbService.connectWifi(project, ipAddress, port)
                    handleConnectionResult(success, ipAddress, port, indicator)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                    }
                }
            }
        }.queue()
    }

    private fun handleConnectionResult(success: Boolean, ipAddress: String, port: Int, @Suppress("UNUSED_PARAMETER") indicator: ProgressIndicator) {
        if (success) {
            Thread.sleep(2000)
            val devices = AdbService.getConnectedDevices(project)
            val connected = devices.any { it.serialNumber.startsWith(ipAddress) }

            ApplicationManager.getApplication().invokeLater {
                if (connected) {
                    NotificationUtils.showSuccess(project, "Successfully connected to device at $ipAddress:$port")
                    updateDeviceList()
                } else {
                    NotificationUtils.showError(project, "Connected to $ipAddress:$port but device not visible. Check device settings.")
                }
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

                val ipAddress = AdbService.getDeviceIpAddress(device)
                if (ipAddress.isNullOrBlank()) {
                    NotificationUtils.showError(project, "Cannot find IP address for device ${device.name}. Make sure it's connected to Wi-Fi.")
                    return
                }

                indicator.text = "Enabling TCP/IP mode on ${device.name}..."
                try {
                    AdbService.enableTcpIp(device)
                    indicator.text = "Connecting to $ipAddress:5555..."

                    val success = connectWithRetryAndVerification(ipAddress, indicator)
                    showWifiConnectionResult(success, device.name, ipAddress)
                } catch (e: Exception) {
                    NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                }
            }
        }.queue()
    }

    private fun connectWithRetryAndVerification(ipAddress: String, indicator: ProgressIndicator): Boolean {
        Thread.sleep(2000) // Даем время устройству переключиться в TCP режим

        val initialSuccess = AdbService.connectWifi(project, ipAddress)
        return if (initialSuccess) {
            waitForDeviceConnection(ipAddress, indicator)
        } else {
            false
        }
    }

    private fun waitForDeviceConnection(ipAddress: String, indicator: ProgressIndicator): Boolean {
        val maxAttempts = 10
        val delayBetweenAttempts = 1000L

        repeat(maxAttempts) { attempt ->
            indicator.text = "Verifying connection... (${attempt + 1}/$maxAttempts)"

            val devices = AdbService.getConnectedDevices(project)
            val wifiConnected = devices.any { it.serialNumber.startsWith(ipAddress) }

            if (wifiConnected) {
                SwingUtilities.invokeLater { updateDeviceList() }
                return true
            }

            if (attempt < maxAttempts - 1) {
                Thread.sleep(delayBetweenAttempts)
            }
        }

        SwingUtilities.invokeLater { updateDeviceList() }
        return false
    }

    private fun showWifiConnectionResult(success: Boolean, deviceName: String, ipAddress: String) {
        if (success) {
            NotificationUtils.showSuccess(project, "Successfully connected to $deviceName at $ipAddress.")
        } else {
            NotificationUtils.showError(project, "Failed to connect to $deviceName via Wi-Fi. Check device and network.")
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
            NotificationUtils.showInfo(project, "scrcpy executable not found in PATH or settings. Please select the file.")
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

    private fun openPresetSettings() {
        SettingsDialog(project).show()
    }
}