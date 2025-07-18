package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Менеджер для управления дубликатами пресетов
 * Отвечает за определение дубликатов и управление их видимостью
 */
class DuplicateManager {
    
    // Снимки видимых пресетов для каждого списка при включении режима скрытия дубликатов
    private val visiblePresetsSnapshot = mutableMapOf<String, List<String>>()
    
    // Полный порядок пресетов для каждого списка
    private val presetsOrderSnapshot = mutableMapOf<String, List<String>>()
    
    /**
     * Очищает все снимки
     */
    fun clearSnapshots() {
        visiblePresetsSnapshot.clear()
        presetsOrderSnapshot.clear()
    }
    
    /**
     * Проверяет, есть ли снимки
     */
    fun hasSnapshots(): Boolean = visiblePresetsSnapshot.isNotEmpty()
    
    /**
     * Получает размер снимков
     */
    fun getSnapshotsSize(): Int = visiblePresetsSnapshot.size
    
    /**
     * Получает все снимки для отладки
     */
    fun getSnapshotsDebugInfo(): Map<String, List<String>> = visiblePresetsSnapshot.toMap()
    
    /**
     * Находит индексы дубликатов в списке пресетов
     * Дубликатами считаются пресеты с одинаковыми size и dpi
     */
    fun findDuplicateIndices(presets: List<DevicePreset>): Set<Int> {
        val seen = mutableSetOf<String>()
        val duplicateIndices = mutableSetOf<Int>()
        
        presets.forEachIndexed { index, preset ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = "${preset.size}|${preset.dpi}"
                if (seen.contains(key)) {
                    duplicateIndices.add(index)
                } else {
                    seen.add(key)
                }
            }
        }
        
        return duplicateIndices
    }
    
    /**
     * Получает снимок видимых пресетов для списка
     */
    fun getVisibleSnapshot(listName: String): List<String>? = visiblePresetsSnapshot[listName]
    
    /**
     * Обновляет снимок после изменений
     */
    fun updateSnapshot(listName: String, updatedVisibleKeys: List<String>, fullOrder: List<String>? = null) {
        visiblePresetsSnapshot[listName] = updatedVisibleKeys
        if (fullOrder != null) {
            presetsOrderSnapshot[listName] = fullOrder
        }
        println("ADB_DEBUG: Updated snapshot for list $listName: visible=${updatedVisibleKeys.size}")
    }
}