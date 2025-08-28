package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.services.PresetList

/**
 * Менеджер для управления временными списками пресетов.
 * Централизует всю логику работы с tempPresetLists.
 */
class TempListsManager {
    private val tempPresetLists = mutableMapOf<String, PresetList>()
    private val recentlyResetLists = mutableSetOf<String>()
    
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
    fun getTempList(id: String): PresetList? {
        val list = tempPresetLists[id]
        if (list != null) {
            println("ADB_DEBUG: TempListsManager.getTempList($id) returning list with ${list.presets.size} presets")
            // Печатаем стек вызовов для отладки
            val stackTrace = Thread.currentThread().stackTrace
            println("ADB_DEBUG:   Called from:")
            stackTrace.take(5).drop(2).forEach { element ->
                println("ADB_DEBUG:     at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
            }
        }
        return list
    }
    
    /**
     * Установить временные списки
     */
    fun setTempLists(lists: Map<String, PresetList>) {
        println("ADB_DEBUG: TempListsManager.setTempLists called with ${lists.size} lists")
        lists.forEach { (id, list) ->
            println("ADB_DEBUG:   List $id has ${list.presets.size} presets")
        }
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
    
    /**
     * Удалить список по ID
     */
    fun removeList(listId: String): Boolean {
        println("ADB_DEBUG: TempListsManager.removeList() called for id: $listId")
        return tempPresetLists.remove(listId) != null
    }
    
    /**
     * Обновить временный список новыми данными
     */
    fun updateTempList(listId: String, newList: PresetList) {
        println("ADB_DEBUG: TempListsManager.updateTempList called for listId: $listId")
        println("ADB_DEBUG:   newList has ${newList.presets.size} presets")
        
        val existingList = tempPresetLists[listId]
        if (existingList != null) {
            println("ADB_DEBUG:   existingList before update has ${existingList.presets.size} presets")
            // Обновляем существующий список
            existingList.presets.clear()
            existingList.presets.addAll(newList.presets.map { it.copy(id = it.id) })
            existingList.isDefault = newList.isDefault
            existingList.isImported = newList.isImported
            println("ADB_DEBUG:   existingList after update has ${existingList.presets.size} presets")
        } else {
            println("ADB_DEBUG:   No existing list, creating new one")
            // Если списка нет, добавляем его
            tempPresetLists[listId] = newList.copy(
                id = listId,
                presets = newList.presets.map { it.copy(id = it.id) }.toMutableList()
            )
        }
    }
    
    /**
     * Отметить список как недавно сброшенный
     */
    fun markAsRecentlyReset(listId: String) {
        println("ADB_DEBUG: TempListsManager.markAsRecentlyReset($listId)")
        recentlyResetLists.add(listId)
    }
    
    /**
     * Проверить, был ли список недавно сброшен
     */
    fun isRecentlyReset(listId: String): Boolean {
        return recentlyResetLists.contains(listId)
    }
    
    /**
     * Очистить флаг недавнего сброса для списка
     */
    fun clearResetFlag(listId: String) {
        println("ADB_DEBUG: TempListsManager.clearResetFlag($listId)")
        recentlyResetLists.remove(listId)
    }
}