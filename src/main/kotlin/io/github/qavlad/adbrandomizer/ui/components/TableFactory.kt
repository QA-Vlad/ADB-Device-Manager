package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.ui.theme.ColorScheme
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import javax.swing.JComponent
import javax.swing.table.TableCellRenderer
import java.awt.Component
import java.util.Vector

/**
 * Фабрика для создания и настройки таблицы пресетов
 * Инкапсулирует логику создания таблицы и модели
 */
class TableFactory {
    
    /**
     * Callback-интерфейс для обработки событий редактирования
     */
    interface EditingCallbacks {
        fun onEditCellAt(row: Int, column: Int, oldValue: String)
        fun onRemoveEditor(row: Int, column: Int, oldValue: String?, newValue: String)
        fun onChangeSelection(row: Int, column: Int, oldValue: String)
    }
    
    /**
     * Создает модель таблицы с начальными колонками
     */
    fun createTableModel(historyManager: CommandHistoryManager, showCounters: Boolean = true): DevicePresetTableModel {
        val columnNames = if (showCounters) {
            Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "Size Uses", "DPI Uses", "  "))
        } else {
            Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        }
        return DevicePresetTableModel(Vector<Vector<Any>>(), columnNames, historyManager)
    }
    
    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(
        model: DevicePresetTableModel,
        hoverStateProvider: () -> HoverState,
        editingCallbacks: EditingCallbacks
    ): JBTable {
        return object : JBTable(model) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                // Проверяем, что это не строка с кнопкой
                if (row >= 0 && row < rowCount) {
                    val firstColumnValue = model.getValueAt(row, 0)
                    if (firstColumnValue == "+") {
                        return false // Не позволяем редактировать строку с кнопкой
                    }
                }
                return super.isCellEditable(row, column)
            }

            @Suppress("DEPRECATION")
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                // Проверяем, что индексы в допустимых пределах
                if (row >= rowCount || column >= columnCount) {
                    return super.prepareRenderer(renderer, row, column)
                }

                val component = super.prepareRenderer(renderer, row, column)

                if (component is JComponent) {
                    applyTableCellStyles(component, row, column, model, hoverStateProvider())
                }

                return component
            }

            override fun editCellAt(row: Int, column: Int): Boolean {
                println("ADB_DEBUG: editCellAt called - row=$row, column=$column")
                if (row >= 0 && column >= 0) {
                    val oldValue = model.getValueAt(row, column) as? String ?: ""
                    println("ADB_DEBUG: editCellAt - setting editingCellOldValue='$oldValue'")
                    editingCallbacks.onEditCellAt(row, column, oldValue)
                }
                return super.editCellAt(row, column)
            }

            override fun removeEditor() {
                println("ADB_DEBUG: removeEditor called")
                val cellEditor = this.cellEditor
                if (cellEditor != null) {
                    val editingRow = this.editingRow
                    val editingColumn = this.editingColumn
                    if (editingRow >= 0 && editingColumn >= 0) {
                        val oldValue = model.getValueAt(editingRow, editingColumn) as? String
                        super.removeEditor()
                        val newValue = model.getValueAt(editingRow, editingColumn) as? String ?: ""
                        editingCallbacks.onRemoveEditor(editingRow, editingColumn, oldValue, newValue)
                        return
                    }
                }
                super.removeEditor()
            }

            override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
                println("ADB_DEBUG: changeSelection called - row=$rowIndex, col=$columnIndex")
                
                // Предотвращаем стандартное выделение если используем кастомную навигацию
                val currentHoverState = hoverStateProvider()
                if (currentHoverState.selectedTableRow >= 0 && currentHoverState.selectedTableColumn >= 0) {
                    // Не вызываем super.changeSelection() чтобы избежать стандартного выделения
                    return
                }
                
                if (rowIndex >= 0 && columnIndex >= 0 && columnIndex in 2..4) {
                    val oldRow = selectionModel.leadSelectionIndex
                    val oldColumn = columnModel.selectionModel.leadSelectionIndex

                    if (oldRow != rowIndex || oldColumn != columnIndex) {
                        val oldValue = model.getValueAt(rowIndex, columnIndex) as? String ?: ""
                        println("ADB_DEBUG: changeSelection - setting editingCellOldValue='$oldValue'")
                        editingCallbacks.onChangeSelection(rowIndex, columnIndex, oldValue)
                    }
                }
                super.changeSelection(rowIndex, columnIndex, toggle, extend)
            }
        }
    }
    
    /**
     * Применяет стили к ячейке таблицы
     */
    private fun applyTableCellStyles(
        component: JComponent,
        row: Int,
        column: Int,
        model: DevicePresetTableModel,
        hoverState: HoverState
    ) {
        // Проверяем, является ли это строкой с кнопкой
        val firstColumnValue = if (row >= 0 && row < model.rowCount) model.getValueAt(row, 0) else ""
        val isButtonRow = firstColumnValue == "+"

        if (isButtonRow && column == 0) {
            // Для ячейки с плюсиком проверяем hover состояние
            val isHovered = hoverState.isTableCellHovered(row, column)

            // Применяем hover эффект только если мышь именно на этой ячейке
            if (isHovered) {
                val normalBg = component.background
                val hoverBg = normalBg?.brighter()
                component.background = hoverBg ?: normalBg
            }
            component.isOpaque = true
        } else if (isButtonRow) {
            // Для остальных ячеек строки с кнопкой - обычный фон
            component.isOpaque = true
        } else {
            // Обычная логика для других строк
            val isHovered = hoverState.isTableCellHovered(row, column)
            val isSelectedCell = hoverState.isTableCellSelected(row, column)

            var isInvalidCell = false
            if (column in 3..4) {
                val value = model.getValueAt(row, column)
                val text = value as? String ?: ""
                val isValid = if (text.isBlank()) true else when (column) {
                    3 -> ValidationUtils.isValidSizeFormat(text)
                    4 -> ValidationUtils.isValidDpi(text)
                    else -> true
                }
                if (!isValid) {
                    isInvalidCell = true
                }
            }

            component.background = ColorScheme.getTableCellBackground(
                isSelected = isSelectedCell,
                isHovered = isHovered,
                isError = isInvalidCell
            )
            component.foreground = ColorScheme.getTableCellForeground(
                isError = isInvalidCell
            )
            component.isOpaque = true
        }
    }
}