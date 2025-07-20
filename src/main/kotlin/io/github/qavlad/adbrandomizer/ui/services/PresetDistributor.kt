package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel

/**
 * Сервис для распределения пресетов из таблицы по временным спискам
 * Обрабатывает сложную логику синхронизации в режиме Show All с учетом скрытых дубликатов
 */
class PresetDistributor(
    private val duplicateManager: DuplicateManager
) {
    
    /**
     * Распределяет пресеты из таблицы по временным спискам в режиме Show all presets
     */
    fun distributePresetsToTempLists(
        tableModel: DevicePresetTableModel,
        tempPresetLists: MutableMap<String, PresetList>,
        isHideDuplicatesMode: Boolean,
        getListNameAtRow: (Int) -> String?,
        saveVisiblePresetsSnapshotForAllLists: () -> Unit
    ) {
        println("ADB_DEBUG: Distributing presets to temp lists...")
        println("ADB_DEBUG:   isHideDuplicatesMode: $isHideDuplicatesMode")
        println("ADB_DEBUG:   tableModel row count: ${tableModel.rowCount}")

        if (isHideDuplicatesMode) {
            // Используем специальную логику для режима скрытия дубликатов
            distributeWithHiddenDuplicates(tableModel, tempPresetLists, getListNameAtRow, saveVisiblePresetsSnapshotForAllLists)
        } else {
            // В обычном режиме используем простое распределение
            distributeNormal(tableModel, tempPresetLists, getListNameAtRow)
        }
        println("ADB_DEBUG: Distribution finished.")
        tempPresetLists.forEach { (id, list) ->
            println("ADB_DEBUG:   List ${list.name} ($id) now has ${list.presets.size} presets.")
        }
    }
    
    /**
     * Распределяет пресеты с учетом скрытых дубликатов
     */
    private fun distributeWithHiddenDuplicates(
        tableModel: DevicePresetTableModel,
        tempPresetLists: MutableMap<String, PresetList>,
        getListNameAtRow: (Int) -> String?,
        saveVisiblePresetsSnapshotForAllLists: () -> Unit
    ) {
        // Проверяем наличие снимка и создаем его при необходимости
        if (!duplicateManager.hasSnapshots()) {
            println("ADB_DEBUG: distributePresetsToTempLists - snapshot is empty, creating from current state")
            saveVisiblePresetsSnapshotForAllLists()
        }

        // В режиме скрытия дубликатов используем снимок видимых пресетов
        // Сначала определяем какие пресеты должны остаться видимыми
        val listVisibleIndices = mutableMapOf<String, MutableList<Int>>()

        tempPresetLists.forEach { (listId, list) ->
            val visibleIndices = mutableListOf<Int>()
            val seenKeys = mutableSetOf<String>()

            list.presets.forEachIndexed { index, preset ->
                val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                    "${preset.size}|${preset.dpi}"
                } else {
                    "unique_${listId}_$index"
                }

                if (!seenKeys.contains(key)) {
                    seenKeys.add(key)
                    visibleIndices.add(index)
                }
            }

            listVisibleIndices[list.name] = visibleIndices
        }

        // Сохраняем копии оригинальных списков
        val originalLists = mutableMapOf<String, List<DevicePreset>>()
        tempPresetLists.forEach { (_, list) ->
            originalLists[list.name] = list.presets.map { it.copy() }
        }

        // Собираем обновленные данные из таблицы по спискам
        val updatedPresetsPerList = collectUpdatedPresetsFromTable(tableModel, getListNameAtRow)

        // Обновляем каждый список, сохраняя скрытые элементы
        tempPresetLists.forEach { (_, list) ->
            val originalPresets = originalLists[list.name] ?: emptyList()
            val visibleIndices = listVisibleIndices[list.name] ?: emptyList()

            // Используем снимок видимых пресетов если он есть
            val visibleSnapshot = duplicateManager.getVisibleSnapshot(list.name)
            val updatedPresets = updatedPresetsPerList[list.name] ?: emptyList()

            println("ADB_DEBUG: distributePresetsToTempLists - list: ${list.name}")
            println("ADB_DEBUG:   original presets: ${originalPresets.size}")
            println("ADB_DEBUG:   visible indices: $visibleIndices")
            println("ADB_DEBUG:   visible snapshot size: ${visibleSnapshot?.size ?: "none"}")
            if (visibleSnapshot != null) {
                println("ADB_DEBUG:   visible snapshot content:")
                visibleSnapshot.forEach { key ->
                    println("ADB_DEBUG:     - $key")
                }
            }
            println("ADB_DEBUG:   updated presets: ${updatedPresets.size}")

            // НЕ создаем новый список - будем обновлять существующий на месте
            // чтобы сохранить порядок элементов

            // Improved logic: properly handle deletions and updates
            if (visibleSnapshot != null) {
                updateListWithSnapshot(
                    list,
                    visibleSnapshot,
                    updatedPresets
                )
            } else {
                // Если снимка нет - обновляем список напрямую
                list.presets.clear()
                list.presets.addAll(updatedPresets)
            }
        }
    }
    
    /**
     * Обновляет список с использованием снимка видимых элементов
     */
    private fun updateListWithSnapshot(
        list: PresetList,
        visibleSnapshot: List<String>,
        updatedPresets: List<DevicePreset>
    ) {
        println("ADB_DEBUG:   Using snapshot-based update logic")

        // Step 1: Determine if elements were deleted
        val wasDeleted = updatedPresets.size < visibleSnapshot.size
        println("ADB_DEBUG:   Elements deleted: $wasDeleted (snapshot: ${visibleSnapshot.size}, updated: ${updatedPresets.size})")

        if (wasDeleted) {
            handleDeletionCase(list, visibleSnapshot, updatedPresets)
        } else {
            handleUpdateAddCase(list, visibleSnapshot, updatedPresets)
        }
    }
    
    /**
     * Обрабатывает случай удаления элементов
     */
    private fun handleDeletionCase(
        list: PresetList,
        visibleSnapshot: List<String>,
        updatedPresets: List<DevicePreset>
    ) {
        println("ADB_DEBUG:   Handling deletion case")

        // Create a map of current table presets for quick lookup
        val tablePresetsMap = updatedPresets.associateBy {
            "${it.label}|${it.size}|${it.dpi}"
        }

        // Build new list maintaining order and hidden elements
        val newPresets = mutableListOf<DevicePreset>()
        val processedTableKeys = mutableSetOf<String>()

        // First pass: preserve all elements that still exist in table or were hidden
        list.presets.forEach { preset ->
            val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"

            when {
                // Element exists in table - use updated version
                tablePresetsMap.containsKey(presetKey) -> {
                    newPresets.add(tablePresetsMap[presetKey]!!.copy())
                    processedTableKeys.add(presetKey)
                    println("ADB_DEBUG:   Preserved visible element: $presetKey")
                }
                // Element was hidden (not in visible snapshot) - preserve it
                !visibleSnapshot.contains(presetKey) -> {
                    newPresets.add(preset.copy())
                    println("ADB_DEBUG:   Preserved hidden element: $presetKey")
                }
                // Element was visible but now deleted - check if hidden duplicate should be revealed
                else -> {
                    // For empty presets, check if they're truly deleted or just not visible in the filtered view
                    val isEmptyPreset = preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
                    if (isEmptyPreset) {
                        // Empty presets should be preserved as they're unique and don't have duplicates
                        newPresets.add(preset.copy())
                        println("ADB_DEBUG:   Preserved empty preset: $presetKey")
                    } else {
                        println("ADB_DEBUG:   Element deleted from table: $presetKey")
                        // Check if there's a hidden duplicate that should be revealed
                        val presetCombination = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                            "${preset.size}|${preset.dpi}"
                        } else null

                        if (presetCombination != null) {
                        // Find first hidden duplicate with same size|dpi
                        val hiddenDuplicate = list.presets.find { p ->
                            val pKey = "${p.label}|${p.size}|${p.dpi}"
                            p.size.isNotBlank() && p.dpi.isNotBlank() &&
                                "${p.size}|${p.dpi}" == presetCombination &&
                                !visibleSnapshot.contains(pKey) &&
                                !newPresets.any { np ->
                                    "${np.label}|${np.size}|${np.dpi}" == pKey
                                }
                        }

                        if (hiddenDuplicate != null) {
                            val hiddenKey = "${hiddenDuplicate.label}|${hiddenDuplicate.size}|${hiddenDuplicate.dpi}"
                            println("ADB_DEBUG:   Found hidden duplicate to reveal: $hiddenKey")
                            // Hidden duplicate will be added when we process it in the loop
                        }
                    }
                    }
                }
            }
        }

        // Second pass: add any new elements from table that weren't processed
        updatedPresets.forEach { preset ->
            val key = "${preset.label}|${preset.size}|${preset.dpi}"
            if (!processedTableKeys.contains(key)) {
                newPresets.add(preset.copy())
                println("ADB_DEBUG:   Added new element: $key")
            }
        }

        // Replace the list
        list.presets.clear()
        list.presets.addAll(newPresets)
    }
    
    /**
     * Обрабатывает случай обновления/добавления элементов
     */
    private fun handleUpdateAddCase(
        list: PresetList,
        visibleSnapshot: List<String>,
        updatedPresets: List<DevicePreset>
    ) {
        println("ADB_DEBUG:   Handling update/add case")

        // Build a complete picture of what the list should contain
        val originalPresets = list.presets.map { it.copy() }
        val newListContent = mutableListOf<DevicePreset>()
        
        // Create a map of visible snapshot keys to their positions
        val snapshotPositions = visibleSnapshot.withIndex().associate { (index, key) -> key to index }
        
        println("ADB_DEBUG:   Original list has ${originalPresets.size} presets")
        println("ADB_DEBUG:   Visible snapshot has ${visibleSnapshot.size} entries")
        println("ADB_DEBUG:   Table has ${updatedPresets.size} entries")
        
        // Process each original preset
        originalPresets.forEach { originalPreset ->
            val originalKey = "${originalPreset.label}|${originalPreset.size}|${originalPreset.dpi}"
            
            when {
                // Case 1: Preset is in the visible snapshot
                snapshotPositions.containsKey(originalKey) -> {
                    val snapshotPos = snapshotPositions[originalKey]!!
                    // Check if this preset was edited (position in snapshot but different in table)
                    if (snapshotPos < updatedPresets.size) {
                        val tablePreset = updatedPresets[snapshotPos]
                        val tableKey = "${tablePreset.label}|${tablePreset.size}|${tablePreset.dpi}"
                        
                        if (tableKey != originalKey) {
                            // Check if this is a duplicate situation where table shows a different duplicate
                            val originalSizeDpi = "${originalPreset.size}|${originalPreset.dpi}"
                            val tableSizeDpi = "${tablePreset.size}|${tablePreset.dpi}"
                            
                            if (originalSizeDpi == tableSizeDpi && originalPreset.size.isNotBlank() && originalPreset.dpi.isNotBlank()) {
                                // This is a duplicate - the table is showing a different duplicate than snapshot expected
                                // Keep the original to maintain consistency
                                println("ADB_DEBUG:   Table shows different duplicate at position $snapshotPos: expected $originalKey, got $tableKey - keeping original")
                                newListContent.add(originalPreset)
                            } else {
                                // This preset was actually edited
                                println("ADB_DEBUG:   Preset edited at position $snapshotPos: $originalKey -> $tableKey")
                                newListContent.add(tablePreset.copy())
                            }
                        } else {
                            // Exact match - use the table version
                            println("ADB_DEBUG:   Exact match for visible preset: $originalKey")
                            newListContent.add(tablePreset.copy())
                        }
                    } else {
                        // Snapshot position out of bounds - keep original
                        println("ADB_DEBUG:   Keeping original (snapshot pos out of bounds): $originalKey")
                        newListContent.add(originalPreset)
                    }
                }
                
                // Case 2: Preset is not in visible snapshot (was hidden)
                !snapshotPositions.containsKey(originalKey) -> {
                    // This is a hidden preset - preserve it
                    println("ADB_DEBUG:   Preserving hidden preset: $originalKey")
                    newListContent.add(originalPreset)
                }
            }
        }
        
        // Add any completely new presets from the table
        updatedPresets.forEach { tablePreset ->
            val tableKey = "${tablePreset.label}|${tablePreset.size}|${tablePreset.dpi}"
            
            // Check if this is a new preset not in the original list
            val isNew = !originalPresets.any { preset ->
                "${preset.label}|${preset.size}|${preset.dpi}" == tableKey
            }
            
            if (isNew) {
                println("ADB_DEBUG:   Adding new preset from table: $tableKey")
                newListContent.add(tablePreset.copy())
            }
        }
        
        // Update the list
        list.presets.clear()
        list.presets.addAll(newListContent)
        
        println("ADB_DEBUG:   Final list has ${list.presets.size} presets")
    }
    
    /**
     * Распределяет пресеты в обычном режиме (без скрытия дубликатов)
     */
    private fun distributeNormal(
        tableModel: DevicePresetTableModel,
        tempPresetLists: MutableMap<String, PresetList>,
        getListNameAtRow: (Int) -> String?
    ) {
        // Сохраняем оригинальный порядок пресетов в каждом списке
        val originalOrders = mutableMapOf<String, List<String>>()
        tempPresetLists.values.forEach { list ->
            originalOrders[list.name] = list.presets.map { preset ->
                "${preset.label}|${preset.size}|${preset.dpi}"
            }
        }

        // Собираем обновленные пресеты из таблицы
        val updatedPresetsPerList = mutableMapOf<String, MutableMap<String, DevicePreset>>()
        
        for (i in 0 until tableModel.rowCount) {
            val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
            if (firstColumn == "+") continue

            val listName = getListNameAtRow(i) ?: continue
            val preset = tableModel.getPresetAt(i) ?: continue

            val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
            updatedPresetsPerList.getOrPut(listName) { mutableMapOf() }[presetKey] = preset.copy()
        }

        // Обновляем списки, сохраняя оригинальный порядок
        tempPresetLists.values.forEach { list ->
            val originalOrder = originalOrders[list.name] ?: emptyList()
            val updatedPresetsMap = updatedPresetsPerList[list.name] ?: mutableMapOf()
            
            // If the list has no updates from table, skip it to preserve existing presets
            if (updatedPresetsMap.isEmpty() && list.presets.isNotEmpty()) {
                println("ADB_DEBUG: distributeNormal - No updates for list ${list.name}, preserving existing ${list.presets.size} presets")
                return@forEach
            }
            
            // Создаем новый список, сохраняя оригинальный порядок
            val newPresets = mutableListOf<DevicePreset>()
            
            // Сначала добавляем пресеты в оригинальном порядке
            originalOrder.forEach { presetKey ->
                updatedPresetsMap[presetKey]?.let { preset ->
                    newPresets.add(preset)
                }
            }
            
            // Затем добавляем новые пресеты, которых не было в оригинальном порядке
            updatedPresetsMap.forEach { (presetKey, preset) ->
                if (!originalOrder.contains(presetKey)) {
                    newPresets.add(preset)
                }
            }
            
            // Обновляем список
            list.presets.clear()
            list.presets.addAll(newPresets)
        }
    }
    
    /**
     * Собирает обновленные пресеты из таблицы по спискам
     */
    private fun collectUpdatedPresetsFromTable(
        tableModel: DevicePresetTableModel,
        getListNameAtRow: (Int) -> String?
    ): Map<String, MutableList<DevicePreset>> {
        val updatedPresetsPerList = mutableMapOf<String, MutableList<DevicePreset>>()

        for (i in 0 until tableModel.rowCount) {
            val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
            if (firstColumn == "+") continue

            val listName = getListNameAtRow(i) ?: continue
            val preset = tableModel.getPresetAt(i) ?: continue

            // Don't skip empty presets - they should be preserved
            updatedPresetsPerList.getOrPut(listName) { mutableListOf() }.add(preset)
        }

        return updatedPresetsPerList
    }
}