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
    
    fun redoValueAt(aValue: Any?, row: Int, column: Int) {
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

    /**
     * Находит все дубликаты пресетов по комбинации Size + DPI.
     * @return Map, где ключ - это индекс строки-дубликата, а значение - список индексов других таких же дубликатов.
     */
    fun findDuplicates(): Map<Int, List<Int>> {
        val presets = getPresets()
        val presetGroups = presets.withIndex()
            .groupBy {
                // Группируем по комбинации Size и DPI, пустые значения игнорируем
                if (it.value.size.isNotBlank() && it.value.dpi.isNotBlank()) {
                    "${it.value.size}|${it.value.dpi}"
                } else {
                    // Используем уникальный идентификатор для неполных пресетов, чтобы они не группировались
                    "unique_${it.index}"
                }
            }
            .filter { it.value.size > 1 } // Оставляем только группы, где больше одного элемента (т.е. дубликаты)

        val duplicatesMap = mutableMapOf<Int, List<Int>>()
        presetGroups.values.forEach { group ->
            val indices = group.map { it.index }
            indices.forEach { index ->
                // Для каждой строки-дубликата сохраняем список других дубликатов в этой же группе
                duplicatesMap[index] = indices.filter { it != index }
            }
        }
        return duplicatesMap
    }

    override fun addRow(rowData: Vector<*>?) {
        super.addRow(rowData)
        updateRowNumbers()
    }

    override fun insertRow(row: Int, rowData: Vector<*>?) {
        super.insertRow(row, rowData)
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