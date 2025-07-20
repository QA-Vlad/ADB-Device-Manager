package io.github.qavlad.adbrandomizer.ui.dialogs

import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.services.DuplicateManager
import io.github.qavlad.adbrandomizer.ui.services.TempListsManager

/**
 * Инициализатор обработчиков событий для диалога настроек.
 * Централизует всю логику подключения слушателей и обработчиков.
 */
class EventHandlersInitializer(
    private val controller: SettingsDialogController
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
                    }
                }
            },
            onShowAllPresetsChanged = { showAll ->
                handleShowAllPresetsChanged(
                    showAll = showAll,
                    dialogState = dialogState,
                    duplicateManager = duplicateManager,
                    tableWithButtonPanel = tableWithButtonPanel,
                    onSyncTableChanges = onSyncTableChanges,
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
        onSyncTableChanges: () -> Unit,
        onSetupTableColumns: () -> Unit,
        onLoadPresetsIntoTable: () -> Unit
    ) {
        controller.stopTableEditing()
        
        // Сохраняем текущее состояние перед переключением
        if (controller.isTableInitialized() && !dialogState.isFirstLoad()) {
            // Only sync when EXITING Show All mode, not when entering it
            if (!showAll && dialogState.isShowAllPresetsMode()) {
                onSyncTableChanges()
            }
        }
        
        // Сохраняем снимок видимых пресетов ПЕРЕД переключением режима
        if (showAll && dialogState.isHideDuplicatesMode() && 
            !dialogState.isShowAllPresetsMode() && !duplicateManager.hasSnapshots()) {
            println("ADB_DEBUG: Saving snapshot before switching to Show all mode")
            controller.saveVisiblePresetsSnapshotForAllLists()
        }
        
        dialogState.withModeSwitching {
            dialogState.withTableUpdate {
                dialogState.setShowAllPresetsMode(showAll)
                tableWithButtonPanel?.setAddButtonVisible(!showAll)
                if (controller.isTableInitialized()) {
                    onSetupTableColumns()
                    onLoadPresetsIntoTable()
                }
            }
        }
        
        // Очищаем снимок при выходе из режима Show all
        if (!showAll) {
            println("ADB_DEBUG: Clearing snapshot when exiting Show all mode")
            duplicateManager.clearSnapshots()
            
            // Сохраняем порядок для текущего списка после выхода из режима Show All
            controller.getCurrentPresetList()?.let { list ->
                controller.getPresetOrderManager().saveNormalModeOrder(list.id, list.presets)
                println("ADB_DEBUG: Saved order for list ${list.name} after exiting Show All mode")
            }
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
        
        // Синхронизируем состояние таблицы только при ВКЛЮЧЕНИИ фильтра дубликатов
        if (controller.isTableInitialized() && !dialogState.isFirstLoad() && hideDuplicates) {
            onSyncTableChanges()
        }
        
        dialogState.setHideDuplicatesMode(hideDuplicates)
        
        // После включения фильтра дублей сохраняем снимок для всех списков
        if (hideDuplicates && !dialogState.isFirstLoad()) {
            println("ADB_DEBUG: Saving snapshot after enabling hide duplicates")
            controller.saveVisiblePresetsSnapshotForAllLists()
        }
        
        // Проверяем, что таблица инициализирована и не идет процесс дублирования
        if (controller.isTableInitialized() && !controller.isDuplicatingPreset()) {
            controller.removeTableModelListener()
            
            dialogState.withDuplicatesFilterSwitching {
                dialogState.withTableUpdate {
                    onLoadPresetsIntoTable()
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
        SettingsService.setShowCounters(showCounters)
        
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
        
        // Сбрасываем все счетчики
        UsageCounterService.resetAllCounters()
        
        // Перезагружаем таблицу для обновления отображения
        if (controller.isTableInitialized()) {
            onLoadPresetsIntoTable()
        }
    }
    
}