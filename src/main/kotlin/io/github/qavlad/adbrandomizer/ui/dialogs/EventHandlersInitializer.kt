package io.github.qavlad.adbrandomizer.ui.dialogs

import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.services.DuplicateManager
import io.github.qavlad.adbrandomizer.ui.services.TempListsManager
import io.github.qavlad.adbrandomizer.ui.services.SelectionTracker
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory

/**
 * Инициализатор обработчиков событий для диалога настроек.
 * Централизует всю логику подключения слушателей и обработчиков.
 */
class EventHandlersInitializer(
    private val controller: PresetsDialogController
) {
    /**
     * Создает и инициализирует панель управления списками
     */
    fun createAndInitializeListManagerPanel(
        dialogState: DialogStateManager,
        duplicateManager: DuplicateManager,
        tempListsManager: TempListsManager,
        tableWithButtonPanel: TableWithAddButtonPanel?,
        onCurrentListChanged: (PresetList?) -> Unit,
        onLoadPresetsIntoTable: () -> Unit,
        onSyncTableChanges: () -> Unit,
        onSetupTableColumns: () -> Unit,
        onResetSorting: () -> Unit = {}
    ): PresetListManagerPanel {
        return PresetListManagerPanel(
            onListChanged = { presetList ->
                println("ADB_DEBUG: onListChanged called with: ${presetList.name} having ${presetList.presets.size} presets")
                // Останавливаем редактирование если оно активно
                controller.stopTableEditing()
                
                // Обновляем порядок в памяти перед переключением, если он был изменен
                if (controller.stateManager.normalModeOrderChanged && controller.stateManager.currentPresetList != null && !dialogState.isShowAllPresetsMode()) {
                    // Если включен режим скрытия дубликатов, не обновляем порядок из таблицы
                    // так как он уже обновлен в onRowMoved с полным списком
                    if (!dialogState.isHideDuplicatesMode()) {
                        val tablePresets = controller.tableModel.getPresets()
                        if (tablePresets.isNotEmpty()) {
                            // Только обновляем в памяти, не сохраняем в настройки
                            controller.serviceLocator.presetOrderManager.updateNormalModeOrderInMemory(controller.stateManager.currentPresetList!!.id, tablePresets)
                            
                            // Также обновляем tempList чтобы при переключении обратно порядок сохранился
                            // НО НЕ делаем этого, если только что произошёл reset
                            val listId = controller.stateManager.currentPresetList!!.id
                            if (!tempListsManager.isRecentlyReset(listId)) {
                                val tempList = tempListsManager.getTempList(listId)
                                if (tempList != null) {
                                    tempList.presets.clear()
                                    tempList.presets.addAll(tablePresets)
                                    println("ADB_DEBUG: Updated tempList order before switching lists for '${controller.stateManager.currentPresetList!!.name}'")
                                }
                            } else {
                                println("ADB_DEBUG: Skipping tempList update from tablePresets because list $listId was recently reset")
                            }
                            
                            println("ADB_DEBUG: Updated in-memory order before switching lists for '${controller.stateManager.currentPresetList!!.name}' with ${tablePresets.size} presets")
                        }
                    } else {
                        println("ADB_DEBUG: Skipping order update from table in Hide Duplicates mode - order already updated in onRowMoved with full list")
                    }
                }
                
                // НЕ сбрасываем флаг - он должен остаться true чтобы при OK сохранить изменения
                // controller.normalModeOrderChanged = false
                
                // Добавляем слушатель к модели
                controller.addTableModelListener()
                
                dialogState.withListSwitching {
                    // При ресете нужно обновить временный список
                    val newCurrentList = tempListsManager.getTempList(presetList.id)
                    
                    println("ADB_DEBUG: In onListChanged.withListSwitching:")
                    println("ADB_DEBUG:   presetList.id: ${presetList.id}, has ${presetList.presets.size} presets")
                    println("ADB_DEBUG:   newCurrentList from tempListsManager: ${newCurrentList?.presets?.size} presets")
                    
                    // Если временный список существует, но содержит меньше пресетов чем оригинал,
                    // это может быть результат ресета - обновляем временный список
                    if (newCurrentList != null && newCurrentList.presets.size < presetList.presets.size) {
                        PluginLogger.warn(LogCategory.PRESET_SERVICE,
                            "Updating temp list after reset: %d -> %d presets",
                            newCurrentList.presets.size, presetList.presets.size)
                        // Обновляем временный список данными из переданного списка
                        newCurrentList.presets.clear()
                        newCurrentList.presets.addAll(presetList.presets.map { it.copy(id = it.id) })
                    }
                    
                    println("ADB_DEBUG:   Final newCurrentList to be used: ${newCurrentList?.presets?.size} presets")
                    onCurrentListChanged(newCurrentList)
                    println("ADB_DEBUG: onListChanged - set currentPresetList to: ${newCurrentList?.name}, presets: ${newCurrentList?.presets?.size}")
                    
                    // Очищаем кэш активного списка при переключении
                    PresetListService.clearAllCaches()
                    
                    // Очищаем снимок при переключении списка только если не в режиме скрытия дубликатов
                    if (!dialogState.isShowAllPresetsMode() && !dialogState.isHideDuplicatesMode()) {
                        duplicateManager.clearSnapshots()
                    }
                    
                    if (controller.isTableInitialized()) {
                        onLoadPresetsIntoTable()
                        // Очищаем флаг reset после загрузки данных в таблицу
                        if (tempListsManager.isRecentlyReset(presetList.id)) {
                            tempListsManager.clearResetFlag(presetList.id)
                            println("ADB_DEBUG: Cleared reset flag for list ${presetList.id} after loading presets into table")
                        }
                        // Ориентация уже применена к данным в initializeTempPresetLists
                        println("ADB_DEBUG: onListChanged - orientation already applied to all lists")
                    }
                }
            },
            onShowAllPresetsChanged = { showAll ->
                handleShowAllPresetsChanged(
                    showAll = showAll,
                    dialogState = dialogState,
                    duplicateManager = duplicateManager,
                    tableWithButtonPanel = tableWithButtonPanel,
                    onSetupTableColumns = onSetupTableColumns,
                    onLoadPresetsIntoTable = onLoadPresetsIntoTable
                )
            },
            onHideDuplicatesChanged = { hideDuplicates ->
                handleHideDuplicatesChanged(
                    hideDuplicates = hideDuplicates,
                    dialogState = dialogState,
                    duplicateManager = duplicateManager,
                    onSyncTableChanges = onSyncTableChanges,
                    onLoadPresetsIntoTable = onLoadPresetsIntoTable
                )
            },
            onResetSorting = onResetSorting,
            onShowCountersChanged = { showCounters ->
                handleShowCountersChanged(
                    showCounters = showCounters,
                    onSetupTableColumns = onSetupTableColumns,
                    onLoadPresetsIntoTable = onLoadPresetsIntoTable
                )
            },
            onResetCounters = {
                handleResetCounters(onLoadPresetsIntoTable)
            },
            onListDeleted = { deletedListId ->
                // Удаляем список из tempLists
                tempListsManager.removeList(deletedListId)
                println("ADB_DEBUG: Removed list with id $deletedListId from tempLists")
            },
            onListReset = { listId, resetList ->
                println("ADB_DEBUG: EventHandlersInitializer.onListReset called")
                println("ADB_DEBUG:   listId: $listId")
                println("ADB_DEBUG:   resetList has ${resetList.presets.size} presets")
                
                // Отмечаем список как недавно сброшенный
                tempListsManager.markAsRecentlyReset(listId)
                // Обновляем временный список после ресета
                tempListsManager.updateTempList(listId, resetList)
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Updated temp list after reset: %s with %d presets", 
                    resetList.name, resetList.presets.size)
            },
            onListCreated = { newList ->
                // Добавляем новый список в tempLists
                tempListsManager.getMutableTempLists()[newList.id] = newList
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Added new list to tempLists: %s with id %s", 
                    newList.name, newList.id)
            },
            onListImported = { importedList ->
                // Добавляем импортированный список в tempLists
                println("ADB_DEBUG: onListImported BEFORE - tempLists size: ${tempListsManager.getTempLists().size}")
                tempListsManager.getMutableTempLists()[importedList.id] = importedList
                println("ADB_DEBUG: onListImported AFTER - tempLists size: ${tempListsManager.getTempLists().size}")
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Added imported list to tempLists: %s with id %s, presets: %d", 
                    importedList.name, importedList.id, importedList.presets.size)
                println("ADB_DEBUG: onListImported - added list '${importedList.name}' to tempLists, total lists now: ${tempListsManager.getTempLists().size}")
                
                // Проверяем, что список действительно добавлен
                val addedList = tempListsManager.getTempList(importedList.id)
                if (addedList != null) {
                    println("ADB_DEBUG: Verified - list '${addedList.name}' is in tempLists with ${addedList.presets.size} presets")
                } else {
                    println("ADB_DEBUG: ERROR - list was not added to tempLists!")
                }
            },
            onClearListOrderInMemory = { listId ->
                // Очищаем сохранённый порядок drag & drop для списка при его сбросе
                controller.serviceLocator.presetOrderManager.clearNormalModeOrderInMemoryForList(listId)
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Cleared in-memory order for reset list: %s", listId)
            },
            onResetOrientation = {
                // Сбрасываем ориентацию в вертикальную при сбросе списка
                controller.orientationPanel?.resetToPortrait()
                PluginLogger.info(LogCategory.UI_EVENTS, 
                    "Reset orientation to portrait after preset list reset")
            },
            onLoadPresetsIntoTable = {
                // Обновляем таблицу при импорте в режиме Show all
                println("ADB_DEBUG: onLoadPresetsIntoTable callback called from PresetListManagerPanel")
                println("ADB_DEBUG: Current tempLists size before loading: ${tempListsManager.getTempLists().size}")
                onLoadPresetsIntoTable()
                println("ADB_DEBUG: onLoadPresetsIntoTable completed")
            }
        )
    }
    
    
    /**
     * Обработка изменения режима Show All Presets
     */
    private fun handleShowAllPresetsChanged(
        showAll: Boolean,
        dialogState: DialogStateManager,
        duplicateManager: DuplicateManager,
        tableWithButtonPanel: TableWithAddButtonPanel?,
        onSetupTableColumns: () -> Unit,
        onLoadPresetsIntoTable: () -> Unit
    ) {
        controller.stopTableEditing()
        
        // Очищаем выделение при смене режима
        SelectionTracker.clearSelection()
        controller.clearTableSelection()
        
        // В режиме Show All не нужно синхронизировать изменения обратно в temp lists
        // так как это может привести к потере дубликатов
        // Ранее здесь была проверка и вызов onSyncTableChanges(), но это приводило к потере дубликатов
        
        // Сохраняем снимок видимых пресетов ПЕРЕД переключением режима
        if (showAll && dialogState.isHideDuplicatesMode() && 
            !dialogState.isShowAllPresetsMode() && !duplicateManager.hasSnapshots()) {
            println("ADB_DEBUG: Saving snapshot before switching to Show all mode")
            controller.saveVisiblePresetsSnapshotForAllLists()
        }
        
        // Сохраняем текущий порядок перед переключением режима
        if (controller.isTableInitialized() && dialogState.isShowAllPresetsMode() && !showAll) {
            // Переключаемся из Show All в обычный режим - сохраняем порядок Show All
            controller.saveCurrentShowAllOrderFromTable()
        }
        
        // В обычном режиме обновляем порядок в памяти только если он был изменен через drag & drop
        if (controller.isTableInitialized() && !dialogState.isShowAllPresetsMode() && showAll) {
            if (controller.stateManager.normalModeOrderChanged && controller.stateManager.currentPresetList != null) {
                // Если включен режим скрытия дубликатов, не обновляем порядок из таблицы
                // так как он уже обновлен в onRowMoved с полным списком
                if (!dialogState.isHideDuplicatesMode()) {
                    val tablePresets = controller.tableModel.getPresets()
                    if (tablePresets.isNotEmpty()) {
                        // Только обновляем в памяти, не сохраняем в настройки
                        controller.serviceLocator.presetOrderManager.updateNormalModeOrderInMemory(controller.stateManager.currentPresetList!!.id, tablePresets)
                        
                        // Также обновляем tempList
                        val tempList = controller.serviceLocator.tempListsManager.getTempList(controller.stateManager.currentPresetList!!.id)
                        if (tempList != null) {
                            tempList.presets.clear()
                            tempList.presets.addAll(tablePresets)
                        }
                        
                        println("ADB_DEBUG: Updated in-memory order before switching to Show All for list '${controller.stateManager.currentPresetList!!.name}' with ${tablePresets.size} presets")
                    }
                } else {
                    println("ADB_DEBUG: Skipping order update from table in Hide Duplicates mode before switching to Show All - order already updated in onRowMoved with full list")
                }
            } else {
                println("ADB_DEBUG: Switching from normal to Show All mode - normal order not changed via drag & drop")
            }
        }
        
        dialogState.withModeSwitching {
            dialogState.withTableUpdate {
                dialogState.setShowAllPresetsMode(showAll)
                tableWithButtonPanel?.setAddButtonVisible(!showAll)
                if (controller.isTableInitialized()) {
                    onSetupTableColumns()
                    onLoadPresetsIntoTable()
                    // Ориентация уже применена к данным в initializeTempPresetLists
                    println("ADB_DEBUG: handleShowAllPresetsChanged - orientation already applied to all lists")
                }
            }
        }
        
        // Очищаем снимок при выходе из режима Show all
        if (!showAll) {
            println("ADB_DEBUG: Clearing snapshot when exiting Show all mode")
            duplicateManager.clearSnapshots()
            // Не сохраняем порядок при выходе из Show All - используем сохранённый при инициализации
        }
    }
    
    /**
     * Обработка изменения режима Hide Duplicates
     */
    private fun handleHideDuplicatesChanged(
        hideDuplicates: Boolean,
        dialogState: DialogStateManager,
        duplicateManager: DuplicateManager,
        onSyncTableChanges: () -> Unit,
        onLoadPresetsIntoTable: () -> Unit
    ) {
        if (dialogState.isPerformingHistoryOperation()) {
            println("ADB_DEBUG: onHideDuplicatesChanged skipped during history operation")
            return
        }
        
        println("ADB_DEBUG: onHideDuplicatesChanged called with: $hideDuplicates")
        controller.stopTableEditing()
        
        // Сохраняем текущий порядок таблицы в памяти перед переключением режима
        // НО НЕ при первой загрузке, так как порядок ещё не был восстановлен из сохранённого
        if (controller.isTableInitialized() && dialogState.isShowAllPresetsMode() && !dialogState.isFirstLoad()) {
            println("ADB_DEBUG: Saving current table order to memory before toggling Hide Duplicates")
            controller.saveCurrentTableOrderToMemory()
        }
        
        // ПЕРЕД включением фильтра дублей сохраняем снимок того, какие дубли будут скрыты
        if (hideDuplicates && !dialogState.isFirstLoad()) {
            println("ADB_DEBUG: Saving snapshot BEFORE enabling hide duplicates")
            controller.saveVisiblePresetsSnapshotForAllLists()
        }
        
        // Синхронизируем состояние таблицы только при ВКЛЮЧЕНИИ фильтра дубликатов
        // НО не в режиме Show All, так как это может привести к потере данных
        if (controller.isTableInitialized() && !dialogState.isFirstLoad() && hideDuplicates && !dialogState.isShowAllPresetsMode()) {
            onSyncTableChanges()
        }
        
        // Синхронизируем состояние сортировки при переключении режима
        // Но НЕ при первой загрузке, чтобы не перезаписать сохраненное состояние
        if (dialogState.isShowAllPresetsMode() && !dialogState.isFirstLoad()) {
            println("ADB_DEBUG: Syncing sort state for hide duplicates toggle (not first load)")
            controller.syncSortStateForHideDuplicatesToggle(hideDuplicates)
        } else if (dialogState.isFirstLoad()) {
            println("ADB_DEBUG: Skipping sort state sync during first load to preserve saved state")
        }
        
        dialogState.setHideDuplicatesMode(hideDuplicates)
        
        // Проверяем, что таблица инициализирована и не идет процесс дублирования
        if (controller.isTableInitialized() && !controller.isDuplicatingPreset()) {
            controller.removeTableModelListener()
            
            dialogState.withDuplicatesFilterSwitching {
                dialogState.withTableUpdate {
                    onLoadPresetsIntoTable()
                    // Ориентация уже применена к данным в initializeTempPresetLists
                    println("ADB_DEBUG: handleHideDuplicatesChanged - orientation already applied to all lists")
                }
            }
            
            controller.addTableModelListener()
            
            // Очищаем снимок при отключении фильтра дубликатов
            if (!hideDuplicates) {
                duplicateManager.clearSnapshots()
            }
        }
    }
    
    /**
     * Обработка изменения видимости счетчиков
     */
    private fun handleShowCountersChanged(
        showCounters: Boolean,
        onSetupTableColumns: () -> Unit,
        onLoadPresetsIntoTable: () -> Unit
    ) {
        println("ADB_DEBUG: Show counters changed to: $showCounters")
        
        // Останавливаем редактирование если оно активно
        controller.stopTableEditing()
        
        // Сохраняем настройку
        PresetStorageService.setShowCounters(showCounters)
        
        // Пересоздаем колонки таблицы
        onSetupTableColumns()
        
        // Перезагружаем данные в таблицу
        if (controller.isTableInitialized()) {
            onLoadPresetsIntoTable()
        }
    }
    
    /**
     * Обработка сброса счетчиков использования
     */
    private fun handleResetCounters(onLoadPresetsIntoTable: () -> Unit) {
        println("ADB_DEBUG: Resetting usage counters")
        
        // Сохраняем текущее состояние счётчиков перед сбросом
        val countersSnapshot = UsageCounterService.createSnapshot()
        controller.saveCountersSnapshot(countersSnapshot)
        
        // Сбрасываем все счетчики
        UsageCounterService.resetAllCounters()
        
        // Перезагружаем таблицу для обновления отображения
        if (controller.isTableInitialized()) {
            onLoadPresetsIntoTable()
        }
    }
    
}