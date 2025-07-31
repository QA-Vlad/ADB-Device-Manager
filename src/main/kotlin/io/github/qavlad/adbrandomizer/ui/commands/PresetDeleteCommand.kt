package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Команда для удаления пресета
 */
class PresetDeleteCommand(
    controller: CommandContext,
    private val rowIndex: Int,
    private val presetData: DevicePreset,
    private val listName: String? = null,
    private val actualListIndex: Int? = null // Реальный индекс в списке (может отличаться от rowIndex при скрытии дубликатов)
) : AbstractPresetCommand(controller) {

    override val description: String
        get() = "Delete preset '${presetData.label}' at row $rowIndex"

    override fun execute() {
        // Этот метод вызывается при первоначальном действии
        if (rowIndex < tableModel.rowCount) {
            tableModel.removeRow(rowIndex)
        }
        // TableModelListener должен вызвать syncTableChangesToTempLists и удалить элемент из temp-списка
    }

    override fun undo() {
        logCommandExecutionMode("PresetDeleteCommand.undo()")
        
        // Для команд добавления/удаления не переключаем режим, так как они синхронизируются между режимами
        
        val targetListName = listName ?: currentPresetList?.name
        println("ADB_DEBUG: Undoing delete for preset '${presetData.label}' in list '$targetListName'")

        if (targetListName != null) {
            val targetList = tempPresetLists.values.find { it.name == targetListName }
            if (targetList != null) {
                // Сначала пытаемся восстановить из корзины
                val restoredPreset = controller.getPresetRecycleBin().restoreFromRecycleBin(
                    targetListName, 
                    actualListIndex ?: rowIndex,
                    presetData.id
                )
                
                val presetToRestore = restoredPreset ?: presetData.copy(id = presetData.id)
                
                // При восстановлении важно вставить пресет в правильное место с учётом текущей сортировки
                // Если сортировка не активна, используем исходный индекс
                val currentSortState = controller.getTableSortingService()?.getCurrentSortState()
                val indexToRestore = if (currentSortState?.activeColumn != null) {
                    // При активной сортировке просто добавляем в конец, сортировка произойдёт при перезагрузке таблицы
                    targetList.presets.size
                } else {
                    // Без сортировки восстанавливаем на исходную позицию
                    (actualListIndex ?: rowIndex).coerceAtMost(targetList.presets.size)
                }
                
                targetList.presets.add(indexToRestore, presetToRestore)
                println("ADB_DEBUG: Restored preset to temp list '$targetListName' at index $indexToRestore")
                println("ADB_DEBUG:   Active sort: ${currentSortState?.activeColumn ?: "none"}")
                println("ADB_DEBUG:   Preset restored from recycle bin: ${restoredPreset != null}")

                if (!isShowAllPresetsMode && targetListName != currentPresetList?.name) {
                    val listToSwitch = tempPresetLists.values.find { it.name == targetListName }
                    if (listToSwitch != null) {
                        controller.setCurrentPresetList(listToSwitch)
                    }
                }
            }
        }

        invokeLater {
            withTableUpdateDisabled {
                // Сохраняем текущее состояние сортировки перед перезагрузкой
                val currentSortState = controller.getTableSortingService()?.getCurrentSortState()
                
                loadPresetsIntoTable()
                refreshTable()
                
                // Если была активная сортировка, применяем её заново
                if (currentSortState != null && currentSortState.activeColumn != null) {
                    println("ADB_DEBUG: Reapplying sort after undo - column: ${currentSortState.activeColumn}")
                    controller.getTableSortingService()?.reapplyCurrentSort()
                }
                
                updateUI() // Обновляем UI после всех изменений
            }
        }
    }

    override fun redo() {
        logCommandExecutionMode("PresetDeleteCommand.redo()")
        
        // Для команд добавления/удаления не переключаем режим, так как они синхронизируются между режимами
        
        val targetListName = listName ?: currentPresetList?.name
        println("ADB_DEBUG: Redoing delete for preset '${presetData.label}' in list '$targetListName'")

        if (targetListName != null) {
            val targetList = tempPresetLists.values.find { it.name == targetListName }
            if (targetList != null) {
                // Ищем пресет по ID, так как индексы могут измениться из-за сортировки
                val indexToRemove = targetList.presets.indexOfFirst { it.id == presetData.id }
                
                if (indexToRemove >= 0) {
                    val presetToRemove = targetList.presets[indexToRemove]
                    targetList.presets.removeAt(indexToRemove)
                    
                    // Перемещаем удалённый пресет в корзину
                    controller.getPresetRecycleBin().moveToRecycleBin(
                        presetToRemove,
                        targetListName,
                        indexToRemove
                    )
                    
                    println("ADB_DEBUG: Removed preset from temp list '$targetListName' at index $indexToRemove (found by ID: ${presetData.id})")
                    println("ADB_DEBUG:   Preset moved to recycle bin")
                } else {
                    println("ADB_DEBUG: Warning: Could not find preset with ID ${presetData.id} in list '$targetListName'")
                }
            }
        }

        invokeLater {
            withTableUpdateDisabled {
                // Сохраняем текущее состояние сортировки перед перезагрузкой
                val currentSortState = controller.getTableSortingService()?.getCurrentSortState()
                
                loadPresetsIntoTable()
                refreshTable()
                
                // Если была активная сортировка, применяем её заново
                if (currentSortState != null && currentSortState.activeColumn != null) {
                    println("ADB_DEBUG: Reapplying sort after redo - column: ${currentSortState.activeColumn}")
                    controller.getTableSortingService()?.reapplyCurrentSort()
                }
                
                updateUI() // Обновляем UI после всех изменений
            }
        }
    }
}