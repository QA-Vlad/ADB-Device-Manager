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
    private val listName: String?, // Запоминаем имя списка, в котором произошло изменение
    private val originalLabel: String, // Исходный label пресета для точной идентификации
    private val originalSize: String,  // Исходный size пресета для точной идентификации
    private val originalDpi: String,   // Исходный dpi пресета для точной идентификации
    private val editedColumn: Int,     // Какая колонка редактировалась
    private val presetId: String,      // ID пресета для точной идентификации
    private val presetIndex: Int       // Индекс пресета в списке на момент создания команды
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
            updatePresetInTempList(valueToSet)
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

    private fun updatePresetInTempList(value: String) {
        // В режиме Show all работаем с конкретным списком по имени
        val targetList = if (isShowAllPresetsMode && listName != null) {
            tempPresetLists.values.find { it.name == listName }
        } else {
            currentPresetList
        }
        
        targetList?.let { list ->
            // Сначала пытаемся найти по ID
            var actualRowIndex = list.presets.indexOfFirst { preset ->
                preset.id == presetId
            }
            
            // Если не нашли по ID, пытаемся найти по индексу и атрибутам
            if (actualRowIndex < 0 && presetIndex >= 0 && presetIndex < list.presets.size) {
                val candidatePreset = list.presets[presetIndex]
                // Проверяем, что пресет на этом индексе имеет подходящие атрибуты
                val matches = when (editedColumn) {
                    2 -> candidatePreset.size == originalSize && candidatePreset.dpi == originalDpi
                    3 -> candidatePreset.label == originalLabel && candidatePreset.dpi == originalDpi
                    4 -> candidatePreset.label == originalLabel && candidatePreset.size == originalSize
                    else -> false
                }
                if (matches) {
                    actualRowIndex = presetIndex
                    println("ADB_DEBUG: Found preset by index $presetIndex and attributes")
                }
            }
            
            // Если всё ещё не нашли, ищем по атрибутам
            if (actualRowIndex < 0) {
                actualRowIndex = list.presets.indexOfFirst { preset ->
                    when (editedColumn) {
                        2 -> preset.size == originalSize && preset.dpi == originalDpi
                        3 -> preset.label == originalLabel && preset.dpi == originalDpi
                        4 -> preset.label == originalLabel && preset.size == originalSize
                        else -> false
                    }
                }
                if (actualRowIndex >= 0) {
                    println("ADB_DEBUG: Found preset by attributes at index $actualRowIndex")
                }
            }

            if (actualRowIndex >= 0) {
                val preset = list.presets[actualRowIndex]
                // Создаем новый пресет с обновленным значением
                val updatedPreset = when (editedColumn) {
                    2 -> preset.copy(label = value)
                    3 -> preset.copy(size = value)
                    4 -> preset.copy(dpi = value)
                    else -> preset
                }
                list.presets[actualRowIndex] = updatedPreset
                println("ADB_DEBUG: Updated preset in temp list '${list.name}': row=$actualRowIndex, col=$editedColumn, value=$value")
                println("ADB_DEBUG: Original values: label='$originalLabel', size='$originalSize', dpi='$originalDpi'")
            } else {
                // Не нашли пресет с исходными значениями
                println("ADB_DEBUG: Could not find preset with original values in list '${list.name}'")
                val searchCriteria = when (editedColumn) {
                    2 -> "size='$originalSize', dpi='$originalDpi' (editing label)"
                    3 -> "label='$originalLabel', dpi='$originalDpi' (editing size)"
                    4 -> "label='$originalLabel', size='$originalSize' (editing dpi)"
                    else -> "label='$originalLabel', size='$originalSize', dpi='$originalDpi'"
                }
                println("ADB_DEBUG: Looking for preset with: $searchCriteria")
                logPresetList("Current presets in list", list)
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
}
