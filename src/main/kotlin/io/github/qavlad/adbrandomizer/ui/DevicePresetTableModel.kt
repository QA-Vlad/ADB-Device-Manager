// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DevicePresetTableModel.kt
package io.github.qavlad.adbrandomizer.ui

import io.github.qavlad.adbrandomizer.services.DevicePreset
import java.util.*
import javax.swing.table.DefaultTableModel

class DevicePresetTableModel(
    data: Vector<Vector<Any>>,
    columnNames: Vector<String>,
    private val historyManager: HistoryManager
) : DefaultTableModel(data, columnNames) {

    private var isUndoOperation = false

    init {
        updateRowNumbers()
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        // Запрещаем редактирование иконки "перетащить" и номера строки
        return column != 0 && column != 1
    }

    override fun setValueAt(aValue: Any?, row: Int, column: Int) {
        val oldValue = getValueAt(row, column)
        super.setValueAt(aValue, row, column)

        // Записываем в историю, только если это не операция отмены и значение действительно изменилось
        if (!isUndoOperation && oldValue != aValue && aValue is String) {
            historyManager.addToHistory(row, column, oldValue as? String ?: "", aValue)
        }
    }

    /**
     * Устанавливает значение в ячейку в рамках операции отмены, не создавая новую запись в истории.
     */
    fun undoValueAt(aValue: Any?, row: Int, column: Int) {
        isUndoOperation = true
        try {
            setValueAt(aValue, row, column)
        } finally {
            isUndoOperation = false
        }
    }

    fun getPresets(): List<DevicePreset> {
        return dataVector.map { row ->
            DevicePreset(
                label = row.elementAt(2) as? String ?: "",
                size = row.elementAt(3) as? String ?: "",
                dpi = row.elementAt(4) as? String ?: ""
            )
        }
    }

    override fun addRow(rowData: Vector<*>?) {
        super.addRow(rowData)
        updateRowNumbers()
    }

    override fun removeRow(row: Int) {
        super.removeRow(row)
        updateRowNumbers()
    }

    override fun moveRow(start: Int, end: Int, to: Int) {
        super.moveRow(start, end, to)
        updateRowNumbers()
    }

    private fun updateRowNumbers() {
        // Временно отключаем историю, чтобы обновление номеров строк не засоряло её
        isUndoOperation = true
        try {
            for (i in 0 until rowCount) {
                // Используем super.setValueAt, чтобы не вызывать наш переопределенный метод
                super.setValueAt(i + 1, i, 1)
            }
        } finally {
            isUndoOperation = false
        }
    }
}