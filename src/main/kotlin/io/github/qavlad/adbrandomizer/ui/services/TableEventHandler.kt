package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetApplicationService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import java.awt.event.MouseEvent
import com.intellij.ui.table.JBTable
import javax.swing.*

/**
 * Обработчик событий таблицы пресетов
 * Управляет кликами, редактированием ячеек, контекстным меню и другими событиями
 */
class TableEventHandler(
    private val project: Project?
) {
    
    /**
     * Обрабатывает клик по ячейке таблицы
     */
    fun handleCellClick(
        table: JTable,
        tableModel: DevicePresetTableModel,
        row: Int,
        column: Int,
        clickCount: Int,
        onApplyPreset: (DevicePreset, Boolean, Boolean) -> Unit,
        onAddNewPreset: () -> Unit
    ) {
        // Проверяем, является ли это строкой с кнопкой "+"
        val firstColumnValue = tableModel.getValueAt(row, 0)
        if (firstColumnValue == "+") {
            if (column == 0) { // Проверяем клик именно по иконке
                onAddNewPreset()
            }
            return
        }
        
        when (column) {
            0 -> handleCheckboxClick(tableModel, row)
            // Колонка 1 - это номер пресета, не обрабатываем клики по ней
            2, 3, 4 -> handlePresetCellClick(table, row, column, clickCount)
            5 -> handleApplyClick(tableModel, row, onApplyPreset)
        }
    }
    
    /**
     * Показывает контекстное меню
     */
    fun showContextMenu(
        e: MouseEvent,
        table: JBTable,
        tableModel: DevicePresetTableModel,
        isShowAllMode: Boolean,
        canUndo: Boolean,
        canRedo: Boolean,
        onDuplicate: (Int) -> Unit,
        onDelete: (Int) -> Unit,
        onUndo: () -> Unit,
        onRedo: () -> Unit
    ) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return

        // Проверяем, что это не строка с кнопкой
        if (row >= 0 && row < tableModel.rowCount) {
            val firstColumnValue = tableModel.getValueAt(row, 0)
            if (firstColumnValue == "+") {
                return // Не показываем контекстное меню для строки с кнопкой
            }
        }

        val preset = tableModel.getPresetAt(row) ?: return
        val popupMenu = JPopupMenu()

        // В режиме "Show all presets" не показываем опцию дублирования
        if (!isShowAllMode) {
            val shortcut = if (SystemInfo.isMac) "Cmd+D" else "Ctrl+D"
            val duplicateItem = JMenuItem("Duplicate ($shortcut)")
            duplicateItem.addActionListener { onDuplicate(row) }
            popupMenu.add(duplicateItem)
        }

        val deleteItem = JMenuItem("Delete")
        deleteItem.addActionListener { onDelete(row) }
        popupMenu.add(deleteItem)

        popupMenu.addSeparator()

        val undoItem = JMenuItem("Undo")
        undoItem.isEnabled = canUndo
        undoItem.addActionListener { onUndo() }
        popupMenu.add(undoItem)

        val redoItem = JMenuItem("Redo")
        redoItem.isEnabled = canRedo
        redoItem.addActionListener { onRedo() }
        popupMenu.add(redoItem)

        if (preset.dpi.isNotBlank() || preset.size.isNotBlank()) {
            popupMenu.addSeparator()
        }

        if (preset.dpi.isNotBlank()) {
            val applyDpiItem = JMenuItem("Apply DPI only (${preset.dpi})")
            applyDpiItem.addActionListener { 
                project?.let { PresetApplicationService.applyPreset(it, preset, setSize = false, setDpi = true) }
            }
            popupMenu.add(applyDpiItem)
        }

        if (preset.size.isNotBlank()) {
            val applySizeItem = JMenuItem("Apply Size only (${preset.size})")
            applySizeItem.addActionListener { 
                project?.let { PresetApplicationService.applyPreset(it, preset, setSize = true, setDpi = false) }
            }
            popupMenu.add(applySizeItem)
        }

        if (preset.dpi.isNotBlank() && preset.size.isNotBlank()) {
            val applyBothItem = JMenuItem("Apply Size and DPI")
            applyBothItem.addActionListener { 
                project?.let { PresetApplicationService.applyPreset(it, preset, setSize = true, setDpi = true) }
            }
            popupMenu.add(applyBothItem)
        }

        popupMenu.show(e.component, e.x, e.y)
    }
    
    private fun handleCheckboxClick(tableModel: DevicePresetTableModel, row: Int) {
        val currentValue = tableModel.getValueAt(row, 0) as? Boolean ?: false
        tableModel.setValueAt(!currentValue, row, 0)
    }
    
    private fun handlePresetCellClick(
        table: JTable,
        row: Int,
        column: Int,
        clickCount: Int
    ) {
        if (clickCount == 1) {
            SwingUtilities.invokeLater {
                table.editCellAt(row, column)
                table.editorComponent?.requestFocus()
            }
        }
    }
    
    private fun handleApplyClick(
        tableModel: DevicePresetTableModel,
        row: Int,
        onApplyPreset: (DevicePreset, Boolean, Boolean) -> Unit
    ) {
        val preset = DevicePreset(
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
        
        // Определяем, что применять на основе заполненности полей
        val setSize = preset.size.isNotBlank()
        val setDpi = preset.dpi.isNotBlank()
        
        if (setSize || setDpi) {
            onApplyPreset(preset, setSize, setDpi)
        }
    }
}