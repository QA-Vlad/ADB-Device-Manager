package io.github.qavlad.adbdevicemanager.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbdevicemanager.services.PresetStorageService
import io.github.qavlad.adbdevicemanager.ui.components.DevicePresetTableModel
import io.github.qavlad.adbdevicemanager.ui.dialogs.DialogStateManager

/**
 * Контроллер для управления сортировкой таблицы
 * Обеспечивает интеграцию между UI и TableSortingService
 */
class TableSortingController(
    private val dialogState: DialogStateManager,
    private val tableColumnManager: TableColumnManager
) {
    
    /**
     * Настраивает колонки таблицы в зависимости от режима
     */
    fun setupTableColumns(table: JBTable, tableModel: DevicePresetTableModel) {
        tableColumnManager.setupTableColumns(table, tableModel, dialogState.isShowAllPresetsMode())
    }
    
    /**
     * Обрабатывает клик по заголовку колонки
     */
    fun handleHeaderClick(
        columnIndex: Int,
        table: JBTable,
        onApplySorting: () -> Unit
    ) {
        val hasCounters = PresetStorageService.getShowCounters()
        val listColumnIndex = if (hasCounters) 8 else 6
        
        println("ADB_DEBUG: handleHeaderClick - columnIndex: $columnIndex, hasCounters: $hasCounters")
        
        val columnName = when (columnIndex) {
            2 -> "Label"
            3 -> "Size"
            4 -> "DPI"
            5 -> if (hasCounters) "Size Uses" else null
            6 -> if (hasCounters) "DPI Uses" else if (dialogState.isShowAllPresetsMode()) "List" else null
            listColumnIndex -> if (dialogState.isShowAllPresetsMode()) "List" else null
            else -> null
        }
        
        println("ADB_DEBUG: handleHeaderClick - columnName: $columnName")
        
        if (columnName != null) {
            // Обрабатываем клик через сервис сортировки
            TableSortingService.handleColumnClick(
                columnName,
                dialogState.isShowAllPresetsMode(),
                dialogState.isHideDuplicatesMode()
            )
            
            // Применяем сортировку
            onApplySorting()
            
            // Обновляем заголовки таблицы для отображения индикаторов сортировки
            table.tableHeader.repaint()
        }
    }
    
    
    /**
     * Обрабатывает сброс сортировки
     */
    fun handleResetSorting(
        table: JBTable,
        onLoadPresetsIntoTable: () -> Unit
    ) {
        // Сбрасываем сортировку для текущего режима
        TableSortingService.resetSort(
            dialogState.isShowAllPresetsMode(),
            dialogState.isHideDuplicatesMode()
        )
        
        // Перезагружаем таблицу без сортировки
        onLoadPresetsIntoTable()
        
        // Обновляем заголовки таблицы
        table.tableHeader.repaint()
    }
    
    /**
     * Синхронизирует состояние сортировки при переключении режима Hide Duplicates
     */
    fun syncSortStateForHideDuplicatesToggle(hideDuplicates: Boolean) {
        TableSortingService.syncSortStateForHideDuplicatesToggle(
            dialogState.isShowAllPresetsMode(),
            hideDuplicates
        )
    }
    
    /**
     * Восстанавливает состояние сортировки из снимка
     */
    fun restoreSortStateFromSnapshot() {
        TableSortingService.restoreSortStateFromSnapshot()
    }
}