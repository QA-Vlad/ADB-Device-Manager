package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList

/**
 * Менеджер для управления временными списками пресетов.
 * Централизует всю логику работы с tempPresetLists.
 */
class TempListsManager {
    private val tempPresetLists = mutableMapOf<String, PresetList>()
    
    /**
     * Получить все временные списки
     */
    fun getTempLists(): Map<String, PresetList> = tempPresetLists
    
    /**
     * Получить изменяемые временные списки (для совместимости)
     */
    fun getMutableTempLists(): MutableMap<String, PresetList> = tempPresetLists
    
    /**
     * Получить временный список по ID
     */
    fun getTempList(id: String): PresetList? = tempPresetLists[id]
    
    /**
     * Установить временные списки
     */
    fun setTempLists(lists: Map<String, PresetList>) {
        tempPresetLists.clear()
        tempPresetLists.putAll(lists)
    }
    
    /**
     * Очистить все временные списки
     */
    fun clear() {
        println("ADB_DEBUG: TempListsManager.clear() called!")
        println("ADB_DEBUG:   Stack trace:")
        Thread.currentThread().stackTrace.take(10).forEach { element ->
            println("ADB_DEBUG:     at $element")
        }
        tempPresetLists.clear()
    }

    /**
     * Получить количество временных списков
     */
    fun size(): Int = tempPresetLists.size
    
    /**
     * Проверить, пусты ли временные списки
     */
    fun isEmpty(): Boolean = tempPresetLists.isEmpty()
    
    /**
     * Проверить, не пусты ли временные списки
     */
    fun isNotEmpty(): Boolean = tempPresetLists.isNotEmpty()
}