package io.github.qavlad.adbrandomizer.services

import java.util.UUID

/**
 * Представляет список пресетов с уникальным идентификатором и именем
 */
data class PresetList(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var presets: MutableList<DevicePreset> = mutableListOf(),
    val isDefault: Boolean = false
) {
    /**
     * Создает копию списка с новым ID
     */
    fun copy(newName: String = name): PresetList {
        return PresetList(
            id = UUID.randomUUID().toString(),
            name = newName,
            presets = presets.map { it.copy() }.toMutableList(),
            isDefault = false
        )
    }
}
