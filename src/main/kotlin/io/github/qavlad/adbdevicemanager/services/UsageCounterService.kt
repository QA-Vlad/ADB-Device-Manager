package io.github.qavlad.adbdevicemanager.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Сервис для управления счетчиками использования Size и DPI
 */
object UsageCounterService {
    private const val SIZE_COUNTERS_KEY = "ADB_RANDOMIZER_SIZE_USAGE_COUNTERS"
    private const val DPI_COUNTERS_KEY = "ADB_RANDOMIZER_DPI_USAGE_COUNTERS"
    
    private val gson = Gson()
    
    // Кэш для счетчиков
    private var sizeCounters: MutableMap<String, Int> = mutableMapOf()
    private var dpiCounters: MutableMap<String, Int> = mutableMapOf()
    
    init {
        loadCounters()
    }
    
    /**
     * Загружает счетчики из настроек
     */
    private fun loadCounters() {
        try {
            // Загружаем счетчики Size
            val sizeJson = PresetStorageService.getStringList(SIZE_COUNTERS_KEY).firstOrNull()
            if (!sizeJson.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                sizeCounters = gson.fromJson<Map<String, Int>>(sizeJson, type).toMutableMap()
            }
            
            // Загружаем счетчики DPI
            val dpiJson = PresetStorageService.getStringList(DPI_COUNTERS_KEY).firstOrNull()
            if (!dpiJson.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                dpiCounters = gson.fromJson<Map<String, Int>>(dpiJson, type).toMutableMap()
            }
        } catch (e: Exception) {
            println("Error loading usage counters: ${e.message}")
        }
    }
    
    /**
     * Сохраняет счетчики в настройки
     */
    private fun saveCounters() {
        try {
            val sizeJson = gson.toJson(sizeCounters)
            PresetStorageService.setStringList(SIZE_COUNTERS_KEY, listOf(sizeJson))
            
            val dpiJson = gson.toJson(dpiCounters)
            PresetStorageService.setStringList(DPI_COUNTERS_KEY, listOf(dpiJson))
        } catch (e: Exception) {
            println("Error saving usage counters: ${e.message}")
        }
    }
    
    /**
     * Увеличивает счетчик использования для Size
     */
    fun incrementSizeCounter(size: String) {
        if (size.isNotBlank()) {
            val normalizedSize = normalizeSize(size)
            sizeCounters[normalizedSize] = (sizeCounters[normalizedSize] ?: 0) + 1
            saveCounters()
            println("ADB_DEBUG: Incremented size counter for '$normalizedSize' to ${sizeCounters[normalizedSize]}")
        }
    }
    
    /**
     * Увеличивает счетчик использования для DPI
     */
    fun incrementDpiCounter(dpi: String) {
        if (dpi.isNotBlank()) {
            val normalizedDpi = normalizeDpi(dpi)
            dpiCounters[normalizedDpi] = (dpiCounters[normalizedDpi] ?: 0) + 1
            saveCounters()
            println("ADB_DEBUG: Incremented DPI counter for '$normalizedDpi' to ${dpiCounters[normalizedDpi]}")
        }
    }
    
    /**
     * Получает счетчик использования для Size
     */
    fun getSizeCounter(size: String): Int {
        return if (size.isNotBlank()) {
            sizeCounters[normalizeSize(size)] ?: 0
        } else {
            0
        }
    }
    
    /**
     * Получает счетчик использования для DPI
     */
    fun getDpiCounter(dpi: String): Int {
        return if (dpi.isNotBlank()) {
            dpiCounters[normalizeDpi(dpi)] ?: 0
        } else {
            0
        }
    }
    
    /**
     * Обновляет счетчики при изменении значения Size
     * Возвращает счетчик для нового значения
     */
    fun updateSizeValue(oldSize: String, newSize: String): Int {
        // Ничего не делаем, если значения одинаковые
        if (normalizeSize(oldSize) == normalizeSize(newSize)) {
            return getSizeCounter(newSize)
        }
        
        // Возвращаем счетчик для нового значения (или 0 если его нет)
        return getSizeCounter(newSize)
    }
    
    /**
     * Обновляет счетчики при изменении значения DPI
     * Возвращает счетчик для нового значения
     */
    fun updateDpiValue(oldDpi: String, newDpi: String): Int {
        // Ничего не делаем, если значения одинаковые
        if (normalizeDpi(oldDpi) == normalizeDpi(newDpi)) {
            return getDpiCounter(newDpi)
        }
        
        // Возвращаем счетчик для нового значения (или 0 если его нет)
        return getDpiCounter(newDpi)
    }
    
    /**
     * Нормализует значение Size для единообразного хранения
     */
    private fun normalizeSize(size: String): String {
        return size.trim().replace("\\s+".toRegex(), "")
    }
    
    /**
     * Нормализует значение DPI для единообразного хранения
     */
    private fun normalizeDpi(dpi: String): String {
        return dpi.trim().replace("\\s+".toRegex(), "")
    }
    
    /**
     * Сбрасывает все счетчики
     */
    fun resetAllCounters() {
        sizeCounters.clear()
        dpiCounters.clear()
        saveCounters()
    }
    
    /**
     * Создаёт снимок текущего состояния счётчиков
     */
    fun createSnapshot(): Pair<Map<String, Int>, Map<String, Int>> {
        return Pair(sizeCounters.toMap(), dpiCounters.toMap())
    }
    
    /**
     * Восстанавливает счётчики из снимка
     */
    fun restoreFromSnapshot(snapshot: Pair<Map<String, Int>, Map<String, Int>>) {
        sizeCounters.clear()
        sizeCounters.putAll(snapshot.first)
        dpiCounters.clear()
        dpiCounters.putAll(snapshot.second)
        saveCounters()
    }

}