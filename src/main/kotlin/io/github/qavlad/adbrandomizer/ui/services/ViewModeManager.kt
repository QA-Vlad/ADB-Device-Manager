package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Менеджер для управления режимами отображения пресетов
 * Содержит утилитарные методы для работы с режимами Show All и Hide Duplicates
 */
class ViewModeManager(
    private val presetOrderManager: PresetOrderManager = PresetOrderManager()
) {
    
    /**
     * Подготавливает модель таблицы для режима Show All
     */
    fun prepareShowAllTableModel(
        tempPresetLists: Map<String, PresetList>,
        savedOrder: List<String>?
    ): List<Pair<String, DevicePreset>> {
        println("ADB_DEBUG: ViewModeManager.prepareShowAllTableModel called")
        println("ADB_DEBUG:   savedOrder size: ${savedOrder?.size ?: 0}")
        if (savedOrder != null && savedOrder.isNotEmpty()) {
            println("ADB_DEBUG:   First 5 items from savedOrder:")
            savedOrder.take(5).forEachIndexed { index, item ->
                println("ADB_DEBUG:     [$index] $item")
            }
        }
        
        val allPresets = mutableListOf<Pair<String, DevicePreset>>()
        
        // Приоритет: savedOrder (drag & drop) > fixedOrder > обычный порядок
        if (savedOrder != null && savedOrder.isNotEmpty()) {
            // Используем сохраненный порядок drag & drop (высший приоритет)
            savedOrder.forEach { key ->
                parsePresetKeyAndAdd(key, tempPresetLists, allPresets, supportOldFormat = true)
            }
            
            // Добавляем новые пресеты, которых нет в сохраненном порядке
            tempPresetLists.forEach { (_, list) ->
                list.presets.forEach { preset ->
                    val key = "${list.name}::${preset.label}::${preset.size}::${preset.dpi}"
                    if (!savedOrder.contains(key)) {
                        allPresets.add(list.name to preset)
                    }
                }
            }
        } else {
            // Если нет savedOrder, пытаемся использовать фиксированный порядок
            val fixedOrder = presetOrderManager.getFixedShowAllOrder()
            println("ADB_DEBUG:   fixedOrder size: ${fixedOrder.size}")
            
            if (fixedOrder.isNotEmpty()) {
                // Используем фиксированный порядок
                fixedOrder.forEach { key ->
                    parsePresetKeyAndAdd(key, tempPresetLists, allPresets)
                }
                
                // Добавляем новые пресеты, которых нет в фиксированном порядке
                tempPresetLists.forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        val key = "${list.name}::${preset.label}::${preset.size}::${preset.dpi}"
                        if (!fixedOrder.contains(key)) {
                            // Новый пресет - добавляем его после пресетов из того же списка
                            val lastIndexOfSameList = allPresets.indexOfLast { it.first == list.name }
                            if (lastIndexOfSameList >= 0) {
                                allPresets.add(lastIndexOfSameList + 1, list.name to preset)
                            } else {
                                allPresets.add(list.name to preset)
                            }
                        }
                    }
                }
            } else {
                // Обычный порядок - по спискам (и фиксируем его)
                tempPresetLists.forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        allPresets.add(list.name to preset)
                    }
                }
                
                // Фиксируем порядок при первом использовании
                if (!presetOrderManager.hasFixedShowAllOrder()) {
                    presetOrderManager.fixShowAllOrder(tempPresetLists)
                }
            }
        }
        
        return allPresets
    }
    
    /**
     * Парсит ключ пресета и добавляет его в список
     */
    private fun parsePresetKeyAndAdd(
        key: String,
        tempPresetLists: Map<String, PresetList>,
        allPresets: MutableList<Pair<String, DevicePreset>>,
        supportOldFormat: Boolean = false
    ) {
        val parts = key.split("::")
        if (parts.size >= 4) {
            val listName = parts[0]
            val label = parts[1]
            val size = parts[2]
            val dpi = parts[3]
            
            val list = tempPresetLists.values.find { it.name == listName }
            val preset = list?.presets?.find { p ->
                p.label == label && p.size == size && p.dpi == dpi
            }
            
            if (preset != null) {
                allPresets.add(listName to preset)
            }
        } else if (supportOldFormat && parts.size >= 2) {
            // Обратная совместимость со старым форматом
            val listName = parts[0]
            val label = parts[1]
            
            val list = tempPresetLists.values.find { it.name == listName }
            val preset = list?.presets?.find { p -> p.label == label }
            
            if (preset != null) {
                allPresets.add(listName to preset)
            }
        }
    }
}
