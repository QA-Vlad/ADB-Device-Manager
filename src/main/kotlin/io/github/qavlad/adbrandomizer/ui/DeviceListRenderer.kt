// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DeviceListRenderer.kt
package io.github.qavlad.adbrandomizer.ui

import com.android.ddmlib.IDevice
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import java.awt.*
import javax.swing.*

/**
 * Рендерер для списка устройств в AdbControlsPanel
 */
class DeviceListRenderer(
    private val hoveredCellIndex: () -> Int,
    private val hoveredButtonType: () -> String?,
    private val onMirrorClick: (DeviceInfo) -> Unit,
    private val onWifiClick: (IDevice) -> Unit
) : ListCellRenderer<DeviceInfo> {

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

    companion object {
        private val wifiSerialRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")
    }

    override fun getListCellRendererComponent(
        list: JList<out DeviceInfo>?, value: DeviceInfo?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val mainPanel = JPanel(BorderLayout(10, 0)).apply {
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            // Hover только на кнопках, НЕ на всей области
            background = list?.background ?: JBColor.background()
            isOpaque = true
        }

        if (value == null || list == null) return mainPanel

        val connectionIcon = if (wifiSerialRegex.matches(value.logicalSerialNumber)) wifiIcon else usbIcon
        mainPanel.add(JLabel(connectionIcon), BorderLayout.WEST)

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val serialForDisplay = value.displaySerialNumber ?: value.logicalSerialNumber
        val nameLabel = JLabel("${value.displayName} ($serialForDisplay)").apply {
            foreground = list.foreground
            alignmentX = Component.LEFT_ALIGNMENT
        }
        textPanel.add(nameLabel)

        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val ipInfo = if (wifiSerialRegex.matches(value.logicalSerialNumber)) {
            value.logicalSerialNumber
        } else {
            if (value.ipAddress != null) {
                "${value.ipAddress}:5555"
            } else {
                "IP not available"
            }
        }
        val infoText = "Android ${value.androidVersion} (API ${value.apiLevel}) - $ipInfo"

        val infoLabel = JLabel(infoText).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
        }
        infoPanel.add(infoLabel)

        if (!wifiSerialRegex.matches(value.logicalSerialNumber)) {
            val model = list.model
            var wifiConnectionExists = false
            for (i in 0 until model.size) {
                val otherDevice = model.getElementAt(i)
                if (value.isSamePhysicalDevice(otherDevice) && wifiSerialRegex.matches(otherDevice.logicalSerialNumber)) {
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

        // Создаем кнопки как отдельные компоненты с правильными границами
        val buttonPanel = createButtonPanel(value, index)
        mainPanel.add(buttonPanel, BorderLayout.EAST)

        return mainPanel
    }

    private fun createButtonPanel(deviceInfo: DeviceInfo, index: Int): JComponent {
        val panel = JPanel().apply {
            layout = FlowLayout(FlowLayout.RIGHT, 5, 0)
            isOpaque = false
            preferredSize = Dimension(115, 30)
        }

        // Кнопка Mirror
        val mirrorButton = JButton(scrcpyIcon).apply {
            preferredSize = Dimension(35, 25)
            toolTipText = "Mirror screen with scrcpy"
            isBorderPainted = false
            isFocusPainted = false

            // Hover эффект
            isContentAreaFilled = (hoveredCellIndex() == index && hoveredButtonType() == "MIRROR")
            background = if (hoveredCellIndex() == index && hoveredButtonType() == "MIRROR") {
                JBColor.LIGHT_GRAY
            } else {
                UIManager.getColor("Button.background")
            }
            addActionListener {
                onMirrorClick(deviceInfo)
            }
        }
        panel.add(mirrorButton)

        // Кнопка Wi-Fi (только для USB устройств)
        if (!wifiSerialRegex.matches(deviceInfo.logicalSerialNumber)) {
            val wifiButton = JButton("Wi-Fi").apply {
                preferredSize = Dimension(70, 25)
                toolTipText = "Connect via Wi-Fi"
                isFocusPainted = false

                // Hover эффект
                isContentAreaFilled = (hoveredCellIndex() == index && hoveredButtonType() == "WIFI")
                background = if (hoveredCellIndex() == index && hoveredButtonType() == "WIFI") {
                    JBColor.LIGHT_GRAY
                } else {
                    UIManager.getColor("Button.background")
                }
                addActionListener {
                    onWifiClick(deviceInfo.device)
                }
            }
            panel.add(wifiButton)
        } else {
            panel.add(Box.createRigidArea(Dimension(70, 25)))
        }

        return panel
    }
}

/**
 * Data class для хранения информации об устройстве
 */
data class DeviceInfo(
    val device: IDevice,
    val displayName: String,
    val displaySerialNumber: String?,  // Теперь может быть null
    val logicalSerialNumber: String,
    val androidVersion: String,
    val apiLevel: String,
    val ipAddress: String?
) {
    companion object {
        private val wifiSerialRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceInfo
        return logicalSerialNumber == other.logicalSerialNumber
    }

    override fun hashCode(): Int = logicalSerialNumber.hashCode()

    // Метод для безопасного сравнения устройств
    fun isSamePhysicalDevice(other: DeviceInfo): Boolean {
        // Если оба серийных номера не null и не пустые
        if (!displaySerialNumber.isNullOrBlank() && !other.displaySerialNumber.isNullOrBlank()) {
            return displaySerialNumber == other.displaySerialNumber
        }

        // Если один из них Wi-Fi, а другой USB, но с похожими именами
        if (wifiSerialRegex.matches(logicalSerialNumber) != wifiSerialRegex.matches(other.logicalSerialNumber)) {
            return displayName == other.displayName && androidVersion == other.androidVersion
        }

        return false
    }
}