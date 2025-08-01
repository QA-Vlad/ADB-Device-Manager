package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager

/**
 * Менеджер для управления сохранением пресетов и их порядка.
 * Объединяет логику сохранения из контроллера для упрощения кода.
 */
class PresetSaveManager(
    private val presetOrderManager: PresetOrderManager,
    private val settingsPersistenceService: PresetsPersistenceService
) {
    
    /**
     * Сохраняет все настройки включая порядок пресетов
     */
    fun saveSettings(
        table: JBTable,
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        currentPresetList: PresetList?,
        dialogState: DialogStateManager,
        normalModeOrderChanged: Boolean,
        modifiedListIds: Set<String>,
        onSaveCurrentTableState: () -> Unit
    ) {
        // Конвертируем все размеры обратно в портретную ориентацию перед сохранением
        // чтобы в файлах всегда хранились оригинальные значения
        val savedOrientation = PresetStorageService.getOrientation()
        println("ADB_DEBUG: PresetSaveManager.saveSettings - savedOrientation: $savedOrientation")
        if (savedOrientation == "LANDSCAPE") {
            println("ADB_DEBUG: Converting all presets back to portrait orientation before saving")
            var convertedCount = 0
            tempListsManager.getTempLists().values.forEach { list ->
                println("ADB_DEBUG:   Processing list: ${list.name}")
                list.presets.forEach { preset ->
                    val parts = preset.size.split("x")
                    if (parts.size == 2) {
                        val width = parts[0].toIntOrNull()
                        val height = parts[1].toIntOrNull()
                        if (width != null && height != null && width > height) {
                            // Меняем местами только если ширина больше высоты (горизонтальная ориентация)
                            val oldSize = preset.size
                            preset.size = "${height}x${width}"
                            println("ADB_DEBUG:     Converted ${preset.label}: $oldSize -> ${preset.size}")
                            convertedCount++
                        }
                    }
                }
            }
            println("ADB_DEBUG: Converted $convertedCount presets to portrait orientation")
        }
        
        // Если включен режим Show All, нужно сохранить текущий порядок из таблицы
        if (dialogState.isShowAllPresetsMode()) {
            saveShowAllModeOrder(
                tableModel,
                tempListsManager,
                dialogState,
                normalModeOrderChanged,
                modifiedListIds
            )
        } else {
            // В обычном режиме сохраняем порядок для всех изменённых списков
            saveNormalModeOrder(
                tableModel,
                tempListsManager,
                currentPresetList,
                dialogState,
                normalModeOrderChanged,
                modifiedListIds
            )
        }
        
        // Делегируем основное сохранение существующему сервису
        settingsPersistenceService.saveSettings(
            table = table,
            tempLists = tempListsManager.getTempLists(),
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            presetOrderManager = presetOrderManager,
            onSaveCurrentTableState = onSaveCurrentTableState,
            onSaveShowAllPresetsOrder = { 
                // Порядок уже сохранен выше
            }
        )
        
        // После сохранения конвертируем обратно в горизонтальную ориентацию если нужно
        if (savedOrientation == "LANDSCAPE") {
            println("ADB_DEBUG: Converting presets back to landscape orientation after saving")
            tempListsManager.getTempLists().values.forEach { list ->
                list.presets.forEach { preset ->
                    val parts = preset.size.split("x")
                    if (parts.size == 2) {
                        val width = parts[0].toIntOrNull()
                        val height = parts[1].toIntOrNull()
                        if (width != null && height != null && height > width) {
                            // Меняем местами только если высота больше ширины (портретная ориентация)
                            preset.size = "${height}x${width}"
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Сохраняет текущий порядок пресетов из таблицы в режиме Show All
     */
    fun saveCurrentShowAllOrderFromTable(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        dialogState: DialogStateManager
    ): List<String> {
        if (!dialogState.isShowAllPresetsMode()) return emptyList()
        
        println("ADB_DEBUG: saveCurrentShowAllOrderFromTable - saving Show All order to file")
        
        // Собираем порядок из таблицы
        val allPresetsWithLists = collectPresetsFromTable(tableModel)
        
        if (dialogState.isHideDuplicatesMode()) {
            // В режиме Hide Duplicates сохраняем текущий видимый порядок,
            // но полный порядок со всеми дубликатами уже должен быть сохранён
            
            // Просто обновляем inMemoryTableOrder из существующего полного порядка
            val existingFullOrder = PresetListService.getShowAllPresetsOrder()
            if (existingFullOrder.isNotEmpty()) {
                println("ADB_DEBUG: Using existing full order for Hide Duplicates mode: ${existingFullOrder.size} items")
                return existingFullOrder
            } else {
                // Если по какой-то причине нет полного порядка, создаём его
                println("ADB_DEBUG: WARNING: No existing full order in Hide Duplicates mode, creating from temp lists")
                val fullOrderWithDuplicates = collectAllPresetsFromTempLists(tempListsManager)
                
                presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
                println("ADB_DEBUG: Created and saved full order: ${fullOrderWithDuplicates.size} items")
                
                return fullOrderWithDuplicates.map { (listName, preset) ->
                    "${listName}::${preset.id}"
                }
            }
        } else {
            // В обычном режиме Show All сохраняем как есть
            presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
            println("ADB_DEBUG: Saved Show All order: ${allPresetsWithLists.size} items")
            
            // Возвращаем порядок для использования в памяти
            return allPresetsWithLists.map { (listName, preset) ->
                "${listName}::${preset.id}"
            }
        }
    }
    
    /**
     * Сохраняет текущий порядок таблицы в памяти
     */
    fun saveCurrentTableOrderToMemory(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        dialogState: DialogStateManager
    ): List<String> {
        if (!dialogState.isShowAllPresetsMode()) return emptyList()
        
        // Собираем порядок из таблицы
        val allPresetsWithLists = collectPresetsFromTable(tableModel)
        
        // Если включен Hide Duplicates, нужно восстановить полный порядок с дубликатами
        if (dialogState.isHideDuplicatesMode()) {
            // Получаем текущий сохранённый порядок Show All (который включает все пресеты)
            val existingFullOrder = PresetListService.getShowAllPresetsOrder()
            
            if (existingFullOrder.isNotEmpty()) {
                // Используем существующий полный порядок
                println("ADB_DEBUG: Using existing full order with ${existingFullOrder.size} items")
                return existingFullOrder
            } else {
                // Если нет сохранённого порядка, создаём новый на основе всех пресетов
                println("ADB_DEBUG: No existing full order, creating new one")
                val fullOrderWithDuplicates = collectAllPresetsFromTempLists(tempListsManager)
                
                val inMemoryTableOrder = fullOrderWithDuplicates.map { (listName, preset) ->
                    "${listName}::${preset.id}"
                }
                
                // Сохраняем полный порядок
                presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
                return inMemoryTableOrder
            }
        } else {
            // В обычном режиме Show All сохраняем как есть
            val inMemoryTableOrder = allPresetsWithLists.map { (listName, preset) ->
                "${listName}::${preset.id}"
            }
            
            // Также сохраняем в файл для переключения режимов
            presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
            
            println("ADB_DEBUG: Saved table order to memory: ${inMemoryTableOrder.size} items")
            return inMemoryTableOrder
        }
    }
    
    /**
     * Сохраняет порядок в режиме Show All
     */
    private fun saveShowAllModeOrder(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        dialogState: DialogStateManager,
        normalModeOrderChanged: Boolean,
        modifiedListIds: Set<String>
    ) {
        // Собираем порядок из таблицы
        val allPresetsWithLists = collectPresetsFromTable(tableModel)
        
        // Сначала сохраняем порядок для обычного режима, если он был изменён
        if (normalModeOrderChanged) {
            saveModifiedListsOrder(tempListsManager, modifiedListIds)
        }
        
        // Теперь сохраняем порядок при Save
        if (dialogState.isHideDuplicatesMode()) {
            saveShowAllWithHideDuplicates(allPresetsWithLists, tempListsManager)
        } else {
            // В обычном режиме Show All сохраняем как есть
            presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
            presetOrderManager.updateFixedShowAllOrder(allPresetsWithLists)
        }
    }
    
    /**
     * Сохраняет порядок в обычном режиме
     */
    private fun saveNormalModeOrder(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        currentPresetList: PresetList?,
        dialogState: DialogStateManager,
        normalModeOrderChanged: Boolean,
        modifiedListIds: Set<String>
    ) {
        if (!normalModeOrderChanged) {
            println("ADB_DEBUG: In normal mode - order not changed via drag & drop, using initial order")
            return
        }
        
        // Сохраняем порядок для текущего списка
        if (currentPresetList != null) {
            saveCurrentListOrder(
                currentPresetList,
                tableModel,
                tempListsManager,
                dialogState.isHideDuplicatesMode()
            )
        }
        
        // Сохраняем порядок для всех изменённых списков
        saveModifiedListsOrder(tempListsManager, modifiedListIds, currentPresetList?.id)
        
        println("ADB_DEBUG: Saved normal mode order for ${modifiedListIds.size} modified lists")
    }
    
    /**
     * Сохраняет порядок для текущего списка
     */
    private fun saveCurrentListOrder(
        currentPresetList: PresetList,
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        isHideDuplicatesMode: Boolean
    ) {
        if (isHideDuplicatesMode) {
            // В режиме скрытия дубликатов используем полный порядок из памяти
            val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(currentPresetList.id)
            if (memoryOrder != null) {
                val tempList = tempListsManager.getTempList(currentPresetList.id)
                if (tempList != null) {
                    val orderedPresets = restorePresetsFromMemoryOrder(tempList, memoryOrder)
                    
                    if (orderedPresets.isNotEmpty()) {
                        presetOrderManager.saveNormalModeOrder(currentPresetList.id, orderedPresets)
                        println("ADB_DEBUG: Saved normal mode order for current list '${currentPresetList.name}' with ${orderedPresets.size} presets (from memory)")
                    }
                }
            }
        } else {
            // Без скрытия дубликатов используем порядок из таблицы
            val tablePresets = tableModel.getPresets()
            if (tablePresets.isNotEmpty()) {
                presetOrderManager.saveNormalModeOrder(currentPresetList.id, tablePresets)
                println("ADB_DEBUG: Saved normal mode order for current list '${currentPresetList.name}' with ${tablePresets.size} presets")
            }
        }
    }
    
    /**
     * Сохраняет порядок для всех изменённых списков
     */
    private fun saveModifiedListsOrder(
        tempListsManager: TempListsManager,
        modifiedListIds: Set<String>,
        excludeListId: String? = null
    ) {
        modifiedListIds.forEach { listId ->
            if (listId != excludeListId) { // Исключаем уже сохранённый список
                val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(listId)
                if (memoryOrder != null) {
                    val tempList = tempListsManager.getTempList(listId)
                    if (tempList != null) {
                        val orderedPresets = restorePresetsFromMemoryOrder(tempList, memoryOrder, true)
                        
                        if (orderedPresets.isNotEmpty()) {
                            presetOrderManager.saveNormalModeOrder(listId, orderedPresets)
                            println("ADB_DEBUG: Saved normal mode order for modified list '${tempList.name}' with ${orderedPresets.size} presets")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Сохраняет порядок для режима Show All со скрытыми дубликатами
     */
    private fun saveShowAllWithHideDuplicates(
        allPresetsWithLists: List<Pair<String, DevicePreset>>,
        tempListsManager: TempListsManager
    ) {
        // В режиме Hide Duplicates нужно восстановить полный порядок с дубликатами
        val fullOrderWithDuplicates = mutableListOf<Pair<String, DevicePreset>>()
        val processedPresetIds = mutableSetOf<String>()
        
        // Проходим по видимым пресетам из таблицы
        allPresetsWithLists.forEach { (listName, preset) ->
            fullOrderWithDuplicates.add(listName to preset)
            processedPresetIds.add(preset.id)
            
            // Находим все дубликаты этого пресета
            val duplicateKey = preset.getDuplicateKey()
            tempListsManager.getTempLists().forEach { (_, list) ->
                list.presets.forEach { duplicatePreset ->
                    if (duplicatePreset.id != preset.id && 
                        duplicatePreset.getDuplicateKey() == duplicateKey &&
                        duplicatePreset.id !in processedPresetIds) {
                        fullOrderWithDuplicates.add(list.name to duplicatePreset)
                        processedPresetIds.add(duplicatePreset.id)
                    }
                }
            }
        }
        
        // Добавляем оставшиеся пресеты
        tempListsManager.getTempLists().forEach { (_, list) ->
            list.presets.forEach { preset ->
                if (preset.id !in processedPresetIds) {
                    fullOrderWithDuplicates.add(list.name to preset)
                    processedPresetIds.add(preset.id)
                }
            }
        }
        
        presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
        presetOrderManager.saveShowAllHideDuplicatesOrder(allPresetsWithLists)
    }
    
    /**
     * Собирает пресеты из таблицы
     */
    private fun collectPresetsFromTable(tableModel: DevicePresetTableModel): List<Pair<String, DevicePreset>> {
        val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
        val listColumn = if (tableModel.columnCount > 6) tableModel.columnCount - 1 else -1
        
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
     * Собирает все пресеты из временных списков
     */
    private fun collectAllPresetsFromTempLists(tempListsManager: TempListsManager): List<Pair<String, DevicePreset>> {
        val fullOrderWithDuplicates = mutableListOf<Pair<String, DevicePreset>>()
        
        // Собираем все пресеты из всех списков в их текущем порядке
        tempListsManager.getTempLists().forEach { (_, list) ->
            list.presets.forEach { preset ->
                fullOrderWithDuplicates.add(list.name to preset)
            }
        }
        
        return fullOrderWithDuplicates
    }
    
    /**
     * Восстанавливает пресеты из сохранённого порядка в памяти
     */
    private fun restorePresetsFromMemoryOrder(
        tempList: PresetList,
        memoryOrder: List<String>,
        useId: Boolean = false
    ): List<DevicePreset> {
        val orderedPresets = mutableListOf<DevicePreset>()
        
        // Восстанавливаем порядок из памяти
        memoryOrder.forEach { key ->
            val preset = if (useId) {
                tempList.presets.find { p -> p.id == key }
            } else {
                tempList.presets.find { p ->
                    "${p.label}|${p.size}|${p.dpi}" == key
                }
            }
            if (preset != null) {
                orderedPresets.add(preset)
            }
        }
        
        // Добавляем новые пресеты, которых не было в сохранённом порядке
        val savedKeys = memoryOrder.toSet()
        tempList.presets.forEach { preset ->
            val key = if (useId) preset.id else "${preset.label}|${preset.size}|${preset.dpi}"
            if (key !in savedKeys) {
                orderedPresets.add(preset)
            }
        }
        
        return orderedPresets
    }
}