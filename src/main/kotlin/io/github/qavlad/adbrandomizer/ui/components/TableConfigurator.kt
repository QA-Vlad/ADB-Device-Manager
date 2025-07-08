package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.AbstractTableCellEditor
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.ui.handlers.PresetTransferHandler
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import java.awt.Dimension
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer


class TableConfigurator(
    private val table: JBTable,
    private val hoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val onRowMoved: (Int, Int) -> Unit,
    private val onCellClicked: (Int, Int, Int) -> Unit,
    private val onTableExited: () -> Unit,
    private val validationRenderer: ValidationRenderer,
    private val showContextMenu: (MouseEvent) -> Unit,
    private val historyManager: HistoryManager,
    private val getPresetAtRow: (Int) -> io.github.qavlad.adbrandomizer.services.DevicePreset
) {
    
    fun configure() {
        table.apply {
            tableHeader.reorderingAllowed = false
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = JBUI.scale(PluginConfig.UI.TABLE_ROW_HEIGHT)
            dragEnabled = true
            dropMode = DropMode.INSERT_ROWS
            transferHandler = PresetTransferHandler { fromIndex, toIndex ->
                onRowMoved(fromIndex, toIndex)
            }
            
            selectionModel.clearSelection()
            setRowSelectionAllowed(false)
            setCellSelectionEnabled(false)

            val toolTipManager = ToolTipManager.sharedInstance()
            toolTipManager.initialDelay = PluginConfig.UI.TOOLTIP_INITIAL_DELAY_MS
            toolTipManager.dismissDelay = PluginConfig.UI.TOOLTIP_DISMISS_DELAY_MS
            toolTipManager.reshowDelay = PluginConfig.UI.TOOLTIP_RESHOW_DELAY_MS

            putClientProperty("JTable.stripedBackground", false)
            putClientProperty("Table.isFileList", false)
            putClientProperty("Table.paintOutsideAlternateRows", false)
            putClientProperty("JTable.alternateRowColor", table.background)
            putClientProperty("Table.highlightSelection", false)
            putClientProperty("Table.focusSelectedCell", false)
            putClientProperty("Table.rowHeight", JBUI.scale(PluginConfig.UI.TABLE_ROW_HEIGHT))
            putClientProperty("Table.hoverBackground", null)
            putClientProperty("Table.selectionBackground", background)
            
            setShowHorizontalLines(false)
            setShowVerticalLines(false)
            intercellSpacing = Dimension(0, 0)
            
            addMouseMotionListener(createMouseMotionListener())
            addMouseListener(createMouseListener())
            
            isFocusable = true

            configureColumns()
            setDefaultRenderer(Object::class.java, validationRenderer)
        }
    }
    
    private fun createMouseMotionListener() = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            val row = table.rowAtPoint(e.point)
            val column = table.columnAtPoint(e.point)
            
            // Проверяем, является ли это строкой с кнопкой плюс
            val isButtonRow = if (row >= 0 && row < table.rowCount) {
                table.getValueAt(row, 0) == "+"
            } else {
                false
            }
            
            val oldHoverState = hoverState()
            
            if (isButtonRow) {
                // Для строки с плюсиком hover должен быть только на первой колонке
                if (column == 0) {
                    // Устанавливаем hover только если еще не установлен
                    if (!hoverState().isTableCellHovered(row, column)) {
                        setHoverState(hoverState().withTableHover(row, column))
                        
                        // Перерисовываем старую ячейку
                        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                            table.repaint(oldRect)
                        }
                        
                        // Перерисовываем новую ячейку
                        val newRect = table.getCellRect(row, column, false)
                        table.repaint(newRect)
                    }
                } else {
                    // Не первая колонка в строке с плюсиком - очищаем любой hover
                    if (oldHoverState.hoveredTableRow >= 0 || oldHoverState.hoveredTableColumn >= 0) {
                        setHoverState(hoverState().clearTableHover())
                        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                            table.repaint(oldRect)
                        }
                    }
                }
            } else {
                // Обычная строка - стандартная логика hover
                if (row >= 0 && column >= 0 && !hoverState().isTableCellHovered(row, column)) {
                    setHoverState(hoverState().withTableHover(row, column))
                    
                    // Перерисовываем старую ячейку
                    if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                        val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                        table.repaint(oldRect)
                    }
                    
                    // Перерисовываем новую ячейку  
                    val newRect = table.getCellRect(row, column, false)
                    table.repaint(newRect)
                }
            }
        }
    }
    
    private fun createMouseListener() = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            val row = table.rowAtPoint(e.point)
            val column = table.columnAtPoint(e.point)
            
            // Если клик произошел за пределами таблицы и таблица в режиме редактирования, останавливаем редактирование
            if ((row == -1 || column == -1) && table.isEditing) {
                table.cellEditor?.stopCellEditing()
                return
            }
            
            onCellClicked(row, column, e.clickCount)
        }
        
        override fun mousePressed(e: MouseEvent) {
            if (e.isPopupTrigger) {
                showContextMenu(e)
            }
        }
        
        override fun mouseReleased(e: MouseEvent) {
            if (e.isPopupTrigger) {
                showContextMenu(e)
            }
        }
        
        override fun mouseExited(e: MouseEvent) {
            onTableExited()
        }
    }
    
    private fun configureColumns() {
        table.columnModel.getColumn(0).apply {
            minWidth = JBUI.scale(30)
            maxWidth = JBUI.scale(30)
            
            // Создаем стандартный рендерер для drag-and-drop
            val defaultRenderer = object : DefaultTableCellRenderer() {
                init {
                    horizontalAlignment = CENTER
                    isOpaque = true
                }
                
                // Цвета для hover эффекта
                private val normalBackground = UIManager.getColor("Table.background")
                private val hoverBackground = normalBackground?.brighter()

                override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                    val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    toolTipText = "Drag and drop"
                    
                    // Проверяем hover состояние для drag-and-drop
                    val isHovered = hoverState().isTableCellHovered(row, column)
                    background = if (isHovered) {
                        hoverBackground
                    } else {
                        normalBackground
                    }
                    
                    // Увеличиваем размер шрифта для drag-and-drop иконки
                    font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
                    
                    return component
                }
            }
            
            // Оборачиваем его в комбинированный рендерер
            cellRenderer = FirstColumnCellRenderer(defaultRenderer)
        }

        table.columnModel.getColumn(1).apply {
            minWidth = JBUI.scale(40)
            maxWidth = JBUI.scale(40)
            cellRenderer = object : DefaultTableCellRenderer() {
                init {
                    horizontalAlignment = CENTER
                    isOpaque = true
                }
                
                override fun getTableCellRendererComponent(
                    table: JTable, 
                    value: Any?, 
                    isSelected: Boolean, 
                    hasFocus: Boolean, 
                    row: Int, 
                    column: Int
                ): Component {
                    // Проверяем, является ли это строкой с кнопкой
                    if (row >= 0 && row < table.rowCount) {
                        val firstColumnValue = table.getValueAt(row, 0)
                        if (firstColumnValue == "+") {
                            // Возвращаем пустую ячейку для строки с кнопкой
                            text = ""
                            background = table.background
                            return this
                        }
                    }
                    
                    return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                }
            }
        }

        table.columnModel.getColumn(5).apply {
            minWidth = JBUI.scale(40)
            maxWidth = JBUI.scale(40)
            cellRenderer = ButtonRenderer()
            cellEditor = ButtonEditor(table, historyManager, getPresetAtRow)
        }
    }

    @Suppress("unused")
    fun setDefaultRenderer(clazz: Class<*>, renderer: TableCellRenderer) {
        table.setDefaultRenderer(clazz, renderer)
    }
}

