package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.PresetListService

/**
 * Менеджер для управления состоянием приложения
 * Отвечает за инициализацию, сохранение и восстановление состояния списков пресетов
 */
class StateManager {
    
    /**
     * Инициализирует временные списки пресетов из сохраненных данных
     */
    fun initializeTempPresetLists(): Map<String, PresetList> {
        val tempPresetLists = mutableMapOf<String, PresetList>()
        
        println("ADB_DEBUG: initializeTempPresetLists - Loading all lists")
        
        // Загружаем все списки из сервиса
        val allLists = PresetListService.getAllListsMetadata()
        println("ADB_DEBUG: Found ${allLists.size} lists")
        
        allLists.forEach { metadata ->
            val list = PresetListService.loadPresetList(metadata.id)
            if (list != null) {
                // Не применяем порядок здесь, так как он уже сохранен в файле
                // и будет применен в TableLoader при необходимости
                tempPresetLists[list.id] = list
                println("ADB_DEBUG: Loaded list ${list.name} with ${list.presets.size} presets")
            }
        }
        
        // Если списков нет, создаем дефолтный
        if (tempPresetLists.isEmpty()) {
            println("ADB_DEBUG: No lists found, creating default list")
            val defaultList = PresetList(name = "Default")
            PresetListService.savePresetList(defaultList)
            tempPresetLists[defaultList.id] = defaultList
        }
        
        println("ADB_DEBUG: initializeTempPresetLists - done, loaded ${tempPresetLists.size} lists")
        
        return tempPresetLists
    }
    
    /**
     * Определяет начальный текущий список
     */
    fun determineInitialCurrentList(tempPresetLists: Map<String, PresetList>): PresetList? {
        // Получаем ID активного списка
        val activeListId = PresetListService.getActiveListId()
        println("ADB_DEBUG: determineInitialCurrentList - activeListId: $activeListId")
        
        return if (activeListId != null) {
            val list = tempPresetLists[activeListId] ?: tempPresetLists.values.firstOrNull()
            println("ADB_DEBUG: determineInitialCurrentList - returning list: ${list?.name}, presets count: ${list?.presets?.size}")
            list?.presets?.forEachIndexed { index, preset ->
                println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
            list
        } else {
            val list = tempPresetLists.values.firstOrNull()
            println("ADB_DEBUG: determineInitialCurrentList - no active list, returning first: ${list?.name}")
            list
        }
    }
}