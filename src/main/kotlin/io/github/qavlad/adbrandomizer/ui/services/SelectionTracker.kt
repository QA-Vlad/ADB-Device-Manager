package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import javax.swing.JTable
import javax.swing.SwingUtilities

/**
 * Отслеживает выделение ячеек и восстанавливает его после сортировки или перезагрузки таблицы
 */
object SelectionTracker {
    private var selectedPresetId: String? = null
    private var selectedColumn: Int = -1
    private var hoverStateUpdater: ((Int, Int) -> Unit)? = null
    private var isRestoring: Boolean = false
    private var shouldSkipRestore: Boolean = false
    
    /**
     * Сохраняет текущее выделение
     */
    fun saveSelection(table: JTable, row: Int, column: Int) {
        // Не сохраняем выделение, если идет процесс восстановления
        if (isRestoring) {
            println("ADB_DEBUG: SelectionTracker.saveSelection - skipped during restoration")
            return
        }
        
        val model = table.model as? DevicePresetTableModel ?: return
        
        if (row >= 0 && row < model.rowCount) {
            selectedPresetId = model.getPresetIdAt(row)
            selectedColumn = column
            println("ADB_DEBUG: SelectionTracker.saveSelection - saved presetId: $selectedPresetId, column: $selectedColumn")
        }
    }
    
    /**
     * Устанавливает функцию для обновления HoverState
     */
    fun setHoverStateUpdater(updater: (Int, Int) -> Unit) {
        hoverStateUpdater = updater
    }
    
    /**
     * Восстанавливает выделение после перезагрузки таблицы
     */
    fun restoreSelection(table: JTable) {
        if (shouldSkipRestore) {
            println("ADB_DEBUG: SelectionTracker.restoreSelection - skipped due to shouldSkipRestore flag")
            shouldSkipRestore = false
            return
        }
        
        val model = table.model as? DevicePresetTableModel ?: return
        val presetId = selectedPresetId ?: return
        
        if (selectedColumn < 0) return
        
        // Ищем строку с сохраненным preset ID
        for (row in 0 until model.rowCount) {
            if (model.getPresetIdAt(row) == presetId) {
                println("ADB_DEBUG: SelectionTracker.restoreSelection - found preset at row $row, restoring selection to column $selectedColumn")
                
                // Восстанавливаем выделение с дополнительной задержкой
                // для гарантии, что таблица полностью обновилась
                SwingUtilities.invokeLater {
                    isRestoring = true
                    try {
                        // Восстанавливаем выделение
                        table.changeSelection(row, selectedColumn, false, false)
                        
                        // Убеждаемся, что ячейка видима
                        table.scrollRectToVisible(table.getCellRect(row, selectedColumn, true))
                        
                        // Запрашиваем фокус для таблицы
                        table.requestFocusInWindow()
                        
                        // Форсируем перерисовку ячейки
                        val cellRect = table.getCellRect(row, selectedColumn, true)
                        table.repaint(cellRect)
                        
                        // Обновляем HoverState для правильного отображения выделения
                        hoverStateUpdater?.invoke(row, selectedColumn)
                    } finally {
                        isRestoring = false
                    }
                }
                
                return
            }
        }
        
        println("ADB_DEBUG: SelectionTracker.restoreSelection - preset with id $presetId not found in table")
    }
    
    /**
     * Очищает сохраненное выделение
     */
    fun clearSelection() {
        selectedPresetId = null
        selectedColumn = -1
        println("ADB_DEBUG: SelectionTracker.clearSelection - cleared selection")
    }
    
    /**
     * Проверяет, есть ли сохраненное выделение
     */
    fun hasSelection(): Boolean = selectedPresetId != null && selectedColumn >= 0
    
    /**
     * Устанавливает флаг пропуска восстановления выделения
     */
    fun setSkipNextRestore() {
        shouldSkipRestore = true
        println("ADB_DEBUG: SelectionTracker.setSkipNextRestore - will skip next restore")
    }
}