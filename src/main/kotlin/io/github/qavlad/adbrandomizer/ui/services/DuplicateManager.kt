package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel

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
    
    /**
     * Проверяет, изменился ли статус дубликатов в таблице
     * @return true если в таблице есть дубликаты, которые должны быть скрыты
     */
    fun checkIfDuplicateStatusChanged(
        tableModel: DevicePresetTableModel,
        isShowAllMode: Boolean,
        tempLists: Map<String, PresetList>,
        currentPresetList: PresetList?
    ): Boolean {
        return if (isShowAllMode) {
            checkDuplicatesInShowAllMode(tableModel, tempLists)
        } else {
            checkDuplicatesInNormalMode(tableModel, currentPresetList)
        }
    }
    
    private fun checkDuplicatesInShowAllMode(
        tableModel: DevicePresetTableModel,
        tempLists: Map<String, PresetList>
    ): Boolean {
        val allPresets = mutableListOf<DevicePreset>()
        tempLists.values.forEach { list ->
            allPresets.addAll(list.presets)
        }
        
        // Проверяем, есть ли в таблице элементы, которые должны быть скрыты как дубликаты
        val seenKeys = mutableSetOf<String>()
        for (row in 0 until tableModel.rowCount) {
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            if (firstColumn != "+") {
                val preset = tableModel.getPresetAt(row)
                if (preset != null && preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                    val key = createPresetKey(preset)
                    if (seenKeys.contains(key)) {
                        println("ADB_DEBUG: Found duplicate in table that should be hidden (Show all mode): $key")
                        return true
                    }
                    seenKeys.add(key)
                }
            }
        }
        
        // Также проверяем количество видимых строк
        val currentDuplicates = findDuplicateIndices(allPresets)
        val visibleRowCount = (0 until tableModel.rowCount).count { row ->
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            firstColumn != "+"
        }
        
        val expectedVisibleCount = allPresets.size - currentDuplicates.size
        return visibleRowCount != expectedVisibleCount
    }
    
    private fun checkDuplicatesInNormalMode(
        tableModel: DevicePresetTableModel,
        currentPresetList: PresetList?
    ): Boolean {
        currentPresetList?.let { list ->
            val currentDuplicates = findDuplicateIndices(list.presets)
            
            val seenKeys = mutableSetOf<String>()
            for (row in 0 until tableModel.rowCount) {
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                if (firstColumn != "+") {
                    val preset = tableModel.getPresetAt(row)
                    if (preset != null && preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        val key = createPresetKey(preset)
                        if (seenKeys.contains(key)) {
                            println("ADB_DEBUG: Found duplicate in table that should be hidden: $key")
                            return true
                        }
                        seenKeys.add(key)
                    }
                }
            }
            
            val visibleRowCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }
            
            val expectedVisibleCount = list.presets.size - currentDuplicates.size
            return visibleRowCount != expectedVisibleCount
        }
        return false
    }
    
    /**
     * Находит глобальные дубликаты во всех списках
     * @return множество ключей дубликатов
     */
    fun findGlobalDuplicateKeys(allPresets: List<Pair<String, DevicePreset>>): Set<String> {
        val keyCount = mutableMapOf<String, Int>()
        
        allPresets.forEach { (_, preset) ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = createPresetKey(preset)
                keyCount[key] = keyCount.getOrDefault(key, 0) + 1
            }
        }
        
        return keyCount.filter { it.value > 1 }.keys.toSet()
    }
    
    /**
     * Находит видимые индексы пресетов (исключая дубликаты)
     */
    fun findVisibleIndices(presets: List<DevicePreset>): List<Int> {
        val visibleIndices = mutableListOf<Int>()
        val seenKeys = mutableSetOf<String>()
        
        presets.forEachIndexed { index, preset ->
            val key = createPresetKey(preset, index)
            
            if (!seenKeys.contains(key)) {
                seenKeys.add(key)
                visibleIndices.add(index)
            }
        }
        
        return visibleIndices
    }
    
    /**
     * Создает уникальный ключ для пресета
     */
    fun createPresetKey(preset: DevicePreset, index: Int? = null): String {
        return if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
            "${preset.size}|${preset.dpi}"
        } else {
            "unique_${index ?: System.currentTimeMillis()}"
        }
    }
}