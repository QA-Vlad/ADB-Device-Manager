// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DeviceInfoPanelRenderer.kt
package io.github.qavlad.adbrandomizer.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBFont
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.DeviceDisplayInfo
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*

/**
 * Рендерер для информационной части ячейки устройства
 */
class DeviceInfoPanelRenderer {

    private val usbIcon: Icon = IconLoader.getIcon("/icons/usb.svg", javaClass)
    private val wifiIcon: Icon = IconLoader.getIcon("/icons/wifi.svg", javaClass)

    private var cachedSmallWifiIcon: Icon? = null

    /**
     * Создает панель с информацией об устройстве
     */
    fun createInfoPanel(
        deviceInfo: DeviceInfo,
        allDevices: List<DeviceInfo>,
        listForeground: Color
    ): JPanel {
        val mainPanel = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
        }

        val displayInfo = DeviceConnectionUtils.formatDeviceInfo(deviceInfo)

        // Иконка типа подключения
        val connectionIcon = if (displayInfo.isWifiConnection) wifiIcon else usbIcon
        mainPanel.add(JLabel(connectionIcon), BorderLayout.WEST)

        // Текстовая информация
        val textPanel = createTextPanel(displayInfo, listForeground)
        mainPanel.add(textPanel, BorderLayout.CENTER)

        // Индикатор дополнительного Wi-Fi подключения
        addWifiIndicatorIfNeeded(textPanel, deviceInfo, allDevices)

        return mainPanel
    }

    /**
     * Создает панель с текстовой информацией
     */
    private fun createTextPanel(displayInfo: DeviceDisplayInfo, textForeground: Color): JPanel {
        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Основное название устройства
        val nameLabel = JLabel(displayInfo.title).apply {
            foreground = textForeground
            alignmentX = Component.LEFT_ALIGNMENT
        }
        textPanel.add(nameLabel)

        // Техническая информация
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val infoLabel = JLabel(displayInfo.subtitle).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
        }
        infoPanel.add(infoLabel)

        textPanel.add(infoPanel)

        return textPanel
    }

    /**
     * Добавляет индикатор Wi-Fi подключения, если устройство имеет дополнительное Wi-Fi соединение
     */
    private fun addWifiIndicatorIfNeeded(
        textPanel: JPanel,
        deviceInfo: DeviceInfo,
        allDevices: List<DeviceInfo>
    ) {
        if (DeviceConnectionUtils.hasWifiConnectionForDevice(deviceInfo, allDevices)) {
            if (cachedSmallWifiIcon == null) {
                cachedSmallWifiIcon = createSmallWifiIcon()
            }
            val infoPanel = textPanel.components.last() as JPanel
            infoPanel.add(Box.createHorizontalStrut(4))
            infoPanel.add(JLabel(cachedSmallWifiIcon))
        }
    }

    /**
     * Создает уменьшенную версию Wi-Fi иконки
     */
    private fun createSmallWifiIcon(): Icon {
        // Создаем изображение с помощью современного ImageUtil API
        val originalImage = ImageUtil.createImage(
            wifiIcon.iconWidth,
            wifiIcon.iconHeight,
            BufferedImage.TYPE_INT_ARGB
        )
        val g2d = originalImage.createGraphics()

        // Применяем антиалиасинг для лучшего качества
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        wifiIcon.paintIcon(null, g2d, 0, 0)
        g2d.dispose()

        // Используем ImageUtil для масштабирования (поддерживает HiDPI)
        val scaledImage = ImageUtil.scaleImage(originalImage, 12, 12)
        return ImageIcon(scaledImage)
    }
}