package io.github.qavlad.adbrandomizer.ui.handlers

import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import io.github.qavlad.adbrandomizer.ui.components.Orientation
import io.github.qavlad.adbrandomizer.ui.components.OrientationPanel
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTable
import javax.swing.JButton
import javax.swing.SwingUtilities
import com.intellij.openapi.util.SystemInfo
import java.awt.Container

class KeyboardHandler(
    private val table: JTable,
    private val tableModel: DevicePresetTableModel,
    private val hoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val historyManager: CommandHistoryManager,
    private val validateFields: () -> Unit,
    private val setEditingCellData: (String?, Int, Int) -> Unit,
    private val onDuplicate: (Int) -> Unit,
    private val forceSyncBeforeHistory: () -> Unit = {}
) {
    private var keyEventDispatcher: KeyEventDispatcher? = null
    private var lastUndoTime = 0L
    private val undoDebounceMs = 100L // Минимальный интервал между undo

    fun addGlobalKeyListener() {
        println("ADB_DEBUG: Adding global key listener using KeyboardFocusManager")
        
        // Удаляем старый dispatcher если он есть
        removeGlobalKeyListener()

        val dispatcherId = System.identityHashCode(this)
        keyEventDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                println("ADB_DEBUG: Global key pressed [dispatcher=$dispatcherId]: ${e.keyCode}, isControlDown=${e.isControlDown}, source=${e.source?.javaClass?.simpleName}")
                
                // КОСТЫЛЬ: Для стрелки влево в диалоге экспорта на кнопке Cancel
                if (e.keyCode == KeyEvent.VK_LEFT && System.getProperty("adbrandomizer.exportDialogOpen") == "true") {
                    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    if (focusOwner is JButton && focusOwner.text == "Cancel") {
                        println("ADB_DEBUG: LEFT on Cancel in Export dialog - directly switching to OK")
                        
                        // Находим кнопку OK и переключаем фокус напрямую
                        val window = SwingUtilities.getWindowAncestor(focusOwner)
                        if (window != null) {
                            val okButton = findButtonInContainer(window, "OK")
                            if (okButton != null) {
                                SwingUtilities.invokeLater {
                                    okButton.requestFocusInWindow()
                                }
                                return@KeyEventDispatcher true // Блокируем дальнейшую обработку
                            }
                        }
                    }
                }
                when {
                    e.keyCode == KeyEvent.VK_C && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+C pressed, selectedRow=${hoverState().selectedTableRow}, selectedColumn=${hoverState().selectedTableColumn}")
                        if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
                            copyCellToClipboard()
                            return@KeyEventDispatcher true
                        }
                    }
                    e.keyCode == KeyEvent.VK_V && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+V pressed, selectedRow=${hoverState().selectedTableRow}, selectedColumn=${hoverState().selectedTableColumn}")
                        val row = hoverState().selectedTableRow
                        val column = hoverState().selectedTableColumn
                        
                        // Если таблица в режиме редактирования, не перехватываем событие
                        if (table.isEditing && table.editingRow == row && table.editingColumn == column) {
                            println("ADB_DEBUG: Table is editing - allowing standard paste")
                            return@KeyEventDispatcher false
                        }
                        
                        // Если ячейка выделена, но не редактируется - обрабатываем вставку
                        if (row >= 0 && column >= 0) {
                            pasteCellFromClipboard()
                            return@KeyEventDispatcher true
                        }
                    }
                    e.keyCode == KeyEvent.VK_Z && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+Z pressed in KeyEventDispatcher, история содержит ${historyManager.size()} записей")

                        if (table.isEditing) {
                            println("ADB_DEBUG: Table is in editing mode - ignoring global undo")
                            return@KeyEventDispatcher false
                        } else {
                            performUndo()
                            return@KeyEventDispatcher true
                        }
                    }
                    e.keyCode == KeyEvent.VK_Y && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+Y pressed")

                        if (table.isEditing) {
                            println("ADB_DEBUG: Table is in editing mode - ignoring global redo")
                            return@KeyEventDispatcher false
                        } else {
                            performRedo()
                            return@KeyEventDispatcher true
                        }
                    }
                    // Обработка навигационных клавиш
                    e.keyCode == KeyEvent.VK_UP || e.keyCode == KeyEvent.VK_DOWN || 
                    e.keyCode == KeyEvent.VK_LEFT || e.keyCode == KeyEvent.VK_RIGHT -> {
                        // КОСТЫЛЬ: Если открыт диалог экспорта, не обрабатываем стрелки
                        if (System.getProperty("adbrandomizer.exportDialogOpen") == "true") {
                            println("ADB_DEBUG: Export dialog is open, skipping arrow key handling")
                            return@KeyEventDispatcher false
                        }
                        
                        if (!table.isEditing && table.isFocusOwner) {
                            val currentHoverState = hoverState()
                            
                            // Если ничего не выделено, выделяем первую ячейку Label (0, 2)
                            if (currentHoverState.selectedTableRow < 0 || currentHoverState.selectedTableColumn < 0) {
                                // Проверяем, что в таблице есть строки (кроме строки с кнопкой +)
                                if (table.rowCount > 1) {
                                    setHoverState(currentHoverState.withTableSelection(0, 2))
                                    val rect = table.getCellRect(0, 2, false)
                                    table.repaint(rect)
                                    println("ADB_DEBUG: No cell selected, selecting first Label cell (0, 2)")
                                    return@KeyEventDispatcher true
                                }
                            } else {
                                handleNavigationKey(e.keyCode)
                            }
                            return@KeyEventDispatcher true
                        }
                    }
                    // Обработка Enter для активации редактирования
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        if (!table.isEditing && table.isFocusOwner) {
                            val selectedRow = hoverState().selectedTableRow
                            val selectedColumn = hoverState().selectedTableColumn
                            if (selectedRow >= 0 && selectedColumn >= 0 && table.isCellEditable(selectedRow, selectedColumn)) {
                                SwingUtilities.invokeLater {
                                    table.editCellAt(selectedRow, selectedColumn)
                                    table.editorComponent?.requestFocus()
                                }
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                }
            }
            false
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        println("ADB_DEBUG: KeyEventDispatcher added to KeyboardFocusManager")
    }

    fun removeGlobalKeyListener() {
        keyEventDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            println("ADB_DEBUG: KeyEventDispatcher removed from KeyboardFocusManager")
        }
    }

    fun createTableKeyListener(): KeyListener {
        return object : KeyListener {
            override fun keyPressed(e: KeyEvent) {
                println("ADB_DEBUG: Key pressed: ${e.keyCode}, isControlDown=${e.isControlDown}, selectedRow=${hoverState().selectedTableRow}, selectedColumn=${hoverState().selectedTableColumn}")

                val selectedRow = hoverState().selectedTableRow
                if (selectedRow >= 0) { // Проверяем только то, что строка выбрана
                    val isDuplicateShortcut = e.keyCode == KeyEvent.VK_D && (if (SystemInfo.isMac) e.isMetaDown else e.isControlDown)

                    if (isDuplicateShortcut) {
                        onDuplicate(selectedRow)
                        e.consume()
                        return // Действие выполнено, выходим
                    }

                    // Для всех остальных действий также должна быть выбрана колонка
                    val selectedColumn = hoverState().selectedTableColumn
                    if (selectedColumn >= 0) {
                        when {
                            e.keyCode == KeyEvent.VK_C && e.isControlDown -> {
                                copyCellToClipboard()
                                e.consume()
                            }
                            e.keyCode == KeyEvent.VK_V && e.isControlDown -> {
                                pasteCellFromClipboard()
                                e.consume()
                            }
                            e.keyCode == KeyEvent.VK_DELETE -> {
                                clearSelectedCell()
                                e.consume()
                            }
                            e.keyCode == KeyEvent.VK_Z && e.isControlDown -> {
                                // Обрабатывается глобальным обработчиком, игнорируем здесь
                                println("ADB_DEBUG: Table KeyListener ignoring Ctrl+Z (handled by global)")
                                return
                            }
                            e.keyCode == KeyEvent.VK_Y && e.isControlDown -> {
                                // Обрабатывается глобальным обработчиком, игнорируем здесь
                                println("ADB_DEBUG: Table KeyListener ignoring Ctrl+Y (handled by global)")
                                return
                            }
                            !e.isControlDown && !e.isAltDown -> {
                                println("ADB_DEBUG: Checking if key should start editing: keyCode=${e.keyCode}, range check=${e.keyCode in 32..126}, F2 check=${e.keyCode == KeyEvent.VK_F2}")
                                if (e.keyCode in 32..126 || e.keyCode == KeyEvent.VK_F2) {
                                    println("ADB_DEBUG: Starting edit due to key press")
                                    val row = hoverState().selectedTableRow
                                    val column = hoverState().selectedTableColumn
                                    if (row >= 0 && column >= 0 && table.isCellEditable(row, column)) {
                                        val oldValue = tableModel.getValueAt(row, column) as? String ?: ""
                                        setEditingCellData(oldValue, row, column)
                                        println("ADB_DEBUG: EDITING WILL START via keyPress - row=$row, column=$column, oldValue='$oldValue'")
                                        
                                        // Важно: НЕ используем invokeLater, чтобы текущее событие клавиши
                                        // было доступно в ClearOnTypeCellEditor через EventQueue.getCurrentEvent()
                                        table.editCellAt(row, column)
                                        table.editorComponent?.requestFocus()
                                        
                                        // НЕ вызываем e.consume(), чтобы символ был обработан редактором
                                    }
                                } else {
                                    println("ADB_DEBUG: Key ${e.keyCode} will not start editing")
                                }
                            }
                        }
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {}
            override fun keyTyped(e: KeyEvent) {}
        }
    }

    private fun copyCellToClipboard() {
        if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
            val value = tableModel.getValueAt(hoverState().selectedTableRow, hoverState().selectedTableColumn) as? String ?: ""
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(value), null)
            println("ADB_DEBUG: Скопировано в буфер: '$value'")
        }
    }

    private fun findOrientationPanel(): OrientationPanel? {
        // Поднимаемся по иерархии компонентов от таблицы, чтобы найти OrientationPanel
        var parent: Container? = table.parent
        while (parent != null) {
            // Ищем OrientationPanel среди компонентов каждого уровня
            parent.components.forEach { component ->
                if (component is OrientationPanel) {
                    return component
                }
            }
            parent = parent.parent
        }
        
        println("ADB_DEBUG: OrientationPanel not found in component hierarchy")
        return null
    }
    
    private fun applySmartPasteForSize(sizeText: String): String {
        // Находим OrientationPanel через иерархию компонентов
        val orientationPanel = findOrientationPanel()
        val currentOrientation = orientationPanel?.getCurrentOrientation() ?: return sizeText
        
        // Парсим размер
        val parts = sizeText.split("x", "×")
        if (parts.size != 2) {
            println("ADB_DEBUG: Smart paste - invalid format: '$sizeText'")
            return sizeText
        }
        
        val width = parts[0].trim().toIntOrNull()
        val height = parts[1].trim().toIntOrNull()
        
        if (width == null || height == null) {
            println("ADB_DEBUG: Smart paste - invalid dimensions: '$sizeText'")
            return sizeText
        }
        
        // Определяем ориентацию вставляемого размера
        val isPastedPortrait = height > width
        val isCurrentPortrait = currentOrientation == Orientation.PORTRAIT
        
        // Если ориентации не совпадают - корректируем
        if (isPastedPortrait != isCurrentPortrait) {
            val correctedSize = "${height}x${width}"
            println("ADB_DEBUG: Smart paste - auto-corrected orientation: '$sizeText' -> '$correctedSize' (current: $currentOrientation)")
            return correctedSize
        }
        
        println("ADB_DEBUG: Smart paste - no correction needed, orientation matches")
        return sizeText
    }
    
    private fun pasteCellFromClipboard() {
        if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val data = clipboard.getContents(null)

                if (data != null && data.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val row = hoverState().selectedTableRow
                    val column = hoverState().selectedTableColumn
                    var clipboardText = (data.getTransferData(DataFlavor.stringFlavor) as String).trim()

                    // Проверяем, находится ли таблица в режиме редактирования этой ячейки
                    if (table.isEditing && table.editingRow == row && table.editingColumn == column) {
                        // В режиме редактирования - позволяем стандартному редактору обработать вставку
                        // Ctrl+V будет работать как в обычном текстовом поле
                        return
                    } else {
                        // Умная вставка для колонки Size (индекс 3)
                        if (column == 3 && clipboardText.isNotBlank()) {
                            clipboardText = applySmartPasteForSize(clipboardText)
                        }
                        
                        // Не в режиме редактирования - заменяем содержимое ячейки полностью
                        tableModel.setValueAt(clipboardText, row, column)
                        println("ADB_DEBUG: Вставлено из буфера: '$clipboardText' (история: ${historyManager.size()})")

                        validateFields()
                        table.repaint()
                    }
                }
            } catch (e: Exception) {
                println("ADB_DEBUG: Ошибка при вставке: ${e.message}")
            }
        }
    }

    private fun performUndo() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUndoTime < undoDebounceMs) {
            println("ADB_DEBUG: performUndo ignored - too fast (debounced)")
            return
        }
        lastUndoTime = currentTime
        
        println("ADB_DEBUG: performUndo called from KeyboardHandler")
        
        // Форсируем синхронизацию таблицы перед undo
        forceSyncBeforeHistory()
        
        historyManager.undo()
    }
    
    private fun performRedo() {
        // Форсируем синхронизацию таблицы перед redo
        forceSyncBeforeHistory()
        
        historyManager.redo()
    }

    private fun clearSelectedCell() {
        val selectedRow = hoverState().selectedTableRow
        val selectedColumn = hoverState().selectedTableColumn

        // Проверяем, что ячейка выбрана и она редактируемая
        if (selectedRow >= 0 && selectedColumn in 2..4) {
            val oldValue = tableModel.getValueAt(selectedRow, selectedColumn) as? String ?: ""

            // Если ячейка уже пуста, ничего не делаем
            if (oldValue.isBlank()) {
                return
            }

            // Добавляем команду в историю
            historyManager.addCellEdit(selectedRow, selectedColumn, oldValue, "")

            // Устанавливаем пустое значение
            tableModel.setValueAt("", selectedRow, selectedColumn)

            // Обновляем UI
            validateFields()
            table.repaint()

            println("ADB_DEBUG: Очищена ячейка ($selectedRow, $selectedColumn). Старое значение: '$oldValue'")
        }
    }
    
    private fun handleNavigationKey(keyCode: Int) {
        val currentHoverState = hoverState()
        val selectedRow = currentHoverState.selectedTableRow
        val selectedColumn = currentHoverState.selectedTableColumn
        
        // Только работаем если есть выделенная ячейка
        if (selectedRow < 0 || selectedColumn < 0) {
            return
        }
        
        val newRow = when (keyCode) {
            KeyEvent.VK_UP -> selectedRow - 1
            KeyEvent.VK_DOWN -> selectedRow + 1
            else -> selectedRow
        }
        
        val newColumn = when (keyCode) {
            KeyEvent.VK_LEFT -> selectedColumn - 1
            KeyEvent.VK_RIGHT -> selectedColumn + 1
            else -> selectedColumn
        }
        
        navigateToCell(newRow, newColumn)
    }
    
    private fun navigateToCell(newRow: Int, newColumn: Int) {
        // Проверяем границы таблицы
        if (newRow < 0 || newRow >= table.rowCount || newColumn < 0 || newColumn >= table.columnCount) {
            return
        }
        
        // Пропускаем строку с кнопкой "+"
        val firstColumnValue = tableModel.getValueAt(newRow, 0)
        if (firstColumnValue == "+") {
            return
        }
        
        val currentSelectedColumn = hoverState().selectedTableColumn
        
        // Для навигации учитываем только редактируемые колонки (2, 3, 4)
        val editableColumns = listOf(2, 3, 4)
        val adjustedColumn = when {
            newColumn < 2 -> 2
            newColumn > 4 -> 4
            newColumn !in editableColumns -> {
                // Если попали на не редактируемую колонку, двигаемся в нужном направлении
                if (newColumn < currentSelectedColumn) editableColumns.lastOrNull { it < newColumn } ?: 2
                else editableColumns.firstOrNull { it > newColumn } ?: 4
            }
            else -> newColumn
        }
        
        val oldHoverState = hoverState()
        
        // Обновляем состояние выделения
        setHoverState(oldHoverState.withTableSelection(newRow, adjustedColumn))
        
        // Перерисовываем старую и новую ячейки
        if (oldHoverState.selectedTableRow >= 0 && oldHoverState.selectedTableColumn >= 0) {
            val oldRect = table.getCellRect(oldHoverState.selectedTableRow, oldHoverState.selectedTableColumn, false)
            table.repaint(oldRect)
        }
        
        val newRect = table.getCellRect(newRow, adjustedColumn, false)
        table.repaint(newRect)
    }

    private fun findComponentRecursive(container: java.awt.Container, predicate: (java.awt.Component) -> Boolean): java.awt.Component? {
        for (component in container.components) {
            if (predicate(component)) {
                return component
            }
            if (component is java.awt.Container) {
                val found = findComponentRecursive(component, predicate)
                if (found != null) return found
            }
        }
        return null
    }
    
    private fun findButtonInContainer(container: java.awt.Component, text: String): JButton? {
        if (container is JButton && container.text == text) {
            return container
        }
        if (container is java.awt.Container) {
            for (component in container.components) {
                val found = findButtonInContainer(component, text)
                if (found != null) return found
            }
        }
        return null
    }
}