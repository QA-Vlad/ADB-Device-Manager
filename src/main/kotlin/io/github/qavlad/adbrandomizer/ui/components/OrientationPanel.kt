package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import javax.swing.*
import javax.swing.event.TableColumnModelListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent

/**
 * Панель с иконками ориентации, которая отслеживает позицию колонки Size
 */
class OrientationPanel(private val table: JTable) : JPanel() {
    
    private val portraitIcon = IconLoader.getIcon("/icons/mobile.svg", OrientationPanel::class.java)
    private val landscapeIcon = IconLoader.getIcon("/icons/mobile_landscape.svg", OrientationPanel::class.java)
    
    private val portraitButton = JButton(portraitIcon).apply {
        preferredSize = Dimension(JBUIScale.scale(24), JBUIScale.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "Switch all to portrait orientation"
        
        // Убираем все визуальные эффекты чекбокса
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        
        // Добавляем эффект при наведении
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getDefaultCursor()
            }
        })
        
        addActionListener { 
            PluginLogger.debug(LogCategory.UI_EVENTS, "Portrait orientation clicked")
            // TODO: Implement orientation switch logic
        }
    }
    
    private val landscapeButton = JButton(landscapeIcon).apply {
        preferredSize = Dimension(JBUIScale.scale(24), JBUIScale.scale(24))
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "Switch all to landscape orientation"
        
        // Убираем все визуальные эффекты чекбокса
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        
        // Добавляем эффект при наведении
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                cursor = Cursor.getDefaultCursor()
            }
        })
        
        addActionListener { 
            PluginLogger.debug(LogCategory.UI_EVENTS, "Landscape orientation clicked")
            // TODO: Implement orientation switch logic
        }
    }
    
    init {
        layout = null // Используем абсолютное позиционирование
        preferredSize = Dimension(0, JBUIScale.scale(32))
        minimumSize = Dimension(0, JBUIScale.scale(32))
        maximumSize = Dimension(Integer.MAX_VALUE, JBUIScale.scale(32))
        border = JBUI.Borders.empty(4, 0)
        
        add(portraitButton)
        add(landscapeButton)
        
        // Инициализация позиции и слушателей
        SwingUtilities.invokeLater {
            updateIconsPosition()
            setupColumnListener()
        }
    }
    
    private fun setupColumnListener() {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnAdded(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnRemoved(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnMoved(e: TableColumnModelEvent?) = updateIconsPosition()
            override fun columnMarginChanged(e: ChangeEvent?) = updateIconsPosition()
            override fun columnSelectionChanged(e: ListSelectionEvent?) {}
        })
    }
    
    private fun updateIconsPosition() {
        SwingUtilities.invokeLater {
            if (table.columnModel.columnCount > 3) {
                // Находим колонку Size (индекс 3)
                val sizeColumnIndex = 3
                var sizeColumnX = 0
                
                // Вычисляем X позицию колонки Size
                for (i in 0 until sizeColumnIndex) {
                    sizeColumnX += table.columnModel.getColumn(i).width
                }
                
                val sizeColumn = table.columnModel.getColumn(sizeColumnIndex)
                val sizeColumnWidth = sizeColumn.width
                
                // Выравниваем иконки по левому краю колонки Size
                val leftMargin = JBUIScale.scale(8) // Небольшой отступ от левого края
                val x = sizeColumnX + leftMargin
                val y = JBUIScale.scale(4)
                
                // Позиционируем кнопки
                portraitButton.setBounds(x, y, JBUIScale.scale(24), JBUIScale.scale(24))
                landscapeButton.setBounds(x + JBUIScale.scale(28), y, JBUIScale.scale(24), JBUIScale.scale(24))
                
                PluginLogger.debug(LogCategory.UI_EVENTS, 
                    "Icons positioned: sizeColumnX=$sizeColumnX, width=$sizeColumnWidth, x=$x")
                
                repaint()
            }
        }
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        // Для отладки - рисуем границы панели (закомментировано)
        // g.color = Color.RED.withAlpha(30)
        // g.fillRect(0, 0, width, height)
    }

}