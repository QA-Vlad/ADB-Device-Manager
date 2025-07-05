package io.github.qavlad.adbrandomizer.ui

import java.util.*

data class CellIdentity(val id: String) {
    companion object {
        fun generate(): CellIdentity = CellIdentity(UUID.randomUUID().toString())
    }
}

data class HistoryEntry(
    val cellId: CellIdentity,
    val oldValue: String,
    val newValue: String
)

class HistoryManager(private val maxHistorySize: Int = 50) {
    private val historyStack = mutableListOf<HistoryEntry>()
    private val cellIdMap = mutableMapOf<Pair<Int, Int>, CellIdentity>()
    
    fun getCellId(row: Int, column: Int): CellIdentity {
        val key = Pair(row, column)
        return cellIdMap.getOrPut(key) { CellIdentity.generate() }
    }
    
    fun addToHistory(row: Int, column: Int, oldValue: String, newValue: String) {
        if (oldValue != newValue) {
            val cellId = getCellId(row, column)
            historyStack.add(HistoryEntry(cellId, oldValue, newValue))
            
            if (historyStack.size > maxHistorySize) {
                historyStack.removeAt(0)
            }
            
            println("ADB_DEBUG: Добавлена запись в историю: ($row, $column) [${cellId.id.substring(0, 8)}...] '$oldValue' -> '$newValue'")
        }
    }
    
    fun undoLast(): HistoryEntry? {
        println("ADB_DEBUG: undoLastPaste called - история содержит ${historyStack.size} записей")
        
        if (historyStack.isNotEmpty()) {
            val lastEntry = historyStack.removeAt(historyStack.size - 1)
            
            val coords = findCellById(lastEntry.cellId)
            if (coords != null) {
                val (row, column) = coords
                println("ADB_DEBUG: Отмена операции: восстановлено '${lastEntry.oldValue}' в ячейку ($row, $column) [${lastEntry.cellId.id.substring(0, 8)}...]")
                return lastEntry
            } else {
                println("ADB_DEBUG: ОШИБКА: Ячейка с ID ${lastEntry.cellId.id.substring(0, 8)}... не найдена!")
            }
        } else {
            println("ADB_DEBUG: История пуста - нет операций для отмены")
        }
        return null
    }
    
    fun findCellCoordinates(cellId: CellIdentity): Pair<Int, Int>? {
        return findCellById(cellId)
    }
    
    @Suppress("unused")
    fun getLastEntry(): HistoryEntry? = historyStack.lastOrNull()
    
    fun isEmpty(): Boolean = historyStack.isEmpty()
    
    fun size(): Int = historyStack.size
    
    @Suppress("unused")
    fun clear() {
        historyStack.clear()
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