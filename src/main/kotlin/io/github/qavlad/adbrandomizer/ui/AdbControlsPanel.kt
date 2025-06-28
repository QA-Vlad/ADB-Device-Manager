// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/AdbControlsPanel.kt

package io.github.qavlad.adbrandomizer.ui

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
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
    private val wifiSerialRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")

    // Переменные для отслеживания hover-эффекта на кнопках в списке
    private var hoveredCellIndex: Int = -1
    private var hoveredButtonType: String? = null // "MIRROR" или "WIFI"

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
        return panel
    }

    private fun createDevicesPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Connected Devices")

        deviceList.cellRenderer = DeviceListCellRenderer()
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices connected"

        // Обработчик кликов
        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (hoveredCellIndex != -1 && hoveredButtonType != null) {
                    val deviceInfo = deviceListModel.getElementAt(hoveredCellIndex)
                    when (hoveredButtonType) {
                        "MIRROR" -> handleScrcpyMirror(deviceInfo)
                        "WIFI" -> handleWifiConnect(deviceInfo.device)
                    }
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                if (hoveredCellIndex != -1) {
                    hoveredCellIndex = -1
                    hoveredButtonType = null
                    deviceList.repaint()
                }
            }
        })

        // Обработчик движения мыши для hover-эффектов
        deviceList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = deviceList.locationToIndex(e.point)
                var newHoveredButton: String? = null

                if (index != -1) {
                    val bounds = deviceList.getCellBounds(index, index)
                    // Расчет хитбоксов кнопок
                    val mirrorButtonRect = Rectangle(bounds.x + bounds.width - 115, bounds.y, 35, bounds.height)
                    val wifiButtonRect = Rectangle(bounds.x + bounds.width - 75, bounds.y, 70, bounds.height)
                    val deviceInfo = deviceListModel.getElementAt(index)

                    newHoveredButton = when {
                        mirrorButtonRect.contains(e.point) -> "MIRROR"
                        !wifiSerialRegex.matches(deviceInfo.logicalSerialNumber) && wifiButtonRect.contains(e.point) -> "WIFI"
                        else -> null
                    }
                }

                if (index != hoveredCellIndex || newHoveredButton != hoveredButtonType) {
                    hoveredCellIndex = index
                    hoveredButtonType = newHoveredButton
                    deviceList.repaint()
                }
            }
        })

        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun startDevicePolling() {
        val timer = Timer(3000) { updateDeviceList() }
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
                    val realSerial = device.getProperty("ro.serialno")
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

            ApplicationManager.getApplication().invokeLater {
                val selectedValue = deviceList.selectedValue
                val currentSerials = (0 until deviceListModel.size()).map { deviceListModel.getElementAt(it).logicalSerialNumber }.toSet()
                val newSerials = deviceInfos.map { it.logicalSerialNumber }.toSet()

                // Удаляем те, что исчезли
                (currentSerials - newSerials).forEach { serialToRemove ->
                    val elementToRemove = (0 until deviceListModel.size()).find { deviceListModel.getElementAt(it).logicalSerialNumber == serialToRemove }
                    if (elementToRemove != null) deviceListModel.removeElementAt(elementToRemove)
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

    private fun handleScrcpyMirror(deviceInfo: DeviceInfo) {
        object : Task.Backgroundable(project, "Starting screen mirroring") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Searching for scrcpy..."

                var scrcpyPath = ScrcpyService.findScrcpyExecutable()

                if (scrcpyPath == null) {
                    indicator.text = "Scrcpy not found. Please select it."
                    showNotification("scrcpy executable not found in PATH or settings. Please select the file.")
                    scrcpyPath = ScrcpyService.promptForScrcpyPath(project)
                }

                if (scrcpyPath != null) {
                    val serialToUse = deviceInfo.displaySerialNumber
                    indicator.text = "Running: scrcpy -s $serialToUse"
                    ScrcpyService.launchScrcpy(scrcpyPath, serialToUse)
                } else {
                    showErrorNotification("scrcpy path not provided. Could not start mirroring.")
                }
            }
        }.queue()
    }

    private fun handleWifiConnect(device: IDevice) {
        object : Task.Backgroundable(project, "Connecting to Device via Wi-Fi") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Getting IP address for ${device.name}..."

                val ipAddress = AdbService.getDeviceIpAddress(device)

                if (ipAddress.isNullOrBlank()) {
                    showErrorNotification("Cannot find IP address for device ${device.name}. Make sure it's connected to Wi-Fi.")
                    return
                }

                indicator.text = "Enabling TCP/IP mode on ${device.name}..."
                try {
                    AdbService.enableTcpIp(device)
                    indicator.text = "Connecting to $ipAddress:5555..."
                    val success = AdbService.connectWifi(project, ipAddress)
                    Thread.sleep(2000)
                    SwingUtilities.invokeLater { updateDeviceList() }

                    if (success) {
                        showSuccessNotification("Successfully initiated connection to ${device.name} at $ipAddress. Check the device list.")
                    } else {
                        showErrorNotification("Failed to connect to ${device.name} via Wi-Fi.")
                    }
                } catch (e: Exception) {
                    showErrorNotification("Error connecting to device: ${e.message}")
                }
            }
        }.queue()
    }

    private fun handleNextPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) { showNotification("No presets found in settings."); return }
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun handlePreviousPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) { showNotification("No presets found in settings."); return }
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) { showNotification("No connected devices found."); return }
        val presets = SettingsService.getPresets()
        if (index < 0 || index >= presets.size) { showNotification("Invalid preset index."); return }
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
                    if (width == null || height == null) { showErrorNotification("Invalid size format in preset '${preset.label}': ${preset.size}"); return }
                    appliedSettings.add("Size: ${preset.size}")
                }
                var dpi: Int? = null
                if (setDpi && preset.dpi.isNotBlank()) {
                    dpi = preset.dpi.trim().toIntOrNull()
                    if (dpi == null) { showErrorNotification("Invalid DPI format in preset '${preset.label}': ${preset.dpi}"); return }
                    appliedSettings.add("DPI: ${preset.dpi}")
                }
                if (appliedSettings.isEmpty()) { showNotification("No settings to apply for preset '${preset.label}'"); return }
                devices.forEach { device ->
                    indicator.text = "Applying '${preset.label}' to ${device.name}..."
                    if (setSize && width != null && height != null) AdbService.setSize(device, width, height)
                    if (setDpi && dpi != null) AdbService.setDpi(device, dpi)
                }
                val message = "<html>Preset №${presetNumber}: ${preset.label};<br>${appliedSettings.joinToString(", ")}</html>"
                showSuccessNotification(message)
            }
        }.queue()
    }

    private fun handleResetAction(resetSize: Boolean, resetDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) { showNotification("No connected devices found."); return }
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
                showSuccessNotification("${resetItems.joinToString(" and ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} reset for ${devices.size} device(s).")
            }
        }.queue()
    }

    private fun handleRandomAction(setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) { showNotification("No connected devices found."); return }
        var availablePresets = SettingsService.getPresets().filter { (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank()) }
        if (availablePresets.size > 1 && lastUsedPreset != null) { availablePresets = availablePresets.filter { it != lastUsedPreset } }
        if (availablePresets.isEmpty()) {
            val allSuitablePresets = SettingsService.getPresets().filter { (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank()) }
            if (allSuitablePresets.isNotEmpty()) { availablePresets = allSuitablePresets } else { showNotification("No suitable presets found in settings."); return }
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

    private fun showNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showSuccessNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showErrorNotification(message: String) = showPopup(message, NotificationType.ERROR)

    private fun showPopup(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("ADB Randomizer Notifications").createNotification(message, type).notify(project)
        }
    }

    private data class DeviceInfo(
        val device: IDevice,
        val displayName: String,
        val displaySerialNumber: String,
        val logicalSerialNumber: String,
        val androidVersion: String,
        val apiLevel: String,
        val ipAddress: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DeviceInfo
            return logicalSerialNumber == other.logicalSerialNumber
        }
        override fun hashCode(): Int = logicalSerialNumber.hashCode()
    }

    private inner class DeviceListCellRenderer : ListCellRenderer<DeviceInfo> {
        private val scrcpyIcon: Icon = IconLoader.getIcon("/icons/scrcpy.svg", javaClass)
        private val usbIcon: Icon = IconLoader.getIcon("/icons/usb.svg", javaClass)
        private val wifiIcon: Icon = IconLoader.getIcon("/icons/wifi.svg", javaClass)

        private val smallWifiIcon: Icon by lazy {
            val image = java.awt.image.BufferedImage(wifiIcon.iconWidth, wifiIcon.iconHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2d = image.createGraphics()
            wifiIcon.paintIcon(null, g2d, 0, 0)
            g2d.dispose()
            val scaledImage = image.getScaledInstance(12, 12, Image.SCALE_SMOOTH)
            ImageIcon(scaledImage)
        }

        override fun getListCellRendererComponent(
            list: JList<out DeviceInfo>?, value: DeviceInfo?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val mainPanel = JPanel(BorderLayout(10, 0)).apply {
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                background = if (isSelected) list?.selectionBackground else list?.background
            }

            if (value == null || list == null) return mainPanel

            val connectionIcon = if (wifiSerialRegex.matches(value.logicalSerialNumber)) wifiIcon else usbIcon
            mainPanel.add(JLabel(connectionIcon), BorderLayout.WEST)

            val textPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

            val nameLabel = JLabel("${value.displayName} (${value.displaySerialNumber})").apply {
                foreground = if (isSelected) list.selectionForeground else list.foreground
                alignmentX = LEFT_ALIGNMENT
            }
            textPanel.add(nameLabel)

            val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

            val ipInfo = if (wifiSerialRegex.matches(value.logicalSerialNumber)) value.logicalSerialNumber else value.ipAddress ?: "IP not available"
            val infoText = "Android ${value.androidVersion} (API ${value.apiLevel}) - $ipInfo"

            val infoLabel = JLabel(infoText).apply {
                font = JBFont.small()
                foreground = if (isSelected) list.selectionForeground?.brighter() else JBColor.GRAY
            }
            infoPanel.add(infoLabel)

            if (!wifiSerialRegex.matches(value.logicalSerialNumber)) {
                val model = list.model
                var wifiConnectionExists = false
                for (i in 0 until model.size) {
                    val otherDevice = model.getElementAt(i)
                    if (otherDevice.displaySerialNumber == value.displaySerialNumber && wifiSerialRegex.matches(otherDevice.logicalSerialNumber)) {
                        wifiConnectionExists = true
                        break
                    }
                }
                if (wifiConnectionExists) {
                    infoPanel.add(Box.createHorizontalStrut(4))
                    infoPanel.add(JLabel(smallWifiIcon))
                }
            }
            textPanel.add(infoPanel)
            mainPanel.add(textPanel, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                isOpaque = false
                preferredSize = Dimension(115, 30)
            }

            val mirrorButton = JButton(scrcpyIcon).apply {
                preferredSize = Dimension(35, 25)
                toolTipText = "Mirror screen with scrcpy"
                isBorderPainted = false
                isFocusPainted = false
                isContentAreaFilled = isSelected || (hoveredCellIndex == index && hoveredButtonType == "MIRROR")
            }
            buttonPanel.add(mirrorButton)

            if (!wifiSerialRegex.matches(value.logicalSerialNumber)) {
                val wifiButton = JButton("Wi-Fi").apply {
                    preferredSize = Dimension(70, 25)
                    toolTipText = "Connect via Wi-Fi"
                    isFocusPainted = false
                    isContentAreaFilled = isSelected || (hoveredCellIndex == index && hoveredButtonType == "WIFI")
                }
                buttonPanel.add(wifiButton)
            } else {
                buttonPanel.add(Box.createRigidArea(Dimension(70, 25)))
            }

            mainPanel.add(buttonPanel, BorderLayout.EAST)
            return mainPanel
        }
    }
}