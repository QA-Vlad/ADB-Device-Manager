package io.github.qavlad.adbrandomizer.ui.renderers

import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.services.DeviceInfo
import io.github.qavlad.adbrandomizer.ui.components.HoverState
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

