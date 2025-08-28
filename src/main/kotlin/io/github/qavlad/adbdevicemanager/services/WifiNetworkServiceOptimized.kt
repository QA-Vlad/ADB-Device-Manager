package io.github.qavlad.adbdevicemanager.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbdevicemanager.core.Result
import io.github.qavlad.adbdevicemanager.core.runDeviceOperation
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import com.android.ddmlib.CollectingOutputReceiver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * Оптимизированная версия сервиса для работы с Wi-Fi сетями
 * Использует кеширование и параллельные операции для ускорения
 */
object WifiNetworkServiceOptimized {
    
    private val cacheService by lazy { DeviceCacheService.instance }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Проверяет наличие root доступа на устройстве с кешированием
     */
    fun hasRootAccess(device: IDevice): Boolean {
        // Сначала проверяем кеш
        val cachedRoot = cacheService.getCachedRootAccess(device.serialNumber)
        if (cachedRoot != null) {
            return cachedRoot
        }
        
        // Если не в кеше, проверяем и сохраняем
        val hasRoot = try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("su -c 'id'", receiver, 1, TimeUnit.SECONDS)
            val output = receiver.output.trim()
            output.contains("uid=0") || output.contains("root")
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.NETWORK, "Root check failed: %s", e.message)
            false
        }
        
        // Сохраняем в кеш
        cacheService.cacheRootAccess(device.serialNumber, hasRoot)
        return hasRoot
    }
    
    /**
     * Открывает настройки Wi-Fi с инструкцией для пользователя
     */
    private fun openWifiSettingsWithInstruction(device: IDevice, targetSSID: String) {
        try {
            // Открываем настройки Wi-Fi
            device.executeShellCommand(
                "am start -a android.settings.WIFI_SETTINGS", 
                CollectingOutputReceiver(), 
                2, TimeUnit.SECONDS
            )
            
            // Альтернативный способ через input для показа подсказки
            device.executeShellCommand(
                "input text \"Connect_to_${targetSSID.replace(" ", "_")}\"",
                CollectingOutputReceiver(),
                1, TimeUnit.SECONDS
            )
            
            PluginLogger.info(LogCategory.NETWORK, 
                "Opened Wi-Fi settings on device %s. User needs to manually select '%s'",
                device.serialNumber, targetSSID)
                
        } catch (e: Exception) {
            PluginLogger.warn(LogCategory.NETWORK, 
                "Failed to open Wi-Fi settings: %s", e.message)
        }
    }
    
    /**
     * Получает текущий SSID Wi-Fi сети на ПК
     */
    fun getHostWifiSSID(): Result<String?> {
        return WifiNetworkService.getHostWifiSSID()
    }
    
    /**
     * Получает текущий SSID Wi-Fi сети на Android устройстве
     */
    fun getDeviceWifiSSID(device: IDevice): Result<String?> {
        return WifiNetworkService.getDeviceWifiSSID(device)
    }
    
    /**
     * Быстрое переключение Android устройства на указанную Wi-Fi сеть
     * Использует кеширование и оптимизированные команды
     */
    fun switchDeviceToWifiFast(device: IDevice, ssid: String): Result<Unit> {
        return runDeviceOperation(device.name, "fast switch to WiFi network $ssid") {
            // Включаем Wi-Fi если выключен
            device.executeShellCommand("svc wifi enable", CollectingOutputReceiver(), 2, TimeUnit.SECONDS)
            Thread.sleep(300) // Минимальная задержка
            
            // Проверяем root доступ (с кешем)
            val hasRoot = hasRootAccess(device)
            
            // Получаем тип сети из кеша
            val cachedSecurityType = cacheService.getCachedWifiSecurity(ssid)
            
            PluginLogger.info(LogCategory.NETWORK, 
                "Device %s - Root: %b, Cached security: %s, Fast switching to: %s", 
                device.serialNumber, hasRoot, cachedSecurityType ?: "unknown", ssid)
            
            var connected = false
            
            if (hasRoot) {
                // Для root устройств используем оптимальную команду на основе кеша
                val connectCommand = when (cachedSecurityType) {
                    DeviceCacheService.WifiSecurityType.OPEN -> 
                        "su -c 'cmd wifi connect-network \"$ssid\" open'"
                    DeviceCacheService.WifiSecurityType.WPA2 -> 
                        "su -c 'cmd wifi connect-network \"$ssid\" wpa2'"
                    DeviceCacheService.WifiSecurityType.WPA3 -> 
                        "su -c 'cmd wifi connect-network \"$ssid\" wpa3'"
                    else -> {
                        // Если тип неизвестен, пробуем сначала open (обычно работает для сохранённых)
                        "su -c 'cmd wifi connect-network \"$ssid\" open'"
                    }
                }
                
                val connectReceiver = CollectingOutputReceiver()
                device.executeShellCommand(connectCommand, connectReceiver, 3, TimeUnit.SECONDS)
                
                val result = connectReceiver.output.trim()
                PluginLogger.info(LogCategory.NETWORK, "Root fast connect result: %s", result)
                
                if (result.contains("Connection initiated") || result.contains("connected")) {
                    Thread.sleep(1000) // Минимальное ожидание
                    connected = true
                    
                    // Сохраняем успешный тип подключения в кеш
                    cacheService.cacheSuccessfulConnectionType(ssid, connectCommand)
                    PluginLogger.info(LogCategory.NETWORK, "Fast connection successful via root")
                } else if (cachedSecurityType == null) {
                    // Если первая попытка не удалась и тип неизвестен, пробуем другие варианты
                    val fallbackCommands = listOf(
                        "su -c 'cmd wifi connect-network \"$ssid\" wpa2'",
                        "su -c 'cmd wifi connect-network \"$ssid\" wpa3'"
                    )
                    
                    for (fallbackCmd in fallbackCommands) {
                        val fallbackReceiver = CollectingOutputReceiver()
                        device.executeShellCommand(fallbackCmd, fallbackReceiver, 2, TimeUnit.SECONDS)
                        
                        if (fallbackReceiver.output.contains("Connection initiated")) {
                            Thread.sleep(1000)
                            connected = true
                            cacheService.cacheSuccessfulConnectionType(ssid, fallbackCmd)
                            break
                        }
                    }
                }
            } else {
                // Для устройств без root - открываем настройки Wi-Fi для ручного переключения
                PluginLogger.info(LogCategory.NETWORK, "No root access, opening Wi-Fi settings for manual selection")
                
                // Открываем настройки Wi-Fi с подсказкой
                openWifiSettingsWithInstruction(device, ssid)
                
                // Даём время пользователю на ручное переключение
                PluginLogger.info(LogCategory.NETWORK, 
                    "Waiting for manual Wi-Fi switch to '%s'. User needs to select the network manually.", ssid)
                
                // Не считаем это успешным автоматическим подключением
                connected = false
            }
            
            // Запускаем параллельную проверку IP адреса
            if (connected) {
                launchIpCheck(device)
            }
            
            // Проверяем результат подключения
            if (connected) {
                val verifyResult = getDeviceWifiSSID(device)
                val currentSSID = verifyResult.getOrNull()
                
                if (currentSSID == ssid) {
                    PluginLogger.info(LogCategory.NETWORK, 
                        "Successfully connected device %s to WiFi network: %s", 
                        device.serialNumber, ssid)
                } else {
                    PluginLogger.warn(LogCategory.NETWORK, 
                        "Connection reported success but current SSID is '%s', expected '%s'", 
                        currentSSID ?: "none", ssid)
                }
            } else {
                PluginLogger.warn(LogCategory.NETWORK, 
                    "Could not automatically switch to network '%s'. Please switch manually on the device.", 
                    ssid)
            }
        }
    }
    
    /**
     * Запускает параллельную проверку IP адреса устройства
     */
    private fun launchIpCheck(device: IDevice) {
        scope.launch {
            try {
                // Даём немного времени на получение IP
                delay(500)
                
                // Пытаемся получить IP
                val receiver = CollectingOutputReceiver()
                device.executeShellCommand("ip route | grep 'src ' | head -1", receiver, 2, TimeUnit.SECONDS)
                
                val output = receiver.output
                val ipPattern = Regex("""src\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val match = ipPattern.find(output)
                
                if (match != null) {
                    val ip = match.groupValues[1]
                    cacheService.cacheDeviceIp(device.serialNumber, ip)
                    PluginLogger.debug(LogCategory.NETWORK, "Parallel IP check found: %s for device %s", ip, device.serialNumber)
                }
            } catch (e: Exception) {
                PluginLogger.debug(LogCategory.NETWORK, "Parallel IP check failed: %s", e.message)
            }
        }
    }
    
    /**
     * Получает IP адрес устройства с использованием кеша
     */
    fun getDeviceIpWithCache(device: IDevice): String? {
        // Сначала проверяем кеш
        val cachedIp = cacheService.getCachedDeviceIp(device.serialNumber)
        if (cachedIp != null) {
            return cachedIp
        }
        
        // Если не в кеше, получаем обычным способом
        val result = AdbService.getDeviceIpAddress(device)
        val ip = result.getOrNull()
        
        if (ip != null) {
            cacheService.cacheDeviceIp(device.serialNumber, ip)
        }
        
        return ip
    }
}