package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import javax.swing.SwingUtilities

/**
 * Базовый класс для всех команд работы с пресетами
 * Содержит общую логику и доступ к необходимым компонентам
 */
abstract class AbstractPresetCommand(
    protected val controller: CommandContext
) : UndoableCommand {
    
    // Сохраняем режим, в котором была выполнена операция
    val wasShowAllMode = controller.isShowAllPresetsMode()
    protected val wasHideDuplicatesMode = controller.isHideDuplicatesMode()
    protected val originalListId = controller.getCurrentPresetList()?.id
    
    protected val tableModel: DevicePresetTableModel
        get() = controller.tableModel
        
    protected val table
        get() = controller.table
        
    protected val currentPresetList: PresetList?
        get() = controller.getCurrentPresetList()
        
    protected val tempPresetLists: Map<String, PresetList>
        get() = controller.getTempPresetLists()
        
    protected val isShowAllPresetsMode: Boolean
        get() = controller.isShowAllPresetsMode()
        
    protected val isHideDuplicatesMode: Boolean
        get() = controller.isHideDuplicatesMode()
        
    protected val historyManager: CommandHistoryManager
        get() = controller.historyManager
        
    protected var isTableUpdating: Boolean
        get() = false // Не используется для чтения
        set(value) = controller.setTableUpdating(value)
        
    /**
     * Обновляет таблицу после изменений
     */
    protected fun refreshTable() {
        controller.refreshTable()
    }
    
    /**
     * Перезагружает пресеты в таблицу
     */
    protected fun loadPresetsIntoTable() {
        controller.loadPresetsIntoTable(null)
    }
    
    /**
     * Выполняет операцию с временным отключением слушателя модели таблицы
     */
    protected fun withTableUpdateDisabled(block: () -> Unit) {
        println("ADB_DEBUG: withTableUpdateDisabled - start")
        isTableUpdating = true
        controller.setTableUpdating(true)
        controller.setPerformingHistoryOperation(true)
        try {
            block()
        } finally {
            isTableUpdating = false
            controller.setTableUpdating(false)
            controller.setPerformingHistoryOperation(false)
            println("ADB_DEBUG: withTableUpdateDisabled - end")
        }
    }
    
    /**
     * Обновляет UI для отражения текущего состояния
     */
    protected fun updateUI() {
        controller.updateSelectedListInUI()
    }
    
    /**
     * Выполняет операцию в потоке Swing
     */
    protected fun invokeLater(block: () -> Unit) {
        SwingUtilities.invokeLater(block)
    }
    
    /**
     * Создает Vector для строки таблицы из DevicePreset
     */
    protected fun createTableRow(preset: DevicePreset): java.util.Vector<Any> {
        val showCounters = tableModel.columnCount > 6
        return DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1, showCounters)
    }
    
    /**
     * Проверяет, нужно ли переключить режим для выполнения операции
     */
    protected fun needToSwitchMode(): Boolean {
        val currentShowAll = controller.isShowAllPresetsMode()
        val currentListId = controller.getCurrentPresetList()?.id
        
        // Проверяем режим Show All
        if (wasShowAllMode != currentShowAll) {
            return true
        }
        
        // Если не в режиме Show All, проверяем текущий список
        if (!wasShowAllMode && originalListId != null && originalListId != currentListId) {
            return true
        }
        
        return false
    }
    
    /**
     * Переключается в режим, в котором была выполнена операция
     */
    protected fun switchToOriginalMode() {
        println("ADB_DEBUG: Switching to original mode...")
        println("ADB_DEBUG: Original: ShowAll=$wasShowAllMode, HideDuplicates=$wasHideDuplicatesMode, listId=$originalListId")
        
        // Сначала переключаем список, если нужно
        if (!wasShowAllMode && originalListId != null) {
            val currentListId = controller.getCurrentPresetList()?.id
            if (originalListId != currentListId) {
                println("ADB_DEBUG: Switching to list $originalListId")
                controller.switchToList(originalListId)
            }
        }
        
        // Затем переключаем режим Show All
        if (wasShowAllMode != controller.isShowAllPresetsMode()) {
            println("ADB_DEBUG: Switching Show All mode to $wasShowAllMode")
            controller.setShowAllMode(wasShowAllMode)
        }
    }
    
    /**
     * Выводит отладочную информацию о режимах выполнения команды
     */
    protected fun logCommandExecutionMode(commandName: String, listName: String? = null, additionalInfo: String = "") {
        println("ADB_DEBUG: $commandName - isShowAllMode: $isShowAllPresetsMode, listName: $listName$additionalInfo")
        println("ADB_DEBUG: Command was executed in: ShowAll=$wasShowAllMode, HideDuplicates=$wasHideDuplicatesMode, listId=$originalListId")
        println("ADB_DEBUG: Current mode: ShowAll=${controller.isShowAllPresetsMode()}, HideDuplicates=${controller.isHideDuplicatesMode()}, listId=${controller.getCurrentPresetList()?.id}")
    }
    
    /**
     * Определяет целевой список для операции
     */
    protected fun findTargetList(listName: String?): PresetList? {
        val targetListName = listName ?: currentPresetList?.name
        return if (targetListName != null) {
            tempPresetLists.values.find { it.name == targetListName }
        } else {
            currentPresetList
        }
    }

}