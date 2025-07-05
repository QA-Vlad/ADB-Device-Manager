package io.github.qavlad.adbrandomizer.ui

import java.util.*

data class CellIdentity(val id: String) {
    companion object {
        fun generate(): CellIdentity = CellIdentity(UUID.randomUUID().toString())
    }
}

sealed class HistoryOperation {
    data class CellEdit(
        val cellId: CellIdentity,
        val oldValue: String,
        val newValue: String
    ) : HistoryOperation()
    
    data class PresetAdd(
        val rowIndex: Int,
        val presetData: io.github.qavlad.adbrandomizer.services.DevicePreset
    ) : HistoryOperation()
    
    data class PresetDelete(
        val rowIndex: Int,
        val presetData: io.github.qavlad.adbrandomizer.services.DevicePreset
    ) : HistoryOperation()
    
    data class PresetMove(
        val fromIndex: Int,
        val toIndex: Int
    ) : HistoryOperation()
    
    data class PresetImport(
        val startIndex: Int,
        val importedPresets: List<io.github.qavlad.adbrandomizer.services.DevicePreset>
    ) : HistoryOperation()
    
    data class PresetDuplicate(
        val originalIndex: Int,
        val duplicateIndex: Int,
        val presetData: io.github.qavlad.adbrandomizer.services.DevicePreset
    ) : HistoryOperation()
}

class HistoryManager(private val maxHistorySize: Int = 50) {
    private val historyStack = mutableListOf<HistoryOperation>()
    private val redoStack = mutableListOf<HistoryOperation>()
    private val cellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
    
    fun getCellId(row: Int, column: Int): CellIdentity {
        val key = Pair(row, column)
        return cellIdMap.getOrPut(key) { CellIdentity.generate() }
    }
    
    fun addToHistory(row: Int, column: Int, oldValue: String, newValue: String, clearRedo: Boolean = true) {
        if (oldValue != newValue) {
            val cellId = getCellId(row, column)
            addOperation(HistoryOperation.CellEdit(cellId, oldValue, newValue), clearRedo)
            
            println("ADB_DEBUG: Добавлена запись в историю: ($row, $column) [${cellId.id.substring(0, 8)}...] '$oldValue' -> '$newValue' clearRedo=$clearRedo")
        }
    }
    
    fun addOperation(operation: HistoryOperation, clearRedo: Boolean = true) {
        historyStack.add(operation)
        if (clearRedo) {
            redoStack.clear() // Очищаем redo стек только при новой операции
        }
        
        if (historyStack.size > maxHistorySize) {
            historyStack.removeAt(0)
        }
    }
    
    fun addPresetAdd(rowIndex: Int, preset: io.github.qavlad.adbrandomizer.services.DevicePreset) {
        addOperation(HistoryOperation.PresetAdd(rowIndex, preset))
        println("ADB_DEBUG: Добавлена операция PresetAdd: rowIndex=$rowIndex, preset=${preset.label}")
    }
    
    fun addPresetDelete(rowIndex: Int, preset: io.github.qavlad.adbrandomizer.services.DevicePreset) {
        addOperation(HistoryOperation.PresetDelete(rowIndex, preset))
        println("ADB_DEBUG: Добавлена операция PresetDelete: rowIndex=$rowIndex, preset=${preset.label}")
    }
    
    fun addPresetMove(fromIndex: Int, toIndex: Int) {
        addOperation(HistoryOperation.PresetMove(fromIndex, toIndex))
        println("ADB_DEBUG: Добавлена операция PresetMove: from=$fromIndex, to=$toIndex")
    }
    
    fun addPresetImport(startIndex: Int, presets: List<io.github.qavlad.adbrandomizer.services.DevicePreset>) {
        addOperation(HistoryOperation.PresetImport(startIndex, presets))
        println("ADB_DEBUG: Добавлена операция PresetImport: startIndex=$startIndex, count=${presets.size}")
    }
    
    fun addPresetDuplicate(originalIndex: Int, duplicateIndex: Int, preset: io.github.qavlad.adbrandomizer.services.DevicePreset) {
        addOperation(HistoryOperation.PresetDuplicate(originalIndex, duplicateIndex, preset))
        println("ADB_DEBUG: Добавлена операция PresetDuplicate: original=$originalIndex, duplicate=$duplicateIndex, preset=${preset.label}")
    }
    
    fun undoLast(): HistoryOperation? {
        println("ADB_DEBUG: undoLast called - история содержит ${historyStack.size} записей")
        
        if (historyStack.isNotEmpty()) {
            val lastOperation = historyStack.removeAt(historyStack.size - 1)
            redoStack.add(lastOperation) // Добавляем в redo стек
            
            println("ADB_DEBUG: Отмена операции: ${lastOperation::class.simpleName}")
            return lastOperation
        } else {
            println("ADB_DEBUG: История пуста - нет операций для отмены")
        }
        return null
    }
    
    fun redoLast(): HistoryOperation? {
        println("ADB_DEBUG: redoLast called - redo стек содержит ${redoStack.size} записей")
        
        if (redoStack.isNotEmpty()) {
            val lastOperation = redoStack.removeAt(redoStack.size - 1)
            addOperation(lastOperation, clearRedo = false) // Добавляем обратно, но не очищаем redo стек
            
            println("ADB_DEBUG: Повтор операции: ${lastOperation::class.simpleName}")
            return lastOperation
        } else {
            println("ADB_DEBUG: Redo стек пуст - нет операций для повтора")
        }
        return null
    }
    
    fun findCellCoordinates(cellId: CellIdentity): Pair<Int, Int>? {
        return findCellById(cellId)
    }
    
    @Suppress("unused")
    fun getLastOperation(): HistoryOperation? = historyStack.lastOrNull()
    
    fun isEmpty(): Boolean = historyStack.isEmpty()
    
    fun size(): Int = historyStack.size
    
    @Suppress("unused")
    fun clear() {
        historyStack.clear()
        redoStack.clear()
        cellIdMap.clear()
    }
    
    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        println("ADB_DEBUG: Row moved from $fromIndex to $toIndex - updating cellIdMap")
        
        val newCellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
        
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
        
        println("ADB_DEBUG: cellIdMap updated after row move")
    }
    
    private fun findCellById(cellId: CellIdentity): Pair<Int, Int>? {
        val coords = cellIdMap.entries.find { it.value == cellId }?.key
        println("ADB_DEBUG: Finding cell by ID ${cellId.id.substring(0, 8)}... -> $coords")
        return coords
    }
}