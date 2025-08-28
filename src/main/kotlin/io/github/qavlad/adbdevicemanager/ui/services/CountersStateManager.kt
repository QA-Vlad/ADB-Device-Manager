package io.github.qavlad.adbdevicemanager.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbdevicemanager.services.PresetStorageService
import io.github.qavlad.adbdevicemanager.services.PresetsDialogUpdateNotifier
import io.github.qavlad.adbdevicemanager.services.UsageCounterService
import io.github.qavlad.adbdevicemanager.ui.components.DevicePresetTableModel
import io.github.qavlad.adbdevicemanager.services.DevicePreset
import io.github.qavlad.adbdevicemanager.ui.dialogs.DialogStateManager
import javax.swing.SwingUtilities

/**
 * Управляет состоянием счётчиков использования пресетов
 */
class CountersStateManager(
    private val tableSortingService: TableSortingService,
    private val dialogState: DialogStateManager
) {
    private var updateListener: (() -> Unit)? = null
    private var countersSnapshot: Pair<Map<String, Int>, Map<String, Int>>? = null
    private var pendingPositionCallback: ((DevicePreset) -> Int?)? = null
    
    /**
     * Настраивает слушатель обновлений счётчиков
     */
    fun setupUpdateListener(
        table: JBTable,
        tableModel: DevicePresetTableModel,
        onReloadTable: () -> Unit
    ) {
        updateListener = {
            SwingUtilities.invokeLater {
                // Обновляем счетчики в таблице
                updateTableCounters(tableModel)
                
                // Если есть активная сортировка по счетчикам, нужно пересортировать таблицу
                val sortState = tableSortingService.getSortState(
                    dialogState.isShowAllPresetsMode(), 
                    dialogState.isHideDuplicatesMode()
                )
                val activeColumn = sortState.activeColumn
                if (activeColumn == "Size Uses" || activeColumn == "DPI Uses") {
                    println("ADB_DEBUG: Reloading table due to counter update with active sort: $activeColumn")
                    // Перезагружаем таблицу с сохранением текущей сортировки
                    onReloadTable()
                    
                    // Если есть ожидающий callback для получения позиции, вызываем его после обновления таблицы
                    pendingPositionCallback?.let { _ ->
                        SwingUtilities.invokeLater {
                            // Вызываем callback после обновления TableStateTracker
                            pendingPositionCallback = null
                        }
                    }
                } else {
                    table.repaint()
                }
            }
        }
        updateListener?.let { PresetsDialogUpdateNotifier.addListener(it) }
    }
    
    /**
     * Обновляет счетчики использования в таблице
     */
    private fun updateTableCounters(tableModel: DevicePresetTableModel) {
        val showCounters = PresetStorageService.getShowCounters()
        if (!showCounters || tableModel.columnCount < 7) return  // Нет колонок счетчиков
        
        // Обновляем счетчики для всех строк в таблице
        for (row in 0 until tableModel.rowCount) {
            // Пропускаем строку с кнопкой добавления
            if (tableModel.getValueAt(row, 0) == "+") continue
            
            val size = tableModel.getValueAt(row, 3) as? String ?: ""
            val dpi = tableModel.getValueAt(row, 4) as? String ?: ""
            
            // Обновляем счетчики напрямую через dataVector, чтобы избежать лишних событий
            val rowVector = tableModel.dataVector.elementAt(row) as java.util.Vector<Any>
            
            if (size.isNotBlank()) {
                val sizeCounter = UsageCounterService.getSizeCounter(size)
                rowVector.setElementAt(sizeCounter, 5)
            }
            
            if (dpi.isNotBlank()) {
                val dpiCounter = UsageCounterService.getDpiCounter(dpi)
                rowVector.setElementAt(dpiCounter, 6)
            }
        }
        
        // Уведомляем таблицу об изменении данных
        tableModel.fireTableDataChanged()
    }
    
    /**
     * Сохраняет снимок счётчиков для возможности отката
     */
    fun saveCountersSnapshot(snapshot: Pair<Map<String, Int>, Map<String, Int>>) {
        countersSnapshot = snapshot
    }
    
    /**
     * Восстанавливает счётчики из снимка
     */
    fun restoreCountersFromSnapshot() {
        countersSnapshot?.let {
            println("ADB_DEBUG: Restoring usage counters from snapshot")
            UsageCounterService.restoreFromSnapshot(it)
            countersSnapshot = null
        }
    }
    
    /**
     * Очищает ресурсы при закрытии диалога
     */
    fun dispose() {
        updateListener?.let { PresetsDialogUpdateNotifier.removeListener(it) }
        countersSnapshot = null
    }
}