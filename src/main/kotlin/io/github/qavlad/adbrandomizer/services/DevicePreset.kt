// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/DevicePreset.kt
package io.github.qavlad.adbrandomizer.services

import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.util.UUID

// Простой data-класс для хранения одного пресета
data class DevicePreset(
    var label: String,
    var size: String,
    var dpi: String,
    val id: String = UUID.randomUUID().toString()
) {
    // Для обратной совместимости с местами, где используется getOrGenerateId()
    fun getOrGenerateId(): String = id
    
    /**
     * Создаёт нормализованный ключ для определения дубликатов
     * Нормализует разделители в размере (x, X, х, Х -> x)
     * @return ключ вида "нормализованный_размер|dpi"
     */
    fun getDuplicateKey(): String {
        val normalizedSize = ValidationUtils.normalizeScreenSize(size)
        return "$normalizedSize|$dpi"
    }
}