package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Менеджер для управления режимами отображения пресетов
 * Содержит утилитарные методы для работы с режимами Show All и Hide Duplicates
 */
class ViewModeManager {
    
    /**
     * Подготавливает модель таблицы для режима Show All
     */
    fun prepareShowAllTableModel(
        tempPresetLists: Map<String, PresetList>,
        savedOrder: List<String>?
    ): List<Pair<String, DevicePreset>> {
        val allPresets = mutableListOf<Pair<String, DevicePreset>>()
        
        if (savedOrder != null && savedOrder.isNotEmpty()) {
            // Используем сохраненный порядок
            savedOrder.forEach { key ->
                val parts = key.split(":")
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
                }
            }
            
            // Добавляем новые пресеты, которых нет в сохраненном порядке
            tempPresetLists.forEach { (_, list) ->
                list.presets.forEach { preset ->
                    val key = "${list.name}:${preset.label}:${preset.size}:${preset.dpi}"
                    if (!savedOrder.contains(key)) {
                        allPresets.add(list.name to preset)
                    }
                }
            }
        } else {
            // Обычный порядок - по спискам
            tempPresetLists.forEach { (_, list) ->
                list.presets.forEach { preset ->
                    allPresets.add(list.name to preset)
                }
            }
        }
        
        return allPresets
    }
}
