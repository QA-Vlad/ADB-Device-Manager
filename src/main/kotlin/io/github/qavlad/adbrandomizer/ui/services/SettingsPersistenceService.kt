package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService

/**
 * Сервис для управления сохранением настроек плагина.
 * Централизует логику сохранения списков пресетов и связанных данных.
 */
class SettingsPersistenceService {
    
    /**
     * Сохраняет все настройки
     */
    fun saveSettings(
        table: JBTable,
        tempLists: Map<String, PresetList>,
        isShowAllPresetsMode: Boolean,
        isHideDuplicatesMode: Boolean,
        presetOrderManager: PresetOrderManager,
        onSaveCurrentTableState: () -> Unit,
        onSaveShowAllPresetsOrder: () -> Unit
    ) {
        // Останавливаем редактирование таблицы если оно активно
        stopTableEditingIfNeeded(table)
        
        // Сохраняем текущее состояние таблицы
        onSaveCurrentTableState()
        
        // Очищаем пустые пресеты перед сохранением
        cleanupEmptyPresets(tempLists)
        
        // В режиме Show All не сохраняем изменения порядка в обычных списках
        // Сохраняем только обновления содержимого пресетов
        if (isShowAllPresetsMode) {
            // При включенном Hide Duplicates сохраняем с оригинальным порядком
            // чтобы не потерять скрытые дубликаты
            saveAllPresetListsWithOriginalOrder(tempLists, presetOrderManager)
            if (!isHideDuplicatesMode) {
                onSaveShowAllPresetsOrder()
            } else {
                println("ADB_DEBUG: Saving lists in Show All mode with Hide Duplicates - preserving original order")
            }
            
            // Сохраняем обычный порядок для списков, которые были изменены через drag & drop
            val modifiedListIds = (tempLists.keys.toSet() + presetOrderManager.getNormalModeOrderInMemory().keys).distinct()
            modifiedListIds.forEach { listId ->
                val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(listId)
                if (memoryOrder != null) {
                    // Сохраняем в настройки для использования после перезапуска
                    val tempList = tempLists[listId]
                    if (tempList != null) {
                        val orderedPresets = mutableListOf<DevicePreset>()
                        
                        // Восстанавливаем порядок из памяти
                        memoryOrder.forEach { key ->
                            val preset = tempList.presets.find { p ->
                                "${p.label}|${p.size}|${p.dpi}" == key
                            }
                            if (preset != null) {
                                orderedPresets.add(preset)
                            }
                        }
                        
                        // Добавляем новые пресеты, которых не было в сохранённом порядке
                        val savedKeys = memoryOrder.toSet()
                        tempList.presets.forEach { preset ->
                            val key = "${preset.label}|${preset.size}|${preset.dpi}"
                            if (key !in savedKeys) {
                                orderedPresets.add(preset)
                            }
                        }
                        
                        if (orderedPresets.isNotEmpty()) {
                            presetOrderManager.saveNormalModeOrder(listId, orderedPresets)
                            println("ADB_DEBUG: Saved normal mode order for list id=$listId in Show All mode")
                        }
                    }
                }
            }
        } else {
            // В обычном режиме всегда используем saveAllPresetListsWithOriginalOrder
            // чтобы учесть сохранённый порядок из обычного режима
            saveAllPresetListsWithOriginalOrder(tempLists, presetOrderManager)
        }
    }
    
