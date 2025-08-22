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
            presets = presets.map { it.copy(id = it.id) }.toMutableList(),
            isDefault = false,
            isImported = false
        )
    }
}
