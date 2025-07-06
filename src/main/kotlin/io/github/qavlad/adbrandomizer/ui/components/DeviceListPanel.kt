package io.github.qavlad.adbrandomizer.ui.components

import com.android.ddmlib.IDevice
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.qavlad.adbrandomizer.services.DeviceInfo
import io.github.qavlad.adbrandomizer.ui.renderers.DeviceListRenderer
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*

class DeviceListPanel(
    private val getHoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val getAllDevices: () -> List<DeviceInfo>,
    private val onMirrorClick: (DeviceInfo) -> Unit,
    private val onWifiClick: (IDevice) -> Unit
) : JPanel(BorderLayout()) {

    private val deviceListModel = DefaultListModel<DeviceInfo>()
    private val deviceList = JBList(deviceListModel)

    init {
        setupUI()
        setupDeviceListInteractions()
    }

    private fun setupUI() {
        border = BorderFactory.createTitledBorder("Connected Devices")
        
        deviceList.cellRenderer = DeviceListRenderer(
            getHoverState = getHoverState,
            getAllDevices = getAllDevices,
            onMirrorClick = onMirrorClick,
            onWifiClick = onWifiClick
        )

        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        deviceList.emptyText.text = "No devices connected"
        deviceList.clearSelection()
        deviceList.selectionModel.clearSelection()

        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        add(scrollPane, BorderLayout.CENTER)
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

        val currentHoverState = getHoverState()
        val newHoverState = if (index != -1 && newButtonType != null) {
            HoverState(hoveredDeviceIndex = index, hoveredButtonType = newButtonType)
        } else {
            HoverState.noHover()
        }

        if (currentHoverState != newHoverState) {
            setHoverState(newHoverState)
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
                buttonLayout.mirrorButtonRect.contains(e.point) -> onMirrorClick(deviceInfo)
                buttonLayout.wifiButtonRect?.contains(e.point) == true -> onWifiClick(deviceInfo.device)
            }
        }

        deviceList.clearSelection()
    }

    private fun resetHoverState() {
        val currentHoverState = getHoverState()
        if (currentHoverState.hoveredDeviceIndex != -1 && currentHoverState.hoveredButtonType != null) {
            setHoverState(HoverState.noHover())
            deviceList.cursor = Cursor.getDefaultCursor()
            deviceList.repaint()
        }
    }

    fun updateDeviceList(devices: List<DeviceInfo>) {
        SwingUtilities.invokeLater {
            val selectedValue = deviceList.selectedValue
            val currentSerials = (0 until deviceListModel.size()).map { deviceListModel.getElementAt(it).logicalSerialNumber }.toSet()
            val newSerials = devices.map { it.logicalSerialNumber }.toSet()

            // Удаляем исчезнувшие устройства
            removeDisconnectedDevices(currentSerials, newSerials)

            // Добавляем или обновляем устройства
            updateConnectedDevices(devices)

            // Восстанавливаем выделение
            restoreSelection(selectedValue)
        }
    }

    private fun removeDisconnectedDevices(currentSerials: Set<String>, newSerials: Set<String>) {
        (currentSerials - newSerials).forEach { serialToRemove ->
            val elementToRemove = (0 until deviceListModel.size()).find {
                deviceListModel.getElementAt(it).logicalSerialNumber == serialToRemove
            }
            if (elementToRemove != null) {
                deviceListModel.removeElementAt(elementToRemove)
            }
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

    fun getDeviceListModel(): DefaultListModel<DeviceInfo> = deviceListModel
}