private class ButtonRenderer : JButton(AllIcons.Actions.Cancel), TableCellRenderer {
    private val emptyPanel = JPanel()
    
    init {
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        minimumSize = preferredSize
        maximumSize = preferredSize
        emptyPanel.isOpaque = true
    }
    
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        // Проверяем, является ли это строкой с кнопкой добавления
        if (row >= 0 && row < table.rowCount) {
            val firstColumnValue = table.getValueAt(row, 0)
            if (firstColumnValue == "+") {
                // Возвращаем пустую панель для строки с кнопкой
                emptyPanel.background = table.background
                return emptyPanel
            }
        }
        
        background = if (isSelected) table.selectionBackground else UIManager.getColor("Button.background")
        return this
    }
}

private class ButtonEditor(
    private val table: JTable,
    private val historyManager: HistoryManager,
    private val getPresetAtRow: (Int) -> io.github.qavlad.adbrandomizer.services.DevicePreset
) : AbstractTableCellEditor(), TableCellEditor {
    private val button = JButton(AllIcons.Actions.Cancel)

    init {
        button.preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize

        button.addActionListener {
            val modelRow = table.convertRowIndexToModel(table.editingRow)
            fireEditingStopped()

            if (modelRow != -1) {
                val model = table.model as DefaultTableModel
                
                // Проверяем, что это не строка с кнопкой
                val firstColumnValue = model.getValueAt(modelRow, 0)
                if (firstColumnValue == "+") {
                    return@addActionListener // Не удаляем строку с кнопкой
                }
                
                // Получаем данные пресета перед удалением
                val preset = getPresetAtRow(modelRow)
                
                // Удаляем строку
                model.removeRow(modelRow)
                
                // Добавляем операцию в историю
                historyManager.addPresetDelete(modelRow, preset)
            }
        }
    }

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        button.background = table.selectionBackground
        return button
    }

    override fun getCellEditorValue(): Any = "Delete"
}