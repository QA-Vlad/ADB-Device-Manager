package io.github.qavlad.adbrandomizer.services

import java.util.UUID

/**
 * Представляет список пресетов с уникальным идентификатором и именем
 */
data class PresetList(
    var id: String = UUID.randomUUID().toString(),
    var name: String,
    var presets: MutableList<DevicePreset> = mutableListOf(),
    var isDefault: Boolean = false,
    var isImported: Boolean = false
) {
    /**
     * Создает копию списка с новым ID
     */
    fun copy(newName: String = name): PresetList {
        return PresetList(
            id = UUID.randomUUID().toString(),
            name = newName,
            presets = presets.map { preset -> 
                DevicePreset(
                    label = preset.label,
                    size = preset.size,
                    dpi = preset.dpi,
                    id = UUID.randomUUID().toString() // Генерируем новый ID для каждого пресета
                )
            }.toMutableList(),
            isDefault = false,
            isImported = false
        )
    }
    
    /**
     * Регенерирует ID для списка и всех его пресетов
     * Используется при импорте для предотвращения конфликтов ID
     */
    fun regenerateIds() {
        id = UUID.randomUUID().toString()
        // Создаем новые пресеты с новыми ID, так как id в DevicePreset - это val
        presets = presets.map { preset ->
            DevicePreset(
                label = preset.label,
                size = preset.size,
                dpi = preset.dpi,
                id = UUID.randomUUID().toString()
            )
        }.toMutableList()
    }
}
