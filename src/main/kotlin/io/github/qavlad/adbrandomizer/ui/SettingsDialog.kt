// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/SettingsDialog.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.DeviceStateService
import io.github.qavlad.adbrandomizer.services.PresetApplicationService
import io.github.qavlad.adbrandomizer.services.SettingsDialogUpdateNotifier
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.util.*
import javax.swing.*
import javax.swing.border.Border
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class SettingsDialog(private val project: Project?) : DialogWrapper(project) {
    private lateinit var table: JBTable
    private lateinit var tableModel: DevicePresetTableModel
    private var updateListener: (() -> Unit)? = null
    
    // Универсальное состояние hover эффектов
    private var hoverState = HoverState.noHover()
    
    // Для отмены операций - история изменений
    private data class HistoryEntry(
        val cellId: CellIdentity,
        val oldValue: String,
        val newValue: String
    )
    
    private val historyStack = mutableListOf<HistoryEntry>()
    private val maxHistorySize = 50
    
    // Глобальный обработчик клавиатуры
    private var keyEventDispatcher: KeyEventDispatcher? = null
    
    // Для отслеживания изменений при редактировании
    private var editingCellOldValue: String? = null
    private var editingCellRow: Int = -1
    private var editingCellColumn: Int = -1
    
    // Система ID ячеек: Map(row, column) -> CellIdentity
    private val cellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
    
    // Обработчик перемещения строк для обновления cellIdMap
    private fun onRowMoved(fromIndex: Int, toIndex: Int) {
        println("ADB_DEBUG: Row moved from $fromIndex to $toIndex - updating cellIdMap and selection")
        
        // Создаем новую карту с обновленными координатами
        val newCellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
        
        // Для всех ячеек пересчитываем координаты
        for ((coords, cellId) in cellIdMap) {
            val (row, column) = coords
            val newRow = when {
                row == fromIndex -> toIndex
                fromIndex < toIndex && row in (fromIndex + 1)..toIndex -> row - 1
                fromIndex > toIndex && row in toIndex until fromIndex -> row + 1
                else -> row
            }
            newCellIdMap[Pair(newRow, column)] = cellId
        }
        
        cellIdMap.clear()
        cellIdMap.putAll(newCellIdMap)
        
        // Обновляем выделение ячейки если она была на перемещенной строке
        if (hoverState.selectedTableRow == fromIndex) {
            hoverState = hoverState.withTableSelection(toIndex, hoverState.selectedTableColumn)
            println("ADB_DEBUG: Selection moved from ($fromIndex, ${hoverState.selectedTableColumn}) to ($toIndex, ${hoverState.selectedTableColumn})")
        } else if (hoverState.selectedTableRow != -1) {
            // Обновляем выделение для других строк которые сдвинулись
            val selectedRow = hoverState.selectedTableRow
            val newSelectedRow = when {
                fromIndex < toIndex && selectedRow in (fromIndex + 1)..toIndex -> selectedRow - 1
                fromIndex > toIndex && selectedRow in toIndex until fromIndex -> selectedRow + 1
                else -> selectedRow
            }
            if (newSelectedRow != selectedRow) {
                hoverState = hoverState.withTableSelection(newSelectedRow, hoverState.selectedTableColumn)
                println("ADB_DEBUG: Selection adjusted from ($selectedRow, ${hoverState.selectedTableColumn}) to ($newSelectedRow, ${hoverState.selectedTableColumn})")
            }
        }
        
        // Принудительно перерисовываем таблицу, чтобы обновить визуальное выделение
        SwingUtilities.invokeLater {
            table.repaint()
        }
        
        println("ADB_DEBUG: cellIdMap updated after row move")
    }

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()

        // Добавляем hover эффекты для кнопок диалога
        SwingUtilities.invokeLater {
            addHoverEffectToDialogButtons()
        }
        
        // Добавляем глобальный обработчик клавиатуры для отмены операций
        addGlobalKeyListener()
        
        // Обновляем состояние устройств при открытии диалога только если нет активных пресетов
        if (project != null) {
            val activePresets = DeviceStateService.getCurrentActivePresets()
            if (activePresets.activeSizePreset == null && activePresets.activeDpiPreset == null) {
                DeviceStateService.refreshDeviceStates(project)
            }
            // Небольшая задержка для загрузки состояния устройств
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        
        // Подписываемся на уведомления об обновлениях
        updateListener = {
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        updateListener?.let { SettingsDialogUpdateNotifier.addListener(it) }
    }

    private fun addHoverEffectToDialogButtons() {
        // Находим и обрабатываем кнопки OK и Cancel
        fun processButtons(container: java.awt.Container) {
            for (component in container.components) {
                when (component) {
                    is JButton -> {
                        if (component.text == "Save" || component.text == "Cancel") {
                            ButtonUtils.addHoverEffect(component)
                        }
                    }
                    is java.awt.Container -> processButtons(component)
                }
            }
        }

        processButtons(contentPane)
    }
    
    private fun addGlobalKeyListener() {
        println("ADB_DEBUG: Adding global key listener using KeyboardFocusManager")
        
        // Создаем глобальный обработчик клавиатуры
        keyEventDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                println("ADB_DEBUG: Global key pressed: ${e.keyCode}, isControlDown=${e.isControlDown}")
                when {
                    e.keyCode == KeyEvent.VK_C && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+C pressed, selectedRow=${hoverState.selectedTableRow}, selectedColumn=${hoverState.selectedTableColumn}")
                        if (hoverState.selectedTableRow >= 0 && hoverState.selectedTableColumn >= 0) {
                            copyCellToClipboard()
                            return@KeyEventDispatcher true // Событие обработано
                        }
                    }
                    e.keyCode == KeyEvent.VK_V && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+V pressed, selectedRow=${hoverState.selectedTableRow}, selectedColumn=${hoverState.selectedTableColumn}")
                        if (hoverState.selectedTableRow >= 0 && hoverState.selectedTableColumn >= 0) {
                            pasteCellFromClipboard()
                            return@KeyEventDispatcher true // Событие обработано
                        }
                    }
                    e.keyCode == KeyEvent.VK_Z && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+Z pressed, история содержит ${historyStack.size} записей")
                        
                        // Проверяем, находится ли таблица в режиме редактирования
                        if (table.isEditing) {
                            println("ADB_DEBUG: Table is in editing mode - ignoring global undo")
                            return@KeyEventDispatcher false // Передаем событие дальше (к редактору ячейки)
                        } else {
                            undoLastPaste()
                            return@KeyEventDispatcher true // Событие обработано
                        }
                    }
                }
            }
            false // Событие не обработано, передаем дальше
        }
        
        // Добавляем в глобальный менеджер фокуса
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        println("ADB_DEBUG: KeyEventDispatcher added to KeyboardFocusManager")
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        val presets = SettingsService.getPresets()
        val dataVector = Vector<Vector<Any>>()
        presets.forEachIndexed { index, preset ->
            val row = Vector<Any>()
            row.add("☰")
            row.add(index + 1) // Номер строки
            row.add(preset.label)
            row.add(preset.size)
            row.add(preset.dpi)
            row.add("Delete")
            dataVector.add(row)
        }

        tableModel = DevicePresetTableModel(dataVector, columnNames)
        tableModel.addTableModelListener { event ->
            validateFields()
            
            // Если это изменение значения в ячейке
            if (event.type == javax.swing.event.TableModelEvent.UPDATE && 
                event.firstRow >= 0 && event.column >= 0) {
                
                val currentValue = tableModel.getValueAt(event.firstRow, event.column) as? String ?: ""
                println("ADB_DEBUG: Table model updated - row=${event.firstRow}, column=${event.column}, value='$currentValue'")
                
                // Подробное логирование состояния
                println("ADB_DEBUG: TableModelListener check - editingCellOldValue='$editingCellOldValue', editingCellRow=$editingCellRow, editingCellColumn=$editingCellColumn")
                println("ADB_DEBUG: TableModelListener check - event.firstRow=${event.firstRow}, event.column=${event.column}")
                
                // Проверяем если есть сохраненное старое значение для этой ячейки
                if (editingCellOldValue != null && 
                    editingCellRow == event.firstRow && 
                    editingCellColumn == event.column) {
                    
                    if (editingCellOldValue != currentValue) {
                        addToHistory(event.firstRow, event.column, editingCellOldValue!!, currentValue)
                        println("ADB_DEBUG: EDIT COMPLETED via TableModelListener - added to history: '$editingCellOldValue' -> '$currentValue' (история: ${historyStack.size})")
                    } else {
                        println("ADB_DEBUG: EDIT COMPLETED via TableModelListener - no changes: '$editingCellOldValue'")
                    }
                    
                    // Сбрасываем данные о редактировании
                    editingCellOldValue = null
                    editingCellRow = -1
                    editingCellColumn = -1
                } else {
                    println("ADB_DEBUG: TableModelListener check FAILED - conditions not met")
                }
            }
            
            // Обновляем индикаторы при изменении данных
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }

        table = object : JBTable(tableModel) {
            @Suppress("DEPRECATION")
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                val component = super.prepareRenderer(renderer, row, column)
                
                // Принудительно убираем любые встроенные hover эффекты
                if (component is JComponent) {
                val isHovered = hoverState.isTableCellHovered(row, column)
                val isSelectedCell = hoverState.isTableCellSelected(row, column)
                
                // Сначала проверяем валидацию для колонок 3 и 4 (Size и DPI)
                var isInvalidCell = false
                if (column in 3..4) {
                val value = tableModel.getValueAt(row, column)
                val text = value as? String ?: ""
                val isValid = if (text.isBlank()) true else when (column) {
                3 -> ValidationUtils.isValidSizeFormat(text) // Size в колонке 3
                4 -> ValidationUtils.isValidDpi(text)         // DPI в колонке 4
                else -> true
                }
                if (!isValid) {
                isInvalidCell = true
                component.background = JBColor.PINK
                component.foreground = JBColor.BLACK
                component.isOpaque = true
                }
                }
                
                // Применяем hover/selection эффекты только если ячейка валидна
                if (!isInvalidCell) {
                if (isSelectedCell) {
                // Выделенная ячейка имеет приоритет над hover, но чуть темнее
                component.background = JBColor(Color(230, 230, 250), Color(80, 80, 100))
                component.isOpaque = true
                } else if (isHovered) {
                component.background = JBColor(Gray._240, Gray._70)
                component.isOpaque = true
                } else {
                component.background = UIManager.getColor("Table.background") ?: JBColor.WHITE
                component.isOpaque = true
                }
                }
                    
                    // Логируем только интересные состояния
                    if (isHovered || isSelectedCell || isInvalidCell) {
                        println("ADB_DEBUG: prepareRenderer row=$row, column=$column, isHovered=$isHovered, isSelected=$isSelectedCell, isInvalid=$isInvalidCell")
                    }
                }
                
                return component
            }
            
            override fun editCellAt(row: Int, column: Int): Boolean {
                println("ADB_DEBUG: EDIT CELL AT called - row=$row, column=$column")
                
                // Сохраняем старое значение и координаты перед началом редактирования
                if (row >= 0 && column >= 0) {
                    editingCellOldValue = tableModel.getValueAt(row, column) as? String ?: ""
                    editingCellRow = row
                    editingCellColumn = column
                    println("ADB_DEBUG: EDITING WILL START - row=$row, column=$column, oldValue='$editingCellOldValue'")
                }
                
                return super.editCellAt(row, column)
            }
            
            override fun removeEditor() {
                // Если редактирование было отменено (например, нажат Escape), сбрасываем данные
                if (editingCellOldValue != null) {
                    println("ADB_DEBUG: EDITING CANCELED via removeEditor - clearing old value")
                    editingCellOldValue = null
                    editingCellRow = -1
                    editingCellColumn = -1
                }
                super.removeEditor()
            }
            
            override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
                // Отслеживаем начало редактирования при смене выделения с нажатием клавиши
                println("ADB_DEBUG: changeSelection called - row=$rowIndex, column=$columnIndex, toggle=$toggle, extend=$extend")
                
                // Если это новая ячейка и не просто навигация, сохраняем старое значение
                if (rowIndex >= 0 && columnIndex >= 0 && columnIndex in 2..4) {
                    val oldRow = selectionModel.leadSelectionIndex
                    val oldColumn = columnModel.selectionModel.leadSelectionIndex
                    
                    if (oldRow != rowIndex || oldColumn != columnIndex) {
                        println("ADB_DEBUG: Selection changed to new cell - prepare for possible editing")
                        // Подготавливаемся к возможному редактированию
                        editingCellOldValue = tableModel.getValueAt(rowIndex, columnIndex) as? String ?: ""
                        editingCellRow = rowIndex
                        editingCellColumn = columnIndex
                        println("ADB_DEBUG: PREPARED for editing - row=$rowIndex, column=$columnIndex, oldValue='$editingCellOldValue'")
                    }
                }
                
                super.changeSelection(rowIndex, columnIndex, toggle, extend)
            }
        }
        

        setupTable()
        validateFields()

        val scrollPane = JBScrollPane(table).apply { preferredSize = Dimension(650, 400) }
        val buttonPanel = createButtonPanel()

        val tablePanel = JPanel(BorderLayout()).apply {
            add(table.tableHeader, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(tablePanel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }
    }

    private fun setupTable() {
        table.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = JBUI.scale(35)
            dragEnabled = true
            dropMode = DropMode.INSERT_ROWS
            transferHandler = PresetTransferHandler { fromIndex, toIndex ->
                onRowMoved(fromIndex, toIndex)
            }
            
            // Убираем синее выделение строк
            selectionModel.clearSelection()
            setRowSelectionAllowed(false)
            setCellSelectionEnabled(false)

            // Настраиваем быстрое появление tooltip
            val toolTipManager = ToolTipManager.sharedInstance()
            toolTipManager.initialDelay = 100
            toolTipManager.dismissDelay = 5000
            toolTipManager.reshowDelay = 50

            // Отключаем все встроенные hover эффекты
            putClientProperty("JTable.stripedBackground", false)
            putClientProperty("Table.isFileList", false)
            putClientProperty("Table.paintOutsideAlternateRows", false)
            putClientProperty("JTable.alternateRowColor", table.background)
            putClientProperty("Table.highlightSelection", false)
            putClientProperty("Table.focusSelectedCell", false)
            putClientProperty("Table.rowHeight", JBUI.scale(35))
            putClientProperty("Table.hoverBackground", null)
            putClientProperty("Table.selectionBackground", background)
            
            // Отключаем стандартное выделение строк
            setShowHorizontalLines(false)
            setShowVerticalLines(false)
            intercellSpacing = Dimension(0, 0)
            
            // Добавляем обработчики для UX улучшений
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    
                    if (!hoverState.isTableCellHovered(row, column)) {
                        println("ADB_DEBUG: Updating hover state to row=$row, column=$column")
                        
                        val oldHoverState = hoverState
                        hoverState = hoverState.withTableHover(row, column)
                        
                        // Перерисовываем только затронутые ячейки
                        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                            // Перерисовываем старую ячейку
                            val oldRect = getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                            repaint(oldRect)
                        }
                        
                        if (row >= 0 && column >= 0) {
                            // Перерисовываем новую ячейку
                            val newRect = getCellRect(row, column, false)
                            repaint(newRect)
                        }
                    }
                }
            })
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val column = columnAtPoint(e.point)
                    
                    println("ADB_DEBUG: Mouse clicked at row=$row, column=$column, clickCount=${e.clickCount}")
                    
                    // Обработка кликов по ячейкам
                    if (row >= 0 && column >= 0 && column in 2..4) { // Только для Label, Size, DPI
                        val oldSelectedRow = hoverState.selectedTableRow
                        val oldSelectedColumn = hoverState.selectedTableColumn
                        
                        hoverState = hoverState.withTableSelection(row, column)
                        
                        // Перерисовываем старую выделенную ячейку
                        if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                            val oldRect = getCellRect(oldSelectedRow, oldSelectedColumn, false)
                            repaint(oldRect)
                        }
                        
                        // Перерисовываем новую выделенную ячейку
                        val newRect = getCellRect(row, column, false)
                        repaint(newRect)
                        
                        requestFocus() // Чтобы могли обрабатывать клавиатуру
                        println("ADB_DEBUG: Cell selected ($row, $column)")
                        
                        // Если двойной клик, начинаем редактирование
                        if (e.clickCount == 2) {
                            println("ADB_DEBUG: Double click detected - starting edit at ($row, $column)")
                            editCellAt(row, column)
                            if (editorComponent != null) {
                                editorComponent!!.requestFocus()
                            }
                        }
                    } else {
                        val oldSelectedRow = hoverState.selectedTableRow
                        val oldSelectedColumn = hoverState.selectedTableColumn
                        
                        hoverState = hoverState.clearTableSelection()
                        
                        // Перерисовываем старую выделенную ячейку
                        if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                            val oldRect = getCellRect(oldSelectedRow, oldSelectedColumn, false)
                            repaint(oldRect)
                        }
                    }
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
                    val oldHoverState = hoverState
                    hoverState = hoverState.clearTableHover()
                    
                    // Перерисовываем только последнюю hover ячейку
                    if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
                        val oldRect = getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
                        repaint(oldRect)
                    }
                }
            })
            
            // Добавляем обработчик клавиатуры для Ctrl+C/Ctrl+V и отслеживания начала редактирования
            addKeyListener(object : KeyListener {
                override fun keyPressed(e: KeyEvent) {
                    println("ADB_DEBUG: Key pressed: ${e.keyCode}, isControlDown=${e.isControlDown}, selectedRow=${hoverState.selectedTableRow}, selectedColumn=${hoverState.selectedTableColumn}")
                    
                    if (hoverState.selectedTableRow >= 0 && hoverState.selectedTableColumn >= 0) {
                        when {
                            e.keyCode == KeyEvent.VK_C && e.isControlDown -> {
                                copyCellToClipboard()
                                e.consume()
                            }
                            e.keyCode == KeyEvent.VK_V && e.isControlDown -> {
                                pasteCellFromClipboard()
                                e.consume()
                            }
                            e.keyCode == KeyEvent.VK_Z && e.isControlDown -> {
                                // Проверяем, находится ли таблица в режиме редактирования
                                if (!isEditing) {
                                    undoLastPaste()
                                    e.consume()
                                } else {
                                    println("ADB_DEBUG: Table is editing - local KeyListener ignoring undo")
                                }
                            }
                            // Отслеживаем начало редактирования по обычным клавишам
                            !e.isControlDown && !e.isAltDown -> {
                                println("ADB_DEBUG: Checking if key should start editing: keyCode=${e.keyCode}, range check=${e.keyCode in 32..126}, F2 check=${e.keyCode == KeyEvent.VK_F2}")
                                if (e.keyCode in 32..126 || e.keyCode == KeyEvent.VK_F2) {
                                    println("ADB_DEBUG: Starting edit due to key press")
                                    // Сохраняем старое значение перед началом редактирования
                                    val row = hoverState.selectedTableRow
                                    val column = hoverState.selectedTableColumn
                                    if (row >= 0 && column >= 0) {
                                        editingCellOldValue = tableModel.getValueAt(row, column) as? String ?: ""
                                        editingCellRow = row
                                        editingCellColumn = column
                                        println("ADB_DEBUG: EDITING WILL START via keyPress - row=$row, column=$column, oldValue='$editingCellOldValue'")
                                    }
                                } else {
                                    println("ADB_DEBUG: Key ${e.keyCode} will not start editing")
                                }
                            }
                        }
                    }
                }
                
                override fun keyReleased(e: KeyEvent) {}
                override fun keyTyped(e: KeyEvent) {}
            })
            
            // Чтобы таблица могла получать фокус
            isFocusable = true

            // Колонка 0: Drag handle
            columnModel.getColumn(0).apply {
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

            // Колонка 1: Номер строки
            columnModel.getColumn(1).apply {
                minWidth = JBUI.scale(40)
                maxWidth = JBUI.scale(40)
                cellRenderer = object : DefaultTableCellRenderer() {
                    init {
                        horizontalAlignment = CENTER
                        isOpaque = true
                    }
                }
            }

            // Колонка 5: Delete button (было 4, теперь 5)
            columnModel.getColumn(5).apply {
                minWidth = JBUI.scale(40)
                maxWidth = JBUI.scale(40)
                cellRenderer = ButtonRenderer()
                cellEditor = ButtonEditor(table)
            }
            setDefaultRenderer(Object::class.java, ValidationRenderer())
        }
    }
    
    private fun copyCellToClipboard() {
        if (hoverState.selectedTableRow >= 0 && hoverState.selectedTableColumn >= 0) {
            val value = tableModel.getValueAt(hoverState.selectedTableRow, hoverState.selectedTableColumn) as? String ?: ""
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(value), null)
            println("ADB_DEBUG: Скопировано в буфер: '$value'")
        }
    }
    
    private fun pasteCellFromClipboard() {
        if (hoverState.selectedTableRow >= 0 && hoverState.selectedTableColumn >= 0) {
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val data = clipboard.getContents(null)
                
                if (data != null && data.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val oldValue = tableModel.getValueAt(hoverState.selectedTableRow, hoverState.selectedTableColumn) as? String ?: ""
                    val newValue = (data.getTransferData(DataFlavor.stringFlavor) as String).trim()
                    
                    // Подготавливаем данные для TableModelListener (чтобы он добавил в историю)
                    editingCellOldValue = oldValue
                    editingCellRow = hoverState.selectedTableRow
                    editingCellColumn = hoverState.selectedTableColumn
                    
                    tableModel.setValueAt(newValue, hoverState.selectedTableRow, hoverState.selectedTableColumn)
                    println("ADB_DEBUG: Вставлено из буфера: '$newValue' (история: ${historyStack.size})")
                    
                    // Обновляем валидацию
                    validateFields()
                    repaint()
                }
            } catch (e: Exception) {
                println("ADB_DEBUG: Ошибка при вставке: ${e.message}")
            }
        }
    }
    
    private fun getCellId(row: Int, column: Int): CellIdentity {
        val key = Pair(row, column)
        return cellIdMap.getOrPut(key) { CellIdentity.generate() }
    }
    
    private fun findCellByIdInternal(cellId: CellIdentity): Pair<Int, Int>? {
        return cellIdMap.entries.find { it.value == cellId }?.key
    }
    
    private fun findCellById(cellId: CellIdentity): Pair<Int, Int>? {
        val coords = findCellByIdInternal(cellId)
        println("ADB_DEBUG: Finding cell by ID ${cellId.id.substring(0, 8)}... -> $coords")
        return coords
    }
    
    private fun addToHistory(row: Int, column: Int, oldValue: String, newValue: String) {
        if (oldValue != newValue) {
            val cellId = getCellId(row, column)
            historyStack.add(HistoryEntry(cellId, oldValue, newValue))
            
            // Ограничиваем размер истории
            if (historyStack.size > maxHistorySize) {
                historyStack.removeAt(0)
            }
            
            println("ADB_DEBUG: Добавлена запись в историю: ($row, $column) [${cellId.id.substring(0, 8)}...] '$oldValue' -> '$newValue'")
        }
    }
    
    private fun undoLastPaste() {
        println("ADB_DEBUG: undoLastPaste called - история содержит ${historyStack.size} записей")
        
        if (historyStack.isNotEmpty()) {
            val lastEntry = historyStack.removeAt(historyStack.size - 1)
            
            // Находим ячейку по ID
            val coords = findCellById(lastEntry.cellId)
            if (coords != null) {
                val (row, column) = coords
                tableModel.setValueAt(lastEntry.oldValue, row, column)
                println("ADB_DEBUG: Отмена операции: восстановлено '${lastEntry.oldValue}' в ячейку ($row, $column) [${lastEntry.cellId.id.substring(0, 8)}...]")
            } else {
                println("ADB_DEBUG: ОШИБКА: Ячейка с ID ${lastEntry.cellId.id.substring(0, 8)}... не найдена!")
            }
            
            // Обновляем валидацию
            validateFields()
            repaint()
        } else {
            println("ADB_DEBUG: История пуста - нет операций для отмены")
        }
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel()

        val addButton = JButton("Add Preset", AllIcons.General.Add).apply {
            addActionListener {
                // Добавляем новую строку
                val newRowIndex = tableModel.rowCount
                tableModel.addRow(Vector(listOf("☰", newRowIndex + 1, "", "", "", "Delete")))

                // Выделяем новую ячейку, прокручиваем к ней и начинаем редактирование колонки Label
                SwingUtilities.invokeLater {
                    // Устанавливаем выделение ячейки в нашей системе
                    hoverState = hoverState.withTableSelection(newRowIndex, 2)
                    
                    // Прокручиваем таблицу к новой строке
                    table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true))

                    // Начинаем редактирование колонки Label (индекс 2)
                    table.editCellAt(newRowIndex, 2)
                    table.editorComponent?.requestFocus()
                    
                    println("ADB_DEBUG: New preset created - selected cell ($newRowIndex, 2)")
                    table.repaint()
                }
            }
        }
        ButtonUtils.addHoverEffect(addButton)
        panel.add(addButton)

        val importButton = JButton("Import Common Devices").apply {
            addActionListener {
                val commonPresets = listOf(
                    DevicePreset("Pixel 6 Pro", "1440x3120", "512"),
                    DevicePreset("Pixel 5", "1080x2340", "432")
                )
                val existingLabels = tableModel.getPresets().map { it.label }.toSet()
                commonPresets.forEach {
                    if (!existingLabels.contains(it.label)) {
                        val newRowIndex = tableModel.rowCount
                        tableModel.addRow(Vector(listOf("☰", newRowIndex + 1, it.label, it.size, it.dpi, "Delete")))
                    }
                }
            }
        }
        ButtonUtils.addHoverEffect(importButton)
        panel.add(importButton)

        return panel
    }
    
    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return
        
        val preset = getPresetAtRow(row)
        
        val popupMenu = JPopupMenu()
        
        // Проверяем, есть ли DPI в пресете
        if (preset.dpi.isNotBlank()) {
            val applyDpiItem = JMenuItem("Apply DPI only (${preset.dpi})")
            applyDpiItem.addActionListener {
                applyPresetFromRow(row, setSize = false, setDpi = true)
            }
            popupMenu.add(applyDpiItem)
        }
        
        // Проверяем, есть ли Size в пресете
        if (preset.size.isNotBlank()) {
            val applySizeItem = JMenuItem("Apply Size only (${preset.size})")
            applySizeItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = false)
            }
            popupMenu.add(applySizeItem)
        }
        
        // Добавляем "Apply Size and DPI" только если есть и DPI и Size
        if (preset.dpi.isNotBlank() && preset.size.isNotBlank()) {
            val applyBothItem = JMenuItem("Apply Size and DPI")
            applyBothItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = true)
            }
            popupMenu.add(applyBothItem)
        }
        
        if (popupMenu.componentCount > 0) {
            popupMenu.show(e.component, e.x, e.y)
        }
    }
    
    private fun getPresetAtRow(row: Int): DevicePreset {
        return DevicePreset(
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
    }
    
    private fun applyPresetFromRow(row: Int, setSize: Boolean, setDpi: Boolean) {
        if (project != null) {
            // Берем текущее состояние пресета из таблицы (может быть отредактированным)
            val currentPreset = getPresetAtRow(row)
            
            PresetApplicationService.applyPreset(project, currentPreset, setSize, setDpi)
            
            // Обновляем таблицу после применения пресета
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
    }
    


    private fun validateFields() {
        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            val size = tableModel.getValueAt(i, 3) as? String ?: "" // Теперь Size в колонке 3
            val dpi = tableModel.getValueAt(i, 4) as? String ?: ""  // Теперь DPI в колонке 4
            if (size.isNotBlank() && !ValidationUtils.isValidSizeFormat(size)) allValid = false
            if (dpi.isNotBlank() && !ValidationUtils.isValidDpi(dpi)) allValid = false
        }
        isOKActionEnabled = allValid
        table.repaint()
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()

        // Удаляем пустые строки перед сохранением
        val rowsToRemove = mutableListOf<Int>()

        // Проходим по всем строкам и находим пустые
        for (i in 0 until tableModel.rowCount) {
            val label = (tableModel.getValueAt(i, 2) as? String ?: "").trim()
            val size = (tableModel.getValueAt(i, 3) as? String ?: "").trim()
            val dpi = (tableModel.getValueAt(i, 4) as? String ?: "").trim()

            // Если все три поля пустые, помечаем строку для удаления
            if (label.isEmpty() && size.isEmpty() && dpi.isEmpty()) {
                rowsToRemove.add(i)
            }
        }

        // Удаляем строки в обратном порядке (с конца), чтобы не сбить индексы
        rowsToRemove.reversed().forEach { rowIndex ->
            tableModel.removeRow(rowIndex)
        }

        // Сохраняем только непустые пресеты
        SettingsService.savePresets(tableModel.getPresets())
        
        // Отписываемся от уведомлений
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        
        // Убираем глобальный обработчик клавиатуры
        keyEventDispatcher?.let { 
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            println("ADB_DEBUG: KeyEventDispatcher removed from KeyboardFocusManager")
        }
        
        super.doOKAction()
    }
    
    override fun doCancelAction() {
        // Отписываемся от уведомлений
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        
        // Убираем глобальный обработчик клавиатуры
        keyEventDispatcher?.let { 
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            println("ADB_DEBUG: KeyEventDispatcher removed from KeyboardFocusManager")
        }
        
        super.doCancelAction()
    }

    enum class IndicatorType {
        NONE, GREEN, YELLOW, GRAY
    }

    @Suppress("DEPRECATION")
    inner class ValidationRenderer : DefaultTableCellRenderer() {
        
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            // НЕ вызываем super - полностью переопределяем поведение
            
            // Определяем состояние ячейки
            val isHovered = hoverState.isTableCellHovered(row, column)
            val isSelectedCell = hoverState.isTableCellSelected(row, column)
            
            // Логируем только изменения hover состояния
            if (isHovered || isSelectedCell) {
                println("ADB_DEBUG: Rendering ACTIVE cell row=$row, column=$column, isHovered=$isHovered, isSelectedCell=$isSelectedCell")
            }
            
            // Определяем цвета ячейки на основе состояния
            val cellBackground = when {
                isSelectedCell -> JBColor(Color(230, 230, 250), Color(80, 80, 100))
                isHovered -> JBColor(Gray._240, Gray._70)
                else -> UIManager.getColor("Table.background") ?: JBColor.WHITE
            }
            val cellForeground = UIManager.getColor("Table.foreground") ?: JBColor.BLACK
            
            // Настраиваем компонент
            isOpaque = true
            background = cellBackground
            foreground = cellForeground
            text = value?.toString() ?: ""
            horizontalAlignment = LEFT
            border = null

            when (column) {
                0, 1, 5 -> {
                    // Для колонок с иконками, номерами и кнопками - стандартное поведение
                    border = null
                }
                2 -> {
                    // Для колонки Label - с индикатором если активен весь пресет
                    val preset = getPresetAtRow(row)
                    val activePresets = DeviceStateService.getCurrentActivePresets()
                    
                    val sizeIndicator = getIndicatorType(preset, activePresets, isSize = true)
                    val dpiIndicator = getIndicatorType(preset, activePresets, isSize = false)
                    
                    val text = value as? String ?: ""
                    
                    // Добавляем галочку только если активны ОБА параметра (Size И DPI)
                    if (sizeIndicator != IndicatorType.NONE && dpiIndicator != IndicatorType.NONE && text.isNotBlank()) {
                        // Для серого индикатора не добавляем никаких символов
                        if (sizeIndicator == IndicatorType.GRAY || dpiIndicator == IndicatorType.GRAY) {
                            println("ADB_DEBUG: Рендерим СЕРУЮ рамку для ${preset.label} (sizeIndicator=$sizeIndicator, dpiIndicator=$dpiIndicator)")
                            this.text = text
                            foreground = table.foreground
                            border = GrayParameterBorder()
                        } else {
                            val indicator = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                "✓ " // Желтая галка для измененных
                            } else {
                                "✓ " // Зеленая галка для точно совпадающих
                            }
                            this.text = indicator + text
                            foreground = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                JBColor.ORANGE
                            } else {
                                JBColor.GREEN.darker()
                            }
                            
                            // Добавляем обводку для Label если активен полный пресет
                            border = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                YellowParameterBorder()
                            } else {
                                ActiveParameterBorder()
                            }
                        }
                    } else {
                    this.text = text
                    foreground = cellForeground
                    border = null
                    }
                }
                3, 4 -> {
                    // Для колонок Size и DPI - с индикаторами активности
                    val text = value as? String ?: ""

                    val preset = getPresetAtRow(row)
                    val activePresets = DeviceStateService.getCurrentActivePresets()
                    
                    val isSize = column == 3
                    val indicatorType = getIndicatorType(preset, activePresets, isSize = isSize)

                    // Добавляем обводку для активных параметров
                    border = when (indicatorType) {
                        IndicatorType.GRAY -> GrayParameterBorder()
                        IndicatorType.YELLOW -> YellowParameterBorder()
                        IndicatorType.GREEN -> ActiveParameterBorder()
                        else -> null
                    }
                    
                    // Добавляем индикатор к тексту для активных параметров
                    if (indicatorType != IndicatorType.NONE && text.isNotBlank()) {
                        if (indicatorType == IndicatorType.GRAY) {
                            // Для серого индикатора - просто текст без символов
                            println("ADB_DEBUG: Рендерим серую рамку в колонке ${if (isSize) "SIZE" else "DPI"} для пресета с текстом '$text'")
                            this.text = text
                            foreground = cellForeground
                        } else {
                            // Для остальных - галочка
                            val indicator = "✓ "
                            this.text = indicator + text
                            foreground = when (indicatorType) {
                                IndicatorType.YELLOW -> JBColor.ORANGE
                                else -> JBColor.GREEN.darker()
                            }
                        }
                    } else {
                        this.text = text
                        foreground = cellForeground
                    }
                }
            }

            return this
        }
        
        private fun getIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo, isSize: Boolean): IndicatorType {
            return if (isSize) {
                getSizeIndicatorType(preset, activePresets)
            } else {
                getDpiIndicatorType(preset, activePresets)
            }
        }
        
        private fun getSizeIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
            return getPresetIndicatorType(
                presetValue = preset.size,
                preset = preset,
                resetPreset = activePresets.resetSizePreset,
                activeValue = activePresets.activeSizePreset?.size,
                originalPreset = activePresets.originalSizePreset,
                originalValue = activePresets.originalSizePreset?.size
            )
        }
        
        private fun getDpiIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
            return getPresetIndicatorType(
                presetValue = preset.dpi,
                preset = preset,
                resetPreset = activePresets.resetDpiPreset,
                activeValue = activePresets.activeDpiPreset?.dpi,
                originalPreset = activePresets.originalDpiPreset,
                originalValue = activePresets.originalDpiPreset?.dpi
            )
        }
        
        private fun getPresetIndicatorType(
            presetValue: String,
            preset: DevicePreset,
            resetPreset: DevicePreset?,
            activeValue: String?,
            originalPreset: DevicePreset?,
            originalValue: String?
        ): IndicatorType {
            if (presetValue.isBlank()) return IndicatorType.NONE
            
            // Проверяем, был ли этот конкретный пресет сброшен (по label)
            if (resetPreset?.label == preset.label) {
                return IndicatorType.GRAY
            }
            
            return getParameterIndicatorType(
                preset = preset,
                presetValue = presetValue,
                activeValue = activeValue,
                originalPreset = originalPreset,
                originalValue = originalValue
            )
        }
        
        private fun getParameterIndicatorType(
            preset: DevicePreset,
            presetValue: String,
            activeValue: String?,
            originalPreset: DevicePreset?,
            originalValue: String?
        ): IndicatorType {
            // Проверяем точное совпадение с текущим состоянием устройств
            val isCurrentlyActive = activeValue == presetValue
            
            if (isCurrentlyActive) {
                // Проверяем, был ли этот параметр из этого пресета применен изначально
                val wasFromThisPreset = originalPreset?.label == preset.label
                
                if (wasFromThisPreset) {
                    // Проверяем, изменился ли параметр в этом пресете с момента применения
                    val isModified = presetValue != originalValue
                    return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
                }
                // Если значение совпадает, но пресет не был применен изначально - не показываем индикатор
            }
            
            // Проверяем, не изменился ли активный пресет (для желтой галки без активного совпадения)
            val isFromOriginalPreset = originalPreset?.label == preset.label
            
            if (isFromOriginalPreset) {
                val isModified = presetValue != originalValue
                return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
            }
            
            return IndicatorType.NONE
        }
    }
    
    // Кастомная обводка для активных параметров
    inner class ActiveParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.GREEN
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
    }
    
    // Кастомная обводка для измененных активных параметров
    inner class YellowParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.ORANGE
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
    }
    
    // Кастомная обводка для сброшенных параметров
    inner class GrayParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.GRAY
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
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

private class ButtonEditor(private val table: JTable) : AbstractTableCellEditor(), TableCellEditor {
    private val button = JButton(AllIcons.Actions.Cancel)

    init {
        button.preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize

        button.addActionListener {
            // Когда кнопка нажата, мы получаем строку, которая сейчас редактируется.
            // Это всегда будет правильная строка.
            val modelRow = table.convertRowIndexToModel(table.editingRow)

            // Останавливаем редактирование, чтобы избежать ошибок
            fireEditingStopped()

            if (modelRow != -1) {
                (table.model as DefaultTableModel).removeRow(modelRow)
            }
        }
    }

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        button.background = table.selectionBackground
        return button
    }

    override fun getCellEditorValue(): Any = "Delete"
}