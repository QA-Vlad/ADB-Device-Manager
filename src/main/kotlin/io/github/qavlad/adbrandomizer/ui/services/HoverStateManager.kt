package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.ui.components.HoverState
import com.intellij.ui.table.JBTable
import javax.swing.SwingUtilities

/**
 * Сервис для управления состоянием hover эффектов и выделения в таблице.
 * Централизует всю логику работы с HoverState и визуальными обновлениями.
 */
class HoverStateManager(
    private val table: JBTable,
    private val onStateChanged: (HoverState) -> Unit
) {
    private var currentState = HoverState.noHover()
    private var lastInteractedRow: Int = -1
    
    /**
     * Получает последнюю взаимодействующую строку
     */
    fun getLastInteractedRow(): Int = lastInteractedRow
    
    /**
     * Обновляет последнюю взаимодействующую строку
     */
    fun updateLastInteractedRow(row: Int) {
        lastInteractedRow = row
        println("ADB_DEBUG: HoverStateManager - updated lastInteractedRow to $row")
    }
    
    /**
     * Устанавливает выделение на ячейку таблицы
     */
    fun setTableSelection(row: Int, column: Int) {
        val oldState = currentState
        currentState = currentState.withTableSelection(row, column)
        
        // Перерисовываем старую и новую ячейки
        repaintCellIfNeeded(oldState.selectedTableRow, oldState.selectedTableColumn)
        repaintCellIfNeeded(row, column)
        
        updateLastInteractedRow(row)
        onStateChanged(currentState)
        
        println("ADB_DEBUG: Selected cell ($row, $column)")
    }
    
    /**
     * Очищает hover эффект таблицы
     */
    fun clearTableHover() {
        val oldState = currentState
        currentState = currentState.clearTableHover()
        
        repaintCellIfNeeded(oldState.hoveredTableRow, oldState.hoveredTableColumn)
        onStateChanged(currentState)
        
        println("ADB_DEBUG: Cleared table hover")
    }
    
    /**
     * Очищает выделение таблицы
     */
    fun clearTableSelection() {
        val oldState = currentState
        currentState = currentState.clearTableSelection()
        
        repaintCellIfNeeded(oldState.selectedTableRow, oldState.selectedTableColumn)
        onStateChanged(currentState)
        
        println("ADB_DEBUG: Cleared table selection")
    }
    
    /**
     * Обрабатывает выход курсора из таблицы
     */
    fun handleTableExit() {
        println("ADB_DEBUG: handleTableExit called")
        clearTableHover()
    }
    
    
    /**
     * Выделяет первую ячейку Label в таблице
     */
    fun selectFirstLabelCell() {
        if (table.rowCount > 1) { // > 1 потому что последняя строка - это кнопка "+"
            setTableSelection(0, 2) // колонка 2 - это Label
            println("ADB_DEBUG: Selected first Label cell (0, 2)")
        }
    }
    
    /**
     * Обновляет состояние после перемещения строки
     */
    fun updateAfterRowMove(fromIndex: Int, toIndex: Int) {
        // Обновляем выделение, если оно было на перемещенной строке
        if (currentState.selectedTableRow == fromIndex) {
            currentState = currentState.withTableSelection(toIndex, currentState.selectedTableColumn)
            onStateChanged(currentState)
        }
        
        // Обновляем hover, если он был на перемещенной строке
        if (currentState.hoveredTableRow == fromIndex) {
            currentState = currentState.withTableHover(toIndex, currentState.hoveredTableColumn)
            onStateChanged(currentState)
        }
    }
    
    /**
     * Сбрасывает состояние при переключении режимов
     */
    fun resetForModeSwitch() {
        currentState = HoverState.noHover()
        lastInteractedRow = -1
        onStateChanged(currentState)
        SelectionTracker.clearSelection()
    }
    
    /**
     * Устанавливает полное состояние (используется при восстановлении)
     */
    fun setState(newState: HoverState) {
        val oldState = currentState
        currentState = newState
        
        println("ADB_DEBUG: HoverStateManager.setState - old selected: (${oldState.selectedTableRow}, ${oldState.selectedTableColumn}), new selected: (${newState.selectedTableRow}, ${newState.selectedTableColumn})")
        
        // Перерисовываем все измененные ячейки
        repaintCellIfNeeded(oldState.hoveredTableRow, oldState.hoveredTableColumn)
        repaintCellIfNeeded(oldState.selectedTableRow, oldState.selectedTableColumn)
        repaintCellIfNeeded(newState.hoveredTableRow, newState.hoveredTableColumn)
        repaintCellIfNeeded(newState.selectedTableRow, newState.selectedTableColumn)
        
        onStateChanged(currentState)
    }
    
    /**
     * Перерисовывает ячейку, если координаты валидны
     */
    private fun repaintCellIfNeeded(row: Int, column: Int) {
        if (row >= 0 && column >= 0) {
            SwingUtilities.invokeLater {
                val rect = table.getCellRect(row, column, false)
                table.repaint(rect)
            }
        }
    }
    
    /**
     * Устанавливает функцию обновления для SelectionTracker
     */
    fun setupSelectionTracker() {
        SelectionTracker.setHoverStateUpdater { row, column ->
            setTableSelection(row, column)
        }
    }
}