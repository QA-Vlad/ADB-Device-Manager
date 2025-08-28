package io.github.qavlad.adbdevicemanager.ui.components

import javax.swing.DefaultCellEditor
import javax.swing.JTable
import javax.swing.JTextField
import java.awt.Component
import java.awt.EventQueue
import java.awt.event.KeyEvent

/**
 * Редактор ячейки, который очищает содержимое при начале ввода текста (как в Google Sheets)
 */
class ClearOnTypeCellEditor : DefaultCellEditor(JTextField()) {
    private val textField = editorComponent as JTextField
    private var initialKeyEvent: KeyEvent? = null
    
    override fun getTableCellEditorComponent(
        table: JTable, 
        value: Any?, 
        isSelected: Boolean, 
        row: Int, 
        column: Int
    ): Component {
        // Сохраняем текущее значение
        textField.text = value?.toString() ?: ""
        
        // Проверяем, началось ли редактирование с нажатия символьной клавиши
        val keyEvent = EventQueue.getCurrentEvent() as? KeyEvent
        if (keyEvent != null && keyEvent.id == KeyEvent.KEY_PRESSED) {
            val keyChar = keyEvent.keyChar
            val keyCode = keyEvent.keyCode
            
            // Если нажата символьная клавиша (не служебная)
            if (keyChar != KeyEvent.CHAR_UNDEFINED && 
                !keyEvent.isControlDown && 
                !keyEvent.isAltDown && 
                keyCode != KeyEvent.VK_F2 &&
                keyCode != KeyEvent.VK_ENTER &&
                keyCode != KeyEvent.VK_TAB &&
                keyCode != KeyEvent.VK_ESCAPE &&
                keyCode != KeyEvent.VK_DELETE &&
                keyCode != KeyEvent.VK_BACK_SPACE) {
                
                // Очищаем поле, но НЕ вставляем символ - JTable сделает это сам
                textField.text = ""
                // Сохраняем событие, чтобы знать, что мы уже обработали начальный ввод
                initialKeyEvent = keyEvent
            } else if (keyCode == KeyEvent.VK_F2) {
                // При F2 просто позиционируем курсор в конец
                textField.caretPosition = textField.text.length
                initialKeyEvent = null
            } else if (keyCode == KeyEvent.VK_DELETE || keyCode == KeyEvent.VK_BACK_SPACE) {
                // При Delete/Backspace очищаем содержимое
                textField.text = ""
                initialKeyEvent = keyEvent
            } else {
                // Для других случаев выделяем весь текст
                textField.selectAll()
                initialKeyEvent = null
            }
        } else {
            // Если редактирование началось не с клавиши (например, двойной клик)
            textField.selectAll()
            initialKeyEvent = null
        }
        
        return textField
    }
    
    override fun getCellEditorValue(): Any {
        return textField.text
    }
}