package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Компактная панель с иконками для быстрых действий
 */
class CompactActionPanel(
    private val onConnectDevice: () -> Unit,
    private val onKillAdbServer: () -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)) {

    init {
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(0, 4)
        isOpaque = false
        
        // Кнопка подключения устройства (плюсик)
        val connectButton = createIconButton(
            icon = AllIcons.General.Add,
            tooltip = "Connect Device",
            action = onConnectDevice
        )
        
        // Кнопка перезапуска ADB сервера (обновление)
        val refreshButton = createIconButton(
            icon = AllIcons.Actions.Refresh,
            tooltip = "Kill ADB Server",
            action = onKillAdbServer
        )
        
        add(connectButton)
        add(refreshButton)
    }
    
    private fun createIconButton(
        icon: Icon,
        tooltip: String,
        action: () -> Unit
    ): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            minimumSize = preferredSize
            maximumSize = preferredSize
            
            // Минималистичная стилизация
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            
            addActionListener { action() }
            
            // Добавляем hover эффект
            ButtonUtils.addHoverEffect(this)
        }
    }
}
