package io.github.qavlad.adbrandomizer.ui.dialogs

import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.services.DuplicateManager
import io.github.qavlad.adbrandomizer.ui.services.TempListsManager
import io.github.qavlad.adbrandomizer.ui.services.SelectionTracker

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
                println("ADB_DEBUG: onListChanged called with: ${presetList.name}")
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
                            val tempList = tempListsManager.getTempList(controller.stateManager.currentPresetList!!.id)
                            if (tempList != null) {
                                tempList.presets.clear()
                                tempList.presets.addAll(tablePresets)
                                println("ADB_DEBUG: Updated tempList order before switching lists for '${controller.stateManager.currentPresetList!!.name}'")
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
                    // Переключаемся на временную копию нового списка
                    val newCurrentList = tempListsManager.getTempList(presetList.id)
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