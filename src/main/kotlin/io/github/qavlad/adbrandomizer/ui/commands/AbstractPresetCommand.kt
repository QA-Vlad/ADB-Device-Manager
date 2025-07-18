package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.ui.dialogs.SettingsDialogController
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
    protected val controller: SettingsDialogController
) : UndoableCommand {
    
    protected val tableModel: DevicePresetTableModel
        get() = controller.tableModel
        
    protected val table
        get() = controller.table
        
    protected val currentPresetList: PresetList?
        get() = controller.getCurrentPresetListForCommands()
        
    protected val tempPresetLists: Map<String, PresetList>
        get() = controller.getTempPresetListsForCommands()
        
    protected val isShowAllPresetsMode: Boolean
        get() = controller.getIsShowAllPresetsModeForCommands()
        
    protected val isHideDuplicatesMode: Boolean
        get() = controller.getIsHideDuplicatesModeForCommands()
        
    protected val historyManager: CommandHistoryManager
        get() = controller.historyManager
        
    protected var isTableUpdating: Boolean
        get() = false // Не используется для чтения
        set(value) = controller.setIsTableUpdatingForCommands(value)
        
    /**
     * Обновляет таблицу после изменений
     */
    protected fun refreshTable() {
        controller.refreshTableForCommands()
    }
    
    /**
     * Перезагружает пресеты в таблицу
     */
    protected fun loadPresetsIntoTable() {
        controller.loadPresetsIntoTableForCommands(null)
    }
    
    /**
     * Выполняет операцию с временным отключением слушателя модели таблицы
     */
    protected fun withTableUpdateDisabled(block: () -> Unit) {
        println("ADB_DEBUG: withTableUpdateDisabled - start")
        isTableUpdating = true
        controller.setIsTableUpdatingForCommands(true)
        controller.setIsPerformingHistoryOperationForCommands(true)
        try {
            block()
        } finally {
            isTableUpdating = false
            controller.setIsTableUpdatingForCommands(false)
            controller.setIsPerformingHistoryOperationForCommands(false)
            println("ADB_DEBUG: withTableUpdateDisabled - end")
        }
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
        val rowData = java.util.Vector<Any>()
        rowData.add(false) // checked
        rowData.add(false) // edit mode
        rowData.add(preset.label)
        rowData.add(preset.size)
        rowData.add(preset.dpi)
        return rowData
    }
    
    /**
     * Обновляет UI для отражения текущего состояния
     */
    protected fun updateUI() {
        controller.updateSelectedListInUI()
    }
}