package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Команда для дублирования пресета
 */
class PresetDuplicateCommand(
    controller: CommandContext,
    private val originalIndex: Int,
    private val duplicateIndex: Int,
    private val presetData: DevicePreset
) : AbstractPresetCommand(controller) {
    
    override val description: String
        get() = "Duplicate preset '${presetData.label}' from row $originalIndex to row $duplicateIndex"
    
    override fun execute() {
        // Добавляем дубликат в таблицу
        val rowData = createTableRow(presetData)
        tableModel.insertRow(duplicateIndex, rowData)
        
        // Добавляем в tempPresetList
        currentPresetList?.let { list ->
            if (duplicateIndex <= list.presets.size) {
                list.presets.add(duplicateIndex, presetData.copy())
                println("ADB_DEBUG: Duplicated preset to tempPresetList at index $duplicateIndex")
            }
        }
        
        refreshTable()
        updateUI()
    }
    
    override fun undo() {
        // Удаляем дубликат
        if (duplicateIndex < tableModel.rowCount) {
            // Удаляем из таблицы
            tableModel.removeRow(duplicateIndex)
            
            // Удаляем из tempPresetList
            currentPresetList?.let { list ->
                if (duplicateIndex < list.presets.size) {
                    list.presets.removeAt(duplicateIndex)
                    println("ADB_DEBUG: Removed duplicate from tempPresetList at index $duplicateIndex")
                }
            }
            
            refreshTable()
        updateUI()
        }
    }
    
    override fun redo() {
        execute()
    }
}