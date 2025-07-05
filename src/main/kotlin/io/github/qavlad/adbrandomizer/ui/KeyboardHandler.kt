package io.github.qavlad.adbrandomizer.ui

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTable

class KeyboardHandler(
    private val table: JTable,
    private val tableModel: DevicePresetTableModel,
    private val hoverState: () -> HoverState,
    private val historyManager: HistoryManager,
    private val validateFields: () -> Unit,
    private val setEditingCellData: (String?, Int, Int) -> Unit
) {
    private var keyEventDispatcher: KeyEventDispatcher? = null
    
    fun addGlobalKeyListener() {
        println("ADB_DEBUG: Adding global key listener using KeyboardFocusManager")
        
        keyEventDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                println("ADB_DEBUG: Global key pressed: ${e.keyCode}, isControlDown=${e.isControlDown}")
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
                        if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
                            pasteCellFromClipboard()
                            return@KeyEventDispatcher true
                        }
                    }
                    e.keyCode == KeyEvent.VK_Z && e.isControlDown -> {
                        println("ADB_DEBUG: Ctrl+Z pressed, история содержит ${historyManager.size()} записей")
                        
                        if (table.isEditing) {
                            println("ADB_DEBUG: Table is in editing mode - ignoring global undo")
                            return@KeyEventDispatcher false
                        } else {
                            undoLastPaste()
                            return@KeyEventDispatcher true
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
                
                if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
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
                            if (!table.isEditing) {
                                undoLastPaste()
                                e.consume()
                            } else {
                                println("ADB_DEBUG: Table is editing - local KeyListener ignoring undo")
                            }
                        }
                        !e.isControlDown && !e.isAltDown -> {
                            println("ADB_DEBUG: Checking if key should start editing: keyCode=${e.keyCode}, range check=${e.keyCode in 32..126}, F2 check=${e.keyCode == KeyEvent.VK_F2}")
                            if (e.keyCode in 32..126 || e.keyCode == KeyEvent.VK_F2) {
                                println("ADB_DEBUG: Starting edit due to key press")
                                val row = hoverState().selectedTableRow
                                val column = hoverState().selectedTableColumn
                                if (row >= 0 && column >= 0) {
                                    val oldValue = tableModel.getValueAt(row, column) as? String ?: ""
                                    setEditingCellData(oldValue, row, column)
                                    println("ADB_DEBUG: EDITING WILL START via keyPress - row=$row, column=$column, oldValue='$oldValue'")
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
    
    private fun pasteCellFromClipboard() {
        if (hoverState().selectedTableRow >= 0 && hoverState().selectedTableColumn >= 0) {
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val data = clipboard.getContents(null)
                
                if (data != null && data.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val oldValue = tableModel.getValueAt(hoverState().selectedTableRow, hoverState().selectedTableColumn) as? String ?: ""
                    val newValue = (data.getTransferData(DataFlavor.stringFlavor) as String).trim()
                    
                    setEditingCellData(oldValue, hoverState().selectedTableRow, hoverState().selectedTableColumn)
                    
                    tableModel.setValueAt(newValue, hoverState().selectedTableRow, hoverState().selectedTableColumn)
                    println("ADB_DEBUG: Вставлено из буфера: '$newValue' (история: ${historyManager.size()})")
                    
                    validateFields()
                    table.repaint()
                }
            } catch (e: Exception) {
                println("ADB_DEBUG: Ошибка при вставке: ${e.message}")
            }
        }
    }

    private fun undoLastPaste() {
        val entry = historyManager.undoLast()
        if (entry != null) {
            val coords = historyManager.findCellCoordinates(entry.cellId)
            if (coords != null) {
                // Используем новый метод, который не создает запись в истории
                (table.model as? DevicePresetTableModel)?.undoValueAt(entry.oldValue, coords.first, coords.second)
                validateFields()
                table.repaint()
            }
        }
    }
}