package io.github.qavlad.adbrandomizer.ui.components

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.UsageCounterService
import java.util.*
import javax.swing.table.DefaultTableModel

class DevicePresetTableModel : DefaultTableModel {

    companion object {
        /**
         * Создает Vector строки таблицы из DevicePreset
         */
        fun createRowVector(preset: DevicePreset, rowNumber: Int = 0, showCounters: Boolean = true): Vector<Any> {
            return Vector<Any>().apply {
                add("☰")
                add(rowNumber)
                add(preset.label)
                add(preset.size)
                add(preset.dpi)
                // Добавляем счетчики только если колонки видимы
                if (showCounters) {
                    add(UsageCounterService.getSizeCounter(preset.size))
                    add(UsageCounterService.getDpiCounter(preset.dpi))
                }
                add("")  // Пустая колонка для кнопки удаления
                // Колонка List добавляется отдельно в TableLoader для режима Show All
            }
        }
    }

    private var isUndoOperation = false
    private val historyManager: CommandHistoryManager

    // Конструктор для совместимости с оригинальным SettingsDialog
    constructor(data: Vector<Vector<Any>>, columnNames: Vector<String>, historyManager: CommandHistoryManager) : super(data, columnNames) {
        this.historyManager = historyManager
        updateRowNumbers()
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        // Определяем колонки счетчиков и удаления в зависимости от количества колонок
        val hasCounters = columnCount > 6
        val deleteColumnIndex = when {
            hasCounters && columnCount >= 9 -> 7  // Show All с счетчиками
            hasCounters && columnCount >= 8 -> 7  // Normal со счетчиками
            columnCount >= 7 -> 5                  // Show All без счетчиков
            else -> 5                              // Normal без счетчиков
        }
        
        return when {
            column == 0 || column == 1 -> false    // Drag icon and row number
            hasCounters && (column == 5 || column == 6) -> false  // Size and DPI counters
            column == deleteColumnIndex -> true     // Delete button column should be editable
            column >= deleteColumnIndex -> false    // Columns after delete (like List in Show All)
            else -> true                           // Label, Size, DPI columns
        }
    }

    override fun setValueAt(aValue: Any?, row: Int, column: Int) {
        val oldValue = getValueAt(row, column)
        super.setValueAt(aValue, row, column)

        // Обновляем счетчики при изменении Size или DPI
        if (!isUndoOperation && oldValue != aValue && aValue is String) {
            when (column) {
                3 -> { // Size column
                    val newCounter = UsageCounterService.updateSizeValue(oldValue as? String ?: "", aValue)
                    // Обновляем счетчик в таблице, если колонки видимы
                    if (columnCount > 6) {
                        super.setValueAt(newCounter, row, 5)
                    }
                    // Обновляем счетчики для всех строк с таким же размером
                    updateCountersForSameValue(aValue, true)
                }
                4 -> { // DPI column
                    val newCounter = UsageCounterService.updateDpiValue(oldValue as? String ?: "", aValue)
                    // Обновляем счетчик в таблице, если колонки видимы
                    if (columnCount > 7) {
                        super.setValueAt(newCounter, row, 6)
                    }
                    // Обновляем счетчики для всех строк с таким же DPI
                    updateCountersForSameValue(aValue, false)
                }
            }
            
            // Записываем в историю только изменения в редактируемых колонках (Label, Size, DPI)
            // Исключаем колонку удаления, так как удаление обрабатывается отдельной командой
            if (column in 2..4) {
                historyManager.addCellEdit(row, column, oldValue as? String ?: "", aValue)
            }
        }
    }
    
    /**
     * Обновляет счетчики для всех строк с таким же значением Size или DPI
     */
    private fun updateCountersForSameValue(value: String, isSize: Boolean) {
        if (value.isBlank()) return
        
        val column = if (isSize) 3 else 4 // Size или DPI
        val counterColumn = if (isSize) 5 else 6 // Колонка счетчика
        
        // Проверяем, есть ли колонки счетчиков
        if (columnCount <= counterColumn) return
        
        val counter = if (isSize) {
            UsageCounterService.getSizeCounter(value)
        } else {
            UsageCounterService.getDpiCounter(value)
        }
        
        for (i in 0 until rowCount) {
            val cellValue = getValueAt(i, column) as? String ?: ""
            if (cellValue == value) {
                super.setValueAt(counter, i, counterColumn)
            }
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