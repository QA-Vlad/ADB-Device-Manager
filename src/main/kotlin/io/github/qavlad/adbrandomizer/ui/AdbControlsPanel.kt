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
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
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

    // Переменные для отслеживания hover-эффекта на кнопках в списке
    private var hoveredCellIndex: Int = -1
    private var hoveredButtonType: String? = null // "MIRROR" или "WIFI"

    companion object {
        private val wifiSerialRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")
    }

    init {
        val buttonsPanel = createButtonsPanel()
        val devicesPanel = createDevicesPanel()
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, buttonsPanel, devicesPanel).apply {
            border = null
            resizeWeight = 0.5
            SwingUtilities.invokeLater { setDividerLocation(0.5) }
        }
        add(splitPane, BorderLayout.CENTER)
        startDevicePolling()
    }

    private fun createButtonsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Controls")
        panel.add(createCenteredButton("RANDOM SIZE AND DPI") { handleRandomAction(setSize = true, setDpi = true) })
        panel.add(createCenteredButton("RANDOM SIZE ONLY") { handleRandomAction(setSize = true, setDpi = false) })
        panel.add(createCenteredButton("RANDOM DPI ONLY") { handleRandomAction(setSize = false, setDpi = true) })
        panel.add(createCenteredButton("NEXT PRESET") { handleNextPreset() })
        panel.add(createCenteredButton("PREVIOUS PRESET") { handlePreviousPreset() })
        panel.add(createCenteredButton("Reset size and DPI to default") { handleResetAction(resetSize = true, resetDpi = true) })
        panel.add(createCenteredButton("RESET SIZE ONLY") { handleResetAction(resetSize = true, resetDpi = false) })
        panel.add(createCenteredButton("RESET DPI ONLY") { handleResetAction(resetSize = false, resetDpi = true) })
        panel.add(createCenteredButton("PRESETS") { SettingsDialog(project).show() })
        panel.add(createCenteredButton("CONNECT DEVICE") { handleConnectDevice() })
        return panel
    }

    private fun createDevicesPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Connected Devices")

        // Создаем рендерер с callback'ами
        deviceList.cellRenderer = DeviceListRenderer(
            hoveredCellIndex = { hoveredCellIndex },
            hoveredButtonType = { hoveredButtonType },
            onMirrorClick = { deviceInfo -> handleScrcpyMirror(deviceInfo) },
            onWifiClick = { device -> handleWifiConnect(device) }
        )

        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices connected"

        deviceList.clearSelection()
        deviceList.selectionModel.clearSelection()

        //  Единый обработчик движения мыши с изменением курсора
        deviceList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = deviceList.locationToIndex(e.point)
                var newButtonType: String? = null

                if (index != -1 && index < deviceListModel.size()) {
                    val bounds = deviceList.getCellBounds(index, index)
                    val deviceInfo = deviceListModel.getElementAt(index)
                    val buttonLayout = calculateButtonSizes(bounds, deviceInfo)

                    newButtonType = when {
                        buttonLayout.mirrorButtonRect.contains(e.point) -> "MIRROR"
                        buttonLayout.wifiButtonRect?.contains(e.point) == true -> "WIFI"
                        else -> null
                    }
                }

                // Меняем курсор в зависимости от того, на кнопке ли мы
                deviceList.cursor = if (newButtonType != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }

                if (index != hoveredCellIndex || newButtonType != hoveredButtonType) {
                    hoveredCellIndex = index
                    hoveredButtonType = newButtonType
                    deviceList.repaint()
                }
            }
        })

        //  Обработчик кликов БЕЗ selection
        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = deviceList.locationToIndex(e.point)
                if (index != -1 && index < deviceListModel.size()) {
                    val bounds = deviceList.getCellBounds(index, index)
                    val deviceInfo = deviceListModel.getElementAt(index)
                    val buttonLayout = calculateButtonSizes(bounds, deviceInfo)

                    when {
                        buttonLayout.mirrorButtonRect.contains(e.point) -> {
                            handleScrcpyMirror(deviceInfo)
                        }
                        buttonLayout.wifiButtonRect?.contains(e.point) == true -> {
                            handleWifiConnect(deviceInfo.device)
                        }
                    }
                }

                // Сбрасываем selection после любого клика
                deviceList.clearSelection()
            }

            override fun mouseExited(event: MouseEvent?) {
                if (hoveredCellIndex != -1) {
                    hoveredCellIndex = -1
                    hoveredButtonType = null
                    //  Возвращаем обычный курсор при выходе
                    deviceList.cursor = Cursor.getDefaultCursor()
                    deviceList.repaint()
                }
            }
        })

        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun handleConnectDevice() {
        val input = Messages.showInputDialog(
            project,
            "Enter device IP address and port (example: 192.168.1.100:5555):",
            "Connect Device via Wi-Fi",
            Messages.getQuestionIcon(),
            "192.168.1.100:5555",
            null
        )

        if (input != null && input.isNotBlank()) {
            val parts = input.trim().split(":")
            if (parts.size != 2) {
                Messages.showErrorDialog(project, "Please enter in format: IP:PORT", "Invalid Format")
                return
            }

            val ip = parts[0].trim()
            val portText = parts[1].trim()

            val port = try {
                portText.toInt()
            } catch (_: NumberFormatException) {
                Messages.showErrorDialog(project, "Port must be a number", "Invalid Input")
                return
            }

            if (port < 1 || port > 65535) {
                Messages.showErrorDialog(project, "Port must be between 1 and 65535", "Invalid Input")
                return
            }

            if (!AdbService.isValidIpAddress(ip)) {
                Messages.showErrorDialog(project, "Please enter valid IP address", "Invalid Input")
                return
            }

            handleManualWifiConnect(ip, port)
        }
    }

    // Ручное подключение по Wi-Fi
    private fun handleManualWifiConnect(ipAddress: String, port: Int) {
        object : Task.Backgroundable(project, "Connecting to $ipAddress:$port") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to $ipAddress:$port..."

                try {
                    val success = AdbService.connectWifi(project, ipAddress, port)

                    if (success) {
                        // Ждем немного и проверяем подключение
                        Thread.sleep(2000)
                        val devices = AdbService.getConnectedDevices(project)
                        val connected = devices.any { it.serialNumber.startsWith(ipAddress) }

                        ApplicationManager.getApplication().invokeLater {
                            if (connected) {
                                NotificationUtils.showSuccess(project, "Successfully connected to device at $ipAddress:$port")
                                updateDeviceList() // Обновляем список устройств
                            } else {
                                NotificationUtils.showError(project, "Connected to $ipAddress:$port but device not visible. Check device settings.")
                            }
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            NotificationUtils.showError(project, "Failed to connect to $ipAddress:$port. Make sure device is in TCP/IP mode.")
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                    }
                }
            }
        }.queue()
    }

    // Класс для хранения информации о кнопках
    private data class ButtonLayout(
        val mirrorButtonRect: Rectangle,
        val wifiButtonRect: Rectangle? // null для Wi-Fi устройств
    )

    // Меняем метод calculateButtonSizes:
    private fun calculateButtonSizes(cellBounds: Rectangle, deviceInfo: DeviceInfo): ButtonLayout {
        val buttonPanelWidth = 115
        val buttonSpacing = 5
        val mirrorButtonWidth = 35
        val wifiButtonWidth = 70
        val buttonHeight = 25

        val buttonY = cellBounds.y + (cellBounds.height - buttonHeight) / 2
        val buttonPanelX = cellBounds.x + cellBounds.width - buttonPanelWidth
        val mirrorButtonX = buttonPanelX + buttonSpacing
        val mirrorButtonRect = Rectangle(mirrorButtonX, buttonY, mirrorButtonWidth, buttonHeight)

        // Используем companion object wifiSerialRegex
        val wifiButtonRect = if (!wifiSerialRegex.matches(deviceInfo.logicalSerialNumber)) {
            val wifiButtonX = mirrorButtonX + mirrorButtonWidth + buttonSpacing
            Rectangle(wifiButtonX, buttonY, wifiButtonWidth, buttonHeight)
        } else {
            null
        }

        return ButtonLayout(mirrorButtonRect, wifiButtonRect)
    }

    // В AdbControlsPanel.kt - увеличиваем интервал polling
    private fun startDevicePolling() {
        val timer = Timer(5000) { updateDeviceList() }  // Было 3000, стало 5000
        timer.start()
        updateDeviceList()
    }

    private fun updateDeviceList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val devices = AdbService.getConnectedDevices(project).filter { it.isOnline }
            val deviceInfos = devices.map { device ->
                val nameParts = device.name.replace("_", " ").split('-')
                val manufacturer = nameParts.getOrNull(0)?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: ""
                val model = nameParts.getOrNull(1)?.uppercase(Locale.getDefault()) ?: ""
                val displayName = "$manufacturer $model".trim()

                val logicalSerial = device.serialNumber
                var displaySerial = logicalSerial

                if (wifiSerialRegex.matches(logicalSerial)) {
                    // Улучшенное получение серийного номера с fallback
                    val realSerial = getDeviceRealSerial(device)
                    if (!realSerial.isNullOrBlank()) {
                        displaySerial = realSerial
                    }
                }
                val androidVersion = device.getProperty(IDevice.PROP_BUILD_VERSION) ?: "Unknown"

                DeviceInfo(
                    device = device,
                    displayName = displayName,
                    displaySerialNumber = displaySerial,
                    logicalSerialNumber = logicalSerial,
                    androidVersion = androidVersion,
                    apiLevel = device.version.apiLevel.toString(),
                    ipAddress = AdbService.getDeviceIpAddress(device)
                )
            }

            // КЭШИРОВАНИЕ IP - логируем только при изменениях
            deviceInfos.forEach { deviceInfo ->
                val cachedIp = deviceIpCache[deviceInfo.logicalSerialNumber]
                if (cachedIp != deviceInfo.ipAddress) {
                    deviceIpCache[deviceInfo.logicalSerialNumber] = deviceInfo.ipAddress
                    if (deviceInfo.ipAddress != null) {
                        println("ADB_Randomizer: Device ${deviceInfo.displayName} IP: ${deviceInfo.ipAddress}")
                    }
                }
            }

            ApplicationManager.getApplication().invokeLater {
                val selectedValue = deviceList.selectedValue
                val currentSerials = (0 until deviceListModel.size()).map { deviceListModel.getElementAt(it).logicalSerialNumber }.toSet()
                val newSerials = deviceInfos.map { it.logicalSerialNumber }.toSet()

                // Удаляем те, что исчезли
                (currentSerials - newSerials).forEach { serialToRemove ->
                    val elementToRemove = (0 until deviceListModel.size()).find { deviceListModel.getElementAt(it).logicalSerialNumber == serialToRemove }
                    if (elementToRemove != null) deviceListModel.removeElementAt(elementToRemove)
                    // Также удаляем из кэша
                    deviceIpCache.remove(serialToRemove)
                }

                // Добавляем или обновляем
                deviceInfos.forEach { deviceInfo ->
                    val existingIndex = (0 until deviceListModel.size()).find { deviceListModel.getElementAt(it).logicalSerialNumber == deviceInfo.logicalSerialNumber }
                    if (existingIndex != null) {
                        deviceListModel.set(existingIndex, deviceInfo)
                    } else {
                        deviceListModel.addElement(deviceInfo)
                    }
                }

                if (selectedValue != null && deviceListModel.contains(selectedValue)) {
                    deviceList.setSelectedValue(selectedValue, true)
                }
            }
        }
    }

    // Улучшенное получение реального серийного номера
    private fun getDeviceRealSerial(device: IDevice): String? {
        val properties = listOf(
            "ro.serialno",
            "ro.boot.serialno",
            "gsm.sn1",
            "ril.serialnumber"
        )

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

    // Запуск scrcpy из UI Thread
    private fun handleScrcpyMirror(deviceInfo: DeviceInfo) {
        object : Task.Backgroundable(project, "Starting screen mirroring") {
            private var scrcpyPath: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Searching for scrcpy..."

                scrcpyPath = ScrcpyService.findScrcpyExecutable()

                if (scrcpyPath == null) {
                    indicator.text = "Scrcpy not found."
                    // Перемещаем UI операцию в UI Thread
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showInfo(project, "scrcpy executable not found in PATH or settings. Please select the file.")
                        promptForScrcpyInUIThread(deviceInfo)
                    }
                    return
                }

                launchScrcpyProcess(deviceInfo, scrcpyPath!!, indicator)
            }
        }.queue()
    }

    // Новый метод для работы с UI в правильном потоке
    private fun promptForScrcpyInUIThread(deviceInfo: DeviceInfo) {
        // Показываем диалог совместимости, если scrcpy не найден
        val dialog = ScrcpyCompatibilityDialog(
            project,
            "Not found",
            deviceInfo.displayName,
            ScrcpyCompatibilityDialog.ProblemType.NOT_FOUND
        )
        dialog.show()
        if (dialog.exitCode == ScrcpyCompatibilityDialog.RETRY_EXIT_CODE) {
            // Пользователь выбрал путь к scrcpy, пробуем снова
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
        } else {
            NotificationUtils.showError(project, "scrcpy not found. Could not start mirroring.")
        }
    }

    private fun launchScrcpyProcess(deviceInfo: DeviceInfo, scrcpyPath: String, indicator: ProgressIndicator) {
        val serialToUse = deviceInfo.logicalSerialNumber
        indicator.text = "Running: scrcpy -s $serialToUse"

        // Передаем project в scrcpy для получения правильного ADB
        val success = ScrcpyService.launchScrcpy(scrcpyPath, serialToUse, project)
        if (success) {
            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showSuccess(project, "Screen mirroring started for ${deviceInfo.displayName}")
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showError(project, "Failed to start screen mirroring for ${deviceInfo.displayName}")
            }
        }
    }

    private fun handleWifiConnect(device: IDevice) {
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

                    // Убираем хардкод задержки и добавляем polling подключения
                    val success = connectWithRetry(ipAddress, indicator)

                    if (success) {
                        NotificationUtils.showSuccess(project, "Successfully connected to ${device.name} at $ipAddress.")
                    } else {
                        NotificationUtils.showError(project, "Failed to connect to ${device.name} via Wi-Fi. Check device and network.")
                    }
                } catch (e: Exception) {
                    NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                }
            }

            private fun connectWithRetry(ipAddress: String, indicator: ProgressIndicator): Boolean {
                // Даем время устройству переключиться в TCP режим
                Thread.sleep(2000)

                // Пытаемся подключиться
                val initialSuccess = AdbService.connectWifi(project, ipAddress)

                if (initialSuccess) {
                    // Проверяем подключение через polling
                    return waitForDeviceConnection(ipAddress, indicator)
                }

                return false
            }

            private fun waitForDeviceConnection(ipAddress: String, indicator: ProgressIndicator): Boolean {
                val maxAttempts = 10
                val delayBetweenAttempts = 1000L

                repeat(maxAttempts) { attempt ->
                    indicator.text = "Verifying connection... (${attempt + 1}/$maxAttempts)"

                    // Обновляем список устройств и проверяем наличие Wi-Fi подключения
                    val devices = AdbService.getConnectedDevices(project)
                    val wifiConnected = devices.any { it.serialNumber.startsWith(ipAddress) }

                    if (wifiConnected) {
                        // Обновляем UI в главном потоке
                        SwingUtilities.invokeLater { updateDeviceList() }
                        return true
                    }

                    if (attempt < maxAttempts - 1) {
                        Thread.sleep(delayBetweenAttempts)
                    }
                }

                // Если не подключились, все равно обновляем UI
                SwingUtilities.invokeLater { updateDeviceList() }
                return false
            }
        }.queue()
    }

    private fun handleNextPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            NotificationUtils.showInfo(project, "No presets found in settings.")
            return
        }
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun handlePreviousPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            NotificationUtils.showInfo(project, "No presets found in settings.")
            return
        }
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return
        }
        val presets = SettingsService.getPresets()
        if (index < 0 || index >= presets.size) {
            NotificationUtils.showInfo(project, "Invalid preset index.")
            return
        }
        applyPreset(presets[index], index + 1, setSize = true, setDpi = true)
    }

    private fun applyPreset(preset: DevicePreset, presetNumber: Int, setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        object : Task.Backgroundable(project, "Applying preset") {
            override fun run(indicator: ProgressIndicator) {
                lastUsedPreset = preset
                val appliedSettings = mutableListOf<String>()
                var width: Int? = null; var height: Int? = null
                if (setSize && preset.size.isNotBlank()) {
                    val parts = preset.size.split('x', 'X', 'х', 'Х').map { it.trim() }
                    width = parts.getOrNull(0)?.toIntOrNull(); height = parts.getOrNull(1)?.toIntOrNull()
                    if (width == null || height == null) {
                        NotificationUtils.showError(project, "Invalid size format in preset '${preset.label}': ${preset.size}")
                        return
                    }
                    appliedSettings.add("Size: ${preset.size}")
                }
                var dpi: Int? = null
                if (setDpi && preset.dpi.isNotBlank()) {
                    dpi = preset.dpi.trim().toIntOrNull()
                    if (dpi == null) {
                        NotificationUtils.showError(project, "Invalid DPI format in preset '${preset.label}': ${preset.dpi}")
                        return
                    }
                    appliedSettings.add("DPI: ${preset.dpi}")
                }
                if (appliedSettings.isEmpty()) {
                    NotificationUtils.showInfo(project, "No settings to apply for preset '${preset.label}'")
                    return
                }
                devices.forEach { device ->
                    indicator.text = "Applying '${preset.label}' to ${device.name}..."
                    if (setSize && width != null && height != null) AdbService.setSize(device, width, height)
                    if (setDpi && dpi != null) AdbService.setDpi(device, dpi)
                }
                val message = "<html>Preset №${presetNumber}: ${preset.label};<br>${appliedSettings.joinToString(", ")}</html>"
                NotificationUtils.showSuccess(project, message)
            }
        }.queue()
    }

    private fun handleResetAction(resetSize: Boolean, resetDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return
        }
        val actionDescription = when {
            resetSize && resetDpi -> "Resetting screen and DPI"
            resetSize -> "Resetting screen size"
            resetDpi -> "Resetting DPI"
            else -> "No action"
        }
        object : Task.Backgroundable(project, actionDescription) {
            override fun run(indicator: ProgressIndicator) {
                devices.forEach { device ->
                    indicator.text = "Resetting ${device.name}..."
                    if (resetSize) AdbService.resetSize(device)
                    if (resetDpi) AdbService.resetDpi(device)
                }
                val resetItems = mutableListOf<String>()
                if (resetSize) resetItems.add("screen size")
                if (resetDpi) resetItems.add("DPI")
                NotificationUtils.showSuccess(project, "${resetItems.joinToString(" and ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} reset for ${devices.size} device(s).")
            }
        }.queue()
    }

    private fun handleRandomAction(setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return
        }
        var availablePresets = SettingsService.getPresets().filter { (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank()) }
        if (availablePresets.size > 1 && lastUsedPreset != null) { availablePresets = availablePresets.filter { it != lastUsedPreset } }
        if (availablePresets.isEmpty()) {
            val allSuitablePresets = SettingsService.getPresets().filter { (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank()) }
            if (allSuitablePresets.isNotEmpty()) { availablePresets = allSuitablePresets } else {
                NotificationUtils.showInfo(project, "No suitable presets found in settings.")
                return
            }
        }
        val randomPreset = availablePresets.random()
        val allPresets = SettingsService.getPresets()
        currentPresetIndex = allPresets.indexOf(randomPreset)
        val presetNumber = allPresets.indexOfFirst { it.label == randomPreset.label } + 1
        applyPreset(randomPreset, presetNumber, setSize, setDpi)
    }

    private fun createCenteredButton(text: String, action: () -> Unit): JButton {
        val button = JButton(text)
        button.alignmentX = CENTER_ALIGNMENT
        ButtonUtils.addHoverEffect(button)
        button.addActionListener { action() }
        return button
    }
}