package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import java.util.Vector

/**
 * Команда для перемещения пресета
 */
class PresetMoveCommand(
    controller: CommandContext,
    private val fromIndex: Int,
    private val toIndex: Int,
    var orderAfter: List<String>
) : AbstractPresetCommand(controller) {
    
    // Сохраняем полное состояние всех списков для режима Hide Duplicates
    private var tempListsStateBefore: Map<String, List<DevicePreset>>? = null
    private var tempListsStateAfter: Map<String, List<DevicePreset>>? = null

    // Сохраняем состояние видимых пресетов для режима Hide Duplicates
    private var visiblePresetsStateBefore: List<DevicePreset>? = null
    private var visiblePresetsStateAfter: List<DevicePreset>? = null
    
    // Сохраняем конкретные видимые элементы для каждого списка
    private var visiblePresetsBefore: Map<String, List<DevicePreset>>? = null
    private var visiblePresetsAfter: Map<String, List<DevicePreset>>? = null
    
    // Флаг, указывающий, что состояние "после" нужно сохранить перед синхронизацией
    private var needToSaveStateAfter = false

    init {
        // Если режим Hide Duplicates включен, сохраняем полное состояние
        if (controller.isHideDuplicatesMode()) {
            tempListsStateBefore = controller.getTempPresetLists().mapValues { (_, list) ->
                list.presets.map { it.copy() }
            }
            visiblePresetsStateBefore = controller.getVisiblePresets().map { it.copy() }
            
            // Сохраняем видимые элементы для каждого списка
            visiblePresetsBefore = extractVisiblePresets(tempListsStateBefore!!)
            
            needToSaveStateAfter = true
        }
    }

    override val description: String
        get() = "Move preset from row $fromIndex to row $toIndex"

    override fun execute() {
        // Перемещение уже выполнено в UI, поэтому здесь ничего не делаем
    }
    
    /**
     * Сохраняет состояние перед синхронизацией (вызывается из контроллера перед syncTableChangesToTempLists)
     */
    fun saveStateBeforeSync() {
        if (needToSaveStateAfter && controller.isHideDuplicatesMode()) {
            println("ADB_DEBUG: PresetMoveCommand.saveStateBeforeSync() - saving state before sync")
            
            // Сохраняем текущее состояние таблицы (что видит пользователь)
            val tableModel = controller.tableModel
            val visiblePresetsFromTable = mutableListOf<DevicePreset>()
            
                // Собираем видимые элементы из таблицы
                for (i in 0 until tableModel.rowCount) {
                    val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
                    if (firstColumn != "+") {
                        val label = tableModel.getValueAt(i, 2) as? String ?: ""
                        val size = tableModel.getValueAt(i, 3) as? String ?: ""
                        val dpi = tableModel.getValueAt(i, 4) as? String ?: ""
                        visiblePresetsFromTable.add(DevicePreset(label, size, dpi))
                    }
                }
                
                println("ADB_DEBUG: Visible presets from table after move: ${visiblePresetsFromTable.map { it.label }}")
            
            // Получаем текущий список
            val currentList = controller.getCurrentPresetList()
            val tempLists = controller.getTempPresetLists()
            
            currentList?.let { list ->
                // Создаём копию состояния для сохранения после перемещения
                val tempListsCopy = tempLists.mapValues { (_, tempList) ->
                    PresetList(
                        id = tempList.id,
                        name = tempList.name,
                        presets = tempList.presets.map { it.copy() }.toMutableList()
                    )
                }
                
                tempListsCopy[list.id]?.let { tempListCopy ->
                    // В режиме скрытия дубликатов нужно найти реальные индексы в полном списке
                    val visiblePresets = mutableListOf<DevicePreset>()
                    val seenKeys = mutableSetOf<String>()
                    
                    // Определяем видимые пресеты
                    tempListCopy.presets.forEach { preset ->
                        val key = "${preset.size}|${preset.dpi}"
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key)
                            visiblePresets.add(preset)
                        }
                    }
                    
                    println("ADB_DEBUG: Visible presets: ${visiblePresets.map { it.label }}")
                    println("ADB_DEBUG: Table fromIndex=$fromIndex, toIndex=$toIndex")
                    
                    if (fromIndex < visiblePresets.size && toIndex <= visiblePresets.size) {
                        // Находим элемент, который перемещается
                        val movedPreset = visiblePresets[fromIndex]
                        
                        // Находим реальный индекс этого элемента в полном списке
                        val realFromIndex = tempListCopy.presets.indexOf(movedPreset)
                        
                        // Определяем целевую позицию
                        val targetPreset = if (toIndex < visiblePresets.size) {
                            visiblePresets[toIndex]
                        } else {
                            visiblePresets.last()
                        }
                        
                        var realToIndex = tempListCopy.presets.indexOf(targetPreset)
                        
                        // Если перемещаем вниз, то вставляем после целевого элемента
                        if (fromIndex < toIndex && realToIndex >= 0) {
                            realToIndex += 1
                        }
                        
                        println("ADB_DEBUG: Real indices: from $realFromIndex to $realToIndex")
                        println("ADB_DEBUG: Moving ${movedPreset.label} to position near ${targetPreset.label}")
                        println("ADB_DEBUG: tempListCopy before move: ${tempListCopy.presets.map { it.label }}")
                        
                        if (realFromIndex >= 0 && realFromIndex < tempListCopy.presets.size) {
                            tempListCopy.presets.removeAt(realFromIndex)
                            val adjustedToIndex = if (realToIndex > realFromIndex) realToIndex - 1 else realToIndex
                            tempListCopy.presets.add(adjustedToIndex.coerceIn(0, tempListCopy.presets.size), movedPreset)
                            
                            println("ADB_DEBUG: tempListCopy after move: ${tempListCopy.presets.map { it.label }}")
                        }
                    }
                    
                    // Сохраняем состояние из копии
                    tempListsStateAfter = tempListsCopy.mapValues { (_, list) ->
                        list.presets.map { it.copy() }
                    }
                    
                    // ВАЖНО: Сохраняем ТОЧНО те элементы, которые видны в таблице после перемещения
                    val visiblePresetsMap = mutableMapOf<String, List<DevicePreset>>()
                    visiblePresetsMap[list.id] = visiblePresetsFromTable
                    tempLists.forEach { (id, _) ->
                        if (id != list.id) {
                            // Для других списков используем стандартную логику
                            visiblePresetsMap[id] = extractVisiblePresets(mapOf(id to (tempListsStateAfter!![id] ?: emptyList())))[id] ?: emptyList()
                        }
                    }
                    visiblePresetsAfter = visiblePresetsMap
                    
                    visiblePresetsStateAfter = visiblePresetsFromTable
                }
            }
            
            println("ADB_DEBUG: Saved tempListsStateAfter with ${tempListsStateAfter?.size} lists")
            tempListsStateAfter?.forEach { (id, presets) ->
                println("ADB_DEBUG:   List $id has ${presets.size} presets")
                if (id == currentList?.id) {
                    println("ADB_DEBUG:   Current list order: ${presets.map { it.label }}")
                }
            }
            needToSaveStateAfter = false
        }
    }

    /**
     * Сохраняет состояние после выполнения операции (для обратной совместимости)
     */
    fun saveStateAfter() {
        println("ADB_DEBUG: PresetMoveCommand.saveStateAfter() - isHideDuplicates: ${controller.isHideDuplicatesMode()}")
        if (needToSaveStateAfter && controller.isHideDuplicatesMode()) {
            tempListsStateAfter = controller.getTempPresetLists().mapValues { (_, list) ->
                list.presets.map { it.copy() }
            }
            visiblePresetsStateAfter = controller.getVisiblePresets().map { it.copy() }
            println("ADB_DEBUG: Saved tempListsStateAfter with ${tempListsStateAfter?.size} lists and ${visiblePresetsStateAfter?.size} visible presets")
            needToSaveStateAfter = false
        }
    }

    override fun undo() {
        logCommandExecutionMode("PresetMoveCommand.undo()", null, ", isHideDuplicates: ${controller.isHideDuplicatesMode()}, tempListsStateBefore: ${tempListsStateBefore != null}")
        
        // Проверяем, нужно ли переключить режим
        if (needToSwitchMode()) {
            println("ADB_DEBUG: Need to switch mode before undo")
            switchToOriginalMode()
            // После переключения режима, вызываем undo еще раз
            undo()
            return
        }
        
        // Добавляем отладку состояния до восстановления
        val currentList = controller.getCurrentPresetList()
        currentList?.let {
            val tempList = controller.getTempPresetLists()[it.id]
            println("ADB_DEBUG: Before undo - current tempList order: ${tempList?.presets?.map { p -> p.label }}")
        }
        
        when {
            tempListsStateBefore != null -> {
                // Если есть сохраненное состояние, всегда используем его
                println("ADB_DEBUG: Using saved state for undo")
                restoreState(tempListsStateBefore!!, visiblePresetsStateBefore, visiblePresetsBefore)
            }
            controller.isShowAllPresetsMode() -> {
                controller.tableModel.moveRow(toIndex, toIndex, fromIndex)
                controller.syncTableChangesToTempLists()
            }
            else -> {
                controller.getTempPresetLists()[controller.getCurrentPresetList()?.id]?.let { list ->
                    val preset = list.presets.removeAt(toIndex)
                    list.presets.add(fromIndex, preset)
                    controller.loadPresetsIntoTable(null)
                }
            }
        }
    }

    override fun redo() {
        logCommandExecutionMode("PresetMoveCommand.redo()", null, ", isHideDuplicates: ${controller.isHideDuplicatesMode()}, tempListsStateAfter: ${tempListsStateAfter != null}")
        
        // Проверяем, нужно ли переключить режим
        if (needToSwitchMode()) {
            println("ADB_DEBUG: Need to switch mode before redo")
            switchToOriginalMode()
            // После переключения режима, вызываем redo еще раз
            redo()
            return
        }
        
        // Если состояние было сохранено (операция была выполнена в режиме Hide Duplicates),
        // то восстанавливаем это состояние независимо от текущего режима
        if (tempListsStateAfter != null) {
            println("ADB_DEBUG: Restoring saved state after move operation")
            restoreState(tempListsStateAfter!!, visiblePresetsStateAfter, visiblePresetsAfter)
        } else {
            // Иначе выполняем стандартное перемещение
            when {
                controller.isShowAllPresetsMode() -> {
                    controller.tableModel.moveRow(fromIndex, fromIndex, toIndex)
                    controller.syncTableChangesToTempLists()
                }
                else -> {
                    controller.getTempPresetLists()[controller.getCurrentPresetList()?.id]?.let { list ->
                        val preset = list.presets.removeAt(fromIndex)
                        list.presets.add(toIndex, preset)
                        controller.loadPresetsIntoTable(null)
                    }
                }
            }
        }
    }

    /**
     * Извлекает видимые элементы для каждого списка
     */
    private fun extractVisiblePresets(state: Map<String, List<DevicePreset>>): Map<String, List<DevicePreset>> {
        val result = mutableMapOf<String, List<DevicePreset>>()
        
        state.forEach { (listId, presets) ->
            val visiblePresets = mutableListOf<DevicePreset>()
            val seenKeys = mutableSetOf<String>()
            
            println("ADB_DEBUG: extractVisiblePresets for list $listId:")
            presets.forEach { preset ->
                val key = "${preset.size}|${preset.dpi}"
                
                if (preset.size.isBlank() || preset.dpi.isBlank() || !seenKeys.contains(key)) {
                    visiblePresets.add(preset.copy())
                    println("ADB_DEBUG:   Preset '${preset.label}' is visible")
                    if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        seenKeys.add(key)
                    }
                } else {
                    println("ADB_DEBUG:   Preset '${preset.label}' is hidden (duplicate of $key)")
                }
            }
            
            result[listId] = visiblePresets
            println("ADB_DEBUG:   Total visible presets for list $listId: ${visiblePresets.size}")
        }
        
        return result
    }
    
    /**
     * Восстанавливает состояние всех списков
     */
    private fun restoreState(
        state: Map<String, List<DevicePreset>>, 
        visiblePresets: List<DevicePreset>?,
        savedVisiblePresets: Map<String, List<DevicePreset>>?
    ) {
        println("ADB_DEBUG: PresetMoveCommand.restoreState() - Starting restoration")
        println("ADB_DEBUG: Current isHideDuplicatesMode: ${controller.isHideDuplicatesMode()}")
        println("ADB_DEBUG: Current isShowAllMode: ${controller.isShowAllPresetsMode()}")
        println("ADB_DEBUG: Saved visiblePresets: ${visiblePresets?.size}")

        controller.setPerformingHistoryOperation(true)

        try {
            val tempLists = controller.getTempPresetLists()
            state.forEach { (listId, presets) ->
                tempLists[listId]?.let { list ->
                    println("ADB_DEBUG: Restoring list '$listId' (${list.name}) with ${presets.size} presets")
                    println("ADB_DEBUG:   Order to restore: ${presets.map { it.label }}")
                    list.presets.clear()
                    list.presets.addAll(presets.map { it.copy() })
                    println("ADB_DEBUG:   After restore: ${list.presets.map { it.label }}")
                }
            }

            // Загружаем пресеты с учетом сохраненных видимых элементов
            if (controller.isHideDuplicatesMode() && savedVisiblePresets != null) {
                // В режиме скрытия дубликатов используем сохраненные видимые элементы
                loadExactVisiblePresets(savedVisiblePresets)
            } else {
                // В обычном режиме загружаем все пресеты
                controller.loadPresetsIntoTable(null)
            }
            
            println("ADB_DEBUG: Table now has ${controller.tableModel.rowCount} rows")
        } finally {
            controller.setPerformingHistoryOperation(false)
        }
    }
    
    /**
     * Загружает точно те пресеты, которые были сохранены как видимые
     */
    private fun loadExactVisiblePresets(
        savedVisiblePresets: Map<String, List<DevicePreset>>
    ) {
        val tableModel = controller.tableModel
        val currentList = controller.getCurrentPresetList() ?: return
        
        // Очищаем таблицу
        while (tableModel.rowCount > 0) {
            tableModel.removeRow(0)
        }
        
        // Загружаем точно те пресеты, которые были видимыми
        val currentListId = currentList.id
        val visiblePresets = savedVisiblePresets[currentListId] ?: return
        
        println("ADB_DEBUG: Loading exact visible presets for list ${currentList.name}")
        println("ADB_DEBUG:   Visible presets count: ${visiblePresets.size}")
        
        visiblePresets.forEach { preset ->
            val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1)
            tableModel.addRow(rowData)
            println("ADB_DEBUG:   Added visible preset: ${preset.label}")
        }
        
        // Добавляем строку с кнопкой "+"
        val plusRow = Vector<Any>().apply {
            add("+")
            add(tableModel.rowCount + 1)
            add("")
            add("")
            add("")
            add("")
        }
        tableModel.addRow(plusRow)
        
        println("ADB_DEBUG: Loaded ${tableModel.rowCount - 1} visible presets (plus '+' button)")
    }
}
