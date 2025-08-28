package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.services.DevicePreset
import io.github.qavlad.adbdevicemanager.services.PresetList
import io.github.qavlad.adbdevicemanager.ui.components.CommandHistoryManager
import io.github.qavlad.adbdevicemanager.ui.components.DevicePresetTableModel
import javax.swing.JOptionPane
import javax.swing.JTable
import javax.swing.SwingUtilities

/**
 * Сервис для выполнения CRUD операций с пресетами
 * Инкапсулирует логику добавления, удаления, дублирования и перемещения пресетов
 */
class PresetOperationsService(
    private val historyManager: CommandHistoryManager,
    private val presetOrderManager: PresetOrderManager? = null
) {
    
    /**
     * Добавляет новый пресет
     */
    fun addNewPreset(
        tableModel: DevicePresetTableModel,
        table: JTable,
        isShowAllMode: Boolean,
        @Suppress("UNUSED_PARAMETER") isHideDuplicatesMode: Boolean,
        currentListName: String?,
        onPresetAdded: (Int) -> Unit
    ) {
        if (isShowAllMode) {
            JOptionPane.showMessageDialog(
                table,
                "Cannot add presets in 'Show all presets' mode.\nPlease switch to a specific list.",
                "Action Not Available",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }
        
        // Удаляем строку с кнопкой, если она есть
        val lastRowIndex = tableModel.rowCount - 1
        if (lastRowIndex >= 0 && tableModel.getValueAt(lastRowIndex, 0) == "+") {
            tableModel.removeRow(lastRowIndex)
        }
        
        val newRowIndex = tableModel.rowCount
        val newPreset = DevicePreset("", "", "")
        
        // Добавляем новую строку
        val rowData = createRowData(newPreset, newRowIndex + 1)
        tableModel.addRow(rowData)
        
        // Добавляем в историю
        historyManager.addPresetAdd(newRowIndex, newPreset, currentListName)
        
        // Добавляем в фиксированный порядок, если есть имя списка
        if (currentListName != null && presetOrderManager != null) {
            // Определяем, после какого пресета добавить новый
            val afterPreset = if (newRowIndex > 0) {
                getPresetFromRow(tableModel, newRowIndex - 1)
            } else {
                null
            }
            presetOrderManager.addToFixedOrder(currentListName, newPreset, afterPreset)
        }
        
        // Уведомляем о добавлении
        onPresetAdded(newRowIndex)
        
        // Фокусируемся на новой строке
        SwingUtilities.invokeLater {
            table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true))
            table.setRowSelectionInterval(newRowIndex, newRowIndex)
            table.editCellAt(newRowIndex, 2)
            table.editorComponent?.requestFocus()
        }
    }
    
    /**
     * Дублирует пресет
     */
    fun duplicatePreset(
        row: Int,
        tableModel: DevicePresetTableModel,
        table: JTable,
        isShowAllMode: Boolean,
        isHideDuplicatesMode: Boolean,
        onDuplicatesFilterToggle: (Boolean) -> Unit,
        currentListName: String? = null
    ): Boolean {
        if (row < 0 || row >= tableModel.rowCount) return false
        
        // Проверяем, что это не строка с кнопкой
        val firstColumnValue = tableModel.getValueAt(row, 0)
        if (firstColumnValue == "+") {
            return false
        }
        
        if (isShowAllMode) {
            JOptionPane.showMessageDialog(
                table,
                "Cannot duplicate presets in 'Show all presets' mode.\nPlease switch to a specific list.",
                "Action Not Available",
                JOptionPane.INFORMATION_MESSAGE
            )
            return false
        }
        
        val originalPreset = getPresetFromRow(tableModel, row)
        val newPreset = originalPreset.copy(label = "${originalPreset.label} (copy)")
        
        val insertIndex = row + 1
        // Учитываем, что после вставки все последующие строки сдвинутся
        val rowData = createRowData(newPreset, insertIndex + 1)
        
        println("ADB_DEBUG: duplicatePreset - before insertRow, tableModel.rowCount=${tableModel.rowCount}")
        tableModel.insertRow(insertIndex, rowData)
        println("ADB_DEBUG: duplicatePreset - after insertRow, tableModel.rowCount=${tableModel.rowCount}")
        
        // Если включен режим скрытия дублей, автоматически отключаем его ПОСЛЕ вставки
        if (isHideDuplicatesMode) {
            println("ADB_DEBUG: duplicatePreset - disabling hide duplicates mode")
            onDuplicatesFilterToggle(false)
        }
        
        // Добавляем в фиксированный порядок, если есть менеджер
        if (presetOrderManager != null) {
            // Определяем имя списка
            val listName = getCurrentListName(tableModel, row) ?: currentListName
            if (listName != null) {
                presetOrderManager.addToFixedOrder(listName, newPreset, originalPreset)
            }
        }
        
        SwingUtilities.invokeLater {
            table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
            table.setRowSelectionInterval(insertIndex, insertIndex)
            table.editCellAt(insertIndex, 2)
            table.editorComponent?.requestFocusInWindow()
        }
        
        historyManager.addPresetDuplicate(row, insertIndex, originalPreset)
        return true
    }
    
    /**
     * Удаляет пресет
     */
    fun deletePreset(
        row: Int,
        tableModel: DevicePresetTableModel,
        isShowAllMode: Boolean,
        @Suppress("UNUSED_PARAMETER") tempPresetLists: Map<String, PresetList>,
        currentPresetList: PresetList?
    ): Boolean {
        if (row < 0 || row >= tableModel.rowCount) return false
        
        // Проверяем, что это не строка с кнопкой
        val firstColumnValue = tableModel.getValueAt(row, 0)
        if (firstColumnValue == "+") {
            return false
        }
        
        val preset = getPresetFromRow(tableModel, row)
        val listName = if (isShowAllMode) {
            getListNameFromRow(tableModel, row)
        } else {
            currentPresetList?.name
        }
        
        // Добавляем в историю перед удалением
        historyManager.addPresetDelete(row, preset, listName)
        
        // Удаляем из таблицы
        tableModel.removeRow(row)
        
        return true
    }
    
    /**
     * Создает данные строки для таблицы
     */
    private fun createRowData(preset: DevicePreset, rowIndex: Int): Array<Any> {
        // Используем стандартный метод создания строки таблицы
        val vector = DevicePresetTableModel.createRowVector(preset, rowIndex, showCounters = true)
        return vector.toTypedArray()
    }
    
    /**
     * Получает пресет из строки таблицы
     */
    private fun getPresetFromRow(tableModel: DevicePresetTableModel, row: Int): DevicePreset {
        // Используем метод getPresetAt для получения пресета с ID
        return tableModel.getPresetAt(row) ?: DevicePreset("", "", "")
    }
    
    /**
     * Получает имя списка из строки таблицы (для режима Show All)
     */
    private fun getListNameFromRow(tableModel: DevicePresetTableModel, row: Int): String? {
        return if (tableModel.columnCount > 6) {
            tableModel.getValueAt(row, 6) as? String
        } else {
            null
        }
    }
    
    /**
     * Получает имя текущего списка для пресета
     * В режиме Show All получает из таблицы, в обычном режиме использует переданное имя
     */
    private fun getCurrentListName(tableModel: DevicePresetTableModel, row: Int): String? {
        // Проверяем, находимся ли мы в режиме Show All по количеству колонок
        return if (tableModel.columnCount > 6) {
            getListNameFromRow(tableModel, row)
        } else {
            // В обычном режиме имя списка должно быть передано через контекст
            // Возвращаем null, чтобы вызывающий код мог обработать это
            null
        }
    }
}