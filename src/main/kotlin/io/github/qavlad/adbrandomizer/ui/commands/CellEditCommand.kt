package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.ui.components.CellIdentity
import io.github.qavlad.adbrandomizer.services.PresetList

/**
 * Команда для редактирования ячейки таблицы
 */
class CellEditCommand(
    controller: CommandContext,
    val cellId: CellIdentity,
    val oldValue: String,
    val newValue: String,
    private val listName: String? // Запоминаем имя списка, в котором произошло изменение
) : AbstractPresetCommand(controller) {

    override val description: String
        get() = "Edit cell: '$oldValue' → '$newValue'"

    override fun execute() {
        // Логика прямого действия не нужна, так как изменение уже в UI
    }

    override fun undo() {
        performOperation(oldValue)
    }

    override fun redo() {
        performOperation(newValue)
    }

    private fun performOperation(valueToSet: String) {
        println("ADB_DEBUG: CellEditCommand.performOperation - cellId: ${cellId.id.substring(0, 8)}..., value: '$valueToSet'")
        
        // В режиме Show all не переключаем текущий список
        if (isShowAllPresetsMode) {
            println("ADB_DEBUG: Show all mode - listName: $listName")
            
            // Найти нужный список по имени
            val targetList = tempPresetLists.values.find { it.name == listName }
            if (targetList != null) {
                logPresetList("Found target list", targetList)
            } else {
                println("ADB_DEBUG: Target list '$listName' not found!")
            }
        } else {
            // В обычном режиме работаем как раньше
            currentPresetList?.let { list ->
                logPresetList("Current preset list", list)
            }
            
            // Найти нужный список и сделать его активным, если он не активен
            val targetList = tempPresetLists.values.find { it.name == listName }
            if (targetList != null && targetList.name != currentPresetList?.name) {
                println("ADB_DEBUG: Switching to list '${targetList.name}' for command")
                controller.setCurrentPresetList(targetList)
            }
        }

        // 2. Найти координаты ячейки
        val coords = historyManager.findCellCoordinates(cellId)
        println("ADB_DEBUG: Cell coordinates: $coords")
        
        if (coords != null) {
            // 3. Обновить значение в tempPresetLists
            updatePresetInTempList(coords, valueToSet)
        }

        // 4. Перезагрузить таблицу и обновить UI
        println("ADB_DEBUG: Reloading table after cell edit command")
        invokeLater {
            withTableUpdateDisabled {
                loadPresetsIntoTable()
                refreshTable()
                updateUI()
            }
        }
    }

    private fun updatePresetInTempList(coords: Pair<Int, Int>, value: String) {
        // В режиме Show all работаем с конкретным списком по имени
        val targetList = if (isShowAllPresetsMode && listName != null) {
            tempPresetLists.values.find { it.name == listName }
        } else {
            currentPresetList
        }
        
        targetList?.let { list ->
            val actualRowIndex = when {
                isShowAllPresetsMode -> findPresetIndexInList(list, coords)
                isHideDuplicatesMode -> findPresetIndexInList(list, coords)
                else -> coords.first
            }

            if (actualRowIndex >= 0 && actualRowIndex < list.presets.size) {
                val preset = list.presets[actualRowIndex]
                // Создаем новый пресет с обновленным значением
                val updatedPreset = when (coords.second) {
                    2 -> preset.copy(label = value)
                    3 -> preset.copy(size = value)
                    4 -> preset.copy(dpi = value)
                    else -> preset
                }
                list.presets[actualRowIndex] = updatedPreset
                println("ADB_DEBUG: Updated preset in temp list '${list.name}': row=$actualRowIndex, col=${coords.second}, value=$value")
            } else {
                // В режиме скрытия дубликатов может быть так, что мы не нашли точное совпадение
                // Это может произойти если редактируется дубликат
                println("ADB_DEBUG: Could not find exact preset match in temp list. Coords: $coords")
                // Обновляем в таблице напрямую - синхронизация произойдет через TableModelListener
                tableModel.setValueAt(value, coords.first, coords.second)
            }
        }
    }
    
    /**
     * Выводит отладочную информацию о списке пресетов
     */
    private fun logPresetList(prefix: String, list: PresetList) {
        println("ADB_DEBUG: $prefix '${list.name}' has ${list.presets.size} presets")
        list.presets.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
    }
    
    /**
     * Находит индекс пресета в списке по координатам из таблицы
     * Используется в режимах Show All и Hide Duplicates
     */
    private fun findPresetIndexInList(list: PresetList, coords: Pair<Int, Int>): Int {
        val tableRow = coords.first
        if (tableRow < 0 || tableRow >= tableModel.rowCount) {
            return -1
        }
        
        val tablePreset = tableModel.getPresetAt(tableRow) ?: return -1
        
        // Ищем пресет в списке, сравнивая по полям в зависимости от редактируемой колонки
        return list.presets.indexOfFirst { preset ->
            when (coords.second) {
                2 -> preset.size == tablePreset.size && preset.dpi == tablePreset.dpi
                3 -> preset.label == tablePreset.label && preset.dpi == tablePreset.dpi
                4 -> preset.label == tablePreset.label && preset.size == tablePreset.size
                else -> false
            }
        }
    }
}
