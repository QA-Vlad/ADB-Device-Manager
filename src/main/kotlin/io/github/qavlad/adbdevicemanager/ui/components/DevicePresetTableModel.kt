package io.github.qavlad.adbdevicemanager.ui.components

import io.github.qavlad.adbdevicemanager.services.DevicePreset
import io.github.qavlad.adbdevicemanager.services.UsageCounterService
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
                // НЕ добавляем ID как видимую колонку - храним его в отдельной структуре
            }
        }
    }

    private var isUndoOperation = false
    private var isOrientationChange = false
    private val historyManager: CommandHistoryManager
    // Храним ID пресетов в отдельной структуре вместо видимой колонки
    private val presetIds = mutableMapOf<Int, String>()

    // Конструктор для совместимости с оригинальным SettingsDialog
    constructor(data: Vector<Vector<Any>>, columnNames: Vector<String>, historyManager: CommandHistoryManager) : super(data, columnNames) {
        this.historyManager = historyManager
        updateRowNumbers()
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        // Определяем колонки счетчиков и удаления в зависимости от количества колонок
        // В Show All режиме: 9 колонок со счетчиками, 7 без счетчиков
        // В Normal режиме: 8 колонок со счетчиками, 6 без счетчиков
        val isShowAllMode = columnCount == 9 || columnCount == 7
        val hasCounters = when {
            isShowAllMode -> columnCount == 9
            else -> columnCount == 8
        }
        
        val deleteColumnIndex = when {
            hasCounters -> 7                       // Со счетчиками всегда на позиции 7
            isShowAllMode -> 5                     // Show All без счетчиков на позиции 5
            else -> 5                              // Normal без счетчиков на позиции 5
        }
        
        val result = when {
            column == 0 || column == 1 -> false    // Drag icon and row number
            hasCounters && (column == 5 || column == 6) -> false  // Size and DPI counters
            column == deleteColumnIndex -> true     // Delete button column should be editable
            column >= deleteColumnIndex -> false    // Columns after delete (like List in Show All)
            else -> true                           // Label, Size, DPI columns
        }
        
        // Отладка для колонки удаления
        if (column == 5 || column == 7) {
            println("ADB_DEBUG: isCellEditable - row: $row, column: $column, columnCount: $columnCount, isShowAllMode: $isShowAllMode, hasCounters: $hasCounters, deleteColumnIndex: $deleteColumnIndex, result: $result")
        }
        
        return result
    }

    override fun setValueAt(aValue: Any?, row: Int, column: Int) {
        val oldValue = getValueAt(row, column)
        
        // Захватываем информацию о пресете ДО изменения для истории команд
        val presetBeforeEdit = if (!isUndoOperation && !isOrientationChange && column in 2..4) {
            getPresetAt(row)
        } else null
        
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
            if (column in 2..4 && presetBeforeEdit != null) {
                historyManager.addCellEdit(row, column, oldValue as? String ?: "", aValue, presetBeforeEdit)
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
        return dataVector.mapIndexedNotNull { index, row ->
            val rowVector = row as Vector<Any>
            val firstColumn = rowVector.elementAtOrNull(0) as? String ?: ""

            // Пропускаем строку с кнопкой
            if (firstColumn == "+") {
                return@mapIndexedNotNull null
            }

            // Получаем ID из внутренней карты или генерируем новый и сохраняем
            val id = presetIds[index] ?: run {
                val newId = UUID.randomUUID().toString()
                presetIds[index] = newId
                println("ADB_DEBUG: DevicePresetTableModel.getPresets - Generated new ID for row $index: $newId")
                newId
            }
            
            DevicePreset(
                label = rowVector.elementAt(2) as? String ?: "",
                size = rowVector.elementAt(3) as? String ?: "",
                dpi = rowVector.elementAt(4) as? String ?: "",
                id = id
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

        // Получаем ID из внутренней карты или генерируем новый и сохраняем
        val id = presetIds[row] ?: run {
            val newId = UUID.randomUUID().toString()
            presetIds[row] = newId
            println("ADB_DEBUG: DevicePresetTableModel.getPresetAt - Generated new ID for row $row: $newId")
            newId
        }
        
        return DevicePreset(
            label = rowVector.elementAt(2) as? String ?: "",
            size = rowVector.elementAt(3) as? String ?: "",
            dpi = rowVector.elementAt(4) as? String ?: "",
            id = id
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

    /**
     * Добавляет строку с привязкой ID пресета
     */
    fun addRowWithPresetId(rowData: Vector<*>, presetId: String) {
        val rowIndex = rowCount
        addRow(rowData)
        presetIds[rowIndex] = presetId
        println("ADB_DEBUG: DevicePresetTableModel.addRowWithPresetId - row $rowIndex, presetId: $presetId")
    }
    
    /**
     * Получает ID пресета по индексу строки
     */
    fun getPresetIdAt(row: Int): String? {
        return presetIds[row]
    }

    override fun addRow(rowData: Vector<*>?) {
        if (rowData != null) {
            println("ADB_DEBUG: DevicePresetTableModel.addRow - rowData size: ${rowData.size}, columnCount: $columnCount")
            if (rowData.size > 6) {
                println("ADB_DEBUG: DevicePresetTableModel.addRow - last element (should be listName in Show All): '${rowData.last()}'")
            }
        }
        super.addRow(rowData)
        // Не обновляем номера строк для строки с кнопкой плюсика
        if (rowData != null && rowData.isNotEmpty() && rowData[0] != "+") {
            updateRowNumbers()
        }
    }

    override fun insertRow(row: Int, rowData: Vector<*>?) {
        super.insertRow(row, rowData)
        // Обновляем карту ID после вставки строки
        updatePresetIdsAfterInsertion(row)
        updateRowNumbers()
    }
    
    private fun updatePresetIdsAfterInsertion(insertedRow: Int) {
        val newIds = mutableMapOf<Int, String>()
        presetIds.forEach { (row, id) ->
            when {
                row < insertedRow -> newIds[row] = id
                row >= insertedRow -> newIds[row + 1] = id
            }
        }
        // Новая строка пока не имеет ID - он будет присвоен при первом обращении
        presetIds.clear()
        presetIds.putAll(newIds)
    }

    override fun removeRow(row: Int) {
        super.removeRow(row)
        // Обновляем карту ID после удаления строки
        updatePresetIdsAfterRemoval(row)
        updateRowNumbers()
    }
    
    /**
     * Очищает все ID при полной очистке таблицы
     */
    fun clearAllPresetIds() {
        presetIds.clear()
        println("ADB_DEBUG: DevicePresetTableModel.clearAllPresetIds - Cleared all preset IDs")
    }

    override fun moveRow(start: Int, end: Int, to: Int) {
        // Сохраняем ID перемещаемых строк
        val movedId = presetIds[start]
        
        super.moveRow(start, end, to)
        
        // Обновляем карту ID после перемещения
        if (movedId != null) {
            updatePresetIdsAfterMove(start, to, movedId)
        }
        
        updateRowNumbers()
    }
    
    private fun updatePresetIdsAfterRemoval(removedRow: Int) {
        val newIds = mutableMapOf<Int, String>()
        presetIds.forEach { (row, id) ->
            when {
                row < removedRow -> newIds[row] = id
                row > removedRow -> newIds[row - 1] = id
                // row == removedRow is removed, so we skip it
            }
        }
        presetIds.clear()
        presetIds.putAll(newIds)
    }
    
    private fun updatePresetIdsAfterMove(from: Int, to: Int, movedId: String) {
        val newIds = mutableMapOf<Int, String>()
        
        presetIds.forEach { (row, id) ->
            when {
                row == from -> {} // Skip the moved row for now
                from < to && row > from && row <= to -> newIds[row - 1] = id
                from > to && row >= to && row < from -> newIds[row + 1] = id
                else -> newIds[row] = id
            }
        }
        
        // Place the moved ID at the new position
        newIds[to] = movedId
        
        presetIds.clear()
        presetIds.putAll(newIds)
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
    
    /**
     * Устанавливает флаг, указывающий, что происходит изменение ориентации
     * @param isChanging true, если происходит изменение ориентации
     */
    fun setOrientationChanging(isChanging: Boolean) {
        isOrientationChange = isChanging
    }
}