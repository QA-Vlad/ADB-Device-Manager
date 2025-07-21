package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel

/**
 * Сервис для управления сохранением настроек плагина.
 * Централизует логику сохранения списков пресетов и связанных данных.
 */
class SettingsPersistenceService {
    
    /**
     * Сохраняет все настройки
     */
    fun saveSettings(
        table: JBTable,
        tempLists: Map<String, PresetList>,
        isShowAllPresetsMode: Boolean,
        onSaveCurrentTableState: () -> Unit,
        onSaveShowAllPresetsOrder: () -> Unit
    ) {
        // Останавливаем редактирование таблицы если оно активно
        stopTableEditingIfNeeded(table)
        
        // Сохраняем текущее состояние таблицы
        onSaveCurrentTableState()
        
        // Очищаем пустые пресеты перед сохранением
        cleanupEmptyPresets(tempLists)
        
        // Сохраняем все списки в файлы
        saveAllPresetLists(tempLists)
        
        // Сохраняем порядок для режима "Show all presets"
        if (isShowAllPresetsMode) {
            onSaveShowAllPresetsOrder()
        }
    }
    
    /**
     * Останавливает редактирование таблицы если оно активно
     */
    private fun stopTableEditingIfNeeded(table: JBTable) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }
    
    /**
     * Удаляет пустые пресеты из всех списков
     */
    private fun cleanupEmptyPresets(tempLists: Map<String, PresetList>) {
        tempLists.values.forEach { list ->
            list.presets.removeIf { preset ->
                preset.label.trim().isEmpty() &&
                preset.size.trim().isEmpty() &&
                preset.dpi.trim().isEmpty()
            }
        }
    }
    
    /**
     * Сохраняет все списки пресетов в файлы
     */
    private fun saveAllPresetLists(tempLists: Map<String, PresetList>) {
        tempLists.values.forEach { list ->
            PresetListService.savePresetList(list)
        }
    }
    
    /**
     * Сохраняет порядок пресетов для режима Show All из модели таблицы
     */
    fun saveShowAllPresetsOrder(tableModel: DevicePresetTableModel) {
        val presetsOrder = mutableListOf<String>()
        
        for (row in 0 until tableModel.rowCount) {
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            
            // Пропускаем строку с кнопкой "+"
            if (firstColumn == "+") continue
            
            // В режиме Show All колонка List всегда последняя
            val listColumnIndex = tableModel.columnCount - 1
            val listName = if (tableModel.columnCount >= 7) {  // Минимум 7 колонок в Show All
                tableModel.getValueAt(row, listColumnIndex) as? String ?: ""
            } else {
                ""
            }
            
            if (listName.isNotEmpty()) {
                val preset = tableModel.getPresetAt(row)
                if (preset != null) {
                    // Сохраняем в формате: listName::label::size::dpi (формат должен совпадать с ViewModeManager)
                    presetsOrder.add("$listName::${preset.label}::${preset.size}::${preset.dpi}")
                }
            }
        }
        
        PresetListService.saveShowAllPresetsOrder(presetsOrder)
        println("ADB_DEBUG: Saved show all presets order with ${presetsOrder.size} items")
    }
    
    /**
     * Восстанавливает оригинальное состояние из сохраненных снимков
     */
    fun restoreOriginalState(
        tempListsManager: TempListsManager,
        originalPresetLists: Map<String, PresetList>,
        snapshotManager: SnapshotManager
    ) {
        println("ADB_DEBUG: Restoring original state")
        
        // Восстанавливаем из сохраненных оригиналов
        snapshotManager.restoreSnapshots(
            tempListsManager.getMutableTempLists(),
            originalPresetLists
        )
        
        // Сохраняем восстановленные списки в файлы
        saveAllPresetLists(tempListsManager.getTempLists())
        
        // Не очищаем сохраненный порядок - он должен сохраняться между сессиями
        // PresetListService.saveShowAllPresetsOrder(emptyList())
        
        println("ADB_DEBUG: Original state restored and saved to files")
    }
}