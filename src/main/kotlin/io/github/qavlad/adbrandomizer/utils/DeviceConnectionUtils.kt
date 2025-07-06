package io.github.qavlad.adbrandomizer.utils

import io.github.qavlad.adbrandomizer.services.DeviceInfo
import java.awt.Rectangle

object DeviceConnectionUtils {

    private val wifiSerialRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")

    /**
     * Проверяет, является ли подключение Wi-Fi на основе серийного номера
     */
    fun isWifiConnection(serialNumber: String): Boolean {
        return wifiSerialRegex.matches(serialNumber)
    }

    /**
     * Форматирует информацию об устройстве для отображения
     */
    fun formatDeviceInfo(deviceInfo: DeviceInfo): DeviceDisplayInfo {
        val ipInfo = if (isWifiConnection(deviceInfo.logicalSerialNumber)) {
            deviceInfo.logicalSerialNumber
        } else {
            if (deviceInfo.ipAddress != null) {
                "${deviceInfo.ipAddress}:5555"
            } else {
                "IP not available"
            }
        }

        val serialForDisplay = deviceInfo.displaySerialNumber ?: deviceInfo.logicalSerialNumber
        val deviceTitle = "${deviceInfo.displayName} ($serialForDisplay)"
        val deviceSubtitle = "Android ${deviceInfo.androidVersion} (API ${deviceInfo.apiLevel}) - $ipInfo"

        return DeviceDisplayInfo(
            title = deviceTitle,
            subtitle = deviceSubtitle,
            isWifiConnection = isWifiConnection(deviceInfo.logicalSerialNumber)
        )
    }

    /**
     * Проверяет, существует ли Wi-Fi подключение для того же физического устройства
     */
    fun hasWifiConnectionForDevice(deviceInfo: DeviceInfo, allDevices: List<DeviceInfo>): Boolean {
        if (isWifiConnection(deviceInfo.logicalSerialNumber)) {
            return false // Само устройство уже Wi-Fi
        }

        return allDevices.any { otherDevice ->
            // Убрали isSamePhysicalDevice, так как его нет в предоставленном коде.
            // Сравнение по displaySerialNumber - разумная альтернатива.
            (deviceInfo.displaySerialNumber != null && deviceInfo.displaySerialNumber == otherDevice.displaySerialNumber) &&
                    isWifiConnection(otherDevice.logicalSerialNumber)
        }
    }

    /**
     * Вычисляет расположение кнопок в ячейке списка
     */
    fun calculateButtonLayout(cellBounds: Rectangle, isWifiDevice: Boolean): ButtonLayout {
        val buttonPanelWidth = 115
        val buttonSpacing = 5
        val mirrorButtonWidth = 35
        val wifiButtonWidth = 70
        val buttonHeight = 25

        val buttonY = cellBounds.y + (cellBounds.height - buttonHeight) / 2
        val buttonPanelX = cellBounds.x + cellBounds.width - buttonPanelWidth
        val mirrorButtonX = buttonPanelX + buttonSpacing
        val mirrorButtonRect = Rectangle(mirrorButtonX, buttonY, mirrorButtonWidth, buttonHeight)

        val wifiButtonRect = if (!isWifiDevice) {
            val wifiButtonX = mirrorButtonX + mirrorButtonWidth + buttonSpacing
            Rectangle(wifiButtonX, buttonY, wifiButtonWidth, buttonHeight)
        } else {
            null
        }

        return ButtonLayout(mirrorButtonRect, wifiButtonRect)
    }
}

/**
 * Данные для отображения информации об устройстве
 */
data class DeviceDisplayInfo(
    val title: String,
    val subtitle: String,
    val isWifiConnection: Boolean
)

/**
 * Расположение кнопок в ячейке
 */
data class ButtonLayout(
    val mirrorButtonRect: Rectangle,
    val wifiButtonRect: Rectangle?
)