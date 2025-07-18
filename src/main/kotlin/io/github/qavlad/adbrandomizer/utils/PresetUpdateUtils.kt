package io.github.qavlad.adbrandomizer.utils

import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Утилиты для обновления списков пресетов
 */
object PresetUpdateUtils {
    
    /**
     * Обновляет список пресетов с учетом видимых индексов
     * 
     * @param originalPresets Оригинальный список пресетов
     * @param visibleIndices Индексы видимых элементов
     * @param updatedPresets Обновленные пресеты из таблицы
     * @return Новый список пресетов с обновленными видимыми элементами
     */
    fun updatePresetsWithVisibleIndices(
        originalPresets: List<DevicePreset>,
        visibleIndices: List<Int>,
        updatedPresets: List<DevicePreset>
    ): List<DevicePreset> {
        val newPresets = mutableListOf<DevicePreset>()
        var updatedIndex = 0
        
        originalPresets.forEachIndexed { index, originalPreset ->
            if (visibleIndices.contains(index) && updatedIndex < updatedPresets.size) {
                // Это был видимый пресет - берем обновленную версию
                newPresets.add(updatedPresets[updatedIndex])
                updatedIndex++
            } else {
                // Это был скрытый дубликат - сохраняем как есть
                newPresets.add(originalPreset)
            }
        }
        
        // Добавляем оставшиеся обновленные пресеты, если они есть
        while (updatedIndex < updatedPresets.size) {
            newPresets.add(updatedPresets[updatedIndex])
            updatedIndex++
        }
        
        return newPresets
    }
}