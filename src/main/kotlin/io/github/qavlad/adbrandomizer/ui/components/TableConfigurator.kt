package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.AbstractTableCellEditor
import io.github.qavlad.adbrandomizer.ui.handlers.PresetTransferHandler
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import java.awt.Dimension
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import java.awt.Color
import com.intellij.ui.JBColor
import io.github.qavlad.adbrandomizer.config.PluginConfig

private fun configureButtonAppearance(button: JButton, trashIcon: Icon, showAllMode: Boolean) {
    button.icon = trashIcon
    if (showAllMode) {
        button.foreground = UIManager.getColor("Button.disabledText")
        button.isOpaque = false
    } else {
        button.foreground = UIManager.getColor("Button.foreground")
        button.isOpaque = true
    }
}

object Icons {
    const val DELETE_ICON_PATH = "/resources/icons/delete.svg"
    fun loadIcon(path: String): Icon? {
        return try {
            val url = javaClass.getResource(path)
            if (url != null) {
                ImageIcon(url)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

class TableConfigurator(
    private val table: JBTable,
    private val hoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val onRowMoved: (Int, Int) -> Unit,
    private val onCellClicked: (Int, Int, Int) -> Unit,
    private val onTableExited: () -> Unit,
    private val validationRenderer: ValidationRenderer,
    private val showContextMenu: (MouseEvent) -> Unit,
    private val isShowAllPresetsMode: () -> Boolean = { false },
    private val onPresetDeletedFromEditor: (Int) -> Unit,
    private val onDragStarted: () -> Unit = {},
    private val onDragEnded: () -> Unit = {},
    private val columnWidthConfig: PluginConfig.UI.ColumnWidthConfig = PluginConfig.UI.ColumnWidthConfig()
) {
    
    private fun isAddButtonRow(table: JTable, row: Int): Boolean {
        return row >= 0 && row < table.rowCount && table.getValueAt(row, 0) == "+"
    }
    
    fun configure() {
        table.apply {
            tableHeader.reorderingAllowed = false
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = JBUI.scale(PluginConfig.UI.TABLE_ROW_HEIGHT)
            dragEnabled = true
            dropMode = DropMode.INSERT_ROWS
            transferHandler = PresetTransferHandler(
                onRowMoved = { fromIndex, toIndex ->
                    onRowMoved(fromIndex, toIndex)
                },
                onDragStarted = onDragStarted,
                onDragEnded = onDragEnded
            )
            
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
    
    private fun repaintTableCells(oldHoverState: HoverState, newRow: Int, newColumn: Int) {
        // Перерисовываем старую ячейку
        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
            table.repaint(oldRect)
        }
        
        // Перерисовываем новую ячейку, если координаты валидны
        if (newRow >= 0 && newColumn >= 0) {
            val newRect = table.getCellRect(newRow, newColumn, false)
            table.repaint(newRect)
        }
    }
    
    private fun createMouseMotionListener() = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            val row = table.rowAtPoint(e.point)
            val column = table.columnAtPoint(e.point)
            
            // Проверяем, является ли это строкой с кнопкой плюс
            val isButtonRow = isAddButtonRow(table, row)
            
            val oldHoverState = hoverState()
            
            if (isButtonRow) {
                // Для строки с плюсиком hover должен быть только на первой колонке
                if (column == 0) {
                    // Устанавливаем hover только если еще не установлен
                    if (!hoverState().isTableCellHovered(row, column)) {
                        setHoverState(hoverState().withTableHover(row, column))
                        
                        repaintTableCells(oldHoverState, row, column)
                    }
                } else {
                    // Не первая колонка в строке с плюсиком - очищаем любой hover
                    if (oldHoverState.hoveredTableRow >= 0 || oldHoverState.hoveredTableColumn >= 0) {
                        setHoverState(hoverState().clearTableHover())
                        repaintTableCells(oldHoverState, -1, -1)
                    }
                }
            } else {
                // Обычная строка - стандартная логика hover
                if (row >= 0 && column >= 0 && !hoverState().isTableCellHovered(row, column)) {
                    setHoverState(hoverState().withTableHover(row, column))
                    
                    repaintTableCells(oldHoverState, row, column)
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
    
    fun configureColumns() {
        // Column 0: Drag Handle
                table.columnModel.getColumn(0).apply {
            preferredWidth = JBUI.scale(columnWidthConfig.dragHandle)
            minWidth = JBUI.scale(columnWidthConfig.dragHandle)
            maxWidth = JBUI.scale(columnWidthConfig.dragHandle)
            // Use a custom renderer that displays the icon based on the cell value
            setCellRenderer { table, value, _, _, _, _ ->
                val iconLabel = if (value == "+") "+" else "☰"
                JLabel(iconLabel).apply {
                    horizontalAlignment = SwingConstants.CENTER
                    // Применяем стиль только для плюсика
                    font = if (value == "+") {
                        table?.font?.deriveFont(table.font.size * 1.8f)
                    } else {
                        table?.font
                    }
                }
            }
            // Make the cell not editable
            cellEditor = null
        }

        table.columnModel.getColumn(1).apply {
            minWidth = JBUI.scale(columnWidthConfig.number)
            maxWidth = JBUI.scale(columnWidthConfig.number)
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
                    if (isAddButtonRow(table, row)) {
                        // Возвращаем пустую ячейку для строки с кнопкой
                        text = ""
                        background = table.background
                        return this
                    }
                    
                    return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                }
            }
        }

        // Настраиваем колонку удаления - позиция зависит от количества колонок
        // В нормальном режиме: " ", "№", "Label", "Size", "DPI", ["Size Uses", "DPI Uses",] "  "
        // В Show All режиме: " ", "№", "Label", "Size", "DPI", ["Size Uses", "DPI Uses",] "  ", "List"
        val deleteColumnIndex = when {
            isShowAllPresetsMode() && table.columnModel.columnCount >= 9 -> 7  // Show All с счетчиками (9 колонок)
            isShowAllPresetsMode() && table.columnModel.columnCount >= 7 -> 5  // Show All без счетчиков (7 колонок)
            !isShowAllPresetsMode() && table.columnModel.columnCount >= 8 -> 7 // Normal со счетчиками (8 колонок) - исправлено!
            else -> 5  // Normal без счетчиков (6 колонок)
        }
        
        println("ADB_DEBUG: Configuring delete column - columnCount: ${table.columnModel.columnCount}, isShowAllPresetsMode: ${isShowAllPresetsMode()}, deleteColumnIndex: $deleteColumnIndex")
        
        if (table.columnModel.columnCount > deleteColumnIndex) {
            table.columnModel.getColumn(deleteColumnIndex).apply {
                minWidth = JBUI.scale(columnWidthConfig.deleteButton)
                maxWidth = JBUI.scale(columnWidthConfig.deleteButton)
                cellRenderer = ButtonRenderer(isShowAllPresetsMode)
                cellEditor = ButtonEditor(
                    table = table,
                    isShowAllPresetsMode = isShowAllPresetsMode,
                    onPresetDeletedFromEditor = onPresetDeletedFromEditor
                )
                
                // Добавляем проверку редактируемости
                println("ADB_DEBUG: Delete column $deleteColumnIndex configured, isCellEditable check...")
            }
        }
        
        // Настраиваем ширину остальных колонок
        configureColumnWidth(2, columnWidthConfig.label)  // Label
        configureColumnWidth(3, columnWidthConfig.size)   // Size
        configureColumnWidth(4, columnWidthConfig.dpi)    // DPI
        
        // Счетчики использования (если включены)
        val hasCounters = SettingsService.getShowCounters()
        if (hasCounters) {
            configureColumnWidth(5, columnWidthConfig.sizeUses)  // Size Uses
            configureColumnWidth(6, columnWidthConfig.dpiUses)   // DPI Uses
        }
        
        // Колонка List (только в режиме Show All)
        if (isShowAllPresetsMode() && table.columnModel.columnCount > deleteColumnIndex + 1) {
            configureColumnWidth(deleteColumnIndex + 1, columnWidthConfig.listColumn)
        }
    }
    
    private fun configureColumnWidth(columnIndex: Int, width: Int?) {
        if (columnIndex < table.columnModel.columnCount && width != null) {
            table.columnModel.getColumn(columnIndex).apply {
                preferredWidth = JBUI.scale(width)
                minWidth = JBUI.scale(width / 2)  // Минимум - половина от заданной ширины
                maxWidth = JBUI.scale(width * 2)  // Максимум - в два раза больше заданной
            }
        }
        // Если width == null, оставляем автоматическую ширину
    }
}

private class ButtonRenderer(
    private val isShowAllPresetsMode: () -> Boolean = { false }
) : JButton(), TableCellRenderer {
    private val emptyPanel = JPanel()
    private val trashIcon: Icon = Icons.loadIcon(Icons.DELETE_ICON_PATH) ?: DeleteIcon()
    
    init {
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        minimumSize = preferredSize
        maximumSize = preferredSize
        emptyPanel.isOpaque = true
        icon = trashIcon
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
    }
    
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        // Проверяем, является ли это строкой с кнопкой добавления
        if (row >= 0 && row < table.rowCount && table.getValueAt(row, 0) == "+") {
            // Возвращаем пустую панель для строки с кнопкой
            emptyPanel.background = table.background
            return emptyPanel
        }
        
        // Проверяем, находимся ли мы в режиме "Show all presets"
        val showAllMode = isShowAllPresetsMode()
        // println("ADB_DEBUG: ButtonRenderer - row: $row, showAllMode: $showAllMode")
        // Не отключаем кнопку в рендерере, чтобы она могла реагировать на клики
        isEnabled = true
        
        // Меняем внешний вид в зависимости от активности
        configureButtonAppearance(this, trashIcon, showAllMode)
        
        if (showAllMode) {
            // Используем более светлый фон для неактивного состояния
            val bg = table.background
            background = JBColor(Color(bg.red, bg.green, bg.blue, 100), Color(bg.red, bg.green, bg.blue, 100))
        } else {
            background = UIManager.getColor("Button.background")
        }
        
        return this
    }
}

private class ButtonEditor(
    private val table: JTable,
    private val isShowAllPresetsMode: () -> Boolean = { false },
    private val onPresetDeletedFromEditor: (Int) -> Unit
) : AbstractTableCellEditor(), TableCellEditor {
    private val button = JButton()
    private val trashIcon: Icon = Icons.loadIcon(Icons.DELETE_ICON_PATH) ?: DeleteIcon()

    init {
        button.preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize
        button.icon = trashIcon
        button.isFocusPainted = false
        button.isBorderPainted = false
        button.isContentAreaFilled = false

        button.addActionListener {
            val modelRow = table.convertRowIndexToModel(table.editingRow)
            fireEditingStopped()

            if (modelRow != -1) {
                onPresetDeletedFromEditor(modelRow)
            }
        }
    }
    
    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        // println("ADB_DEBUG: ButtonEditor.getTableCellEditorComponent - row: $row, column: $column")
        // Обновляем состояние кнопки в зависимости от режима
        val showAllMode = isShowAllPresetsMode()
        println("ADB_DEBUG: ButtonEditor - showAllMode: $showAllMode")
        button.isEnabled = true // Не отключаем кнопку, чтобы она могла обработать клик
        println("ADB_DEBUG: ButtonEditor - button.isEnabled: ${button.isEnabled}")
        
        configureButtonAppearance(button, trashIcon, showAllMode)
        
        button.background = table.selectionBackground
        return button
    }

    override fun getCellEditorValue(): Any = "Delete"
}