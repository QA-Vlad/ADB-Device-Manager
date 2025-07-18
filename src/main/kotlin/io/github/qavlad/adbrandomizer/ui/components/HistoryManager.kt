package io.github.qavlad.adbrandomizer.ui.components

import java.util.*

data class CellIdentity(val id: String) {
    companion object {
        fun generate(): CellIdentity = CellIdentity(UUID.randomUUID().toString())
    }
}

/**
 * Базовые функции для работы с ячейками, используемые в менеджерах истории
 */
object CellManagerUtils {
    
    /**
     * Обновляет карту идентификаторов ячеек при перемещении строки
     */
    fun updateCellIdMapOnRowMove(
        cellIdMap: MutableMap<Pair<Int, Int>, CellIdentity>,
        fromIndex: Int, 
        toIndex: Int
    ) {
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
    }
    
    /**
     * Ищет координаты ячейки по идентификатору
     */
    fun findCellCoordinates(
        cellIdMap: Map<Pair<Int, Int>, CellIdentity>,
        cellId: CellIdentity
    ): Pair<Int, Int>? {
        return cellIdMap.entries.find { it.value == cellId }?.key
    }
}