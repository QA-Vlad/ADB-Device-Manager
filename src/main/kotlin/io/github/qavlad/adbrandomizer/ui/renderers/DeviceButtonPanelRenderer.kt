package io.github.qavlad.adbrandomizer.ui.renderers

import com.android.ddmlib.IDevice
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import io.github.qavlad.adbrandomizer.services.DeviceInfo
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import java.awt.*
import javax.swing.*

/**
 * Рендерер для панели кнопок в ячейке устройства
 */
class DeviceButtonPanelRenderer {

    private val scrcpyIcon: Icon = IconLoader.getIcon("/icons/scrcpy.svg", javaClass)

    /**
     * Создает панель с кнопками для устройства
     */
    fun createButtonPanel(
        deviceInfo: DeviceInfo,
        index: Int,
        hoverState: HoverState,
        onMirrorClick: (DeviceInfo) -> Unit,
        onWifiClick: (IDevice) -> Unit
    ): JComponent {
        val panel = JPanel().apply {
            layout = FlowLayout(FlowLayout.RIGHT, 5, 0)
            isOpaque = false
            preferredSize = Dimension(115, 30)
        }

        // Кнопка Mirror
        val mirrorButton = createMirrorButton(deviceInfo, index, hoverState, onMirrorClick)
        panel.add(mirrorButton)

        // Кнопка Wi-Fi (только для USB устройств)
        val wifiButton = createWifiButtonIfNeeded(deviceInfo, index, hoverState, onWifiClick)
        if (wifiButton != null) {
            panel.add(wifiButton)
        } else {
            // Добавляем пустое место для выравнивания
            panel.add(Box.createRigidArea(Dimension(70, 25)))
        }

        return panel
    }

    /**
     * Создает кнопку для запуска screen mirroring
     */
    private fun createMirrorButton(
        deviceInfo: DeviceInfo,
        index: Int,
        hoverState: HoverState,
        onMirrorClick: (DeviceInfo) -> Unit
    ): JButton {
        return JButton(scrcpyIcon).apply {
            preferredSize = Dimension(35, 25)
            toolTipText = "Mirror screen with scrcpy"
            isBorderPainted = false
            isFocusPainted = false

            // Применяем hover эффект
            applyHoverEffect(this, index, HoverState.BUTTON_TYPE_MIRROR, hoverState)

            addActionListener {
                onMirrorClick(deviceInfo)
            }
        }
    }

    /**
     * Создает кнопку Wi-Fi подключения для USB устройств
     */
    private fun createWifiButtonIfNeeded(
        deviceInfo: DeviceInfo,
        index: Int,
        hoverState: HoverState,
        onWifiClick: (IDevice) -> Unit
    ): JButton? {
        if (DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)) {
            return null // Wi-Fi устройства не нуждаются в Wi-Fi кнопке
        }

        return JButton("Wi-Fi").apply {
            preferredSize = Dimension(70, 25)
            toolTipText = "Connect via Wi-Fi"
            isFocusPainted = false

            // Применяем hover эффект
            applyHoverEffect(this, index, HoverState.BUTTON_TYPE_WIFI, hoverState)

            addActionListener {
                onWifiClick(deviceInfo.device)
            }
        }
    }

    /**
     * Применяет hover эффект к кнопке
     */
    private fun applyHoverEffect(
        button: JButton,
        index: Int,
        buttonType: String,
        hoverState: HoverState
    ) {
        val isHovered = hoverState.isDeviceButtonHovered(index, buttonType)

        button.isContentAreaFilled = isHovered
        button.background = if (isHovered) {
            JBColor.LIGHT_GRAY
        } else {
            UIManager.getColor("Button.background")
        }
    }
}