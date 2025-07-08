package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import java.awt.*
import javax.swing.*
import javax.swing.table.TableCellRenderer
import javax.swing.table.DefaultTableCellRenderer


/**
 * Комбинированный рендерер для первой колонки:
 * - Показывает кнопку добавления для строки с маркером "+"
 * - Показывает обычный drag-and-drop для остальных строк
 */
class FirstColumnCellRenderer(
    private val defaultRenderer: TableCellRenderer
) : DefaultTableCellRenderer() {
    
    // Иконка для плюсика
    private val addIcon = AllIcons.General.Add
    
    init {
        horizontalAlignment = CENTER
        isOpaque = true
    }
    
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        // Сначала вызываем родительский метод для базовой инициализации
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        
        // Если это строка с кнопкой добавления
        if (value == "+") {
            // Настраиваем рендерер для плюсика
            text = ""
            icon = addIcon
            toolTipText = "Add new preset"
            
            // НЕ применяем hover здесь - это делается в prepareRenderer()
            return this
        }
        
        // Для обычных строк используем стандартный рендерер с иконкой drag-and-drop
        return defaultRenderer.getTableCellRendererComponent(
            table, value, isSelected, hasFocus, row, column
        )
    }
}

/**
 * Кастомная панель для размещения таблицы с виртуальной строкой для кнопки добавления
 */
class TableWithAddButtonPanel(
    private val table: JTable,
    private val scrollPane: JScrollPane,
    private val onAddPreset: () -> Unit
) : JPanel(BorderLayout()) {
    
    private var showAddButton = true
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // Просто добавляем scrollPane с таблицей
        add(scrollPane, BorderLayout.CENTER)
        
        // Обработчик кликов по таблице
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                
                // Проверяем, что кликнули по ячейке с кнопкой
                if (row >= 0 && column == 0) {
                    val value = table.getValueAt(row, column)
                    if (value == "+") {
                        onAddPreset()
                    }
                }
            }
            
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val column = table.columnAtPoint(e.point)
                
                // Меняем курсор при наведении на кнопку - только для первой колонки
                if (row >= 0 && column == 0) {
                    val value = table.getValueAt(row, column)
                    if (value == "+") {
                        table.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        table.cursor = Cursor.getDefaultCursor()
                    }
                } else {
                    table.cursor = Cursor.getDefaultCursor()
                }
            }
        })
    }
    
    /**
     * Обновляет видимость кнопки добавления в зависимости от режима
     */
    fun setAddButtonVisible(visible: Boolean) {
        showAddButton = visible
        // Перерисовываем таблицу
        table.repaint()
    }
    
    /**
     * Обновляет позицию кнопки при изменении количества строк
     */
    fun updateButtonPosition(forceScroll: Boolean = false) {
        SwingUtilities.invokeLater {
            // Автоскролл к последней строке
            if (forceScroll && table.rowCount > 0) {
                val lastRowIndex = table.rowCount - 1
                val lastRowRect = table.getCellRect(lastRowIndex, 0, true)
                table.scrollRectToVisible(lastRowRect)
            }
        }
    }
}
