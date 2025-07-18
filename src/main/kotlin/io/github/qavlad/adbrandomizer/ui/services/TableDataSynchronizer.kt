package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import io.github.qavlad.adbrandomizer.ui.commands.PresetMoveCommand
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager
import io.github.qavlad.adbrandomizer.utils.PresetUpdateUtils
import javax.swing.SwingUtilities

/**
 * Сервис для синхронизации данных между таблицей и списками пресетов
 * Отвечает за поддержание консистентности данных при различных режимах отображения
 */
class TableDataSynchronizer(
    private val duplicateManager: DuplicateManager,
    private val presetDistributor: PresetDistributor? = null
) {
    
    /**
     * Результат проверки возможности синхронизации
     */
    data class SyncCheckResult(
        val canSync: Boolean,
        val reason: String? = null
    )
    
    /**
     * Контекст синхронизации с необходимыми флагами состояния
     */
    data class SyncContext(
        val isTableUpdating: Boolean,
        val isSwitchingMode: Boolean,
        val isSwitchingList: Boolean,
        val isSwitchingDuplicatesFilter: Boolean,
        val isPerformingHistoryOperation: Boolean,
        val isFirstLoad: Boolean,
        val isShowAllPresetsMode: Boolean,
        val isHideDuplicatesMode: Boolean,
        val isDragAndDropInProgress: Boolean = false
    )
    
    /**
     * Проверяет, можно ли выполнить синхронизацию
     */
    fun canSync(context: SyncContext): SyncCheckResult {
        if (context.isTableUpdating) {
            return SyncCheckResult(false, "isTableUpdating=true")
        }
        
        if (context.isSwitchingMode) {
            return SyncCheckResult(false, "isSwitchingMode=true")
        }
        
        if (context.isSwitchingList) {
            return SyncCheckResult(false, "isSwitchingList=true")
        }
        
        if (context.isSwitchingDuplicatesFilter) {
            return SyncCheckResult(false, "isSwitchingDuplicatesFilter=true")
        }
        
        if (context.isPerformingHistoryOperation) {
            return SyncCheckResult(false, "isPerformingHistoryOperation=true")
        }
        
        if (context.isFirstLoad) {
            return SyncCheckResult(false, "isFirstLoad=true")
        }
        
        return SyncCheckResult(true)
    }
    
    /**
     * Основной метод синхронизации изменений таблицы с временными списками
     */
    fun syncTableChangesToTempLists(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        currentPresetList: PresetList?,
        context: SyncContext,
        getListNameAtRow: (Int) -> String?,
        saveVisiblePresetsSnapshotForAllLists: () -> Unit,
        onReloadRequired: () -> Unit,
        historyManager: CommandHistoryManager? = null,
        dialogState: DialogStateManager? = null
    ) {
        println("ADB_DEBUG: syncTableChangesToTempLists called")
        println("ADB_DEBUG: Current call stack:")
        Thread.currentThread().stackTrace.take(10).forEach { element ->
            println("ADB_DEBUG:   at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        
        val syncCheck = canSync(context)
        if (!syncCheck.canSync) {
            println("ADB_DEBUG: syncTableChangesToTempLists: ${syncCheck.reason}, skip")
            return
        }

        println("ADB_DEBUG: syncTableChangesToTempLists - start, isShowAllPresetsMode: ${context.isShowAllPresetsMode}")
        println("ADB_DEBUG: syncTableChangesToTempLists - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempListsManager.getTempLists().forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }

        // Не синхронизируем при отключении фильтра дубликатов, если количество строк в таблице меньше количества пресетов
        // Это означает, что в таблице показаны только видимые пресеты, а синхронизация испортит скрытые
        if (!context.isHideDuplicatesMode && currentPresetList != null) {
            val tablePresetCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }
            if (tablePresetCount < currentPresetList.presets.size) {
                println("ADB_DEBUG: skip sync - filter just disabled, table has $tablePresetCount rows but list has ${currentPresetList.presets.size} presets")
                return
            }
        }

        if (context.isShowAllPresetsMode) {
            presetDistributor?.distributePresetsToTempLists(
                tableModel = tableModel,
                tempPresetLists = tempListsManager.getMutableTempLists(),
                isHideDuplicatesMode = context.isHideDuplicatesMode,
                getListNameAtRow = getListNameAtRow,
                saveVisiblePresetsSnapshotForAllLists = if (context.isDragAndDropInProgress) {
                    // Не создаем новый снимок во время drag and drop
                    {}
                } else {
                    saveVisiblePresetsSnapshotForAllLists
                }
            )
        } else {
            currentPresetList?.let { list ->
                // Всегда используем syncCurrentList для корректной синхронизации
                syncCurrentList(tableModel, list, context, onReloadRequired)
            }
        }
        
        println("ADB_DEBUG: syncTableChangesToTempLists - after, currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempListsManager.getTempLists().forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }

        // Проверяем, нужно ли обновить таблицу из-за изменения статуса дублей
        if (context.isHideDuplicatesMode && !context.isFirstLoad && !context.isSwitchingDuplicatesFilter) {
            // В режиме Show all всегда проверяем изменение статуса дублей
            // так как дубликаты могут быть в разных списках
            if (context.isShowAllPresetsMode) {
                println("ADB_DEBUG: In Show all mode with hide duplicates, isDragAndDropInProgress = ${context.isDragAndDropInProgress}")
                if (!context.isDragAndDropInProgress) {
                    println("ADB_DEBUG: Reloading table after sync")
                    // Перезагружаем таблицу для отображения изменений
                    onReloadRequired()
                } else {
                    println("ADB_DEBUG: Skipping table reload - drag and drop in progress")
                }
            } else if (checkIfDuplicateStatusChanged(tableModel, context, tempListsManager.getTempLists(), currentPresetList)) {
                println("ADB_DEBUG: Duplicate status changed after edit, reloading table")
                // Перезагружаем таблицу для отображения изменений
                onReloadRequired()
            }
        }

        // Сбрасываем флаг drag and drop после всех операций
        if (context.isDragAndDropInProgress) {
            // Сохраняем состояние после операции для режима Hide Duplicates
            if (context.isHideDuplicatesMode && historyManager != null) {
                val lastCommand = historyManager.getLastCommand()
                if (lastCommand is PresetMoveCommand) {
                    lastCommand.saveStateAfter()
                }
            }
            
            SwingUtilities.invokeLater {
                dialogState?.endDragAndDrop()
            }
        }
    }
    
    /**
     * Синхронизирует изменения из таблицы после drag and drop операции
     * Используется только для обновления порядка элементов без перезагрузки таблицы
     */
    fun syncTableChangesAfterDragDrop(
        tableModel: DevicePresetTableModel,
        tempListsManager: TempListsManager,
        getListNameAtRow: (Int) -> String?,
        getPresetAtRow: (Int) -> DevicePreset,
        isShowAllPresetsMode: Boolean,
        isHideDuplicatesMode: Boolean
    ) {
        if (!isShowAllPresetsMode || !isHideDuplicatesMode) {
            return
        }
        
        println("ADB_DEBUG: syncTableChangesAfterDragDrop - updating order from table")
        
        // Собираем текущий порядок из таблицы
        val tableOrder = mutableListOf<Pair<String, DevicePreset>>()
        for (i in 0 until tableModel.rowCount) {
            val listName = getListNameAtRow(i) ?: continue
            val preset = getPresetAtRow(i)
            tableOrder.add(listName to preset)
        }
        
        // Обновляем порядок в tempPresetLists
        tempListsManager.getTempLists().values.forEach { list ->
            // Собираем пресеты этого списка из таблицы в правильном порядке
            val presetsFromTable = tableOrder
                .filter { it.first == list.name }
                .map { it.second }
            
            // Также нужно сохранить скрытые дубликаты
            val visibleKeys = presetsFromTable.map { "${it.label}|${it.size}|${it.dpi}" }.toSet()
            val hiddenPresets = list.presets.filter { preset ->
                val key = "${preset.label}|${preset.size}|${preset.dpi}"
                !visibleKeys.contains(key)
            }
            
            // Обновляем список: сначала видимые в новом порядке, потом скрытые
            list.presets.clear()
            list.presets.addAll(presetsFromTable.map { it.copy() })
            list.presets.addAll(hiddenPresets)
            
            println("ADB_DEBUG:   Updated list ${list.name} with ${presetsFromTable.size} visible + ${hiddenPresets.size} hidden presets")
        }
    }
    
    /**
     * Проверяет, изменился ли статус дублей после редактирования
     */
    private fun checkIfDuplicateStatusChanged(
        tableModel: DevicePresetTableModel,
        context: SyncContext,
        tempLists: Map<String, PresetList>,
        currentPresetList: PresetList?
    ): Boolean {
        return duplicateManager.checkIfDuplicateStatusChanged(
            tableModel = tableModel,
            isShowAllMode = context.isShowAllPresetsMode,
            tempLists = tempLists,
            currentPresetList = currentPresetList
        )
    }
    
    /**
     * Синхронизирует изменения из таблицы с текущим списком (обычный режим)
     */
    fun syncCurrentList(
        tableModel: DevicePresetTableModel,
        currentList: PresetList,
        context: SyncContext,
        onReloadRequired: () -> Unit
    ) {
        val tablePresets = getPresetsFromTable(tableModel)
        
        // Проверка на пустую таблицу
        if (tablePresets.isEmpty() && currentList.presets.isNotEmpty() && !context.isHideDuplicatesMode) {
            println("ADB_DEBUG: skip sync, table is empty but current list is not (not in hide duplicates mode)")
            return
        }
        
        if (context.isHideDuplicatesMode) {
            syncWithHiddenDuplicates(tablePresets, currentList, onReloadRequired)
        } else {
            syncWithoutHiddenDuplicates(tablePresets, currentList)
        }
    }
    
    
    /**
     * Получает пресеты из таблицы (исключая строку с кнопкой "+")
     */
    private fun getPresetsFromTable(tableModel: DevicePresetTableModel): List<DevicePreset> {
        return tableModel.getPresets()
    }
    
    /**
     * Синхронизация с учетом скрытых дубликатов
     */
    /**
     * Синхронизация с учетом скрытых дубликатов (новая, исправленная логика)
     */
    private fun syncWithHiddenDuplicates(
        tablePresets: List<DevicePreset>,
        currentList: PresetList,
        onReloadRequired: () -> Unit
    ) {
        val originalPresets = currentList.presets.toList()
        val visibleOriginalPresets = getVisiblePresets(originalPresets)
        
        println("ADB_DEBUG: syncWithHiddenDuplicates - hide duplicates mode")
        println("ADB_DEBUG:   original presets count: ${originalPresets.size}")
        println("ADB_DEBUG:   updated table presets count: ${tablePresets.size}")
        println("ADB_DEBUG:   visible original presets count: ${visibleOriginalPresets.size}")

        // Проверяем тип операции
        when {
            tablePresets.size < visibleOriginalPresets.size -> {
                // Удаление
                handleDeletion(tablePresets, originalPresets, visibleOriginalPresets, currentList)
                onReloadRequired()
            }
            tablePresets.size > visibleOriginalPresets.size -> {
                // Добавление
                handleAddition(tablePresets, originalPresets, visibleOriginalPresets, currentList, onReloadRequired)
            }
            else -> {
                // Обновление без изменения количества
                handleUpdate(tablePresets, originalPresets, visibleOriginalPresets, currentList)
            }
        }
    }



    /**
     * Сохраняет текущее состояние таблицы во временные списки
     */
    fun saveCurrentTableState(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        tempListsManager: TempListsManager,
        isShowAllPresetsMode: Boolean,
        isHideDuplicatesMode: Boolean,
        getListNameAtRow: (Int) -> String?,
        saveVisiblePresetsSnapshotForAllLists: () -> Unit
    ) {
        println("ADB_DEBUG: saveCurrentTableState - start, isShowAllPresetsMode: $isShowAllPresetsMode")
        println("ADB_DEBUG: saveCurrentTableState - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        if (tableModel.rowCount == 0 && currentPresetList?.presets?.isNotEmpty() == true) {
            println("ADB_DEBUG: skip saveCurrentTableState, table is empty but current list is not")
            return
        }

        if (isShowAllPresetsMode) {
            // В режиме "Show all presets" распределяем изменения по спискам
            presetDistributor?.distributePresetsToTempLists(
                tableModel = tableModel,
                tempPresetLists = tempListsManager.getMutableTempLists(),
                isHideDuplicatesMode = isHideDuplicatesMode,
                getListNameAtRow = getListNameAtRow,
                saveVisiblePresetsSnapshotForAllLists = saveVisiblePresetsSnapshotForAllLists
            )
        } else {
            // В обычном режиме обновляем только текущий список
            currentPresetList?.let { list ->
                // Используем ту же логику, что и в syncTableChangesToTempLists
                if (isHideDuplicatesMode) {
                    // Используем ту же логику, что и в syncTableChangesToTempLists
                    val originalPresets = list.presets.map { it.copy() }

                    // Определяем какие индексы были видимы в таблице
                    val visibleIndices = duplicateManager.findVisibleIndices(originalPresets)

                    // Получаем обновленные пресеты из таблицы
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    val updatedTablePresets = tableModel.getPresets()

                    // Создаем новый список, обновляя только видимые элементы
                    val newPresets = PresetUpdateUtils.updatePresetsWithVisibleIndices(originalPresets, visibleIndices, updatedTablePresets)

                    // Заменяем список
                    list.presets.clear()
                    list.presets.addAll(newPresets)
                } else {
                    // В обычном режиме без скрытия дубликатов - просто заменяем все
                    list.presets.clear()
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    list.presets.addAll(tableModel.getPresets())
                }
            }
        }
    }
    
    /**
     * Создает ключ для пресета на основе размера и DPI
     */
    private fun DevicePreset.toKey(): String = "${this.size}|${this.dpi}"
    
    /**
     * Создает полный ключ для пресета (с label)
     */
    private fun DevicePreset.toFullKey(): String = "${this.label}|${this.size}|${this.dpi}"
    
    /**
     * Обновляет список пресетов новыми данными
     */
    private fun updatePresetList(list: PresetList, newPresets: List<DevicePreset>) {
        list.presets.clear()
        list.presets.addAll(newPresets)
    }
    
    /**
     * Синхронизация без учета скрытых дубликатов (простая замена)
     */
    private fun syncWithoutHiddenDuplicates(
        tablePresets: List<DevicePreset>,
        currentList: PresetList
    ) {
        println("ADB_DEBUG: syncWithoutHiddenDuplicates - simple sync")
        println("ADB_DEBUG: Table presets (${tablePresets.size}):")
        tablePresets.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        updatePresetList(currentList, tablePresets)
        println("ADB_DEBUG:   Updated list with ${tablePresets.size} presets")
    }
    
    /**
     * Определяет видимые пресеты (первые вхождения каждой комбинации size|dpi)
     */
    private fun getVisiblePresets(presets: List<DevicePreset>): List<Pair<Int, DevicePreset>> {
        val visiblePresets = mutableListOf<Pair<Int, DevicePreset>>()
        val seenKeys = mutableSetOf<String>()
        
        presets.forEachIndexed { index, preset ->
            val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                preset.toKey()
            } else {
                "unique_$index"
            }
            
            if (!seenKeys.contains(key)) {
                seenKeys.add(key)
                visiblePresets.add(index to preset)
            }
        }
        
        return visiblePresets
    }
    
    /**
     * Обрабатывает удаление пресетов
     */
    private fun handleDeletion(
        tablePresets: List<DevicePreset>,
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>,
        currentList: PresetList
    ) {
        val deletedCount = visibleOriginalPresets.size - tablePresets.size
        println("ADB_DEBUG:   Detected deletion of $deletedCount preset(s)")
        
        // Находим какой пресет был удален путем сравнения
        var deletedVisibleIndex = -1
        for (i in visibleOriginalPresets.indices) {
            if (i >= tablePresets.size ||
                visibleOriginalPresets[i].second.label != tablePresets[i].label) {
                deletedVisibleIndex = i
                break
            }
        }
        
        println("ADB_DEBUG:   Deleted visible index: $deletedVisibleIndex")
        
        // Создаем карту обновленных пресетов
        val updatedPresetsMap = mutableMapOf<Int, DevicePreset>()
        var tableIndex = 0
        visibleOriginalPresets.forEachIndexed { visibleIndex, (originalIndex, _) ->
            if (visibleIndex != deletedVisibleIndex && tableIndex < tablePresets.size) {
                updatedPresetsMap[originalIndex] = tablePresets[tableIndex]
                tableIndex++
            }
        }
        
        // Находим удаленный элемент и его ключ
        var deletedPresetKey: String? = null
        var firstHiddenDuplicateIndex = -1
        
        if (deletedVisibleIndex >= 0 && deletedVisibleIndex < visibleOriginalPresets.size) {
            val deletedPreset = visibleOriginalPresets[deletedVisibleIndex].second
            if (deletedPreset.size.isNotBlank() && deletedPreset.dpi.isNotBlank()) {
                deletedPresetKey = "${deletedPreset.size}|${deletedPreset.dpi}"
                
                // Ищем первый скрытый дубликат
                originalPresets.forEachIndexed { index, preset ->
                    val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        preset.toKey()
                    } else {
                        null
                    }
                    
                    if (key == deletedPresetKey && !visibleOriginalPresets.any { it.first == index }) {
                        if (firstHiddenDuplicateIndex == -1) {
                            firstHiddenDuplicateIndex = index
                            println("ADB_DEBUG:   Found first hidden duplicate at index $index: ${preset.label}")
                        }
                    }
                }
            }
        }
        
        // Создаем новый список, сохраняя оригинальный порядок
        val newPresets = mutableListOf<DevicePreset>()
        val deletedOriginalIndex = if (deletedVisibleIndex >= 0) visibleOriginalPresets[deletedVisibleIndex].first else -1
        
        originalPresets.forEachIndexed { index, originalPreset ->
            when {
                index == deletedOriginalIndex -> {
                    // Удаленный элемент - пропускаем
                    println("ADB_DEBUG:   Skipping deleted preset at index $index: ${originalPreset.label}")
                }
                updatedPresetsMap.containsKey(index) -> {
                    // Видимый элемент - берем обновленную версию
                    newPresets.add(updatedPresetsMap[index]!!)
                    println("ADB_DEBUG:   Added updated visible preset from index $index: ${updatedPresetsMap[index]!!.label}")
                }
                index == firstHiddenDuplicateIndex && deletedPresetKey != null -> {
                    // Первый скрытый дубликат становится видимым на месте удаленного
                    // Пропускаем его здесь, он будет добавлен на место удаленного
                    println("ADB_DEBUG:   Skipping first hidden duplicate (will be promoted): ${originalPreset.label}")
                }
                else -> {
                    // Остальные элементы сохраняем как есть
                    // Важно: НЕ удаляем другие скрытые дубликаты!
                    newPresets.add(originalPreset)
                }
            }
        }
        
        // Если был найден скрытый дубликат, вставляем его на место удаленного
        if (firstHiddenDuplicateIndex >= 0 && deletedOriginalIndex >= 0) {
            val promotedDuplicate = originalPresets[firstHiddenDuplicateIndex]
            // Находим правильную позицию для вставки - считаем сколько элементов идет до удаленного
            var insertPosition = 0
            for (i in 0 until deletedOriginalIndex) {
                // Считаем элементы, которые останутся в новом списке и идут до удаленного
                if (i != firstHiddenDuplicateIndex) {
                    insertPosition++
                }
            }
            newPresets.add(insertPosition, promotedDuplicate)
            println("ADB_DEBUG:   Promoted hidden duplicate to position $insertPosition: ${promotedDuplicate.label}")
        }
        
        // Обновляем список
        updatePresetList(currentList, newPresets)
        
        println("ADB_DEBUG:   Final list size: ${currentList.presets.size}")
        
        // Обновляем снимок
        updateSnapshot(currentList)
    }
    
    /**
     * Обрабатывает добавление пресетов
     */
    private fun handleAddition(
        tablePresets: List<DevicePreset>,
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>,
        currentList: PresetList,
        onReloadRequired: () -> Unit
    ) {
        val addedCount = tablePresets.size - visibleOriginalPresets.size
        println("ADB_DEBUG:   Detected addition of $addedCount preset(s)")
        
        // Проверяем, есть ли новые пустые пресеты
        val emptyPresetsInTable = tablePresets.filter { preset ->
            preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
        }
        
        if (emptyPresetsInTable.isNotEmpty()) {
            println("ADB_DEBUG:   ${emptyPresetsInTable.size} empty preset(s) detected in table")
            
            // Обрабатываем добавление новых пустых пресетов
            val newPresets = mergeWithEmptyPresets(
                tablePresets, 
                originalPresets, 
                visibleOriginalPresets
            )
            
            updatePresetList(currentList, newPresets)
            println("ADB_DEBUG:   Updated list now has ${currentList.presets.size} presets")
        } else {
            // Это восстановление после undo или другая операция
            println("ADB_DEBUG:   Addition/restore detected (not from '+' button)")
            println("ADB_DEBUG:   Triggering table reload")
            
            SwingUtilities.invokeLater {
                onReloadRequired()
            }
        }
    }
    
    /**
     * Обрабатывает обновление пресетов без изменения количества
     */
    private fun handleUpdate(
        tablePresets: List<DevicePreset>,
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>,
        currentList: PresetList
    ) {
        println("ADB_DEBUG:   Regular update without addition/deletion")
        
        val newPresets = updatePresetsWithMatching(
            tablePresets,
            originalPresets, 
            visibleOriginalPresets
        )
        
        updatePresetList(currentList, newPresets)
        
        // Обновляем снимок
        updateSnapshot(currentList)
    }
    /**
     * Обновляет пресеты с сопоставлением по содержимому
     */
    private fun updatePresetsWithMatching(
        tablePresets: List<DevicePreset>,
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>
    ): List<DevicePreset> {
        val newPresets = mutableListOf<DevicePreset>()
        val processedIndices = mutableSetOf<Int>()
        
        // Сначала добавляем все обновленные видимые пресеты
        tablePresets.forEach { tablePreset ->
            val matchingOriginalIndex = visibleOriginalPresets.find { (_, originalPreset) ->
                originalPreset.label == tablePreset.label &&
                originalPreset.size == tablePreset.size &&
                originalPreset.dpi == tablePreset.dpi
            }?.first
            
            if (matchingOriginalIndex != null) {
                processedIndices.add(matchingOriginalIndex)
                newPresets.add(tablePreset)
                addHiddenDuplicatesAfterVisible(originalPresets, visibleOriginalPresets, tablePreset, matchingOriginalIndex, newPresets, processedIndices)
            } else {
                newPresets.add(tablePreset)
            }
        }
        
        addRemainingHiddenDuplicates(originalPresets, visibleOriginalPresets, processedIndices, newPresets)
        return newPresets
    }
    
    /**
     * Добавляет скрытые дубликаты после видимого элемента
     */
    private fun addHiddenDuplicatesAfterVisible(
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>,
        tablePreset: DevicePreset,
        matchingOriginalIndex: Int,
        newPresets: MutableList<DevicePreset>,
        processedIndices: MutableSet<Int>
    ) {
        var foundFirst = false
        originalPresets.forEachIndexed { index, preset ->
            if (index == matchingOriginalIndex) {
                foundFirst = true
            } else if (foundFirst && preset.size == tablePreset.size && preset.dpi == tablePreset.dpi) {
                if (!visibleOriginalPresets.any { it.first == index }) {
                    newPresets.add(preset)
                    processedIndices.add(index)
                } else {
                    return@forEachIndexed
                }
            }
        }
    }
    
    /**
     * Добавляет оставшиеся скрытые дубликаты
     */
    private fun addRemainingHiddenDuplicates(
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>,
        processedIndices: Set<Int>,
        newPresets: MutableList<DevicePreset>
    ) {
        originalPresets.forEachIndexed { index, preset ->
            if (!processedIndices.contains(index) && !visibleOriginalPresets.any { it.first == index }) {
                val insertIndex = newPresets.indexOfFirst { 
                    it.size == preset.size && it.dpi == preset.dpi 
                }
                
                if (insertIndex >= 0) {
                    var lastIndex = insertIndex
                    for (i in insertIndex + 1 until newPresets.size) {
                        if (newPresets[i].size == preset.size && newPresets[i].dpi == preset.dpi) {
                            lastIndex = i
                        } else {
                            break
                        }
                    }
                    newPresets.add(lastIndex + 1, preset)
                } else {
                    newPresets.add(preset)
                }
            }
        }
    }
    
    /**
     * Обновляет снимок после изменений
     */
    private fun updateSnapshot(list: PresetList) {
        val visiblePresets = getVisiblePresets(list.presets)
        val updatedVisibleKeys = visiblePresets.map { (_, preset) ->
            preset.toFullKey()
        }
        
        val fullOrder = list.presets.map { preset ->
            preset.toFullKey()
        }
        
        duplicateManager.updateSnapshot(list.name, updatedVisibleKeys, fullOrder)
        println("ADB_DEBUG:   Updated snapshot with ${updatedVisibleKeys.size} visible presets")
    }
    
    /**
     * Объединяет оригинальные пресеты с новыми пустыми пресетами из таблицы
     */
    private fun mergeWithEmptyPresets(
        tablePresets: List<DevicePreset>,
        originalPresets: List<DevicePreset>,
        visibleOriginalPresets: List<Pair<Int, DevicePreset>>
    ): List<DevicePreset> {
        val visibleIndices = visibleOriginalPresets.map { it.first }
        return PresetUpdateUtils.updatePresetsWithVisibleIndices(
            originalPresets,
            visibleIndices,
            tablePresets
        )
    }
}