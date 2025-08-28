package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.services.DevicePreset

/**
 * Корзина для временного хранения удалённых пресетов.
 * Сохраняет оригинальные пресеты с их ID для возможности восстановления.
 */
class PresetRecycleBin {
    // Хранилище удалённых пресетов: ключ - комбинация listName и индекса, значение - пресет
    private val deletedPresets = mutableMapOf<String, DeletedPresetInfo>()
    
    /**
     * Информация об удалённом пресете
     */
    data class DeletedPresetInfo(
        val preset: DevicePreset,
        val listName: String,
        val originalIndex: Int,
        val deletionTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Помещает пресет в корзину
     */
    fun moveToRecycleBin(preset: DevicePreset, listName: String, originalIndex: Int) {
        val key = generateKey(listName, originalIndex, preset.id)
        val info = DeletedPresetInfo(preset, listName, originalIndex)
        deletedPresets[key] = info
        
        println("ADB_DEBUG: PresetRecycleBin - moved preset to recycle bin: ${preset.label} (id: ${preset.id}) from list '$listName' at index $originalIndex")
        println("ADB_DEBUG:   Recycle bin now contains ${deletedPresets.size} items")
    }
    
    /**
     * Восстанавливает пресет из корзины
     */
    fun restoreFromRecycleBin(listName: String, originalIndex: Int, presetId: String? = null): DevicePreset? {
        // Сначала пробуем найти по точному ключу
        if (presetId != null) {
            val key = generateKey(listName, originalIndex, presetId)
            val info = deletedPresets.remove(key)
            if (info != null) {
                println("ADB_DEBUG: PresetRecycleBin - restored preset: ${info.preset.label} (id: ${info.preset.id}) to list '$listName'")
                return info.preset
            }
        }
        
        // Если не нашли по точному ключу, ищем по listName и индексу
        val partialKey = "$listName:$originalIndex:"
        val matchingEntry = deletedPresets.entries.find { it.key.startsWith(partialKey) }
        
        if (matchingEntry != null) {
            val info = deletedPresets.remove(matchingEntry.key)
            if (info != null) {
                println("ADB_DEBUG: PresetRecycleBin - restored preset by partial key: ${info.preset.label} (id: ${info.preset.id}) to list '$listName'")
                return info.preset
            }
        }
        
        println("ADB_DEBUG: PresetRecycleBin - no preset found for list '$listName' at index $originalIndex")
        return null
    }
    
    /**
     * Ищет удалённый пресет без удаления из корзины
     */
    fun findDeletedPreset(listName: String, originalIndex: Int): DevicePreset? {
        val partialKey = "$listName:$originalIndex:"
        val matchingEntry = deletedPresets.entries.find { it.key.startsWith(partialKey) }
        return matchingEntry?.value?.preset
    }
    
    /**
     * Очищает корзину
     */
    fun clear() {
        println("ADB_DEBUG: PresetRecycleBin - clearing ${deletedPresets.size} items")
        deletedPresets.clear()
    }
    
    /**
     * Проверяет, пуста ли корзина
     */
    fun isEmpty(): Boolean = deletedPresets.isEmpty()
    
    /**
     * Возвращает количество элементов в корзине
     */
    fun size(): Int = deletedPresets.size
    
    /**
     * Генерирует уникальный ключ для хранения
     */
    private fun generateKey(listName: String, index: Int, presetId: String): String {
        return "$listName:$index:$presetId"
    }

}