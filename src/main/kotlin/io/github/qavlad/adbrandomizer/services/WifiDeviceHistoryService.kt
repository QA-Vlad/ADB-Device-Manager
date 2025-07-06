package io.github.qavlad.adbrandomizer.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent

object WifiDeviceHistoryService {
    private const val HISTORY_KEY = "adbrandomizer.wifiDeviceHistory"
    private val properties = PropertiesComponent.getInstance()
    private val gson = Gson()

    data class WifiDeviceHistoryEntry(
        val ipAddress: String,
        val port: Int,
        val displayName: String,
        val androidVersion: String,
        val apiLevel: String,
        val logicalSerialNumber: String,
        val realSerialNumber: String? = null  // Настоящий серийный номер устройства
    )

    fun getHistory(): List<WifiDeviceHistoryEntry> {
        val json = properties.getValue(HISTORY_KEY)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<WifiDeviceHistoryEntry>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addOrUpdateDevice(entry: WifiDeviceHistoryEntry) {
        val current = getHistory().toMutableList()
        val existingIndex = current.indexOfFirst {
            it.ipAddress == entry.ipAddress && it.port == entry.port
        }
        if (existingIndex >= 0) {
            current[existingIndex] = entry
        } else {
            current.add(entry)
        }
        saveHistory(current)
    }

    fun saveHistory(list: List<WifiDeviceHistoryEntry>) {
        val json = gson.toJson(list)
        properties.setValue(HISTORY_KEY, json)
    }


} 