package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import javax.swing.SwingUtilities

/**
 * Сервис для загрузки данных в таблицу пресетов
 * Управляет загрузкой пресетов с учетом различных режимов отображения
 */
class TableLoader(
    private val duplicateManager: DuplicateManager,
    private val viewModeManager: ViewModeManager
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
        onAddButtonRow: () -> Unit
    ) {
        println("ADB_DEBUG: TableLoader.loadPresetsIntoTable called")
        println("ADB_DEBUG:   isShowAllMode: $isShowAllMode")
        println("ADB_DEBUG:   currentPresetList: ${currentPresetList?.name}")
        currentPresetList?.let { list ->
            println("ADB_DEBUG:   currentPresetList contents (${list.presets.size} items):")
            list.presets.forEachIndexed { index, preset ->
                println("ADB_DEBUG:     [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
        }
        
        SwingUtilities.invokeLater {
            onTableUpdating(true)
            
            try {
                // Очищаем таблицу
                while (tableModel.rowCount > 0) {
                    tableModel.removeRow(0)
                }
                
                if (isShowAllMode) {
                    loadAllPresets(
                        tableModel,
                        tempPresetLists,
                        isHideDuplicatesMode,
                        isFirstLoad,
                        isSwitchingDuplicatesFilter
                    )
                } else {
                    loadCurrentListPresets(
                        tableModel,
                        currentPresetList,
                        isHideDuplicatesMode,
                        isFirstLoad,
                        isSwitchingList,
                        isSwitchingMode,
                        isSwitchingDuplicatesFilter
                    )
                }
                
                // Добавляем строку с кнопкой "+"
                if (!isShowAllMode) {
                    onAddButtonRow()
                }
                
                println("ADB_DEBUG: loadPresetsIntoTable - done, table has ${tableModel.rowCount} rows")
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
        isSwitchingDuplicatesFilter: Boolean
    ) {
        println("ADB_DEBUG: loadPresetsIntoTable - Show all presets mode")
        
        // Получаем сохраненный порядок
        val savedOrder = PresetListService.getShowAllPresetsOrder()
        val allPresets = viewModeManager.prepareShowAllTableModel(
            tempPresetLists,
            savedOrder
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
        isSwitchingDuplicatesFilter: Boolean
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
                    isSwitchingDuplicatesFilter
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
        val duplicateKeys = findGlobalDuplicateKeys(allPresets)
        val processedKeys = mutableSetOf<String>()
        var skippedCount = 0
        
        allPresets.forEach { (listName, preset) ->
            val key = "${preset.size}|${preset.dpi}"
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
        allPresets.forEach { (listName, preset) ->
            addPresetRow(tableModel, preset, listName)
        }
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
        @Suppress("UNUSED_PARAMETER") isSwitchingDuplicatesFilter: Boolean
    ) {
        val duplicateIndices = duplicateManager.findDuplicateIndices(list.presets)
        
        list.presets.forEachIndexed { index, preset ->
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
        list.presets.forEach { preset ->
            addPresetRow(tableModel, preset, null)
        }
    }
    
    /**
     * Добавляет строку пресета в таблицу
     */
    private fun addPresetRow(
        tableModel: DevicePresetTableModel,
        preset: DevicePreset,
        listName: String?
    ) {
        val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1)
        if (listName != null) {
            rowData.add(listName)
        }
        tableModel.addRow(rowData)
    }
    
    /**
     * Находит глобальные дубликаты во всех списках
     */
    private fun findGlobalDuplicateKeys(allPresets: List<Pair<String, DevicePreset>>): Set<String> {
        val keyCount = mutableMapOf<String, Int>()
        
        allPresets.forEach { (_, preset) ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = "${preset.size}|${preset.dpi}"
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
}