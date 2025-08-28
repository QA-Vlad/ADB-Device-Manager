package io.github.qavlad.adbdevicemanager.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.TableCellRenderer

/**
 * Рендерер для заголовка колонки "List" с иконкой папки и вертикальной линией
 */
class ListColumnHeaderRenderer : TableCellRenderer {
    private val panel = JPanel(BorderLayout())
    private val label = JLabel()
    
    init {
        label.horizontalAlignment = SwingConstants.LEFT // Выравнивание по левому краю
        label.icon = AllIcons.Nodes.Folder
        label.iconTextGap = JBUI.scale(4)
        label.border = BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, JBUI.scale(4))
        
        panel.add(label, BorderLayout.CENTER)
        // Добавляем вертикальную линию слева через рамку
        panel.border = BorderFactory.createCompoundBorder(
            VerticalLineHeaderBorder(),
            UIManager.getBorder("TableHeader.cellBorder")
        )
    }
    
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        label.text = value?.toString() ?: ""
        label.background = UIManager.getColor("TableHeader.background")
        label.foreground = UIManager.getColor("TableHeader.foreground")
        label.font = table.tableHeader.font
        label.isOpaque = true
        
        panel.background = UIManager.getColor("TableHeader.background")
        panel.isOpaque = true
        
        return panel
    }
}

/**
 * Кастомная рамка с вертикальной линией слева для заголовка
 */
class VerticalLineHeaderBorder : javax.swing.border.AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create()
        try {
            // Используем цвет разделителя из UIManager
            g2.color = UIManager.getColor("Separator.foreground") ?: JBColor.GRAY
            // Рисуем толстую вертикальную линию слева
            g2.fillRect(x, y, JBUI.scale(2), height)
        } finally {
            g2.dispose()
        }
    }
    
    override fun getBorderInsets(c: Component): java.awt.Insets {
        return JBUI.insetsLeft(2)
    }
    
    override fun isBorderOpaque(): Boolean = true
}