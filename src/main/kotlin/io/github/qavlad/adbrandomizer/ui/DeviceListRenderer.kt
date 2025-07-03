// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DeviceListRenderer.kt
package io.github.qavlad.adbrandomizer.ui

import com.android.ddmlib.IDevice
import java.awt.*
import javax.swing.*

/**
 * Главный рендерер для списка устройств
 * Координирует работу компонентов и собирает финальный UI
 */
class DeviceListRenderer(
    private val getHoverState: () -> HoverState,
    private val getAllDevices: () -> List<DeviceInfo>,
    private val onMirrorClick: (DeviceInfo) -> Unit,
    private val onWifiClick: (IDevice) -> Unit
) : ListCellRenderer<DeviceInfo> {

    private val deviceInfoRenderer = DeviceInfoPanelRenderer()
    private val deviceButtonRenderer = DeviceButtonPanelRenderer()

    override fun getListCellRendererComponent(
        list: JList<out DeviceInfo>?,
        value: DeviceInfo?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {

        val mainPanel = JPanel(BorderLayout(10, 0)).apply {
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            background = list?.background ?: UIManager.getColor("List.background")
            isOpaque = true
        }

        // Проверяем валидность данных
        if (value == null || list == null) {
            return mainPanel
        }

        val hoverState = getHoverState()
        val allDevices = getAllDevices()

        // Создаем панель с информацией об устройстве
        val infoPanel = deviceInfoRenderer.createInfoPanel(
            deviceInfo = value,
            allDevices = allDevices,
            listForeground = list.foreground
        )
        mainPanel.add(infoPanel, BorderLayout.CENTER)

        // Создаем панель с кнопками
        val buttonPanel = deviceButtonRenderer.createButtonPanel(
            deviceInfo = value,
            index = index,
            hoverState = hoverState,
            onMirrorClick = onMirrorClick,
            onWifiClick = onWifiClick
        )
        mainPanel.add(buttonPanel, BorderLayout.EAST)

        return mainPanel
    }
}

/**
 * Data class для хранения информации об устройстве
 * Остается без изменений, но перенесен в отдельный файл для лучшей организации
 */
data class DeviceInfo(
    val device: IDevice,
    val displayName: String,
    val displaySerialNumber: String?,
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

    /**
     * Метод для безопасного сравнения устройств
     */
    fun isSamePhysicalDevice(other: DeviceInfo): Boolean {
        // Если оба серийных номера не null и не пустые
        if (!displaySerialNumber.isNullOrBlank() && !other.displaySerialNumber.isNullOrBlank()) {
            return displaySerialNumber == other.displaySerialNumber
        }

        // Если один из них Wi-Fi, а другой USB, но с похожими именами
        val thisIsWifi = logicalSerialNumber.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$"""))
        val otherIsWifi = other.logicalSerialNumber.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$"""))

        if (thisIsWifi != otherIsWifi) {
            return displayName == other.displayName && androidVersion == other.androidVersion
        }

        return false
    }
}