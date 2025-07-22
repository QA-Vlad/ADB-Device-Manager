package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory

/**
 * Отслеживает состояние таблицы пресетов и предоставляет актуальные позиции пресетов
 */
object TableStateTracker {
    private val presetPositions = mutableMapOf<String, Int>()
    private var isTableUpdateInProgress = false
    
    /**
     * Обновляет состояние на основе текущей модели таблицы
     */
    fun updateTableState(tableModel: DevicePresetTableModel) {
        println("ADB_DEBUG: TableStateTracker.updateTableState called")
        if (isTableUpdateInProgress) {
            println("ADB_DEBUG: TableStateTracker.updateTableState - skipping, update already in progress")
            return
        }
        
        isTableUpdateInProgress = true
        try {
            presetPositions.clear()
            
            var visibleRow = 1
            println("ADB_DEBUG: TableStateTracker - processing ${tableModel.rowCount} rows")
            for (row in 0 until tableModel.rowCount) {
                val preset = tableModel.getPresetAt(row)
                if (preset != null) {
                    val key = getPresetKey(preset)
                    presetPositions[key] = visibleRow
                    println("ADB_DEBUG: TableStateTracker - mapped preset '$key' to position $visibleRow")
                    visibleRow++
                    
                    PluginLogger.trace(LogCategory.TABLE_OPERATIONS, "TableStateTracker: Preset at position %d: %s", visibleRow - 1, key)
                }
            }
            
            PluginLogger.debug(LogCategory.TABLE_OPERATIONS, "TableStateTracker: Updated state with %d visible presets", presetPositions.size)
        } finally {
            isTableUpdateInProgress = false
        }
    }
    
    /**
     * Получает актуальную позицию пресета в таблице
     */
    fun getPresetPosition(preset: DevicePreset): Int? {
        val key = getPresetKey(preset)
        val position = presetPositions[key]
        
        println("ADB_DEBUG: TableStateTracker.getPresetPosition - key: '$key', position: ${position ?: "not found"}")
        println("ADB_DEBUG: TableStateTracker - current positions map size: ${presetPositions.size}")
        
        // Выведем все текущие позиции для отладки
        println("ADB_DEBUG: TableStateTracker - all current positions:")
        presetPositions.entries.sortedBy { it.value }.forEach { (k, v) ->
            println("ADB_DEBUG:   Position $v: $k")
        }
        
        PluginLogger.debug(LogCategory.TABLE_OPERATIONS, "TableStateTracker: Getting position for %s: %s", key, position ?: "not found")
        
        return position
    }
    
    /**
     * Создает уникальный ключ для пресета
     */
    private fun getPresetKey(preset: DevicePreset): String {
        return "${preset.label}::${preset.size}::${preset.dpi}"
    }
    
    /**
     * Очищает состояние
     */
    fun clear() {
        presetPositions.clear()
        PluginLogger.debug(LogCategory.TABLE_OPERATIONS, "TableStateTracker: State cleared")
    }
}