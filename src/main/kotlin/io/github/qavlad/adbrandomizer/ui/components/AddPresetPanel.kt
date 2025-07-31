package io.github.qavlad.adbrandomizer.ui.components

import java.awt.*
import javax.swing.*


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
