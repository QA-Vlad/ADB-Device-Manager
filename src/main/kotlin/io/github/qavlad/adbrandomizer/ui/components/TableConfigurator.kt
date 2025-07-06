package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.AbstractTableCellEditor
import io.github.qavlad.adbrandomizer.ui.handlers.PresetTransferHandler
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import java.awt.Dimension
import java.awt.Component
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
            rowHeight = JBUI.scale(35)
            dragEnabled = true
            dropMode = DropMode.INSERT_ROWS
            transferHandler = PresetTransferHandler { fromIndex, toIndex ->
                onRowMoved(fromIndex, toIndex)
            }
            
            selectionModel.clearSelection()
            setRowSelectionAllowed(false)
            setCellSelectionEnabled(false)

            val toolTipManager = ToolTipManager.sharedInstance()
            toolTipManager.initialDelay = 100
            toolTipManager.dismissDelay = 5000
            toolTipManager.reshowDelay = 50

            putClientProperty("JTable.stripedBackground", false)
            putClientProperty("Table.isFileList", false)
            putClientProperty("Table.paintOutsideAlternateRows", false)
            putClientProperty("JTable.alternateRowColor", table.background)
            putClientProperty("Table.highlightSelection", false)
            putClientProperty("Table.focusSelectedCell", false)
            putClientProperty("Table.rowHeight", JBUI.scale(35))
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
            
            if (!hoverState().isTableCellHovered(row, column)) {
                println("ADB_DEBUG: Updating hover state to row=$row, column=$column")
                
                val oldHoverState = hoverState()
                setHoverState(hoverState().withTableHover(row, column))
                
                if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                    val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                    table.repaint(oldRect)
                }
                
                if (row >= 0 && column >= 0) {
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
            cellRenderer = object : DefaultTableCellRenderer() {
                init {
                    horizontalAlignment = CENTER
                    isOpaque = true
                }

                override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                    val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    toolTipText = "Drag and drop"
                    return component
                }
            }
        }

        table.columnModel.getColumn(1).apply {
            minWidth = JBUI.scale(40)
            maxWidth = JBUI.scale(40)
            cellRenderer = object : DefaultTableCellRenderer() {
                init {
                    horizontalAlignment = CENTER
                    isOpaque = true
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
}

private class ButtonRenderer : JButton(AllIcons.Actions.Cancel), TableCellRenderer {
    init {
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
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
                // Получаем данные пресета перед удалением
                val preset = getPresetAtRow(modelRow)
                
                // Удаляем строку
                (table.model as DefaultTableModel).removeRow(modelRow)
                
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