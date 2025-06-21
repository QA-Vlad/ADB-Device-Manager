// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/DevicePreset.kt
package io.github.qavlad.adbrandomizer.services

// Простой data-класс для хранения одного пресета
data class DevicePreset(
    var label: String,
    var size: String,
    var dpi: String
)