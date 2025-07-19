package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Команда для добавления нового пресета
 */
class PresetAddCommand(
    controller: CommandContext,
    private val rowIndex: Int,
    private val presetData: DevicePreset,
    private val listName: String? = null, // Название списка для режима Show all
    private var actualListIndex: Int? = null // Реальный индекс в списке (может отличаться при скрытии дубликатов)
) : AbstractPresetCommand(controller) {
    
    override val description: String
        get() = "Add preset at row $rowIndex"
    
    override fun execute() {
        // Добавляем новую строку в таблицу
        val rowData = createTableRow(presetData)
        tableModel.insertRow(rowIndex, rowData)
        
        // Примечание: syncTableChangesToTempLists автоматически добавит пресет в список
        // поэтому здесь мы не трогаем tempPresetList
        println("ADB_DEBUG: PresetAddCommand.execute() - added row at index $rowIndex")
        
        refreshTable()
        updateUI()
    }
    
    override fun undo() {
        logCommandExecutionMode("PresetAddCommand.undo()", listName, ", actualListIndex: $actualListIndex")
        
        // Для команд добавления/удаления не переключаем режим, так как они синхронизируются между режимами
        
        // Определяем целевой список
        val targetList = findTargetList(listName)
        val targetListName = listName ?: currentPresetList?.name
        
        if (targetList != null) {
            // Для пустых пресетов ищем последний пустой пресет
            if (presetData.label.isBlank() && presetData.size.isBlank() && presetData.dpi.isBlank()) {
                // Ищем последний пустой пресет
                val indexToRemove = targetList.presets.indexOfLast { preset ->
                    preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
                }
                
                if (indexToRemove >= 0) {
                    targetList.presets.removeAt(indexToRemove)
                    println("ADB_DEBUG: Removed last empty preset from list '$targetListName' at index $indexToRemove")
                } else {
                    println("ADB_DEBUG: Failed to find empty preset to remove")
                }
            } else {
                // Для непустых пресетов используем actualListIndex или ищем по содержимому
                val indexToRemove = actualListIndex ?: targetList.presets.indexOfFirst { preset ->
                    preset.label == presetData.label &&
                    preset.size == presetData.size &&
                    preset.dpi == presetData.dpi
                }
                
                if (indexToRemove >= 0 && indexToRemove < targetList.presets.size) {
                    targetList.presets.removeAt(indexToRemove)
                    println("ADB_DEBUG: Removed preset from list '$targetListName' at index $indexToRemove")
                } else {
                    println("ADB_DEBUG: Failed to remove preset - index $indexToRemove out of bounds or not found")
                }
            }
        }
        
        invokeLater {
            withTableUpdateDisabled {
                loadPresetsIntoTable()
                refreshTable()
                updateUI()
            }
        }
    }
    
    override fun redo() {
        logCommandExecutionMode("PresetAddCommand.redo()", listName)
        
        // Для команд добавления/удаления не переключаем режим, так как они синхронизируются между режимами
        
        // Определяем целевой список
        val targetList = findTargetList(listName)
        val targetListName = listName ?: currentPresetList?.name
        
        // Добавляем пресет обратно в список
        targetList?.let { list ->
            list.presets.add(presetData.copy())
            println("ADB_DEBUG: Re-added preset to list '$targetListName' at end")
        }
        
        invokeLater {
            withTableUpdateDisabled {
                loadPresetsIntoTable()
                refreshTable()
                updateUI()
            }
        }
    }
}