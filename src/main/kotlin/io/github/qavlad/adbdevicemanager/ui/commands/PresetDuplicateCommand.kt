package io.github.qavlad.adbdevicemanager.ui.commands

import io.github.qavlad.adbdevicemanager.services.DevicePreset

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
                // Для дублирования создаем новый пресет с новым ID
                list.presets.add(duplicateIndex, DevicePreset(presetData.label, presetData.size, presetData.dpi))
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