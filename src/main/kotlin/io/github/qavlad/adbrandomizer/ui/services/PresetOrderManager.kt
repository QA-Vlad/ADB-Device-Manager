package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.services.SettingsService

/**
 * Менеджер для управления порядком пресетов в разных режимах отображения.
 * Обеспечивает независимое сохранение порядка для режимов Show All и обычного режима.
 */
class PresetOrderManager {
    
    companion object {
        private const val NORMAL_MODE_ORDER_PREFIX = "NORMAL_MODE_ORDER_"
        private const val SHOW_ALL_HIDE_DUPLICATES_ORDER = "SHOW_ALL_HIDE_DUPLICATES_ORDER"
        private const val SHOW_ALL_FIXED_ORDER = "SHOW_ALL_FIXED_ORDER"
        
        /**
         * Генерирует ключи для пресетов с учетом дубликатов
         */
        fun generatePresetKeys(listName: String, presets: List<DevicePreset>): List<String> {
            // Группируем пресеты по ключу для определения дубликатов
            val presetGroups = mutableMapOf<String, MutableList<DevicePreset>>()
            presets.forEach { preset ->
                val key = "${preset.label}::${preset.size}::${preset.dpi}"
                presetGroups.getOrPut(key) { mutableListOf() }.add(preset)
            }
            
            // Генерируем ключи с индексами для дубликатов
            return presets.map { preset ->
                val key = "${preset.label}::${preset.size}::${preset.dpi}"
                val group = presetGroups[key] ?: listOf()
                if (group.size > 1) {
                    // Есть дубликаты - добавляем индекс
                    val indexInGroup = group.indexOf(preset)
                    "${listName}::${preset.label}::${preset.size}::${preset.dpi}::$indexInGroup"
                } else {
                    // Нет дубликатов - используем обычный формат
                    "${listName}::${preset.label}::${preset.size}::${preset.dpi}"
                }
            }
        }
    }
    
    /**
     * Сохраняет порядок пресетов для текущего списка в обычном режиме
     */
    fun saveNormalModeOrder(listId: String, presets: List<DevicePreset>) {
        // Используем комбинацию label, size и dpi для уникальной идентификации
        val order = presets.map { "${it.label}|${it.size}|${it.dpi}" }
        val key = "$NORMAL_MODE_ORDER_PREFIX$listId"
        println("ADB_DEBUG: PresetOrderManager.saveNormalModeOrder - listId: $listId, order size: ${order.size}")
        order.forEachIndexed { index, item ->
            println("ADB_DEBUG:   [$index] $item")
        }
        SettingsService.setStringList(key, order)
    }

    /**
     * Сохраняет порядок для режима Show All (без скрытия дубликатов)
     */
    fun saveShowAllModeOrder(allPresets: List<Pair<String, DevicePreset>>) {
        // Группируем пресеты по списку и ключу для определения дубликатов
        val listGroups = allPresets.groupBy { it.first }
        val order = mutableListOf<String>()
        
        listGroups.forEach { (listName, presetsInList) ->
            val presetGroups = mutableMapOf<String, MutableList<Int>>()
            presetsInList.forEachIndexed { index, (_, preset) ->
                val key = "${preset.label}::${preset.size}::${preset.dpi}"
                presetGroups.getOrPut(key) { mutableListOf() }.add(index)
            }
            
            presetsInList.forEachIndexed { index, (_, preset) ->
                val key = "${preset.label}::${preset.size}::${preset.dpi}"
                val group = presetGroups[key] ?: listOf()
                
                if (group.size > 1) {
                    // Есть дубликаты - добавляем индекс
                    val indexInGroup = group.indexOf(index)
                    order.add("${listName}::${preset.label}::${preset.size}::${preset.dpi}::$indexInGroup")
                } else {
                    // Нет дубликатов - используем обычный формат
                    order.add("${listName}::${preset.label}::${preset.size}::${preset.dpi}")
                }
            }
        }
        
        PresetListService.saveShowAllPresetsOrder(order)
    }
    
