package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.ui.components.*
import javax.swing.Timer
import javax.swing.event.TableModelListener

/**
 * Фабрика для создания компонентов диалога настроек.
 * Инкапсулирует всю логику создания и настройки UI компонентов.
 */
class DialogComponentsFactory {
    private val tableFactory = TableFactory()
    
    
    /**
     * Создает модель таблицы
     */
    fun createTableModel(historyManager: CommandHistoryManager): DevicePresetTableModel {
        return tableFactory.createTableModel(historyManager)
    }
    
    /**
     * Создает таблицу с настроенными обработчиками редактирования
     */
    fun createTable(
        model: DevicePresetTableModel,
        hoverStateProvider: () -> HoverState,
        dialogState: DialogStateManager,
        historyManager: CommandHistoryManager
    ): JBTable {
        val editingCallbacks = createEditingCallbacks(dialogState, historyManager)
        return tableFactory.createTable(model, hoverStateProvider, editingCallbacks)
    }
    
    
    /**
     * Создает слушатель модели таблицы с группировкой обновлений
     */
    fun createTableModelListener(
        dialogState: DialogStateManager,
        historyManager: CommandHistoryManager,
        onValidateFields: () -> Unit,
        onSyncTableChanges: () -> Unit,
        onTableRepaint: () -> Unit
    ): TableModelListenerWithTimer {
        return TableModelListenerWithTimer(
            dialogState = dialogState,
            historyManager = historyManager,
            onValidateFields = onValidateFields,
            onSyncTableChanges = onSyncTableChanges,
            onTableRepaint = onTableRepaint
        )
    }
    
    /**
     * Создает обработчики редактирования ячеек
     */
    private fun createEditingCallbacks(
        dialogState: DialogStateManager,
        historyManager: CommandHistoryManager
    ): TableFactory.EditingCallbacks {
        return object : TableFactory.EditingCallbacks {
            override fun onEditCellAt(row: Int, column: Int, oldValue: String) {
                dialogState.setEditingCell(row, column, oldValue)
            }
            
            override fun onRemoveEditor(row: Int, column: Int, oldValue: String?, newValue: String) {
                println("ADB_DEBUG: removeEditor called - editingCellOldValue=$oldValue, row=$row, col=$column")
                if (oldValue != null && newValue != oldValue) {
                    println("ADB_DEBUG: removeEditor - adding command to history from removeEditor")
                    historyManager.addCellEdit(row, column, oldValue, newValue)
                }
                dialogState.clearEditingCell()
            }
            
            override fun onChangeSelection(row: Int, column: Int, oldValue: String) {
                val editingState = dialogState.getEditingCellState()
                println("ADB_DEBUG: changeSelection - setting editingCellOldValue='$oldValue' (was: '${editingState.oldValue}')")
                dialogState.setEditingCell(row, column, oldValue)
            }
        }
    }
}

/**
 * Обертка для TableModelListener с таймером для группировки обновлений
 */
class TableModelListenerWithTimer(
    private val dialogState: DialogStateManager,
    private val historyManager: CommandHistoryManager,
    private val onValidateFields: () -> Unit,
    private val onSyncTableChanges: () -> Unit,
    private val onTableRepaint: () -> Unit,
    private val batchDelay: Int = 50
) {
    private var pendingTableUpdates = 0
    private var lastUpdateTimer: Timer? = null
    
    val listener: TableModelListener = TableModelListener { e ->
        if (dialogState.isTableUpdating()) {
            return@TableModelListener
        }
        
        pendingTableUpdates++
        println("ADB_DEBUG: pendingTableUpdates incremented to: $pendingTableUpdates")
        
        // Останавливаем предыдущий таймер
        lastUpdateTimer?.stop()
        
        // Всегда выполняем валидацию сразу
        onValidateFields()
        
        // Создаем новый таймер для группировки обновлений
        lastUpdateTimer = Timer(batchDelay) {
            if (pendingTableUpdates > 0) {
                processUpdates(e)
                pendingTableUpdates = 0
            }
            lastUpdateTimer = null
        }.apply {
            isRepeats = false
            start()
        }
    }
    
    private fun processUpdates(e: javax.swing.event.TableModelEvent) {
        val eventType = when(e.type) {
            javax.swing.event.TableModelEvent.UPDATE -> "UPDATE"
            javax.swing.event.TableModelEvent.INSERT -> "INSERT"
            javax.swing.event.TableModelEvent.DELETE -> "DELETE"
            else -> "UNKNOWN(${e.type})"
        }
        println("ADB_DEBUG: TableModelListener batch update - processing $pendingTableUpdates updates, type: $eventType")
        
        // Сохраняем состояние перед синхронизацией для команды перемещения
        if (dialogState.isDragAndDropInProgress() && dialogState.isHideDuplicatesMode()) {
            val lastCommand = historyManager.getLastCommand()
            if (lastCommand is io.github.qavlad.adbrandomizer.ui.commands.PresetMoveCommand) {
                println("ADB_DEBUG: Saving state before sync for PresetMoveCommand")
                lastCommand.saveStateBeforeSync()
            }
        }
        
        // Синхронизируем изменения с временными списками
        if (e.type == javax.swing.event.TableModelEvent.UPDATE ||
            e.type == javax.swing.event.TableModelEvent.DELETE) {
            onSyncTableChanges()
        }
        
        onTableRepaint()
    }
    
    fun forceSyncPendingUpdates() {
        println("ADB_DEBUG: forceSyncPendingUpdates called")
        println("ADB_DEBUG:   pendingTableUpdates = $pendingTableUpdates")
        println("ADB_DEBUG:   lastUpdateTimer = $lastUpdateTimer")
        
        // Останавливаем все таймеры
        lastUpdateTimer?.stop()
        
        // Выполняем синхронизацию немедленно
        if (pendingTableUpdates > 0) {
            println("ADB_DEBUG: Forcing sync of $pendingTableUpdates pending updates")
            onSyncTableChanges()
            pendingTableUpdates = 0
        } else {
            println("ADB_DEBUG: No pending updates to sync")
        }
    }
}