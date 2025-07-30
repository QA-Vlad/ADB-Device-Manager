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
        
        // Списки, которые не удалось загрузить (файлы удалены)
        val missingLists = mutableListOf<String>()
        
        allLists.forEach { metadata ->
            val list = PresetListService.loadPresetList(metadata.id)
            if (list != null) {
                // Не применяем порядок здесь, так как он уже сохранен в файле
                // и будет применен в TableLoader при необходимости
                tempPresetLists[list.id] = list
                println("ADB_DEBUG: Loaded list ${list.name} with ${list.presets.size} presets")
            } else {
                println("ADB_DEBUG: Failed to load list ${metadata.name} (id: ${metadata.id}) - file may have been deleted")
                missingLists.add(metadata.id)
            }
        }
        
        // Удаляем из метаданных списки, файлы которых отсутствуют
        if (missingLists.isNotEmpty()) {
            println("ADB_DEBUG: Removing ${missingLists.size} missing lists from metadata")
            missingLists.forEach { listId ->
                PresetListService.deleteList(listId)
            }
        }
        
        // Проверяем, что активный список всё еще существует
        val activeListId = PresetListService.getActiveListId()
        if (activeListId != null && !tempPresetLists.containsKey(activeListId)) {
            println("ADB_DEBUG: Active list $activeListId no longer exists, will reset to first available")
            // Активный список больше не существует
            // Новый активный список будет установлен в determineInitialCurrentList
            // Пока не устанавливаем, так как determineInitialCurrentList сделает это сам
        }
        
        // Если списков нет, создаем дефолтный
        if (tempPresetLists.isEmpty()) {
            println("ADB_DEBUG: No lists found, creating default list")
            val defaultList = PresetList(name = "Default")
            PresetListService.savePresetList(defaultList)
            tempPresetLists[defaultList.id] = defaultList
            PresetListService.setActiveListId(defaultList.id)
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
        
        // Если активный список существует и он есть в загруженных списках - используем его
        if (activeListId != null && tempPresetLists.containsKey(activeListId)) {
            val list = tempPresetLists[activeListId]
            println("ADB_DEBUG: determineInitialCurrentList - returning active list: ${list?.name}, presets count: ${list?.presets?.size}")
            list?.presets?.forEachIndexed { index, preset ->
                println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
            }
            return list
        }
        
        // Активного списка нет или он не существует - выбираем первый доступный
        val firstList = tempPresetLists.values.firstOrNull()
        if (firstList != null) {
            println("ADB_DEBUG: determineInitialCurrentList - no valid active list, setting first available: ${firstList.name}")
            PresetListService.setActiveListId(firstList.id)
            return firstList
        }
        
        println("ADB_DEBUG: determineInitialCurrentList - no lists available")
        return null
    }
}