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
    // Храним исходный порядок из файлов для каждого списка
    private val originalFileOrder = mutableMapOf<String, List<String>>()
    
    // Храним порядок в памяти для обычного режима (после drag & drop)
    private val normalModeOrderInMemory = mutableMapOf<String, List<String>>()
    
    companion object {
        private const val NORMAL_MODE_ORDER_PREFIX = "NORMAL_MODE_ORDER_"
        private const val SHOW_ALL_HIDE_DUPLICATES_ORDER = "SHOW_ALL_HIDE_DUPLICATES_ORDER"
        private const val SHOW_ALL_FIXED_ORDER = "SHOW_ALL_FIXED_ORDER"

    }
    
    /**
     * Сохраняет порядок пресетов для текущего списка в обычном режиме
     */
    fun saveNormalModeOrder(listId: String, presets: List<DevicePreset>) {
        // Используем ID для уникальной идентификации пресетов
        val order = presets.map { it.id }
        val key = "$NORMAL_MODE_ORDER_PREFIX$listId"
        println("ADB_DEBUG: PresetOrderManager.saveNormalModeOrder - listId: $listId, order size: ${order.size}")
        order.forEachIndexed { index, item ->
            println("ADB_DEBUG:   [$index] $item")
        }
        SettingsService.setStringList(key, order)
    }

    /**
     * Получает сохранённый порядок для обычного режима
     */
    fun getNormalModeOrder(listId: String): List<String>? {
        val key = "$NORMAL_MODE_ORDER_PREFIX$listId"
        val order = SettingsService.getStringList(key)
        return order.ifEmpty { null }
    }

    /**
     * Сохраняет порядок для режима Show All (без скрытия дубликатов)
     */
    fun saveShowAllModeOrder(allPresets: List<Pair<String, DevicePreset>>) {
        // Используем ID пресетов для сохранения порядка
        val order = allPresets.map { (listName, preset) ->
            "${listName}::${preset.id}"
        }
        
        println("ADB_DEBUG: PresetOrderManager.saveShowAllModeOrder - saving ${order.size} items:")
        order.take(5).forEachIndexed { index, key ->
            println("ADB_DEBUG:   [$index] $key")
        }
        if (order.size > 5) {
            println("ADB_DEBUG:   ... and ${order.size - 5} more items")
        }
        
        PresetListService.saveShowAllPresetsOrder(order)
    }
    
    /**
     * Сохраняет порядок для режима Show All со скрытыми дубликатами
     */
    fun saveShowAllHideDuplicatesOrder(visiblePresets: List<Pair<String, DevicePreset>>) {
        val order = visiblePresets.map { "${it.first}::${it.second.id}" }
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
            // Используем ID для фиксации порядка
            list.presets.forEach { preset ->
                fixedOrder.add("${list.name}::${preset.id}")
            }
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
        val keyToRemove = "${listName}::${preset.id}"
        fixedOrder.remove(keyToRemove)
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
    }
    
    /**
     * Добавляет пресет в фиксированный порядок после указанного пресета
     */
    fun addToFixedOrder(listName: String, preset: DevicePreset, afterPreset: DevicePreset?) {
        val fixedOrder = getFixedShowAllOrder().toMutableList()
        val newKey = "${listName}::${preset.id}"
        
        // Проверяем, нет ли уже такого ключа в списке
        if (fixedOrder.contains(newKey)) {
            println("ADB_DEBUG: PresetOrderManager.addToFixedOrder - key already exists: $newKey")
            return
        }
        
        if (afterPreset != null) {
            // Ищем позицию пресета, после которого нужно вставить новый
            val afterKey = "${listName}::${afterPreset.id}"
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
        // Используем ID для обновления фиксированного порядка
        val fixedOrder = allPresets.map { (listName, preset) ->
            "${listName}::${preset.id}"
        }
        
        SettingsService.setStringList(SHOW_ALL_FIXED_ORDER, fixedOrder)
        println("ADB_DEBUG: PresetOrderManager.updateFixedShowAllOrder - updated fixed order with ${fixedOrder.size} items")
    }

    /**
     * Сохраняет исходный порядок пресетов из файла для списка
     */
    fun saveOriginalFileOrder(listId: String, presets: List<DevicePreset>) {
        val order = presets.map { it.id }
        originalFileOrder[listId] = order
        println("ADB_DEBUG: PresetOrderManager.saveOriginalFileOrder - saved original order for listId: $listId, size: ${order.size}")
    }
    
    /**
     * Получает исходный порядок пресетов из файла для списка
     */
    fun getOriginalFileOrder(listId: String): List<String>? {
        return originalFileOrder[listId]
    }
    
    /**
     * Очищает все исходные порядки (при закрытии диалога)
     */
    fun clearOriginalFileOrders() {
        originalFileOrder.clear()
        normalModeOrderInMemory.clear()
        println("ADB_DEBUG: PresetOrderManager.clearOriginalFileOrders - cleared all original file orders and in-memory orders")
    }
    
    /**
     * Обновляет порядок пресетов в памяти для обычного режима
     */
    fun updateNormalModeOrderInMemory(listId: String, presets: List<DevicePreset>) {
        val order = presets.map { it.id }
        normalModeOrderInMemory[listId] = order
        println("ADB_DEBUG: PresetOrderManager.updateNormalModeOrderInMemory - listId: $listId, order size: ${order.size}")
    }
    
    /**
     * Получает порядок из памяти для обычного режима
     */
    fun getNormalModeOrderInMemory(listId: String): List<String>? {
        return normalModeOrderInMemory[listId]
    }
    
    /**
     * Получает все ключи списков с порядком в памяти
     */
    fun getNormalModeOrderInMemory(): Map<String, List<String>> {
        return normalModeOrderInMemory.toMap()
    }

}