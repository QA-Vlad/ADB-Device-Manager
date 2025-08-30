package io.github.qavlad.adbdevicemanager.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent

object WifiDeviceHistoryService {
    private const val HISTORY_KEY = "adbrandomizer.wifiDeviceHistory"
    private val gson = Gson()

    data class WifiDeviceHistoryEntry(
        val ipAddress: String,
        val port: Int,
        val displayName: String,
        val androidVersion: String,
        val apiLevel: String,
        val logicalSerialNumber: String,
        val realSerialNumber: String? = null,  // Настоящий серийный номер устройства
        val defaultResolutionWidth: Int? = null,  // Дефолтная ширина экрана
        val defaultResolutionHeight: Int? = null, // Дефолтная высота экрана
        val defaultDpi: Int? = null,  // Дефолтный DPI
        val alternativeIpAddresses: MutableSet<String>? = mutableSetOf(), // История всех IP-адресов устройства
        val lastSuccessfulConnection: Long? = null // Время последнего успешного подключения
    )

    fun getHistory(): List<WifiDeviceHistoryEntry> {
        val properties = PropertiesComponent.getInstance()
        val json = properties.getValue(HISTORY_KEY)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<WifiDeviceHistoryEntry>>() {}.type
            val entries: List<WifiDeviceHistoryEntry> = gson.fromJson(json, type)
            // Исправляем null значения для старых данных
            entries.map { entry ->
                if (entry.alternativeIpAddresses == null) {
                    entry.copy(alternativeIpAddresses = mutableSetOf())
                } else {
                    entry
                }
            }
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
        val properties = PropertiesComponent.getInstance()
        properties.setValue(HISTORY_KEY, json)
    }
    
    fun getDeviceBySerialNumber(serialNumber: String): WifiDeviceHistoryEntry? {
        return getHistory().find { 
            it.realSerialNumber == serialNumber || it.logicalSerialNumber == serialNumber
        }
    }
    
    fun getDeviceByIpAddress(ipAddress: String): WifiDeviceHistoryEntry? {
        return getHistory().find { 
            it.ipAddress == ipAddress || (it.alternativeIpAddresses?.contains(ipAddress) == true)
        }
    }
    
    /**
     * Добавляет альтернативный IP-адрес к устройству
     */
    fun addAlternativeIpAddress(deviceSerial: String, newIpAddress: String) {
        val current = getHistory().toMutableList()
        val deviceIndex = current.indexOfFirst { 
            it.logicalSerialNumber == deviceSerial || it.realSerialNumber == deviceSerial
        }
        
        if (deviceIndex >= 0) {
            val device = current[deviceIndex]
            val updatedIps = device.alternativeIpAddresses ?: mutableSetOf()
            updatedIps.add(newIpAddress)
            current[deviceIndex] = device.copy(alternativeIpAddresses = updatedIps)
            saveHistory(current)
        }
    }
    
    /**
     * Обновляет время последнего успешного подключения
     */
    fun updateLastSuccessfulConnection(deviceSerial: String) {
        val current = getHistory().toMutableList()
        val deviceIndex = current.indexOfFirst { 
            it.logicalSerialNumber == deviceSerial || it.realSerialNumber == deviceSerial
        }
        
        if (deviceIndex >= 0) {
            val device = current[deviceIndex]
            current[deviceIndex] = device.copy(
                lastSuccessfulConnection = System.currentTimeMillis()
            )
            saveHistory(current)
        }
    }
    
    /**
     * Получает все известные IP-адреса для устройства
     */
    fun getAllKnownIpAddresses(deviceSerial: String): Set<String> {
        val device = getHistory().find { 
            it.logicalSerialNumber == deviceSerial || it.realSerialNumber == deviceSerial
        }
        
        return device?.let {
            mutableSetOf(it.ipAddress).apply {
                it.alternativeIpAddresses?.let { ips -> addAll(ips) }
            }
        } ?: emptySet()
    }

} 