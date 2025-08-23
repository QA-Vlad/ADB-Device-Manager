package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager
import javax.swing.JTable
import javax.swing.SwingUtilities

/**
 * Сервис для загрузки данных в таблицу пресетов
 * Управляет загрузкой пресетов с учетом различных режимов отображения
 */
class TableLoader(
    private val viewModeManager: ViewModeManager,
    private val presetOrderManager: PresetOrderManager = PresetOrderManager(),
    private val tableSortingService: TableSortingService? = null,
    private val dialogStateManager: DialogStateManager? = null
) {
    
    /**
     * Загружает пресеты в таблицу с учетом текущих режимов
     */
    fun loadPresetsIntoTable(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        tempPresetLists: Map<String, PresetList>,
        isShowAllMode: Boolean,
        isHideDuplicatesMode: Boolean,
        isFirstLoad: Boolean,
        isSwitchingList: Boolean,
        isSwitchingMode: Boolean,
        isSwitchingDuplicatesFilter: Boolean,
        onTableUpdating: (Boolean) -> Unit,
        onAddButtonRow: () -> Unit,
        inMemoryOrder: List<String>? = null,
        initialHiddenDuplicates: Map<String, Set<String>>? = null,
        table: JTable? = null,
        onClearTableSelection: (() -> Unit)? = null
    ) {
        println("ADB_DEBUG: TableLoader.loadPresetsIntoTable called")
        println("ADB_DEBUG:   isShowAllMode: $isShowAllMode")
        println("ADB_DEBUG:   currentPresetList: ${currentPresetList?.name}")
        println("ADB_DEBUG:   tempPresetLists.size: ${tempPresetLists.size}")
        println("ADB_DEBUG:   tempPresetLists.isEmpty: ${tempPresetLists.isEmpty()}")
        
        // Log all tempPresetLists
        if (tempPresetLists.isEmpty()) {
            println("ADB_DEBUG:   tempPresetLists is EMPTY!")
        } else {
            println("ADB_DEBUG:   tempPresetLists contents:")
            tempPresetLists.forEach { (listId, list) ->
                println("ADB_DEBUG:     List '${list.name}' (id: $listId) has ${list.presets.size} presets")
            }
        }
        
        currentPresetList?.let { list ->
            println("ADB_DEBUG:   currentPresetList contents (${list.presets.size} items):")
            list.presets.forEachIndexed { index, preset ->
                println("ADB_DEBUG:     [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
        }
        
        SwingUtilities.invokeLater {
            onTableUpdating(true)
            
            try {
                // Очищаем таблицу и все ID
                tableModel.clearAllPresetIds()
                while (tableModel.rowCount > 0) {
                    tableModel.removeRow(0)
                }
                
                if (isShowAllMode) {
                    loadAllPresets(
                        tableModel,
                        tempPresetLists,
                        isHideDuplicatesMode,
                        isFirstLoad,
                        isSwitchingDuplicatesFilter,
                        inMemoryOrder,
                        initialHiddenDuplicates
                    )
                } else {
                    loadCurrentListPresets(
                        tableModel,
                        currentPresetList,
                        isHideDuplicatesMode,
                        isFirstLoad,
                        isSwitchingList,
                        isSwitchingMode,
                        isSwitchingDuplicatesFilter,
                        initialHiddenDuplicates
                    )
                }
                
                // Добавляем строку с кнопкой "+"
                if (!isShowAllMode) {
                    onAddButtonRow()
                }
                
                println("ADB_DEBUG: loadPresetsIntoTable - done, table has ${tableModel.rowCount} rows")
                
                // Обновляем состояние таблицы для отслеживания позиций пресетов
                TableStateTracker.updateTableState(tableModel)
                
                // Восстанавливаем выделение, если таблица передана
                if (table != null && SelectionTracker.hasSelection()) {
                    // Очищаем старое выделение из HoverState перед восстановлением
                    onClearTableSelection?.invoke()
                    
                    // Задерживаем восстановление выделения, чтобы убедиться,
                    // что таблица полностью обновилась
                    SwingUtilities.invokeLater {
                        SelectionTracker.restoreSelection(table)
                    }
                }
            } finally {
                onTableUpdating(false)
            }
        }
    }
    
    /**
     * Загружает все пресеты в режиме Show All
     */
    private fun loadAllPresets(
        tableModel: DevicePresetTableModel,
        tempPresetLists: Map<String, PresetList>,
        isHideDuplicatesMode: Boolean,
        isFirstLoad: Boolean,
        isSwitchingDuplicatesFilter: Boolean,
        inMemoryOrder: List<String>? = null,
        initialHiddenDuplicates: Map<String, Set<String>>? = null
    ) {
        println("ADB_DEBUG: loadPresetsIntoTable - Show all presets mode")
        
        // Используем порядок из памяти если он есть, иначе из файла
        val orderToUse = if (!inMemoryOrder.isNullOrEmpty()) {
            println("ADB_DEBUG: Using in-memory order with ${inMemoryOrder.size} items")
            inMemoryOrder
        } else {
            PresetListService.getShowAllPresetsOrder()
        }
        
        // Собираем все скрытые дубликаты из всех списков
        val allHiddenDuplicateIds = initialHiddenDuplicates?.values?.flatten()?.toSet()
        
        val allPresets = viewModeManager.prepareShowAllTableModel(
            tempPresetLists,
            orderToUse,
            allHiddenDuplicateIds
        )
        
        if (isHideDuplicatesMode) {
            loadAllPresetsWithHiddenDuplicates(
                tableModel,
                allPresets,
                tempPresetLists,
                isFirstLoad,
                isSwitchingDuplicatesFilter
            )
        } else {
            loadAllPresetsNormal(tableModel, allPresets)
        }
    }
    
    /**
     * Загружает пресеты текущего списка
     */
    private fun loadCurrentListPresets(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        isHideDuplicatesMode: Boolean,
        isFirstLoad: Boolean,
        isSwitchingList: Boolean,
        isSwitchingMode: Boolean,
        isSwitchingDuplicatesFilter: Boolean,
        initialHiddenDuplicates: Map<String, Set<String>>? = null
    ) {
        currentPresetList?.let { list ->
            println("ADB_DEBUG: Loading list: ${list.name}, presets count: ${list.presets.size}")
            
            if (isHideDuplicatesMode) {
                loadCurrentListWithHiddenDuplicates(
                    tableModel,
                    list,
                    isFirstLoad,
                    isSwitchingList,
                    isSwitchingMode,
                    isSwitchingDuplicatesFilter,
                    initialHiddenDuplicates?.get(list.id)
                )
            } else {
                loadCurrentListNormal(tableModel, list)
            }
        }
    }
    
    /**
     * Загружает все пресеты с учетом скрытых дубликатов
     */
    private fun loadAllPresetsWithHiddenDuplicates(
        tableModel: DevicePresetTableModel,
        allPresets: List<Pair<String, DevicePreset>>,
        @Suppress("UNUSED_PARAMETER") tempPresetLists: Map<String, PresetList>,
        @Suppress("UNUSED_PARAMETER") isFirstLoad: Boolean,
        @Suppress("UNUSED_PARAMETER") isSwitchingDuplicatesFilter: Boolean
    ) {
        // Применяем сохраненный порядок для режима Show All со скрытыми дубликатами
        val savedOrder = presetOrderManager.getShowAllHideDuplicatesOrder()
        val orderedPresets = if (savedOrder != null) {
            // Создаем карту для быстрого поиска
            val orderMap = savedOrder.withIndex().associate { it.value to it.index }
            allPresets.sortedBy { (listName, preset) ->
                val key = "$listName::${preset.label}"
                orderMap[key] ?: Int.MAX_VALUE
            }
        } else {
            allPresets
        }
        
        // Применяем сортировку, если она активна
        val sortedPresets = tableSortingService?.sortPresetsWithLists(orderedPresets, true) ?: orderedPresets
        
        val duplicateKeys = findGlobalDuplicateKeys(sortedPresets)
        val processedKeys = mutableSetOf<String>()
        var skippedCount = 0
        
        sortedPresets.forEach { (listName, preset) ->
            val key = preset.getDuplicateKey()
            val isEmptyPreset = preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
            
            val shouldSkip = when {
                isEmptyPreset -> false
                key in duplicateKeys && key in processedKeys -> {
                    skippedCount++
                    true
                }
                else -> false
            }
            
            if (!shouldSkip) {
                if (key in duplicateKeys) {
                    processedKeys.add(key)
                }
                addPresetRow(tableModel, preset, listName)
            }
        }
        
        println("ADB_DEBUG: Loaded ${tableModel.rowCount} presets (skipped $skippedCount duplicates)")
    }
    
    /**
     * Загружает все пресеты в обычном режиме
     */
    private fun loadAllPresetsNormal(
        tableModel: DevicePresetTableModel,
        allPresets: List<Pair<String, DevicePreset>>
    ) {
        println("ADB_DEBUG: loadAllPresetsNormal - start")
        println("ADB_DEBUG:   allPresets size: ${allPresets.size}")
        println("ADB_DEBUG:   First 5 presets:")
        allPresets.take(5).forEachIndexed { index, (listName, preset) ->
            println("ADB_DEBUG:     [$index] $listName - ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        
        // Применяем сортировку, если она активна
        val sortedPresets = if (tableSortingService != null) {
            println("ADB_DEBUG:   tableSortingService is not null, checking for active sorting")
            val sorted = tableSortingService.sortPresetsWithLists(allPresets, false)
            println("ADB_DEBUG:   After sorting, first 5 presets:")
            sorted.take(5).forEachIndexed { index, (listName, preset) ->
                println("ADB_DEBUG:     [$index] $listName - ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
            sorted
        } else {
            println("ADB_DEBUG:   tableSortingService is null, no sorting")
            allPresets
        }
        
        println("ADB_DEBUG:   Adding ${sortedPresets.size} rows to table")
        sortedPresets.forEachIndexed { index, (listName, preset) ->
            println("ADB_DEBUG:   Adding row $index: listName='$listName', preset='${preset.label}'")
            addPresetRow(tableModel, preset, listName)
        }
        println("ADB_DEBUG: loadAllPresetsNormal - done")
    }
    
    /**
     * Загружает пресеты текущего списка с учетом скрытых дубликатов
     */
    private fun loadCurrentListWithHiddenDuplicates(
        tableModel: DevicePresetTableModel,
        list: PresetList,
        @Suppress("UNUSED_PARAMETER") isFirstLoad: Boolean,
        @Suppress("UNUSED_PARAMETER") isSwitchingList: Boolean,
        @Suppress("UNUSED_PARAMETER") isSwitchingMode: Boolean,
        @Suppress("UNUSED_PARAMETER") isSwitchingDuplicatesFilter: Boolean,
        initialHiddenDuplicatesForList: Set<String>? = null
    ) {
        println("ADB_DEBUG: loadCurrentListWithHiddenDuplicates - start")
        
        // Применяем сортировку к списку пресетов
        val sortedPresets = if (tableSortingService != null) {
            println("ADB_DEBUG: loadCurrentListWithHiddenDuplicates - applying sort")
            tableSortingService.sortPresets(list.presets, isShowAll = false, isHideDuplicates = true)
        } else {
            list.presets
        }
        
        // Создаем временную копию для позиционирования, не изменяя оригинальный список
        val listPairs = sortedPresets.map { list.name to it }
        val processedPairs = mutableListOf<Pair<String, DevicePreset>>()
        processedPairs.addAll(listPairs)
        
        // Используем начальные скрытые дубли или вычисляем их
        val hiddenDuplicatesBeforePositioning = if (initialHiddenDuplicatesForList != null) {
            println("ADB_DEBUG: Using initial hidden duplicates: ${initialHiddenDuplicatesForList.size} items")
            initialHiddenDuplicatesForList
        } else {
            // Вычисляем, какие пресеты были скрыты как дубли ДО позиционирования
            val hiddenIds = mutableSetOf<String>()
            val seenKeysBeforePositioning = mutableSetOf<String>()
            
            listPairs.forEach { (_, preset) ->
                val key = preset.getDuplicateKey()
                if (seenKeysBeforePositioning.contains(key)) {
                    hiddenIds.add(preset.id)
                } else {
                    seenKeysBeforePositioning.add(key)
                }
            }
            println("ADB_DEBUG: Calculated hidden duplicates: ${hiddenIds.size} items")
            hiddenIds
        }
        
        // Сохраняем исходный порядок перед позиционированием
        val originalOrder = processedPairs.map { it.second.id }
        
        // Создаем временную копию списка для позиционирования (не изменяем оригинал)
        PresetList(
            id = list.id,
            name = list.name,
            isDefault = list.isDefault
        ).apply {
            presets.addAll(list.presets.map { it.copy(id = it.id) })
        }
        
        // Проверяем, активна ли сортировка
        val isSortingActive = tableSortingService?.getCurrentSortState()?.activeColumn != null
        
        // Вызываем handleFormerDuplicatesPositioning с временной копией
        viewModeManager.handleFormerDuplicatesPositioning(
            processedPairs,
            hiddenDuplicatesBeforePositioning,
            isShowAllMode = false,
            isSortingActive = isSortingActive
        )
        
        // Проверяем, изменился ли порядок после позиционирования
        val newOrder = processedPairs.map { it.second.id }
        val orderChanged = originalOrder != newOrder
        
        if (orderChanged) {
            println("ADB_DEBUG: Order changed after former duplicate positioning in normal mode")
            
            // В обычном режиме обновляем порядок в оригинальном списке
            val newPresets = mutableListOf<DevicePreset>()
            processedPairs.forEach { (_, preset) ->
                // Находим оригинальный пресет по ID
                val originalPreset = list.presets.find { it.id == preset.id }
                if (originalPreset != null) {
                    newPresets.add(originalPreset)
                }
            }
            
            // Заменяем пресеты в списке на новый порядок
            list.presets.clear()
            list.presets.addAll(newPresets)
            
            // Сохраняем обновленный список в файл
            println("ADB_DEBUG: Saving list with updated order after former duplicate positioning")
            PresetListService.savePresetList(list)
        }
        
        // Теперь фильтруем дубликаты после позиционирования
        val duplicateIndices = mutableSetOf<Int>()
        val seenKeys = mutableSetOf<String>()
        
        processedPairs.forEachIndexed { index, (_, preset) ->
            val key = preset.getDuplicateKey()
            if (seenKeys.contains(key)) {
                duplicateIndices.add(index)
            } else {
                seenKeys.add(key)
            }
        }
        
        // Добавляем только видимые пресеты в таблицу
        processedPairs.forEachIndexed { index, (_, preset) ->
            if (!duplicateIndices.contains(index)) {
                addPresetRow(tableModel, preset, null)
            }
        }
        
        println("ADB_DEBUG: Loaded ${tableModel.rowCount} visible presets from ${list.presets.size} total")
    }
    
    
    /**
     * Загружает пресеты текущего списка в обычном режиме
     */
    private fun loadCurrentListNormal(
        tableModel: DevicePresetTableModel,
        list: PresetList
    ) {
        // println("ADB_DEBUG: loadCurrentListNormal - start")
        // println("ADB_DEBUG: loadCurrentListNormal - list: ${list.name}, presets count: ${list.presets.size}")
        
        // Проверяем, есть ли в памяти порядок после drag & drop
        val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(list.id)
        val presets = if (memoryOrder != null) {
            println("ADB_DEBUG: Using in-memory order for list '${list.name}' with ${memoryOrder.size} items")
            // Создаем карту пресетов по ID
            val presetsMap = list.presets.associateBy { it.id }
            // Восстанавливаем порядок из памяти
            val orderedPresets = mutableListOf<DevicePreset>()
            memoryOrder.forEach { presetId ->
                presetsMap[presetId]?.let { orderedPresets.add(it) }
            }
            // Добавляем пресеты, которых нет в сохранённом порядке (новые пресеты)
            list.presets.forEach { preset ->
                if (preset.id !in memoryOrder) {
                    orderedPresets.add(preset)
                }
            }
            orderedPresets
        } else {
            // Пресеты уже в правильном порядке в файле, поэтому просто используем их
            list.presets
        }
        
        // println("ADB_DEBUG: loadCurrentListNormal - presets from list:")
        // presets.forEachIndexed { index, preset ->
        //     println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        // }
        
        // Применяем сортировку, если она активна
        val sortedPresets = if (tableSortingService != null) {
            // println("ADB_DEBUG: loadCurrentListNormal - tableSortingService is not null, applying sort")
            val sorted = tableSortingService.sortPresets(presets, isShowAll = false, isHideDuplicates = false)
            // println("ADB_DEBUG: loadCurrentListNormal - after sorting:")
            // sorted.forEachIndexed { index, preset ->
            //     println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            // }
            sorted
        } else {
            println("ADB_DEBUG: loadCurrentListNormal - tableSortingService is null, no sorting")
            presets
        }
        
        // println("ADB_DEBUG: loadCurrentListNormal - adding ${sortedPresets.size} rows to table")
        sortedPresets.forEach { preset ->
            addPresetRow(tableModel, preset, null)
        }
        // println("ADB_DEBUG: loadCurrentListNormal - done")
    }
    
    /**
     * Добавляет строку пресета в таблицу
     */
    private fun addPresetRow(
        tableModel: DevicePresetTableModel,
        preset: DevicePreset,
        listName: String?
    ) {
        // Определяем, показывать ли счетчики на основе наличия колонок счетчиков
        val showCounters = when {
            listName != null -> tableModel.columnCount > 7  // Show All mode
            else -> tableModel.columnCount > 6              // Normal mode
        }
        val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1, showCounters)
        // Закомментированы частые логи
        // println("ADB_DEBUG: addPresetRow - preset id: ${preset.id}, label: ${preset.label}")
        // println("ADB_DEBUG: addPresetRow - Initial rowData size: ${rowData.size}, showCounters: $showCounters")
        if (listName != null) {
            rowData.add(listName)
            // println("ADB_DEBUG: addPresetRow - Added listName '$listName' to rowData. Final rowData size: ${rowData.size}, tableModel.columnCount: ${tableModel.columnCount}")
            // println("ADB_DEBUG: addPresetRow - rowData contents: ${rowData.joinToString(", ")}")
        }
        // Используем новый метод для добавления строки с ID
        tableModel.addRowWithPresetId(rowData, preset.id)
    }
    
    /**
     * Находит глобальные дубликаты во всех списках
     */
    private fun findGlobalDuplicateKeys(allPresets: List<Pair<String, DevicePreset>>): Set<String> {
        val keyCount = mutableMapOf<String, Int>()
        
        allPresets.forEach { (_, preset) ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = preset.getDuplicateKey()
                keyCount[key] = keyCount.getOrDefault(key, 0) + 1
            }
        }
        
        return keyCount.filter { it.value > 1 }.keys.toSet()
    }
    
    /**
     * Добавляет строку с кнопкой "+" в конец таблицы
     */
    fun addButtonRow(tableModel: DevicePresetTableModel, isShowAllMode: Boolean) {
        if (!isShowAllMode) {
            val buttonRowData = java.util.Vector<Any>()
            buttonRowData.add("+")
            repeat(tableModel.columnCount - 1) { buttonRowData.add("") }
            tableModel.addRow(buttonRowData)
        }
    }
    
    /**
     * Обновляет таблицу с проверкой полей и перерисовкой
     */
    fun refreshTable(
        table: JTable,
        onValidateFields: () -> Unit
    ) {
        SwingUtilities.invokeLater {
            onValidateFields()
            table.repaint()
        }
    }
    
    /**
     * Перезагружает таблицу без активации слушателей
     * Сохраняет и восстанавливает выделение
     */
    fun reloadTableWithoutListeners(
        table: JTable,
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        tempPresetLists: Map<String, PresetList>,
        isShowAllMode: Boolean,
        isHideDuplicatesMode: Boolean,
        onTableUpdating: (Boolean) -> Unit,
        onAddButtonRow: () -> Unit,
        onSaveCurrentTableOrder: () -> Unit,
        onClearLastInteractedRow: () -> Unit,
        tableModelListener: javax.swing.event.TableModelListener?,
        inMemoryOrder: List<String>? = null,
        initialHiddenDuplicates: Map<String, Set<String>>? = null
    ) {
        SwingUtilities.invokeLater {
            // Сохраняем текущую выделенную строку
            val selectedRow = table.selectedRow
            val selectedColumn = table.selectedColumn
            println("ADB_DEBUG: reloadTableWithoutListeners - selectedRow: $selectedRow, selectedColumn: $selectedColumn")
            
            // Сохраняем данные о выделенном пресете для восстановления после обновления
            var selectedPresetKey: String? = null
            if (selectedRow >= 0 && selectedRow < tableModel.rowCount) {
                val preset = tableModel.getPresetAt(selectedRow)
                if (preset != null) {
                    selectedPresetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                    println("ADB_DEBUG: reloadTableWithoutListeners - saved preset key: $selectedPresetKey")
                }
            }
            
            // Если мы в режиме Show All, обновляем inMemoryTableOrder перед перезагрузкой
            if (isShowAllMode) {
                onSaveCurrentTableOrder()
                println("ADB_DEBUG: reloadTableWithoutListeners - updated inMemoryTableOrder before reload")
            }
            
            // Сбрасываем lastInteractedRow так как таблица перезагружается
            onClearLastInteractedRow()
            println("ADB_DEBUG: reloadTableWithoutListeners - reset lastInteractedRow to -1")
            
            // Временно отключаем слушатель модели, чтобы избежать рекурсии
            tableModelListener?.let { tableModel.removeTableModelListener(it) }

            dialogStateManager?.withTableUpdate {
                loadPresetsIntoTable(
                    tableModel,
                    currentPresetList,
                    tempPresetLists,
                    isShowAllMode,
                    isHideDuplicatesMode,
                    isFirstLoad = false,
                    isSwitchingList = false,
                    isSwitchingMode = false,
                    isSwitchingDuplicatesFilter = false,
                    onTableUpdating,
                    onAddButtonRow,
                    inMemoryOrder,
                    initialHiddenDuplicates,
                    table,
                    null
                )
            } ?: loadPresetsIntoTable(
                tableModel,
                currentPresetList,
                tempPresetLists,
                isShowAllMode,
                isHideDuplicatesMode,
                isFirstLoad = false,
                isSwitchingList = false,
                isSwitchingMode = false,
                isSwitchingDuplicatesFilter = false,
                onTableUpdating,
                onAddButtonRow,
                inMemoryOrder,
                initialHiddenDuplicates,
                table,
                null
            )
            
            // Возвращаем слушатель на место
            tableModelListener?.let { tableModel.addTableModelListener(it) }
            
            // Восстанавливаем выделение строки
            if (selectedPresetKey != null) {
                println("ADB_DEBUG: reloadTableWithoutListeners - searching for preset key: $selectedPresetKey")
                for (row in 0 until tableModel.rowCount) {
                    val preset = tableModel.getPresetAt(row)
                    if (preset != null) {
                        val key = "${preset.label}|${preset.size}|${preset.dpi}"
                        if (key == selectedPresetKey) {
                            println("ADB_DEBUG: reloadTableWithoutListeners - found at row $row, restoring selection")
                            table.setRowSelectionInterval(row, row)
                            if (selectedColumn >= 0 && selectedColumn < table.columnCount) {
                                table.setColumnSelectionInterval(selectedColumn, selectedColumn)
                            }
                            // Обновляем lastInteractedRow после восстановления выделения
                            println("ADB_DEBUG: reloadTableWithoutListeners - after restore, selectedRow: ${table.selectedRow}")
                            break
                        }
                    }
                }
            } else {
                println("ADB_DEBUG: reloadTableWithoutListeners - no preset key to restore")
            }
        }
    }
}