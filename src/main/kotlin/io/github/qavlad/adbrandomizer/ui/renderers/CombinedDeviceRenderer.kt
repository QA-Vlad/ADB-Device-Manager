package io.github.qavlad.adbrandomizer.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.models.CombinedDeviceInfo
import java.awt.*
import javax.swing.*

/**
 * Рендерер для объединённых устройств (USB + Wi-Fi)
 */
class CombinedDeviceRenderer(
    private val getHoverState: () -> HoverState
) {
    companion object {
        private const val BUTTON_HEIGHT = 22
        private const val SECTION_SPACING = 15
    }

    private val usbIcon: Icon = IconLoader.getIcon("/icons/usb.svg", javaClass)
    private val wifiIcon: Icon = IconLoader.getIcon("/icons/wifi.svg", javaClass)
    private val mirrorIcon = IconLoader.getIcon("/icons/scrcpy.svg", javaClass)
    private val disconnectIcon = AllIcons.Actions.Exit

    fun createComponent(
        device: CombinedDeviceInfo,
        index: Int,
        isSelected: Boolean,
        list: JList<*>
    ): Component {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            background = if (isSelected) list.selectionBackground else list.background
            isOpaque = true
        }

        // Первая строка - название устройства и серийный номер
        val namePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 20)
        }
        
        val nameText = "${device.displayName} (${device.baseSerialNumber})"
        val nameLabel = JLabel(nameText).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }
        namePanel.add(nameLabel)
        panel.add(namePanel)

        // Вторая строка - информация об Android, IP адресе (если есть) и параметрах экрана
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 18)
        }
        
        val infoText = buildString {
            append("Android ${device.androidVersion} (API ${device.apiLevel})")
            // Добавляем IP адрес только если есть USB подключение без Wi-Fi
            // (для устройств только с USB, чтобы видеть их IP для подключения по Wi-Fi)
            if (device.hasUsbConnection && !device.hasWifiConnection) {
                device.ipAddress?.let {
                    append(" • $it")
                }
            }
            device.getFormattedScreenParams()?.let {
                append(" • $it")
            }
        }
        
        val infoLabel = JLabel(infoText).apply {
            font = JBFont.small()
            foreground = if (device.hasModifiedResolution || device.hasModifiedDpi) {
                JBColor(Color(255, 140, 0), Color(255, 160, 0)) // Оранжевый цвет для изменённых параметров
            } else {
                JBColor.GRAY
            }
        }
        infoPanel.add(infoLabel)
        panel.add(infoPanel)

        // Третья строка - кнопки управления подключениями
        val controlsPanel = createControlsPanel(device, index)
        panel.add(Box.createVerticalStrut(4))
        panel.add(controlsPanel)

        return panel
    }

    private fun createControlsPanel(device: CombinedDeviceInfo, index: Int): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, SECTION_SPACING, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, BUTTON_HEIGHT + 4)
        }

        val hoverState = getHoverState()
        val isThisDevice = hoverState.hoveredDeviceIndex == index

        // USB секция
        if (device.hasUsbConnection) {
            val usbPanel = createConnectionSection(
                usbIcon,
                "USB",
                true,
                showMirror = true,
                showConnect = false, // Убираем кнопку Wi-Fi из USB секции
                showDisconnect = false,
                isMirrorHovered = isThisDevice && hoverState.hoveredButtonType == "USB_MIRROR",
                isConnectHovered = false,
                isDisconnectHovered = false
            )
            panel.add(usbPanel)
        } else {
            val usbPanel = createConnectionSection(
                usbIcon,
                "USB",
                false,
                showMirror = false,
                showConnect = false,
                showDisconnect = false,
                isMirrorHovered = false,
                isConnectHovered = false,
                isDisconnectHovered = false
            )
            panel.add(usbPanel)
        }

        // Разделитель
        panel.add(createSeparator())

        // Wi-Fi секция
        if (device.hasWifiConnection) {
            val wifiPanel = createConnectionSection(
                wifiIcon,
                device.wifiDevice?.ipAddress ?: device.ipAddress ?: "Wi-Fi",
                true,
                showMirror = true,
                showConnect = false,
                showDisconnect = true,
                isMirrorHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_MIRROR",
                isConnectHovered = false,
                isDisconnectHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_DISCONNECT"
            )
            panel.add(wifiPanel)
        } else {
            // Если есть USB подключение, но нет Wi-Fi - показываем кнопку Connect
            val showConnectButton = device.hasUsbConnection
            val wifiPanel = createConnectionSection(
                wifiIcon,
                "Wi-Fi",
                false,
                showMirror = false,
                showConnect = showConnectButton,
                showDisconnect = false,
                isMirrorHovered = false,
                isConnectHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_CONNECT",
                isDisconnectHovered = false
            )
            panel.add(wifiPanel)
        }

        return panel
    }

    private fun createConnectionSection(
        icon: Icon,
        text: String,
        isActive: Boolean,
        showMirror: Boolean,
        showConnect: Boolean,
        showDisconnect: Boolean,
        isMirrorHovered: Boolean,
        isConnectHovered: Boolean,
        isDisconnectHovered: Boolean
    ): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        // Иконка подключения
        val iconLabel = JLabel(icon).apply {
            isEnabled = isActive
        }
        panel.add(iconLabel)

        // Текст (IP для Wi-Fi или "USB")
        val textLabel = JLabel(text).apply {
            font = JBFont.small()
            foreground = if (isActive) JBColor.foreground() else JBColor.GRAY
        }
        panel.add(textLabel)

        // Кнопка Mirror
        if (showMirror) {
            val mirrorButton = createIconButton(mirrorIcon, "Mirror", isMirrorHovered)
            panel.add(mirrorButton)
        }

        // Кнопка Connect Wi-Fi
        if (showConnect) {
            val connectButton = createConnectButton(isConnectHovered)
            panel.add(connectButton)
        }

        // Кнопка Disconnect (только для Wi-Fi)
        if (showDisconnect) {
            val disconnectButton = createIconButton(disconnectIcon, "Disconnect", isDisconnectHovered)
            panel.add(disconnectButton)
        }

        return panel
    }

    private fun createIconButton(icon: Icon, tooltip: String, isHovered: Boolean): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(BUTTON_HEIGHT, BUTTON_HEIGHT)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            isContentAreaFilled = isHovered
            isBorderPainted = isHovered
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            if (isHovered) {
                background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
        }
    }
    
    private fun createConnectButton(isHovered: Boolean): JButton {
        return JButton("Connect").apply {
            toolTipText = "Connect via Wi-Fi"
            font = JBFont.small()
            preferredSize = Dimension(55, BUTTON_HEIGHT)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            isContentAreaFilled = true
            isBorderPainted = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            if (isHovered) {
                background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
        }
    }

    private fun createSeparator(): JLabel {
        return JLabel("|").apply {
            foreground = JBColor.GRAY
        }
    }

    /**
     * Вычисляет области кнопок для обработки событий мыши
     * Важно: JList рендереры - это не настоящие компоненты, а просто "штампы" для рисования.
     * Поэтому мы должны вручную вычислять позиции кнопок для обработки кликов.
     */
    fun calculateButtonRects(
        device: CombinedDeviceInfo,
        cellBounds: Rectangle
    ): ButtonRects {
        val rects = ButtonRects()
        
        // Y позиция для всех кнопок - внизу ячейки с отступом
        val yOffset = cellBounds.y + cellBounds.height - BUTTON_HEIGHT - 10
        
        // Основываясь на ваших измерениях:
        // USB Mirror была на x=54, Wi-Fi Mirror на x=204, Disconnect на x=230
        // Добавляем корректировку вправо для точного попадания
        val xCorrectionUsb = 30
        val xCorrectionWifi = 35
        
        if (device.hasUsbConnection) {
            // USB Mirror: измерено 54, добавляем корректировку
            rects.usbMirrorRect = Rectangle(54 + xCorrectionUsb, yOffset, BUTTON_HEIGHT, BUTTON_HEIGHT)
        }
        
        if (device.hasWifiConnection) {
            rects.wifiMirrorRect = Rectangle(204 + xCorrectionWifi, yOffset, BUTTON_HEIGHT, BUTTON_HEIGHT)
            rects.wifiDisconnectRect = Rectangle(230 + xCorrectionWifi, yOffset, BUTTON_HEIGHT, BUTTON_HEIGHT)
        } else if (device.hasUsbConnection) {
            // Wi-Fi Connect: измерено 165, добавляем корректировку
            rects.wifiConnectRect = Rectangle(165 + xCorrectionUsb, yOffset, 55, BUTTON_HEIGHT)
        }
        
        return rects
    }

    data class ButtonRects(
        var usbMirrorRect: Rectangle? = null,
        var wifiConnectRect: Rectangle? = null,
        var wifiMirrorRect: Rectangle? = null,
        var wifiDisconnectRect: Rectangle? = null
    )
}