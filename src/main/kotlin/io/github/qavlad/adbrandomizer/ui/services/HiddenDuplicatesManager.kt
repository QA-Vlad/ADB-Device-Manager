package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetStorageService

/**
 * Управляет состоянием скрытых дубликатов в режиме Hide Duplicates.
 * 
 * ВАЖНО: Этот функционал довольно хрупкий, поэтому любые изменения должны 
 * тщательно тестироваться во всех режимах работы.
 */
class HiddenDuplicatesManager {
    // Хранение начального состояния скрытых дублей для каждого списка
    // Используется при открытии диалога для отслеживания изначально скрытых пресетов
    private val initialHiddenDuplicates = mutableMapOf<String, Set<String>>()
    
    // Хранение текущего состояния скрытых дублей для каждого списка
    // Обновляется при изменениях в таблице
    private val currentHiddenDuplicates = mutableMapOf<String, MutableSet<String>>()
    
    /**
     * Инициализирует начальное состояние скрытых дубликатов при загрузке диалога
     */
    fun initializeHiddenDuplicates(tempLists: Map<String, PresetList>) {
        clear()
        
        // ТОЛЬКО если режим Hide Duplicates уже включен при открытии диалога
        if (!PresetStorageService.getHideDuplicatesMode()) {
            println("ADB_DEBUG: Hide Duplicates mode is disabled, not tracking initial hidden duplicates")
            return
        }
        
        println("ADB_DEBUG: Hide Duplicates mode is already enabled, tracking initial hidden duplicates")
        
        tempLists.forEach { (listId, list) ->
            val hiddenIds = findHiddenDuplicateIds(list.presets)
            
            if (hiddenIds.isNotEmpty()) {
                initialHiddenDuplicates[listId] = hiddenIds
                currentHiddenDuplicates[listId] = hiddenIds.toMutableSet()
                println("ADB_DEBUG: List '${list.name}' has ${hiddenIds.size} initial hidden duplicates")
            }
        }
    }
    
    /**
     * Обновляет текущее состояние скрытых дублей на основе текущих данных
     */
    fun updateCurrentHiddenDuplicates(
        isHideDuplicatesMode: Boolean,
        tempLists: Map<String, PresetList>
    ) {
        if (!isHideDuplicatesMode) {
            return
        }
        
        currentHiddenDuplicates.clear()
        tempLists.forEach { (listId, list) ->
            val hiddenIds = findHiddenDuplicateIds(list.presets)
            
            if (hiddenIds.isNotEmpty()) {
                currentHiddenDuplicates[listId] = hiddenIds.toMutableSet()
            }
        }
    }
    
    /**
     * Находит ID всех дубликатов в списке пресетов
     */
    private fun findHiddenDuplicateIds(presets: List<DevicePreset>): Set<String> {
        val hiddenIds = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()
        
        presets.forEach { preset ->
            val key = preset.getDuplicateKey()
            if (seenKeys.contains(key)) {
                hiddenIds.add(preset.id)
            } else {
                seenKeys.add(key)
            }
        }
        
        return hiddenIds
    }
    
    /**
     * Возвращает карту скрытых дубликатов для использования в TableLoader
     * @return Карта начальных или текущих скрытых дубликатов в зависимости от состояния
     */
    fun getHiddenDuplicatesForTableLoader(): Map<String, Set<String>> {
        return if (currentHiddenDuplicates.isNotEmpty()) {
            // Если у нас есть текущее состояние скрытых дублей, используем его
            currentHiddenDuplicates.toMap()
        } else {
            // Иначе используем начальное состояние
            initialHiddenDuplicates
        }
    }
    
    /**
     * Возвращает только начальные скрытые дубликаты (для особых случаев)
     */
    fun getInitialHiddenDuplicates(): Map<String, Set<String>> = initialHiddenDuplicates
    
    /**
     * Очищает все данные о скрытых дубликатах
     */
    fun clear() {
        initialHiddenDuplicates.clear()
        currentHiddenDuplicates.clear()
    }
}