    /**
     * Сохраняет порядок для режима Show All со скрытыми дубликатами
     */
    fun saveShowAllHideDuplicatesOrder(visiblePresets: List<Pair<String, DevicePreset>>) {
        val order = visiblePresets.map { "${it.first}::${it.second.label}::${it.second.size}::${it.second.dpi}" }
        SettingsService.setStringList(SHOW_ALL_HIDE_DUPLICATES_ORDER, order)
    }
    
    /**
     * Получает сохраненный порядок для режима Show All со скрытыми дубликатами
     */
    fun getShowAllHideDuplicatesOrder(): List<String>? {
        val order = SettingsService.getStringList(SHOW_ALL_HIDE_DUPLICATES_ORDER)
        return order.ifEmpty { null }
    }

    /**
     * Проверяет, был ли зафиксирован порядок для режима Show All
     */
    fun hasFixedShowAllOrder(): Boolean {
        return SettingsService.getStringList(SHOW_ALL_FIXED_ORDER).isNotEmpty()
    }
    
    /**
     * Фиксирует текущий порядок пресетов для режима Show All
     * Это делается один раз при первом формировании Show All
     */
    fun fixShowAllOrder(tempLists: Map<String, PresetList>) {
        if (hasFixedShowAllOrder()) {
            return // Уже зафиксирован
        }
        
        val fixedOrder = mutableListOf<String>()
        tempLists.values.forEach { list ->
            fixedOrder.addAll(generatePresetKeys(list.name, list.presets))
        }
        
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
    }
    
    /**
     * Получает зафиксированный порядок для режима Show All
     */
    fun getFixedShowAllOrder(): List<String> {
        return SettingsService.getStringList(SHOW_ALL_FIXED_ORDER)
    }
    
    /**
     * Удаляет пресет из фиксированного порядка
     */
    fun removeFromFixedOrder(listName: String, preset: DevicePreset) {
        val fixedOrder = getFixedShowAllOrder().toMutableList()
        val keyToRemove = "${listName}::${preset.label}::${preset.size}::${preset.dpi}"
        fixedOrder.remove(keyToRemove)
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
    }
    
    /**
     * Добавляет пресет в фиксированный порядок после указанного пресета
     */
    fun addToFixedOrder(listName: String, preset: DevicePreset, afterPreset: DevicePreset?) {
        val fixedOrder = getFixedShowAllOrder().toMutableList()
        val newKey = "${listName}::${preset.label}::${preset.size}::${preset.dpi}"
        
        // Проверяем, нет ли уже такого ключа в списке
        if (fixedOrder.contains(newKey)) {
            println("ADB_DEBUG: PresetOrderManager.addToFixedOrder - key already exists: $newKey")
            return
        }
        
        if (afterPreset != null) {
            // Ищем позицию пресета, после которого нужно вставить новый
            val afterKey = "${listName}::${afterPreset.label}::${afterPreset.size}::${afterPreset.dpi}"
            val index = fixedOrder.indexOf(afterKey)
            if (index >= 0) {
                fixedOrder.add(index + 1, newKey)
            } else {
                // Если не нашли, добавляем в конец списка этого листа
                val lastIndexOfList = fixedOrder.indexOfLast { it.startsWith("${listName}::") }
                if (lastIndexOfList >= 0) {
                    fixedOrder.add(lastIndexOfList + 1, newKey)
                } else {
                    fixedOrder.add(newKey)
                }
            }
        } else {
            // Добавляем в конец
            fixedOrder.add(newKey)
        }
        
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
    }
    
    /**
     * Обновляет фиксированный порядок для режима Show All после drag & drop
     */
    fun updateFixedShowAllOrder(allPresets: List<Pair<String, DevicePreset>>) {
        // Группируем пресеты по списку
        val listGroups = allPresets.groupBy { it.first }
        val fixedOrder = mutableListOf<String>()
        
        listGroups.forEach { (listName, presetsInList) ->
            val presets = presetsInList.map { it.second }
            fixedOrder.addAll(generatePresetKeys(listName, presets))
        }
        
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
        println("ADB_DEBUG: PresetOrderManager.updateFixedShowAllOrder - updated fixed order with ${fixedOrder.size} items")
    }

}