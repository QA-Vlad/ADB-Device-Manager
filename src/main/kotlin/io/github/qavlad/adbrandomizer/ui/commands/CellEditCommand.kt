package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.ui.dialogs.SettingsDialogController
import io.github.qavlad.adbrandomizer.ui.components.CellIdentity

/**
 * Команда для редактирования ячейки таблицы
 */
class CellEditCommand(
    controller: SettingsDialogController,
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
        
        // Логируем текущее состояние tempPresetLists перед операцией
        currentPresetList?.let { list ->
            println("ADB_DEBUG: Current preset list '${list.name}' has ${list.presets.size} presets")
            list.presets.forEachIndexed { index, preset ->
                println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
        }
        
        // 1. Найти нужный список и сделать его активным, если он не активен
        val targetList = tempPresetLists.values.find { it.name == listName }
        if (targetList != null && targetList.name != currentPresetList?.name) {
            println("ADB_DEBUG: Switching to list '${targetList.name}' for command")
            controller.setCurrentPresetListForCommands(targetList)
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
        currentPresetList?.let { list ->
            val actualRowIndex = if (isHideDuplicatesMode) {
                // В режиме скрытия дубликатов нужно найти индекс в полном списке
                // Получаем текущий пресет из таблицы, по видимому индексу
                val tableRow = coords.first
                if (tableRow >= 0 && tableRow < tableModel.rowCount) {
                    val tablePreset = tableModel.getPresetAt(tableRow)
                    if (tablePreset != null) {
                        // Ищем этот пресет в полном списке
                        list.presets.indexOfFirst { preset ->
                            // Сравниваем по всем полям, учитывая какое поле мы редактируем
                            when (coords.second) {
                                2 -> preset.size == tablePreset.size && preset.dpi == tablePreset.dpi
                                3 -> preset.label == tablePreset.label && preset.dpi == tablePreset.dpi  
                                4 -> preset.label == tablePreset.label && preset.size == tablePreset.size
                                else -> false
                            }
                        }
                    } else {
                        -1
                    }
                } else {
                    -1
                }
            } else {
                coords.first
            }

            if (actualRowIndex >= 0 && actualRowIndex < list.presets.size) {
                val preset = list.presets[actualRowIndex]
                when (coords.second) {
                    2 -> preset.label = value
                    3 -> preset.size = value
                    4 -> preset.dpi = value
                }
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
}
