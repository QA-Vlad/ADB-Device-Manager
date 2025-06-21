// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/SettingsService.kt

package io.github.qavlad.adbrandomizer.services

import com.intellij.ide.util.PropertiesComponent

object SettingsService {
    // Ключи, под которыми мы будем хранить данные
    private const val RESOLUTIONS_KEY = "ADB_RANDOMIZER_RESOLUTIONS"
    private const val DPI_KEY = "ADB_RANDOMIZER_DPI"

    // Значения по умолчанию
    private const val DEFAULT_RESOLUTIONS = "1080x1920,720x1280,1440x2560"
    private const val DEFAULT_DPI = "480,320,560"

    private val properties = PropertiesComponent.getInstance()

    // --- Методы для работы с разрешениями ---
    fun getResolutions(): List<String> {
        val storedValue = properties.getValue(RESOLUTIONS_KEY, DEFAULT_RESOLUTIONS)
        // Возвращаем список, отфильтровав пустые строки на всякий случай
        return storedValue.split(',').filter { it.isNotBlank() }
    }

    fun saveResolutions(resolutions: List<String>) {
        properties.setValue(RESOLUTIONS_KEY, resolutions.joinToString(","))
    }

    // --- Методы для работы с DPI ---
    fun getDpis(): List<Int> {
        val storedValue = properties.getValue(DPI_KEY, DEFAULT_DPI)
        return storedValue.split(',').mapNotNull { it.toIntOrNull() }
    }

    fun saveDpis(dpis: List<Int>) {
        properties.setValue(DPI_KEY, dpis.joinToString(",") { it.toString() })
    }
}