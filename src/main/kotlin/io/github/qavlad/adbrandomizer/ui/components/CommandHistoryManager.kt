package io.github.qavlad.adbrandomizer.ui.components

import io.github.qavlad.adbrandomizer.ui.commands.UndoableCommand
import io.github.qavlad.adbrandomizer.ui.commands.*
import io.github.qavlad.adbrandomizer.ui.dialogs.PresetsDialogController
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Менеджер истории команд, работающий с паттерном Command
 */
class CommandHistoryManager(
    private val controller: PresetsDialogController,
    private val maxHistorySize: Int = 50
) {
    private val historyStack = mutableListOf<UndoableCommand>()
    private val redoStack = mutableListOf<UndoableCommand>()
    private val cellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
    
    /**
     * Получает или создает идентификатор ячейки
     */
    fun getCellId(row: Int, column: Int): CellIdentity {
        val key = Pair(row, column)
        return cellIdMap.getOrPut(key) { CellIdentity.generate() }
    }
    
    /**
     * Добавляет команду редактирования ячейки с информацией о пресете
     */
    fun addCellEdit(row: Int, column: Int, oldValue: String, newValue: String, presetBeforeEdit: DevicePreset) {
        val cellId = getCellId(row, column)
        
        // Определяем имя списка, если мы в режиме Show All
        val listName = if (controller.tableModel.columnCount > 8) {
            val listColumnIndex = controller.tableModel.columnCount - 1
            controller.tableModel.getValueAt(row, listColumnIndex) as? String
        } else {
            null
        }
        
        // Находим индекс пресета в списке
        val targetList = if (listName != null) {
            controller.getTempPresetLists().values.find { it.name == listName }
        } else {
            controller.getCurrentPresetList()
        }
        
        val presetIndex = targetList?.presets?.indexOfFirst { it.id == presetBeforeEdit.id } ?: -1
        
        val command = CellEditCommand(
            controller,
            cellId,
            oldValue,
            newValue,
            listName,
            presetBeforeEdit.label,
            presetBeforeEdit.size,
            presetBeforeEdit.dpi,
            column,
            presetBeforeEdit.id,
            presetIndex
        )
        addCommand(command)
        
        println("ADB_DEBUG: Added cell edit command with preset info: ($row, $column) [${cellId.id.substring(0, 8)}...] '$oldValue' -> '$newValue'")
        println("ADB_DEBUG:   Original preset - label: '${presetBeforeEdit.label}', size: '${presetBeforeEdit.size}', dpi: '${presetBeforeEdit.dpi}'")
        println("ADB_DEBUG:   Preset index in list: $presetIndex")
    }
    
    /**
     * Добавляет команду редактирования ячейки (legacy метод для обратной совместимости)
     */
    fun addCellEdit(row: Int, column: Int, oldValue: String, newValue: String) {
        if (oldValue != newValue) {
            val cellId = getCellId(row, column)
            
            // В режиме Show all нужно определить список из колонки List
            val listName = if (controller.isShowAllPresetsMode()) {
                controller.getListNameAtRow(row) ?: controller.getCurrentPresetList()?.name
            } else {
                controller.getCurrentPresetList()?.name
            }
            
            // Получаем информацию о пресете до изменения
            val presetAtRow = controller.tableModel.getPresetAt(row)
            if (presetAtRow == null) {
                println("ADB_DEBUG: Could not find preset at row $row")
                return
            }
            
            // Определяем исходные значения пресета
            val originalLabel = if (column == 2) oldValue else presetAtRow.label
            val originalSize = if (column == 3) oldValue else presetAtRow.size
            val originalDpi = if (column == 4) oldValue else presetAtRow.dpi
            
            // Проверяем, не добавляли ли мы точно такую же команду только что
            val lastCommand = historyStack.lastOrNull()
            if (lastCommand is CellEditCommand && 
                lastCommand.cellId == cellId && 
                lastCommand.oldValue == oldValue && 
                lastCommand.newValue == newValue) {
                println("ADB_DEBUG: Skipping duplicate cell edit command: ($row, $column) '$oldValue' -> '$newValue'")
                return
            }
            
            // Находим индекс пресета в списке
            val targetList = if (listName != null && controller.isShowAllPresetsMode()) {
                controller.getTempPresetLists().values.find { it.name == listName }
            } else {
                controller.getCurrentPresetList()
            }
            
            val presetIndex = targetList?.presets?.indexOfFirst { it.id == presetAtRow.id } ?: -1
            
            val command = CellEditCommand(
                controller, 
                cellId, 
                oldValue, 
                newValue, 
                listName,
                originalLabel,
                originalSize,
                originalDpi,
                column,
                presetAtRow.id,
                presetIndex
            )
            addCommand(command)
            
            println("ADB_DEBUG: Added cell edit command: ($row, $column) [${cellId.id.substring(0, 8)}...] '$oldValue' -> '$newValue' in list '$listName'")
            
            // Добавляем стек вызовов для отладки
            val stackTrace = Thread.currentThread().stackTrace
            println("ADB_DEBUG: addCellEdit called from:")
            stackTrace.take(10).forEachIndexed { index, element ->
                if (index > 2) { // Пропускаем первые элементы стека
                    println("ADB_DEBUG:   $element")
                }
            }
        }
    }
    
    /**
     * Добавляет команду добавления пресета
     */
    fun addPresetAdd(rowIndex: Int, preset: DevicePreset, listName: String? = null) {
        val command = PresetAddCommand(controller, rowIndex, preset, listName)
        addCommand(command)
        println("ADB_DEBUG: Added preset add command: rowIndex=$rowIndex, preset=${preset.label}, listName=$listName")
    }
    
    /**
     * Добавляет команду удаления пресета
     */
    fun addPresetDelete(rowIndex: Int, preset: DevicePreset, listName: String? = null, actualListIndex: Int? = null) {
        val command = PresetDeleteCommand(controller, rowIndex, preset, listName, actualListIndex)
        addCommand(command)
        println("ADB_DEBUG: Added preset delete command: rowIndex=$rowIndex, actualListIndex=$actualListIndex, preset=${preset.label}, listName=$listName")
    }
    
    /**
     * Добавляет команду перемещения пресета
     */
    fun addPresetMove(fromIndex: Int, toIndex: Int, orderAfter: List<String>) {
        val command = PresetMoveCommand(controller, fromIndex, toIndex, orderAfter)
        addCommand(command)
        println("ADB_DEBUG: Added preset move command: from=$fromIndex, to=$toIndex")
    }

    fun updateLastMoveCommandOrderAfter(orderAfter: List<String>) {
        val lastCommand = historyStack.lastOrNull() as? PresetMoveCommand
        lastCommand?.orderAfter = orderAfter
    }
    
    /**
     * Получает последнюю команду из истории
     */
    fun getLastCommand(): UndoableCommand? = historyStack.lastOrNull()
    

    
    /**
     * Добавляет команду дублирования пресета
     */
    fun addPresetDuplicate(originalIndex: Int, duplicateIndex: Int, preset: DevicePreset) {
        val command = PresetDuplicateCommand(controller, originalIndex, duplicateIndex, preset)
        addCommand(command)
        println("ADB_DEBUG: Added preset duplicate command: original=$originalIndex, duplicate=$duplicateIndex, preset=${preset.label}")
    }
    
    /**
     * Добавляет команду в историю
     */
    private fun addCommand(command: UndoableCommand, clearRedo: Boolean = true) {
        historyStack.add(command)
        if (clearRedo) {
            redoStack.clear()
        }
        
        if (historyStack.size > maxHistorySize) {
            historyStack.removeAt(0)
        }
    }
    
    /**
     * Отменяет последнюю операцию
     */
    fun undo(): Boolean {
        println("ADB_DEBUG: Undo called - history contains ${historyStack.size} commands")
        println("ADB_DEBUG: History stack contents:")
        historyStack.forEachIndexed { index, cmd ->
            println("ADB_DEBUG:   [$index] ${cmd.description}")
        }
        
        if (historyStack.isNotEmpty()) {
            val command = historyStack.removeAt(historyStack.size - 1)
            
            // Проверяем, нужно ли переключить режим
            if (command is AbstractPresetCommand) {
                val wasInShowAll = command.wasShowAllMode
                val currentlyInShowAll = controller.isShowAllMode()
                
                if (wasInShowAll != currentlyInShowAll) {
                    if (wasInShowAll) {
                        println("ADB_DEBUG: Command was executed in Show All mode, but we're in normal mode - enabling Show All")
                        controller.setShowAllMode(true)
                    } else {
                        println("ADB_DEBUG: Command was executed in normal mode, but we're in Show All - disabling Show All")
                        controller.disableShowAllMode()
                    }
                }
            }
            
            redoStack.add(command)
            
            println("ADB_DEBUG: Undoing command: ${command.description}")
            println("ADB_DEBUG: Commands left in history: ${historyStack.size}")
            command.undo()
            return true
        } else {
            println("ADB_DEBUG: History is empty - no commands to undo")
            return false
        }
    }
    
    /**
     * Повторяет последнюю отмененную операцию
     */
    fun redo(): Boolean {
        println("ADB_DEBUG: Redo called - redo stack contains ${redoStack.size} commands")
        
        if (redoStack.isNotEmpty()) {
            val command = redoStack.removeAt(redoStack.size - 1)
            
            // Проверяем, нужно ли переключить режим
            if (command is AbstractPresetCommand) {
                val wasInShowAll = command.wasShowAllMode
                val currentlyInShowAll = controller.isShowAllMode()
                
                if (wasInShowAll != currentlyInShowAll) {
                    if (wasInShowAll) {
                        println("ADB_DEBUG: Command was executed in Show All mode, but we're in normal mode - enabling Show All")
                        controller.setShowAllMode(true)
                    } else {
                        println("ADB_DEBUG: Command was executed in normal mode, but we're in Show All - disabling Show All")
                        controller.disableShowAllMode()
                    }
                }
            }
            
            addCommand(command, clearRedo = false)
            
            println("ADB_DEBUG: Redoing command: ${command.description}")
            command.redo()
            return true
        } else {
            println("ADB_DEBUG: Redo stack is empty - no commands to redo")
            return false
        }
    }
    
    /**
     * Обновляет карту идентификаторов ячеек при перемещении строки
     */
    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        println("ADB_DEBUG: Row moved from $fromIndex to $toIndex - updating cellIdMap")
        CellManagerUtils.updateCellIdMapOnRowMove(cellIdMap, fromIndex, toIndex)
        println("ADB_DEBUG: cellIdMap updated after row move")
    }
    
    /**
     * Ищет координаты ячейки по идентификатору
     */
    fun findCellCoordinates(cellId: CellIdentity): Pair<Int, Int>? {
        val coords = CellManagerUtils.findCellCoordinates(cellIdMap, cellId)
        println("ADB_DEBUG: Finding cell by ID ${cellId.id.substring(0, 8)}... -> $coords")
        return coords
    }
    
    fun isEmpty(): Boolean = historyStack.isEmpty()
    
    fun size(): Int = historyStack.size
    
    fun canUndo(): Boolean = historyStack.isNotEmpty()
    
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    
    /**
     * Очищает всю историю
     */
    fun clear() {
        historyStack.clear()
        redoStack.clear()
        cellIdMap.clear()
    }
}