package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager

/**
 * Обработчик операций drag & drop для таблицы пресетов
 * Управляет перемещением строк и обновлением порядка пресетов
 */
class TableDragDropHandler(
    private val dialogStateManager: DialogStateManager,
    private val presetOrderManager: PresetOrderManager,
    private val tableSortingService: TableSortingService,
    private val tempListsManager: TempListsManager
) {
    
    /**
     * Обрабатывает перемещение строки в таблице
     */
    fun onRowMoved(
        fromIndex: Int,
        toIndex: Int,
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        historyManager: CommandHistoryManager,
        hoverState: HoverState,
        getListNameAtRow: (Int) -> String?,
        onHoverStateChanged: (HoverState) -> Unit,
        onNormalModeOrderChanged: () -> Unit,
        onModifiedListAdded: (String) -> Unit
    ): HoverState {
        println("ADB_DEBUG: onRowMoved called - fromIndex: $fromIndex, toIndex: $toIndex")
        println("ADB_DEBUG:   isShowAllPresetsMode: ${dialogStateManager.isShowAllPresetsMode()}")
        println("ADB_DEBUG:   currentPresetList: ${currentPresetList?.name} (id: ${currentPresetList?.id})")
        
        println("ADB_DEBUG: Table state after drag & drop:")
        val tablePresets = tableModel.getPresets()
        tablePresets.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        
        val orderAfter = tableModel.getPresets().map { it.label }

        historyManager.addPresetMove(fromIndex, toIndex, orderAfter)
        historyManager.onRowMoved(fromIndex, toIndex)

        var updatedHoverState = hoverState
        if (hoverState.selectedTableRow == fromIndex) {
            updatedHoverState = hoverState.withTableSelection(toIndex, hoverState.selectedTableColumn)
            onHoverStateChanged(updatedHoverState)
        } else if (hoverState.selectedTableRow != -1) {
            val selectedRow = hoverState.selectedTableRow
            val newSelectedRow = when {
                fromIndex < toIndex && selectedRow in (fromIndex + 1)..toIndex -> selectedRow - 1
                fromIndex > toIndex && selectedRow in toIndex until fromIndex -> selectedRow + 1
                else -> selectedRow
            }
            if (newSelectedRow != selectedRow) {
                updatedHoverState = hoverState.withTableSelection(newSelectedRow, hoverState.selectedTableColumn)
                onHoverStateChanged(updatedHoverState)
            }
        }

        println("ADB_DEBUG: onRowMoved - after historyManager calls")
        
        // Сохраняем порядок в зависимости от текущего режима
        if (dialogStateManager.isShowAllPresetsMode()) {
            handleShowAllModeOrder(tableModel, getListNameAtRow)
        } else {
            handleNormalModeOrder(
                tableModel, 
                currentPresetList, 
                onNormalModeOrderChanged,
                onModifiedListAdded
            )
        }
        
        // Сбрасываем сортировку после drag & drop
        tableSortingService.resetSort(
            dialogStateManager.isShowAllPresetsMode(), 
            dialogStateManager.isHideDuplicatesMode()
        )
        println("ADB_DEBUG: Reset sorting after drag & drop")
        
        println("ADB_DEBUG: onRowMoved - completed")
        
        return updatedHoverState
    }
    
    /**
     * Обрабатывает порядок в режиме Show All
     */
    private fun handleShowAllModeOrder(
        tableModel: DevicePresetTableModel,
        getListNameAtRow: (Int) -> String?
    ) {
        if (dialogStateManager.isHideDuplicatesMode()) {
            // Сохраняем порядок для режима Show All со скрытыми дубликатами
            val visiblePresets = mutableListOf<Pair<String, DevicePreset>>()
            for (i in 0 until tableModel.rowCount) {
                val preset = tableModel.getPresetAt(i)
                if (preset != null && preset.label.isNotEmpty()) { // Пропускаем строку с кнопкой
                    val listName = getListNameAtRow(i) ?: continue
                    visiblePresets.add(listName to preset)
                }
            }
            
            // Также обновляем основной savedOrder с учетом всех пресетов (включая скрытые дубли)
            val allPresetsWithLists = buildFullOrderWithHiddenDuplicates(
                tableModel, 
                getListNameAtRow,
                tempListsManager.getTempLists()
            )
            
            println("ADB_DEBUG: Updated order in memory for Show All mode with hidden duplicates - total ${allPresetsWithLists.size} presets")
        } else {
            // Собираем порядок из таблицы для Show All режима
            val allPresetsWithLists = collectShowAllOrder(tableModel)
            
            println("ADB_DEBUG: Updated order in memory for Show All mode with ${allPresetsWithLists.size} items")
        }
    }
    
    /**
     * Обрабатывает порядок в обычном режиме
     */
    private fun handleNormalModeOrder(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        onNormalModeOrderChanged: () -> Unit,
        onModifiedListAdded: (String) -> Unit
    ) {
        if (currentPresetList == null) return
        
        // Получаем текущий порядок из таблицы
        val currentTablePresets = tableModel.getPresets()
        
        // Если включено скрытие дублей, нужно восстановить полный список с дублями
        val presetsToSave = if (dialogStateManager.isHideDuplicatesMode()) {
            reconstructFullOrderWithDuplicates(
                currentTablePresets,
                tempListsManager.getTempList(currentPresetList.id)
            )
        } else {
            currentTablePresets
        }
        
        // Обновляем порядок в памяти для использования при переключении списков
        presetOrderManager.updateNormalModeOrderInMemory(currentPresetList.id, presetsToSave)
        println("ADB_DEBUG: Updated normal mode order in memory after drag & drop for list '${currentPresetList.name}' with ${presetsToSave.size} presets")
        
        // Обновляем порядок в tempLists, чтобы он соответствовал текущему порядку в таблице
        val tempList = tempListsManager.getTempList(currentPresetList.id)
        if (tempList != null) {
            tempList.presets.clear()
            tempList.presets.addAll(presetsToSave)
            println("ADB_DEBUG: Updated tempList order for '${tempList.name}' with ${presetsToSave.size} presets")
        }
        
        // Устанавливаем флаг, что порядок был изменен через drag & drop
        onNormalModeOrderChanged()
        onModifiedListAdded(currentPresetList.id)
        println("ADB_DEBUG: Set normalModeOrderChanged = true, added list '${currentPresetList.name}' to modified lists")
    }
    
    /**
     * Строит полный порядок с учетом скрытых дубликатов
     */
    private fun buildFullOrderWithHiddenDuplicates(
        tableModel: DevicePresetTableModel,
        getListNameAtRow: (Int) -> String?,
        tempLists: Map<String, PresetList>
    ): List<Pair<String, DevicePreset>> {
        val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
        val processedPresetIds = mutableSetOf<String>()
        
        // Проходим по видимым пресетам в таблице (они уже в правильном порядке после drag & drop)
        for (i in 0 until tableModel.rowCount) {
            val preset = tableModel.getPresetAt(i)
            if (preset == null || preset.label.isEmpty()) continue // Пропускаем строку с кнопкой
            
            val listName = getListNameAtRow(i) ?: continue
            
            // Добавляем видимый пресет
            allPresetsWithLists.add(listName to preset)
            processedPresetIds.add(preset.id)
            
            // Находим все дубликаты этого пресета во всех списках
            val duplicateKey = preset.getDuplicateKey()
            tempLists.forEach { (_, list) ->
                list.presets.forEach { duplicatePreset ->
                    if (duplicatePreset.id != preset.id && 
                        duplicatePreset.getDuplicateKey() == duplicateKey &&
                        duplicatePreset.id !in processedPresetIds) {
                        // Добавляем дубликат сразу после основного пресета
                        allPresetsWithLists.add(list.name to duplicatePreset)
                        processedPresetIds.add(duplicatePreset.id)
                    }
                }
            }
        }
        
        // Добавляем оставшиеся пресеты, которые не являются дубликатами
        tempLists.forEach { (_, list) ->
            list.presets.forEach { preset ->
                if (preset.id !in processedPresetIds) {
                    allPresetsWithLists.add(list.name to preset)
                    processedPresetIds.add(preset.id)
                }
            }
        }
        
        return allPresetsWithLists
    }
    
    /**
     * Собирает порядок из таблицы для режима Show All
     */
    private fun collectShowAllOrder(tableModel: DevicePresetTableModel): List<Pair<String, DevicePreset>> {
        val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
        val listColumn = if (tableModel.columnCount > 6) 6 else -1
        
        for (i in 0 until tableModel.rowCount) {
            // Пропускаем строку с кнопкой "+"
            if (tableModel.getValueAt(i, 0) == "+") {
                continue
            }
            
            val listName = if (listColumn >= 0) {
                tableModel.getValueAt(i, listColumn)?.toString() ?: ""
            } else {
                ""
            }
            
            if (listName.isNotEmpty()) {
                val preset = tableModel.getPresetAt(i)
                if (preset != null) {
                    allPresetsWithLists.add(listName to preset)
                }
            }
        }
        
        return allPresetsWithLists
    }
    
    /**
     * Восстанавливает полный список с дублями для обычного режима
     */
    private fun reconstructFullOrderWithDuplicates(
        currentTablePresets: List<DevicePreset>,
        tempList: PresetList?
    ): List<DevicePreset> {
        if (tempList == null) return currentTablePresets
        
        // Создаём карты для быстрого поиска
        currentTablePresets.associateBy { "${it.label}|${it.size}|${it.dpi}" }
        tempList.presets.associateBy { "${it.label}|${it.size}|${it.dpi}" }
        
        // Новый список с сохранением порядка
        val fullOrderedList = mutableListOf<DevicePreset>()
        val addedKeys = mutableSetOf<String>()
        
        // Проходим по видимым пресетам и восстанавливаем их позиции с дублями
        currentTablePresets.forEach { visiblePreset ->
            val duplicateKey = visiblePreset.getDuplicateKey()
            
            // Сначала добавляем все пресеты с таким же duplicate key из оригинального списка
            // в порядке их следования в оригинальном списке
            val presetsWithSameDuplicateKey = tempList.presets.filter { preset ->
                preset.getDuplicateKey() == duplicateKey
            }
            
            presetsWithSameDuplicateKey.forEach { preset ->
                val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                if (presetKey !in addedKeys) {
                    fullOrderedList.add(preset)
                    addedKeys.add(presetKey)
                }
            }
        }
        
        // Добавляем оставшиеся пресеты (которые не были дублями видимых)
        tempList.presets.forEach { preset ->
            val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
            if (presetKey !in addedKeys) {
                fullOrderedList.add(preset)
                addedKeys.add(presetKey)
            }
        }
        
        println("ADB_DEBUG: Reconstructed full order with ${fullOrderedList.size} presets (${currentTablePresets.size} visible)")
        println("ADB_DEBUG: Original tempList had ${tempList.presets.size} presets")
        
        return fullOrderedList
    }
}