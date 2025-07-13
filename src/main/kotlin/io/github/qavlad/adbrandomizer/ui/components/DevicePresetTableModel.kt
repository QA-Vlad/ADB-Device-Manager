package io.github.qavlad.adbrandomizer.ui.components

import io.github.qavlad.adbrandomizer.services.DevicePreset
import java.util.*
import javax.swing.table.DefaultTableModel

class DevicePresetTableModel : DefaultTableModel {

    companion object {
        /**
         * Создает Vector строки таблицы из DevicePreset
         */
        fun createRowVector(preset: DevicePreset, rowNumber: Int = 0): Vector<Any> {
            return Vector<Any>().apply {
                add("☰")
                add(rowNumber)
                add(preset.label)
                add(preset.size)
                add(preset.dpi)
                add("Delete")
            }
        }
    }

    private var isUndoOperation = false
    private val historyManager: HistoryManager

    // Конструктор для совместимости с оригинальным SettingsDialog
    constructor(data: Vector<Vector<Any>>, columnNames: Vector<String>, historyManager: HistoryManager) : super(data, columnNames) {
        this.historyManager = historyManager
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
        return dataVector.mapNotNull { row ->
            val rowVector = row as Vector<Any>
            val firstColumn = rowVector.elementAtOrNull(0) as? String ?: ""

            // Пропускаем строку с кнопкой
            if (firstColumn == "+") {
                return@mapNotNull null
            }

            DevicePreset(
                label = rowVector.elementAt(2) as? String ?: "",
                size = rowVector.elementAt(3) as? String ?: "",
                dpi = rowVector.elementAt(4) as? String ?: ""
            )
        }
    }

    fun getPresetAt(row: Int): DevicePreset? {
        if (row < 0 || row >= rowCount) return null

        val rowVector = dataVector.elementAt(row) as? Vector<Any> ?: return null
        val firstColumn = rowVector.elementAtOrNull(0) as? String ?: ""

        // Пропускаем строку с кнопкой
        if (firstColumn == "+") {
            return null
        }

        return DevicePreset(
            label = rowVector.elementAt(2) as? String ?: "",
            size = rowVector.elementAt(3) as? String ?: "",
            dpi = rowVector.elementAt(4) as? String ?: ""
        )
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
        // Не обновляем номера строк для строки с кнопкой плюсика
        if (rowData != null && rowData.isNotEmpty() && rowData[0] != "+") {
            updateRowNumbers()
        }
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

    fun updateRowNumbers() {
        // Временно отключаем историю, чтобы обновление номеров строк не засоряло её
        isUndoOperation = true
        try {
            var actualRowNumber = 1
            for (i in 0 until rowCount) {
                // Не обновляем номер для строки с кнопкой плюсика
                if (getValueAt(i, 0) != "+") {
                    // Используем super.setValueAt, чтобы не вызывать наш переопределенный метод
                    super.setValueAt(actualRowNumber, i, 1)
                    actualRowNumber++
                }
            }
        } finally {
            isUndoOperation = false
        }
    }
}