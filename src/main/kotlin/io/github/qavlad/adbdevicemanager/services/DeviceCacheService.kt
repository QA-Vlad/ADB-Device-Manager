package io.github.qavlad.adbdevicemanager.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * Сервис для кеширования информации об устройствах
 * Хранит информацию о root доступе, типах сетей и других параметрах
 */
@Service(Service.Level.APP)
class DeviceCacheService {
    
    companion object {
        val instance: DeviceCacheService
            get() = service()
    }
    
    // Кеш для root доступа устройств (serial -> hasRoot)
    private val rootAccessCache = ConcurrentHashMap<String, Boolean>()
    
    // Кеш типов Wi-Fi сетей (SSID -> SecurityType)
    private val wifiSecurityCache = ConcurrentHashMap<String, WifiSecurityType>()
    
    // Кеш последних известных IP адресов устройств (serial -> IP)
    private val deviceIpCache = ConcurrentHashMap<String, DeviceIpInfo>()
    
    // Время жизни кеша root доступа (5 минут)
    private val rootCacheTtl = TimeUnit.MINUTES.toMillis(5)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    enum class WifiSecurityType {
        OPEN,
        WPA2,
        WPA3,
        UNKNOWN
    }
    
    data class DeviceIpInfo(
        val ip: String,
        val timestamp: Long,
        val ssid: String? = null
    ) {
        fun isValid(): Boolean {
            return System.currentTimeMillis() - timestamp < TimeUnit.SECONDS.toMillis(30)
        }
    }
    
    /**
     * Проверяет и возвращает закешированную информацию о root доступе
     */
    fun getCachedRootAccess(deviceSerial: String): Boolean? {
        val hasRoot = rootAccessCache[deviceSerial]
        if (hasRoot != null) {
            PluginLogger.debug(LogCategory.NETWORK, "Using cached root status for %s: %b", deviceSerial, hasRoot)
        }
        return hasRoot
    }
    
    /**
     * Сохраняет информацию о root доступе устройства
     */
    fun cacheRootAccess(deviceSerial: String, hasRoot: Boolean) {
        rootAccessCache[deviceSerial] = hasRoot
        PluginLogger.debug(LogCategory.NETWORK, "Cached root status for %s: %b", deviceSerial, hasRoot)
        
        // Автоматически очищаем кеш через 5 минут
        scope.launch {
            delay(rootCacheTtl)
            rootAccessCache.remove(deviceSerial)
            PluginLogger.debug(LogCategory.NETWORK, "Cleared root cache for %s", deviceSerial)
        }
    }
    
    /**
     * Возвращает закешированный тип безопасности для Wi-Fi сети
     */
    fun getCachedWifiSecurity(ssid: String): WifiSecurityType? {
        return wifiSecurityCache[ssid]
    }
    
    /**
     * Сохраняет тип безопасности Wi-Fi сети
     */
    fun cacheWifiSecurity(ssid: String, securityType: WifiSecurityType) {
        wifiSecurityCache[ssid] = securityType
        PluginLogger.debug(LogCategory.NETWORK, "Cached WiFi security for %s: %s", ssid, securityType)
    }
    
    /**
     * Определяет тип безопасности по результату подключения
     */
    fun cacheSuccessfulConnectionType(ssid: String, connectionCommand: String) {
        val securityType = when {
            connectionCommand.contains("open") -> WifiSecurityType.OPEN
            connectionCommand.contains("wpa3") -> WifiSecurityType.WPA3
            connectionCommand.contains("wpa2") -> WifiSecurityType.WPA2
            else -> WifiSecurityType.UNKNOWN
        }
        cacheWifiSecurity(ssid, securityType)
    }
    
    /**
     * Возвращает последний известный IP адрес устройства
     */
    fun getCachedDeviceIp(deviceSerial: String): String? {
        val ipInfo = deviceIpCache[deviceSerial]
        return if (ipInfo?.isValid() == true) {
            PluginLogger.debug(LogCategory.NETWORK, "Using cached IP for %s: %s", deviceSerial, ipInfo.ip)
            ipInfo.ip
        } else {
            null
        }
    }
    
    /**
     * Сохраняет IP адрес устройства
     */
    fun cacheDeviceIp(deviceSerial: String, ip: String, ssid: String? = null) {
        deviceIpCache[deviceSerial] = DeviceIpInfo(ip, System.currentTimeMillis(), ssid)
        PluginLogger.debug(LogCategory.NETWORK, "Cached IP for %s: %s (SSID: %s)", deviceSerial, ip, ssid ?: "unknown")
    }

    /**
     * Очищает весь кеш
     */
    fun clearAllCache() {
        rootAccessCache.clear()
        wifiSecurityCache.clear()
        deviceIpCache.clear()
        PluginLogger.info(LogCategory.NETWORK, "Cleared all device cache")
    }
    
    /**
     * Освобождает ресурсы при выключении
     */
    fun dispose() {
        scope.cancel()
        clearAllCache()
    }
}