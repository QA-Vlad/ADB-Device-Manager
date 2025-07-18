package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel

/**
 * Менеджер для управления временными списками пресетов.
 * Централизует всю логику работы с tempPresetLists.
 */
class TempListsManager {
    private val tempPresetLists = mutableMapOf<String, PresetList>()
    
    /**
     * Получить все временные списки
     */
    fun getTempLists(): Map<String, PresetList> = tempPresetLists
    
    /**
     * Получить изменяемые временные списки (для совместимости)
     */
    fun getMutableTempLists(): MutableMap<String, PresetList> = tempPresetLists
    
    /**
     * Получить временный список по ID
     */
    fun getTempList(id: String): PresetList? = tempPresetLists[id]
    
    /**
     * Установить временные списки
     */
    fun setTempLists(lists: Map<String, PresetList>) {
        tempPresetLists.clear()
        tempPresetLists.putAll(lists)
    }
    
    /**
     * Очистить все временные списки
     */
    fun clear() {
        tempPresetLists.clear()
    }
    
    /**
     * Синхронизировать изменения из таблицы с временными списками
     */
    fun syncTableChangesToTempLists(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?,
        isShowAllPresetsMode: Boolean
    ) {
        if (isShowAllPresetsMode) {
            syncInShowAllMode(tableModel)
        } else {
            syncInNormalMode(tableModel, currentPresetList)
        }
    }
    
    /**
     * Синхронизация в режиме Show All
     */
    private fun syncInShowAllMode(tableModel: DevicePresetTableModel) {
        println("ADB_DEBUG: syncTableChangesToTempLists - Show all mode")
        
        // Создаем карту для быстрого поиска списков по имени
        val listsByName = tempPresetLists.values.associateBy { it.name }
        
        // Проходим по всем строкам таблицы
        for (row in 0 until tableModel.rowCount) {
            val listName = tableModel.getValueAt(row, 0) as? String ?: continue
            
            // Пропускаем строки с "+"
            if (listName == "+") continue
            
            // Находим соответствующий список
            val list = listsByName[listName] ?: continue
            
            // Получаем индекс пресета в списке из скрытой колонки
            val presetIndex = tableModel.getValueAt(row, 5) as? Int ?: continue
            
            // Проверяем корректность индекса
            if (presetIndex >= 0 && presetIndex < list.presets.size) {
                val preset = list.presets[presetIndex]
                
                // Обновляем значения из таблицы
                preset.label = tableModel.getValueAt(row, 2) as? String ?: ""
                preset.size = tableModel.getValueAt(row, 3) as? String ?: ""
                preset.dpi = tableModel.getValueAt(row, 4) as? String ?: ""
            }
        }
    }
    
    /**
     * Синхронизация в обычном режиме
     */
    private fun syncInNormalMode(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?
    ) {
        currentPresetList?.let { list ->
            println("ADB_DEBUG: syncTableChangesToTempLists - Normal mode for list: ${list.name}")
            
            // Очищаем список и заполняем заново из таблицы
            list.presets.clear()
            
            for (row in 0 until tableModel.rowCount) {
                val label = tableModel.getValueAt(row, 2) as? String ?: ""
                val size = tableModel.getValueAt(row, 3) as? String ?: ""  
                val dpi = tableModel.getValueAt(row, 4) as? String ?: ""
                
                list.presets.add(DevicePreset(label, size, dpi))
            }
            
            println("ADB_DEBUG: Synced ${list.presets.size} presets to temp list")
        }
    }
    
    /**
     * Получить количество временных списков
     */
    fun size(): Int = tempPresetLists.size
    
    /**
     * Проверить, пусты ли временные списки
     */
    fun isEmpty(): Boolean = tempPresetLists.isEmpty()
    
    /**
     * Проверить, не пусты ли временные списки
     */
    fun isNotEmpty(): Boolean = tempPresetLists.isNotEmpty()
}