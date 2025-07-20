package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList

/**
 * Менеджер для управления снимками состояний списков пресетов
 * Отвечает за создание и восстановление снимков при различных операциях
 */
class SnapshotManager(
    private val duplicateManager: DuplicateManager
) {
    
    /**
     * Сохраняет снимки видимых пресетов для всех списков
     * Используется при переключении в режим скрытия дубликатов
     */
    fun saveVisiblePresetsSnapshotForAllLists(tempPresetLists: Map<String, PresetList>) {
        println("ADB_DEBUG: saveVisiblePresetsSnapshotForAllLists - start")
        
        duplicateManager.clearSnapshots()
        
        tempPresetLists.forEach { (_, list) ->
            createSnapshotForList(list)
        }
        
        println("ADB_DEBUG: saveVisiblePresetsSnapshotForAllLists - done, saved ${tempPresetLists.size} lists")
    }
    
    /**
     * Сохраняет снимок видимых пресетов для текущего списка
     * Используется при переключении режима скрытия дубликатов в обычном режиме
     */
    fun saveVisiblePresetsSnapshot(currentPresetList: PresetList?) {
        if (currentPresetList == null) return
        
        println("ADB_DEBUG: saveVisiblePresetsSnapshot - start for list ${currentPresetList.name}")
        
        createSnapshotForList(currentPresetList)
        
        println("ADB_DEBUG: saveVisiblePresetsSnapshot - done")
    }
    
    /**
     * Создает снимок для одного списка
     */
    private fun createSnapshotForList(list: PresetList) {
        val duplicateIndices = duplicateManager.findDuplicateIndices(list.presets)
        val visiblePresetKeys = mutableListOf<String>()
        val allPresetKeys = mutableListOf<String>()
        
        list.presets.forEachIndexed { index, preset ->
            val key = "${preset.label}|${preset.size}|${preset.dpi}"
            allPresetKeys.add(key)
            
            // В снимок видимых добавляем только если это не дубликат
            if (!duplicateIndices.contains(index)) {
                visiblePresetKeys.add(key)
            }
        }
        
        // Сохраняем оба снимка для списка
        duplicateManager.updateSnapshot(list.name, visiblePresetKeys, allPresetKeys)
        
        println("ADB_DEBUG:   List ${list.name}: visible=${visiblePresetKeys.size}, total=${allPresetKeys.size}")
    }
    
    /**
     * Создает снимок оригинального состояния всех списков
     * Используется для возможности отката изменений при Cancel
     */
    fun saveOriginalState(tempPresetLists: Map<String, PresetList>): Map<String, PresetList> {
        val originalPresetLists = mutableMapOf<String, PresetList>()
        
        tempPresetLists.forEach { (key, list) ->
            // Сохраняем ID оригинального списка
            originalPresetLists[key] = PresetList(
                id = list.id,
                name = list.name,
                isDefault = list.isDefault
            ).apply {
                list.presets.forEach { preset ->
                    presets.add(preset.copy())
                }
            }
        }
        
        return originalPresetLists
    }
    
    /**
     * Восстанавливает состояние списков из снимка
     */
    fun restoreSnapshots(
        tempPresetLists: MutableMap<String, PresetList>,
        snapshots: Map<String, PresetList>
    ) {
        tempPresetLists.clear()
        snapshots.forEach { (key, snapshotList) ->
            // Восстанавливаем с оригинальным ID
            tempPresetLists[key] = PresetList(
                id = snapshotList.id,
                name = snapshotList.name,
                isDefault = snapshotList.isDefault
            ).apply {
                snapshotList.presets.forEach { preset ->
                    presets.add(preset.copy())
                }
            }
        }
    }
}