    /**
     * Останавливает редактирование таблицы если оно активно
     */
    private fun stopTableEditingIfNeeded(table: JBTable) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }
    
    /**
     * Удаляет пустые пресеты из всех списков
     */
    private fun cleanupEmptyPresets(tempLists: Map<String, PresetList>) {
        tempLists.values.forEach { list ->
            list.presets.removeIf { preset ->
                preset.label.trim().isEmpty() &&
                preset.size.trim().isEmpty() &&
                preset.dpi.trim().isEmpty()
            }
        }
    }
    
    /**
     * Сохраняет все списки пресетов в файлы
     */
    private fun saveAllPresetLists(tempLists: Map<String, PresetList>) {
        tempLists.values.forEach { list ->
            PresetListService.savePresetList(list)
        }
    }
    
    /**
     * Сохраняет списки пресетов с восстановлением оригинального порядка
     * Используется в режиме Show All для сохранения обновлений содержимого
     * без изменения порядка пресетов в обычных списках
     */
    private fun saveAllPresetListsWithOriginalOrder(tempLists: Map<String, PresetList>, presetOrderManager: PresetOrderManager) {
        tempLists.values.forEach { tempList ->
            // Сначала проверяем порядок в памяти (для несохранённых изменений)
            val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(tempList.id)
            val savedOrder = if (memoryOrder != null) {
                println("ADB_DEBUG: Using in-memory normal mode order for list '${tempList.name}' with ${memoryOrder.size} items")
                memoryOrder
            } else {
                // Если в памяти нет, проверяем сохранённый порядок из настроек
                val normalOrder = presetOrderManager.getNormalModeOrder(tempList.id)
                if (normalOrder != null) {
                    println("ADB_DEBUG: Using saved normal mode order for list '${tempList.name}' with ${normalOrder.size} items")
                    normalOrder
                } else {
                    // Если сохранённого порядка нет, используем исходный порядок из файла
                    val originalOrder = presetOrderManager.getOriginalFileOrder(tempList.id)
                    if (originalOrder != null) {
                        println("ADB_DEBUG: Using original file order for list '${tempList.name}' with ${originalOrder.size} items")
                        originalOrder
                    } else {
                        println("ADB_DEBUG: No saved order found for list '${tempList.name}'")
                        null
                    }
                }
            }
            
            if (savedOrder != null) {
                // Создаем карту обновленных пресетов из tempList
                val updatedPresetsMap = tempList.presets.associateBy { 
                    "${it.label}|${it.size}|${it.dpi}"
                }
                
                // Создаем новый список с сохранённым порядком, но обновленным содержимым
                val presetsWithOriginalOrder = mutableListOf<DevicePreset>()
                
                // Добавляем пресеты в сохранённом порядке
                savedOrder.forEach { presetKey ->
                    val updatedPreset = updatedPresetsMap[presetKey]
                    if (updatedPreset != null) {
                        presetsWithOriginalOrder.add(updatedPreset)
                    } else {
                        println("ADB_DEBUG: Preset '$presetKey' was deleted in Show All mode")
                    }
                }
                
                // Добавляем новые пресеты, которых не было в сохранённом порядке
                val savedKeys = savedOrder.toSet()
                tempList.presets.forEach { preset ->
                    val key = "${preset.label}|${preset.size}|${preset.dpi}"
                    if (key !in savedKeys) {
                        presetsWithOriginalOrder.add(preset)
                        println("ADB_DEBUG: New preset '$key' added in Show All mode")
                    }
                }
                
                // Создаем список для сохранения с восстановленным порядком
                val listToSave = PresetList(
                    id = tempList.id,
                    name = tempList.name,
                    presets = presetsWithOriginalOrder
                )
                
                PresetListService.savePresetList(listToSave)
                println("ADB_DEBUG: Saved list '${tempList.name}' with preserved order. Presets count: ${presetsWithOriginalOrder.size}")
            } else {
                // Если сохранённый порядок не найден, сохраняем как есть
                PresetListService.savePresetList(tempList)
                println("ADB_DEBUG: No saved order for '${tempList.name}', saving as is")
            }
        }
    }

    /**
     * Восстанавливает оригинальное состояние из сохраненных снимков
     */
    fun restoreOriginalState(
        tempListsManager: TempListsManager,
        originalPresetLists: Map<String, PresetList>,
        snapshotManager: SnapshotManager
    ) {
        println("ADB_DEBUG: Restoring original state")
        
        // Восстанавливаем из сохраненных оригиналов
        snapshotManager.restoreSnapshots(
            tempListsManager.getMutableTempLists(),
            originalPresetLists
        )
        
        // Сохраняем восстановленные списки в файлы
        saveAllPresetLists(tempListsManager.getTempLists())
        
        // Не очищаем сохраненный порядок - он должен сохраняться между сессиями
        // PresetListService.saveShowAllPresetsOrder(emptyList())
        
        println("ADB_DEBUG: Original state restored and saved to files")
    }
}