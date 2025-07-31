package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager
import javax.swing.JTable
import javax.swing.event.TableModelListener

/**
 * Расширенный загрузчик таблицы, объединяющий всю логику загрузки из контроллера
 */
class ExtendedTableLoader(
    private val tableLoader: TableLoader,
    private val dialogState: DialogStateManager,
    private val hoverStateManager: HoverStateManager?,
    private val hiddenDuplicatesManager: HiddenDuplicatesManager,
    private val tempListsManager: TempListsManager
) {
    
    /**
     * Загружает пресеты в таблицу с учетом текущего состояния
     */
    fun loadPresetsIntoTable(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        table: JTable,
        onAddButtonRow: () -> Unit,
        inMemoryTableOrder: List<String>
    ) {
        println("ADB_DEBUG: ExtendedTableLoader.loadPresetsIntoTable()")
        val tempLists = tempListsManager.getTempLists()
        println("ADB_DEBUG:   tempListsManager.getTempLists().size: ${tempLists.size}")
        println("ADB_DEBUG:   tempListsManager.isEmpty(): ${tempListsManager.isEmpty()}")
        
        if (tempLists.isEmpty()) {
            println("ADB_DEBUG:   WARNING: tempListsManager is EMPTY before passing to TableLoader!")
            println("ADB_DEBUG:   Stack trace:")
            Thread.currentThread().stackTrace.take(10).forEach { element ->
                println("ADB_DEBUG:     at $element")
            }
        }
        
        // Очищаем выделение при смене режимов
        if (dialogState.isSwitchingMode() || dialogState.isSwitchingList()) {
            hoverStateManager?.resetForModeSwitch()
        }
        
        tableLoader.loadPresetsIntoTable(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempLists,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isFirstLoad = dialogState.isFirstLoad(),
            isSwitchingList = dialogState.isSwitchingList(),
            isSwitchingMode = dialogState.isSwitchingMode(),
            isSwitchingDuplicatesFilter = dialogState.isSwitchingDuplicatesFilter(),
            onTableUpdating = { updating -> dialogState.setTableUpdating(updating) },
            onAddButtonRow = { onAddButtonRow() },
            inMemoryOrder = if (dialogState.isShowAllPresetsMode()) {
                if (inMemoryTableOrder.isNotEmpty()) {
                    println("ADB_DEBUG: Using existing inMemoryTableOrder with ${inMemoryTableOrder.size} items")
                    inMemoryTableOrder
                } else {
                    // Попробуем загрузить последний сохранённый порядок
                    val savedOrder = PresetListService.getShowAllPresetsOrder()
                    if (savedOrder.isNotEmpty()) {
                        println("ADB_DEBUG: Loading saved Show All order as inMemoryOrder with ${savedOrder.size} items")
                        savedOrder.ifEmpty { null }
                    } else {
                        null
                    }
                }
            } else {
                null
            },
            initialHiddenDuplicates = hiddenDuplicatesManager.getHiddenDuplicatesForTableLoader(),
            table = table,
            onClearTableSelection = {
                hoverStateManager?.clearTableSelection()
            }
        )
        
        // Обновляем текущее состояние скрытых дублей после загрузки таблицы
        updateCurrentHiddenDuplicates()
    }
    
    /**
     * Синхронно загружает пресеты в таблицу (без invokeLater)
     */
    fun loadPresetsIntoTableSync(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        table: JTable,
        onAddButtonRow: () -> Unit,
        inMemoryTableOrder: List<String>,
        presetsOverride: List<DevicePreset>? = null
    ) {
        println("ADB_DEBUG: loadPresetsIntoTableSync() - Start, presetsOverride: ${presetsOverride?.size}")
        println("ADB_DEBUG: dialogState.isShowAllPresetsMode(): ${dialogState.isShowAllPresetsMode()}")
        println("ADB_DEBUG: dialogState.isHideDuplicatesMode(): ${dialogState.isHideDuplicatesMode()}")
        
        // Используем TableLoader для загрузки пресетов с применением сортировки
        tableLoader.loadPresetsIntoTable(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempListsManager.getTempLists(),
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isFirstLoad = false,
            isSwitchingList = false,
            isSwitchingMode = false,
            isSwitchingDuplicatesFilter = false,
            onTableUpdating = { updating -> dialogState.setTableUpdating(updating) },
            onAddButtonRow = { onAddButtonRow() },
            inMemoryOrder = if (dialogState.isShowAllPresetsMode()) {
                inMemoryTableOrder.ifEmpty {
                    // Используем ту же логику, что и в основном методе
                    val savedOrder = PresetListService.getShowAllPresetsOrder()
                    if (savedOrder.isNotEmpty()) {
                        val convertedOrder = savedOrder.mapNotNull { key ->
                            val parts = key.split("::")
                            if (parts.size == 2) {
                                val listName = parts[0]
                                val presetId = parts[1]
                                tempListsManager.getTempLists().values.find { it.name == listName }?.presets?.find { it.id == presetId }
                                    ?.let { preset ->
                                        "${listName}::${preset.label}::${preset.size}::${preset.dpi}"
                                    }
                            } else null
                        }

                        convertedOrder.ifEmpty {
                            null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            },
            initialHiddenDuplicates = hiddenDuplicatesManager.getHiddenDuplicatesForTableLoader(),
            table = table,
            onClearTableSelection = {
                hoverStateManager?.clearTableSelection()
            }
        )
        
        // Обновляем текущее состояние скрытых дублей после загрузки таблицы
        updateCurrentHiddenDuplicates()
        
        // Обновляем состояние трекера
        TableStateTracker.updateTableState(tableModel)
    }
    
    /**
     * Загружает пресеты в таблицу без вызова слушателей
     * Используется для пересортировки после редактирования ячеек
     */
    fun loadPresetsIntoTableWithoutNotification(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        table: JTable,
        onAddButtonRow: () -> Unit,
        inMemoryTableOrder: List<String>,
        tableModelListener: TableModelListener?
    ) {
        // Временно отключаем слушатель модели
        tableModelListener?.let { tableModel.removeTableModelListener(it) }
        
        dialogState.withTableUpdate {
            loadPresetsIntoTable(tableModel, currentPresetList, table, onAddButtonRow, inMemoryTableOrder)
        }
        
        // Возвращаем слушатель на место
        tableModelListener?.let { tableModel.addTableModelListener(it) }
    }
    
    /**
     * Перезагружает таблицу с временным отключением слушателей для избежания рекурсии
     */
    fun reloadTableWithoutListeners(
        table: JTable,
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        onAddButtonRow: () -> Unit,
        onSaveCurrentTableOrder: () -> Unit,
        tableModelListener: TableModelListener?,
        inMemoryTableOrder: List<String>
    ) {
        tableLoader.reloadTableWithoutListeners(
            table = table,
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempListsManager.getTempLists(),
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            onTableUpdating = { dialogState.setTableUpdating(it) },
            onAddButtonRow = onAddButtonRow,
            onSaveCurrentTableOrder = onSaveCurrentTableOrder,
            onClearLastInteractedRow = { 
                hoverStateManager?.updateLastInteractedRow(-1)
            },
            tableModelListener = tableModelListener,
            inMemoryOrder = inMemoryTableOrder,
            initialHiddenDuplicates = hiddenDuplicatesManager.getInitialHiddenDuplicates()
        )
    }
    
    /**
     * Обновляет таблицу с проверкой полей и перерисовкой
     */
    fun refreshTable(
        table: JTable,
        onValidateFields: () -> Unit
    ) {
        tableLoader.refreshTable(table, onValidateFields)
    }
    
    /**
     * Добавляет специальную строку с кнопкой добавления
     */
    fun addButtonRow(tableModel: DevicePresetTableModel) {
        tableLoader.addButtonRow(tableModel, dialogState.isShowAllPresetsMode())
        tableModel.updateRowNumbers()
    }
    
    /**
     * Обновляет текущее состояние скрытых дублей на основе текущих данных
     */
    private fun updateCurrentHiddenDuplicates() {
        hiddenDuplicatesManager.updateCurrentHiddenDuplicates(
            dialogState.isHideDuplicatesMode(),
            tempListsManager.getTempLists()
        )
    }
}