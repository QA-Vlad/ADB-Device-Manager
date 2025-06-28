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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.qavlad.adbrandomizer.services.AdbService
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.ScrcpyService
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale.getDefault
import javax.swing.*

class AdbControlsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var lastUsedPreset: DevicePreset? = null
    private var currentPresetIndex: Int = -1
    private val deviceListModel = DefaultListModel<DeviceInfo>()
    private val deviceList = JBList(deviceListModel)

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
        panel.add(createCenteredButton("PRESETS") { SettingsDialog(project).show() }) // <-- ИЗМЕНЕНИЕ ЗДЕСЬ
        return panel
    }

    private fun createDevicesPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("Connected Devices")

        deviceList.cellRenderer = DeviceListCellRenderer()
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices connected"

        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = deviceList.locationToIndex(e.point)
                if (index != -1) {
                    val deviceInfo = deviceListModel.getElementAt(index)
                    if (deviceInfo.device == null) return

                    val bounds = deviceList.getCellBounds(index, index)
                    val totalButtonsWidth = 125
                    val buttonsStartX = bounds.x + bounds.width - totalButtonsWidth

                    if (e.x >= buttonsStartX) {
                        val mirrorButtonEndX = buttonsStartX + 45
                        if (e.x <= mirrorButtonEndX) {
                            handleScrcpyMirror(deviceInfo.device)
                        } else {
                            if (!deviceInfo.device.serialNumber.contains(":")) {
                                handleWifiConnect(deviceInfo.device)
                            }
                        }
                    }
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
            val devices = AdbService.getConnectedDevices(project)
            ApplicationManager.getApplication().invokeLater {
                val selectedValue = deviceList.selectedValue
                deviceListModel.clear()
                devices.forEach { device ->
                    val deviceInfo = DeviceInfo(device)
                    deviceListModel.addElement(deviceInfo)
                    if (deviceInfo == selectedValue) {
                        deviceList.setSelectedValue(deviceInfo, true)
                    }
                }
            }
        }
    }

    private fun handleScrcpyMirror(device: IDevice) {
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
                    indicator.text = "Launching scrcpy for ${device.name}..."
                    ScrcpyService.launchScrcpy(scrcpyPath, device)
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
                    SwingUtilities.invokeLater {
                        updateDeviceList()
                    }

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
        if (presets.isEmpty()) {
            showNotification("No presets found in settings.")
            return
        }
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun handlePreviousPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            showNotification("No presets found in settings.")
            return
        }
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }
        val presets = SettingsService.getPresets()
        if (index < 0 || index >= presets.size) {
            showNotification("Invalid preset index.")
            return
        }
        val preset = presets[index]
        applyPreset(preset, index + 1, setSize = true, setDpi = true)
    }

    private fun applyPreset(preset: DevicePreset, presetNumber: Int, setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        object : Task.Backgroundable(project, "Applying preset") {
            override fun run(indicator: ProgressIndicator) {
                lastUsedPreset = preset
                val appliedSettings = mutableListOf<String>()
                var width: Int? = null
                var height: Int? = null
                if (setSize && preset.size.isNotBlank()) {
                    val parts = preset.size.split('x', 'X', 'х', 'Х').map { it.trim() }
                    width = parts.getOrNull(0)?.toIntOrNull()
                    height = parts.getOrNull(1)?.toIntOrNull()
                    if (width == null || height == null) {
                        showErrorNotification("Invalid size format in preset '${preset.label}': ${preset.size}")
                        return
                    }
                    appliedSettings.add("Size: ${preset.size}")
                }
                var dpi: Int? = null
                if (setDpi && preset.dpi.isNotBlank()) {
                    dpi = preset.dpi.trim().toIntOrNull()
                    if (dpi == null) {
                        showErrorNotification("Invalid DPI format in preset '${preset.label}': ${preset.dpi}")
                        return
                    }
                    appliedSettings.add("DPI: ${preset.dpi}")
                }
                if (appliedSettings.isEmpty()) {
                    showNotification("No settings to apply for preset '${preset.label}'")
                    return
                }
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
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
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
                showSuccessNotification("${resetItems.joinToString(" and ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }} reset for ${devices.size} device(s).")
            }
        }.queue()
    }

    private fun handleRandomAction(setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }
        var availablePresets = SettingsService.getPresets().filter {
            (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank())
        }
        if (availablePresets.size > 1 && lastUsedPreset != null) {
            availablePresets = availablePresets.filter { it != lastUsedPreset }
        }
        if (availablePresets.isEmpty()) {
            val allSuitablePresets = SettingsService.getPresets().filter {
                (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank())
            }
            if (allSuitablePresets.isNotEmpty()) {
                availablePresets = allSuitablePresets
            } else {
                showNotification("No suitable presets found in settings.")
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

    private fun showNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showSuccessNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showErrorNotification(message: String) = showPopup(message, NotificationType.ERROR)

    private fun showPopup(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("ADB Randomizer Notifications")
                .createNotification(message, type).notify(project)
        }
    }

    private data class DeviceInfo(val device: IDevice?) {
        override fun toString(): String {
            return device?.let {
                val name = it.name.replace("SAMSUNG-", "").replace("_", " ")
                "$name (${it.serialNumber})"
            } ?: "Unknown device"
        }
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DeviceInfo
            return device?.serialNumber == other.device?.serialNumber
        }
        override fun hashCode(): Int = device?.serialNumber?.hashCode() ?: 0
    }

    private inner class DeviceListCellRenderer : ListCellRenderer<DeviceInfo> {
        private val scrcpyIcon: Icon = IconLoader.getIcon("/icons/scrcpy.svg", DeviceListCellRenderer::class.java)

        override fun getListCellRendererComponent(list: JList<out DeviceInfo>?, value: DeviceInfo?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            val panel = JPanel(BorderLayout())
            panel.background = if (isSelected) list?.selectionBackground else list?.background
            panel.accessibleContext.accessibleName = "Device: ${value.toString()}"

            val label = JLabel(value.toString())
            label.foreground = if (isSelected) list?.selectionForeground else list?.foreground
            panel.add(label, BorderLayout.CENTER)

            value?.device?.let {
                val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
                buttonPanel.isOpaque = false

                val mirrorButton = JButton(scrcpyIcon)
                mirrorButton.toolTipText = "Mirror screen with scrcpy"
                mirrorButton.preferredSize = java.awt.Dimension(35, 25)
                ButtonUtils.addHoverEffect(mirrorButton)
                buttonPanel.add(mirrorButton)

                if (!it.serialNumber.contains(":")) {
                    val wifiButton = JButton("Wi-Fi")
                    wifiButton.toolTipText = "Connect via Wi-Fi"
                    wifiButton.preferredSize = java.awt.Dimension(70, 25)
                    ButtonUtils.addHoverEffect(wifiButton)
                    buttonPanel.add(wifiButton)
                }

                panel.add(buttonPanel, BorderLayout.EAST)
            }
            return panel
        }
    }
}