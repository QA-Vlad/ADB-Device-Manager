package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel

/**
 * Сервис для управления удалением пресетов из временных списков
 */
class PresetDeletionService(
    private val tempListsManager: TempListsManager,
    private val presetRecycleBin: PresetRecycleBin,
    private val presetOrderManager: PresetOrderManager,
    private val duplicateManager: DuplicateManager
) {
    /**
     * Удаляет пресет из временного списка
     * @return Pair<Boolean, Int?> - успешность удаления и реальный индекс в списке (если отличается от row)
     */
    fun deletePresetFromTempList(
        row: Int,
        preset: DevicePreset,
        isShowAllPresetsMode: Boolean,
        isHideDuplicatesMode: Boolean,
        currentPresetList: PresetList?,
        getListNameAtRow: (Int) -> String?,
        tableModel: DevicePresetTableModel
    ): Pair<Boolean, Int?> {
        return if (isShowAllPresetsMode) {
            deleteInShowAllMode(row, preset, isHideDuplicatesMode, getListNameAtRow, tableModel)
        } else {
            deleteInNormalMode(row, isHideDuplicatesMode, currentPresetList)
        }
    }
    
    /**
     * Удаляет пресет в режиме Show All
     */
    private fun deleteInShowAllMode(
        row: Int,
        preset: DevicePreset,
        isHideDuplicatesMode: Boolean,
        getListNameAtRow: (Int) -> String?,
        tableModel: DevicePresetTableModel
    ): Pair<Boolean, Int?> {
        val listName = getListNameAtRow(row) ?: return Pair(false, null)
        val targetList = findTargetList(listName) ?: return Pair(false, null)
        
        return if (isHideDuplicatesMode) {
            deleteInShowAllWithHideDuplicates(row, listName, targetList, tableModel)
        } else {
            deleteInShowAllNormal(preset, listName, targetList)
        }
    }
    
    /**
     * Удаляет пресет в режиме Show All с Hide Duplicates
     */
    private fun deleteInShowAllWithHideDuplicates(
        row: Int,
        listName: String,
        targetList: PresetList,
        tableModel: DevicePresetTableModel
    ): Pair<Boolean, Int?> {
        val presetId = tableModel.getPresetIdAt(row)
        if (presetId != null) {
            val indexToRemove = targetList.presets.indexOfFirst { it.id == presetId }
            
            if (indexToRemove >= 0) {
                val removedPreset = targetList.presets.removeAt(indexToRemove)
                println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' (id: $presetId) from list $listName at index $indexToRemove (hide duplicates mode)")
                
                presetRecycleBin.moveToRecycleBin(removedPreset, listName, indexToRemove)
                presetOrderManager.removeFromFixedOrder(listName, removedPreset)
                return Pair(true, indexToRemove)
            }
            println("ADB_DEBUG: deletePresetFromTempList - preset with id $presetId not found in list $listName")
        } else {
            println("ADB_DEBUG: deletePresetFromTempList - could not get preset ID for row $row")
        }
        return Pair(false, null)
    }
    
    /**
     * Удаляет пресет в обычном режиме Show All
     */
    private fun deleteInShowAllNormal(
        preset: DevicePreset,
        listName: String,
        targetList: PresetList
    ): Pair<Boolean, Int?> {
        val indexToRemove = targetList.presets.indexOfFirst {
            it.label == preset.label &&
            it.size == preset.size &&
            it.dpi == preset.dpi
        }
        
        if (indexToRemove >= 0) {
            val removedPreset = targetList.presets.removeAt(indexToRemove)
            println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from list $listName at index $indexToRemove")
            
            presetRecycleBin.moveToRecycleBin(removedPreset, listName, indexToRemove)
            presetOrderManager.removeFromFixedOrder(listName, removedPreset)
            return Pair(true, indexToRemove)
        }
        
        println("ADB_DEBUG: deletePresetFromTempList - preset not found in list $listName")
        return Pair(false, null)
    }
    
    /**
     * Удаляет пресет в обычном режиме
     */
    private fun deleteInNormalMode(
        row: Int,
        isHideDuplicatesMode: Boolean,
        currentPresetList: PresetList?
    ): Pair<Boolean, Int?> {
        if (currentPresetList == null) return Pair(false, null)
        
        return if (isHideDuplicatesMode) {
            deleteInNormalWithHideDuplicates(row, currentPresetList)
        } else {
            deleteInNormalStandard(row, currentPresetList)
        }
    }
    
    /**
     * Удаляет пресет в обычном режиме с Hide Duplicates
     */
    private fun deleteInNormalWithHideDuplicates(
        row: Int,
        currentPresetList: PresetList
    ): Pair<Boolean, Int?> {
        val duplicateIndices = duplicateManager.findDuplicateIndices(currentPresetList.presets)
        val visibleIndices = currentPresetList.presets.indices.filter { !duplicateIndices.contains(it) }
        
        if (row < visibleIndices.size) {
            val actualIndex = visibleIndices[row]
            val removedPreset = currentPresetList.presets.removeAt(actualIndex)
            println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from current list at index $actualIndex (hide duplicates mode)")
            
            presetRecycleBin.moveToRecycleBin(removedPreset, currentPresetList.name, actualIndex)
            return Pair(true, actualIndex)
        }
        return Pair(false, null)
    }
    
    /**
     * Удаляет пресет в стандартном обычном режиме
     */
    private fun deleteInNormalStandard(
        row: Int,
        currentPresetList: PresetList
    ): Pair<Boolean, Int?> {
        if (row >= 0 && row < currentPresetList.presets.size) {
            val removedPreset = currentPresetList.presets.removeAt(row)
            println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from current list at index $row")
            
            presetRecycleBin.moveToRecycleBin(removedPreset, currentPresetList.name, row)
            presetOrderManager.removeFromFixedOrder(currentPresetList.name, removedPreset)
            return Pair(true, null)
        }
        return Pair(false, null)
    }
    
    /**
     * Находит временный список по имени
     */
    private fun findTargetList(listName: String): PresetList? {
        return tempListsManager.getTempLists().values.find { it.name == listName }
    }
}