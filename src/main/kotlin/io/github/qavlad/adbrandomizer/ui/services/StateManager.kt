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
        // Получаем активный список из сервиса
        val activeList = PresetListService.getActivePresetList()
        
        return if (activeList != null) {
            tempPresetLists[activeList.id] ?: tempPresetLists.values.firstOrNull()
        } else {
            tempPresetLists.values.firstOrNull()
        }
    }
}