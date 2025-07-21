package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import io.github.qavlad.adbrandomizer.ui.commands.PresetMoveCommand
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager
import io.github.qavlad.adbrandomizer.utils.PresetUpdateUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
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
        PluginLogger.trace(LogCategory.SYNC_OPERATIONS, "syncTableChangesToTempLists called")
        
        val syncCheck = canSync(context)
        if (!syncCheck.canSync) {
            PluginLogger.trace(LogCategory.SYNC_OPERATIONS, "syncTableChangesToTempLists: %s, skip", syncCheck.reason)
            return
        }

        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncTableChangesToTempLists - start, isShowAllPresetsMode: %s", context.isShowAllPresetsMode)
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncTableChangesToTempLists - currentPresetList before: %s, presets: %s", currentPresetList?.name, currentPresetList?.presets?.size)
        tempListsManager.getTempLists().forEach { (k, v) -> PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "TEMP_LIST $k: %s, presets: %s", v.name, v.presets.size) }

        // Не синхронизируем при отключении фильтра дубликатов, если количество строк в таблице меньше количества пресетов
        // Это означает, что в таблице показаны только видимые пресеты, а синхронизация испортит скрытые
        if (!context.isHideDuplicatesMode && currentPresetList != null) {
            val tablePresetCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }
            if (tablePresetCount < currentPresetList.presets.size) {
                PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "skip sync - filter just disabled, table has $tablePresetCount rows but list has %s presets", currentPresetList.presets.size)
                return
            }
        }

        if (context.isShowAllPresetsMode) {
            // В режиме Show All при drag & drop не обновляем tempLists
            if (!context.isDragAndDropInProgress) {
                presetDistributor?.distributePresetsToTempLists(
                    tableModel = tableModel,
                    tempPresetLists = tempListsManager.getMutableTempLists(),
                    isHideDuplicatesMode = context.isHideDuplicatesMode,
                    getListNameAtRow = getListNameAtRow,
                    saveVisiblePresetsSnapshotForAllLists = saveVisiblePresetsSnapshotForAllLists
                )
                
                // Сохраняем все списки после синхронизации в режиме Show All
                PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Saving all preset lists after sync in Show All mode")
                tempListsManager.getTempLists().values.forEach { list ->
                    PresetListService.savePresetList(list)
                }
            }
        } else {
            currentPresetList?.let { list ->
                // Всегда используем syncCurrentList для корректной синхронизации
                syncCurrentList(tableModel, list, context, onReloadRequired)
                
                // Сохраняем список в файл после синхронизации
                PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Saving currentPresetList after sync")
                PresetListService.savePresetList(list)
            }
        }
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncTableChangesToTempLists - after, currentPresetList: %s, presets: %s", currentPresetList?.name, currentPresetList?.presets?.size)
        tempListsManager.getTempLists().forEach { (k, v) -> PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "TEMP_LIST $k: %s, presets: %s", v.name, v.presets.size) }

        // Проверяем, нужно ли обновить таблицу из-за изменения статуса дублей
        if (context.isHideDuplicatesMode && !context.isFirstLoad && !context.isSwitchingDuplicatesFilter) {
            // В режиме Show all всегда проверяем изменение статуса дублей
            // так как дубликаты могут быть в разных списках
            if (context.isShowAllPresetsMode) {
                PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "In Show all mode with hide duplicates, isDragAndDropInProgress = %s", context.isDragAndDropInProgress)
                if (!context.isDragAndDropInProgress) {
                    PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Reloading table after sync")
                    // Перезагружаем таблицу для отображения изменений
                    onReloadRequired()
                } else {
                    PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Skipping table reload - drag and drop in progress")
                }
            } else if (checkIfDuplicateStatusChanged(tableModel, context, tempListsManager.getTempLists(), currentPresetList)) {
                PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Duplicate status changed after edit, reloading table")
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncCurrentList - start")
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncCurrentList - currentList before sync: %s", currentList.name)
        currentList.presets.forEachIndexed { index, preset ->
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  before[$index] %s | %s | %s", preset.label, preset.size, preset.dpi)
        }
        
        val tablePresets = getPresetsFromTable(tableModel)
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncCurrentList - table presets:")
        tablePresets.forEachIndexed { index, preset ->
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  table[$index] %s | %s | %s", preset.label, preset.size, preset.dpi)
        }
        
        // Проверка на пустую таблицу
        if (tablePresets.isEmpty() && currentList.presets.isNotEmpty() && !context.isHideDuplicatesMode) {
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "skip sync, table is empty but current list is not (not in hide duplicates mode)")
            return
        }
        
        if (context.isHideDuplicatesMode) {
            syncWithHiddenDuplicates(tablePresets, currentList, onReloadRequired)
        } else {
            syncWithoutHiddenDuplicates(tablePresets, currentList)
        }
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncCurrentList - currentList after sync:")
        currentList.presets.forEachIndexed { index, preset ->
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  after[$index] %s | %s | %s", preset.label, preset.size, preset.dpi)
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
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncWithHiddenDuplicates - hide duplicates mode")
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  original presets count: %s", originalPresets.size)
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  updated table presets count: %s", tablePresets.size)
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  visible original presets count: %s", visibleOriginalPresets.size)

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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "saveCurrentTableState - start, isShowAllPresetsMode: $isShowAllPresetsMode")
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "saveCurrentTableState - currentPresetList before: %s, presets: %s", currentPresetList?.name, currentPresetList?.presets?.size)

        if (tableModel.rowCount == 0 && currentPresetList?.presets?.isNotEmpty() == true) {
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "skip saveCurrentTableState, table is empty but current list is not")
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
    private fun DevicePreset.toKey(): String = "$size|$dpi"
    
    /**
     * Создает полный ключ для пресета (с label)
     */
    private fun DevicePreset.toFullKey(): String = "$label|$size|$dpi"
    
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "syncWithoutHiddenDuplicates - simple sync")
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "Table presets (%s):", tablePresets.size)
        tablePresets.forEachIndexed { index, preset ->
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  [$index] %s | %s | %s", preset.label, preset.size, preset.dpi)
        }
        updatePresetList(currentList, tablePresets)
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Updated list with %s presets", tablePresets.size)
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Detected deletion of $deletedCount preset(s)")
        
        // Находим какой пресет был удален путем сравнения
        var deletedVisibleIndex = -1
        for (i in visibleOriginalPresets.indices) {
            if (i >= tablePresets.size ||
                visibleOriginalPresets[i].second.label != tablePresets[i].label) {
                deletedVisibleIndex = i
                break
            }
        }
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Deleted visible index: $deletedVisibleIndex")
        
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
                            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Found first hidden duplicate at index $index: %s", preset.label)
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
                    PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Skipping deleted preset at index $index: %s", originalPreset.label)
                }
                updatedPresetsMap.containsKey(index) -> {
                    // Видимый элемент - берем обновленную версию
                    newPresets.add(updatedPresetsMap[index]!!)
                    PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Added updated visible preset from index $index: %s", updatedPresetsMap[index]!!.label)
                }
                index == firstHiddenDuplicateIndex && deletedPresetKey != null -> {
                    // Первый скрытый дубликат становится видимым на месте удаленного
                    // Пропускаем его здесь, он будет добавлен на место удаленного
                    PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Skipping first hidden duplicate (will be promoted): %s", originalPreset.label)
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
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Promoted hidden duplicate to position $insertPosition: %s", promotedDuplicate.label)
        }
        
        // Обновляем список
        updatePresetList(currentList, newPresets)
        
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Final list size: %s", currentList.presets.size)
        
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Detected addition of $addedCount preset(s)")
        
        // Проверяем, есть ли новые пустые пресеты
        val emptyPresetsInTable = tablePresets.filter { preset ->
            preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
        }
        
        if (emptyPresetsInTable.isNotEmpty()) {
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  %s empty preset(s) detected in table", emptyPresetsInTable.size)
            
            // Обрабатываем добавление новых пустых пресетов
            val newPresets = mergeWithEmptyPresets(
                tablePresets, 
                originalPresets, 
                visibleOriginalPresets
            )
            
            updatePresetList(currentList, newPresets)
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Updated list now has %s presets", currentList.presets.size)
        } else {
            // Это восстановление после undo или другая операция
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Addition/restore detected (not from '+' button)")
            PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Triggering table reload")
            
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Regular update without addition/deletion")
        
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
        PluginLogger.debug(LogCategory.SYNC_OPERATIONS, "  Updated snapshot with %s visible presets", updatedVisibleKeys.size)
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