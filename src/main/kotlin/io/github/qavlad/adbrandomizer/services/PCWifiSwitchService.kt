package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.CollectingOutputReceiver
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Сервис для переключения WiFi сети на компьютере для соответствия сети устройства.
 * Поддерживает Windows, macOS и Linux.
 * ВНИМАНИЕ: Требует административные права для выполнения операций.
 */
object PCWifiSwitchService {
    
    /**
     * Информация о WiFi сети
     */
    data class WifiNetworkInfo(
        val ssid: String,
        val password: String? = null
    )
    
    /**
     * Получает текущую WiFi сеть устройства
     */
    suspend fun getDeviceWifiNetwork(deviceSerial: String): WifiNetworkInfo? {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем устройство по серийному номеру
                // Получаем список всех устройств и ищем нужное
                val devices = com.android.ddmlib.AndroidDebugBridge.getBridge()?.devices ?: emptyArray()
                val device = devices.find { it.serialNumber == deviceSerial }
                if (device == null) {
                    PluginLogger.warn(LogCategory.NETWORK, "Device %s not found", deviceSerial)
                    return@withContext null
                }
                
                // Получаем SSID через dumpsys wifi
                val receiver = CollectingOutputReceiver()
                device.executeShellCommand("dumpsys wifi | grep mWifiInfo", receiver, 5, TimeUnit.SECONDS)
                val output = receiver.output
                
                if (output.isNotEmpty()) {
                    // Ищем SSID в выводе: SSID: "NetworkName"
                    val ssidMatch = Regex("""SSID:\s*"([^"]+)"""").find(output)
                    val ssid = ssidMatch?.groupValues?.get(1)
                    
                    if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                        PluginLogger.info(LogCategory.NETWORK, "Device %s is connected to WiFi: %s", deviceSerial, ssid)
                        return@withContext WifiNetworkInfo(ssid)
                    }
                }
                
                // Альтернативный способ через settings
                val wifiReceiver = CollectingOutputReceiver()
                device.executeShellCommand("settings get global wifi_on", wifiReceiver, 5, TimeUnit.SECONDS)
                val wifiOn = wifiReceiver.output.trim()
                
                if (wifiOn == "1") {
                    // WiFi включен, пробуем получить SSID другим способом
                    val connReceiver = CollectingOutputReceiver()
                    device.executeShellCommand("dumpsys connectivity | grep NetworkAgentInfo", connReceiver, 5, TimeUnit.SECONDS)
                    val connOutput = connReceiver.output
                    
                    if (connOutput.isNotEmpty()) {
                        val ssidMatch = Regex(""""([^"]+)"""").find(connOutput)
                        val ssid = ssidMatch?.groupValues?.get(1)
                        
                        if (!ssid.isNullOrBlank()) {
                            PluginLogger.info(LogCategory.NETWORK, "Device %s is connected to WiFi: %s (alternative method)", deviceSerial, ssid)
                            return@withContext WifiNetworkInfo(ssid)
                        }
                    }
                }
                
                PluginLogger.warn(LogCategory.NETWORK, "Could not determine WiFi network for device %s", deviceSerial)
                null
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.NETWORK, "Failed to get device WiFi network: %s", e, deviceSerial)
                null
            }
        }
    }
    
    /**
     * Переключает WiFi сеть компьютера
     */
    suspend fun switchPCWifiNetwork(project: Project?, networkInfo: WifiNetworkInfo): Boolean {
        return withContext(Dispatchers.IO) {
            val os = System.getProperty("os.name").lowercase()
            
            // macOS не поддерживается
            if (os.contains("mac")) {
                PluginLogger.info(LogCategory.NETWORK, "PC WiFi switching is not supported on macOS")
                project?.let {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("ADB Randomizer Notifications")
                        .createNotification(
                            "Feature not supported",
                            "Automatic PC WiFi switching is not supported on macOS. Please switch manually or use device-to-PC switching instead.",
                            NotificationType.WARNING
                        )
                        .notify(it)
                }
                return@withContext false
            }
            
            // Проверяем административные права перед попыткой переключения
            if (!hasAdminPrivileges()) {
                PluginLogger.warn(LogCategory.NETWORK, "No admin privileges detected, WiFi switch may fail")
                // Не показываем предупреждение пользователю, просто логируем
            }
            
            try {
                val result = when {
                    os.contains("windows") -> switchWindowsWifi(networkInfo)
                    os.contains("linux") -> switchLinuxWifi(networkInfo)
                    else -> {
                        PluginLogger.error(LogCategory.NETWORK, "Unsupported operating system: %s", null, os)
                        false
                    }
                }
                
                if (result) {
                    project?.let {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("ADB Randomizer Notifications")
                            .createNotification(
                                "WiFi network switched",
                                "PC successfully switched to network: ${networkInfo.ssid}",
                                NotificationType.INFORMATION
                            )
                            .notify(it)
                    }
                    PluginLogger.info(LogCategory.NETWORK, "Successfully switched PC to WiFi network: %s", networkInfo.ssid)
                } else {
                    project?.let {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("ADB Randomizer Notifications")
                            .createNotification(
                                "WiFi switch failed",
                                "Failed to switch PC to network: ${networkInfo.ssid}. Check if you have admin rights.",
                                NotificationType.ERROR
                            )
                            .notify(it)
                    }
                    PluginLogger.error(LogCategory.NETWORK, "Failed to switch PC to WiFi network: %s", null, networkInfo.ssid)
                }
                
                result
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.NETWORK, "Exception while switching PC WiFi network", e)
                
                project?.let {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("ADB Randomizer Notifications")
                        .createNotification(
                            "WiFi switch error",
                            "Error switching to network: ${e.message}",
                            NotificationType.ERROR
                        )
                        .notify(it)
                }
                
                false
            }
        }
    }
    
    /**
     * Переключает WiFi на Windows
     */
    private fun switchWindowsWifi(networkInfo: WifiNetworkInfo): Boolean {
        try {
            // Сначала проверяем, есть ли сохраненный профиль для этой сети
            val checkProcess = ProcessBuilder("cmd.exe", "/c", "netsh", "wlan", "show", "profiles")
                .redirectErrorStream(true)
                .start()
            
            val checkOutput = checkProcess.inputStream.bufferedReader().readText()
            checkProcess.waitFor(5, TimeUnit.SECONDS)
            
            val hasProfile = checkOutput.contains(networkInfo.ssid)
            
            if (hasProfile) {
                // Если профиль есть, просто подключаемся
                PluginLogger.info(LogCategory.NETWORK, "Found existing WiFi profile for: %s", networkInfo.ssid)
                
                // Сначала получаем список интерфейсов
                val interfaceProcess = ProcessBuilder("cmd.exe", "/c", "netsh", "wlan", "show", "interfaces")
                    .redirectErrorStream(true)
                    .start()
                
                val interfaceOutput = interfaceProcess.inputStream.bufferedReader().readText()
                interfaceProcess.waitFor(5, TimeUnit.SECONDS)
                
                // Извлекаем имя интерфейса (поддержка английской и русской версий Windows)
                val interfaceMatch = Regex("""(?:Name|Имя)\s*:\s+(.+)""").find(interfaceOutput)
                val interfaceName = interfaceMatch?.groupValues?.get(1)?.trim()
                
                if (interfaceName.isNullOrBlank()) {
                    PluginLogger.error(LogCategory.NETWORK, "Could not find WiFi interface name. Output: %s", null, interfaceOutput.take(500))
                    return false
                }
                
                PluginLogger.info(LogCategory.NETWORK, "Found WiFi interface: %s", interfaceName)
                
                // Сначала отключаемся от текущей сети
                PluginLogger.info(LogCategory.NETWORK, "Disconnecting from current WiFi network")
                val disconnectCommand = "netsh wlan disconnect interface=\"${interfaceName}\""
                val disconnectProcess = ProcessBuilder("cmd.exe", "/c", disconnectCommand)
                    .redirectErrorStream(true)
                    .start()
                disconnectProcess.waitFor(3, TimeUnit.SECONDS)
                
                // Небольшая задержка после отключения
                Thread.sleep(1000)
                
                // Проверяем доступность сети
                PluginLogger.info(LogCategory.NETWORK, "Checking if network %s is available", networkInfo.ssid)
                val scanCommand = "netsh wlan show networks interface=\"${interfaceName}\""
                val scanProcess = ProcessBuilder("cmd.exe", "/c", scanCommand)
                    .redirectErrorStream(true)
                    .start()
                
                val scanOutput = scanProcess.inputStream.bufferedReader().readText()
                scanProcess.waitFor(5, TimeUnit.SECONDS)
                
                val networkAvailable = scanOutput.contains(networkInfo.ssid)
                
                if (!networkAvailable) {
                    PluginLogger.warn(LogCategory.NETWORK, "Network %s is not in range", networkInfo.ssid)
                    PluginLogger.info(LogCategory.NETWORK, "Available networks: %s", scanOutput.take(500))
                    
                    // Пытаемся обновить список сетей
                    PluginLogger.info(LogCategory.NETWORK, "Refreshing network list...")
                    Thread.sleep(2000)
                    
                    // Повторно сканируем после задержки
                    val rescanProcess = ProcessBuilder("cmd.exe", "/c", scanCommand)
                        .redirectErrorStream(true)
                        .start()
                    val rescanOutput = rescanProcess.inputStream.bufferedReader().readText()
                    rescanProcess.waitFor(5, TimeUnit.SECONDS)
                    
                    if (!rescanOutput.contains(networkInfo.ssid)) {
                        PluginLogger.error(LogCategory.NETWORK, "Network %s still not available after refresh", null, networkInfo.ssid)
                    }
                }
                
                // Формируем команду подключения
                // Используем правильный формат: netsh wlan connect name="SSID" interface="Interface"
                val connectCommand = "netsh wlan connect name=\"${networkInfo.ssid}\" interface=\"${interfaceName}\""
                
                PluginLogger.info(LogCategory.NETWORK, "Executing WiFi connect command: %s", connectCommand)
                
                val connectProcess = ProcessBuilder("cmd.exe", "/c", connectCommand)
                    .redirectErrorStream(true)
                    .start()
                
                val connectOutput = connectProcess.inputStream.bufferedReader().readText()
                val success = connectProcess.waitFor(10, TimeUnit.SECONDS) && connectProcess.exitValue() == 0
                
                if (success) {
                    PluginLogger.info(LogCategory.NETWORK, "Successfully connected to WiFi: %s", networkInfo.ssid)
                } else {
                    PluginLogger.error(LogCategory.NETWORK, "Failed to connect to WiFi: %s, output: %s", null, networkInfo.ssid, connectOutput)
                }
                
                return success
            } else {
                // Профиля нет, нужен пароль
                PluginLogger.warn(LogCategory.NETWORK, "No saved profile for network: %s. Manual connection required.", networkInfo.ssid)
                
                // Пытаемся открыть настройки WiFi для ручного подключения
                ProcessBuilder("cmd.exe", "/c", "start", "ms-settings:network-wifi")
                    .start()
                
                return false
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.NETWORK, "Failed to switch Windows WiFi", e)
            return false
        }
    }

    /**
     * Переключает WiFi на Linux
     */
    private fun switchLinuxWifi(networkInfo: WifiNetworkInfo): Boolean {
        try {
            // Пробуем использовать nmcli (NetworkManager)
            val checkNmcli = ProcessBuilder("which", "nmcli")
                .redirectErrorStream(true)
                .start()
            
            checkNmcli.waitFor(2, TimeUnit.SECONDS)
            
            if (checkNmcli.exitValue() == 0) {
                // nmcli доступен
                PluginLogger.info(LogCategory.NETWORK, "Using nmcli to switch WiFi")
                
                // Проверяем, есть ли сохраненное подключение
                val listProcess = ProcessBuilder("nmcli", "connection", "show")
                    .redirectErrorStream(true)
                    .start()
                
                val listOutput = listProcess.inputStream.bufferedReader().readText()
                listProcess.waitFor(5, TimeUnit.SECONDS)
                
                if (listOutput.contains(networkInfo.ssid)) {
                    // Подключаемся к существующей сети
                    val connectProcess = ProcessBuilder("nmcli", "connection", "up", networkInfo.ssid)
                        .redirectErrorStream(true)
                        .start()
                    
                    val connectOutput = connectProcess.inputStream.bufferedReader().readText()
                    val success = connectProcess.waitFor(10, TimeUnit.SECONDS) && connectProcess.exitValue() == 0
                    
                    if (success) {
                        PluginLogger.info(LogCategory.NETWORK, "Successfully connected to Linux WiFi: %s", networkInfo.ssid)
                    } else {
                        PluginLogger.error(LogCategory.NETWORK, "Failed to connect to Linux WiFi: %s, output: %s", null, networkInfo.ssid, connectOutput)
                    }
                    
                    return success
                } else {
                    // Пытаемся создать новое подключение
                    if (networkInfo.password != null) {
                        val connectProcess = ProcessBuilder(
                            "nmcli", "device", "wifi", "connect",
                            networkInfo.ssid, "password", networkInfo.password
                        )
                            .redirectErrorStream(true)
                            .start()
                        
                        val connectOutput = connectProcess.inputStream.bufferedReader().readText()
                        val success = connectProcess.waitFor(10, TimeUnit.SECONDS) && connectProcess.exitValue() == 0
                        
                        if (success) {
                            PluginLogger.info(LogCategory.NETWORK, "Successfully connected to new Linux WiFi: %s", networkInfo.ssid)
                            return true
                        } else {
                            PluginLogger.error(LogCategory.NETWORK, "Failed to connect to new Linux WiFi: %s, output: %s", null, networkInfo.ssid, connectOutput)
                        }
                    } else {
                        PluginLogger.warn(LogCategory.NETWORK, "Password required for new network: %s", networkInfo.ssid)
                    }
                }
            }
            
            // Fallback: пытаемся открыть настройки сети
            PluginLogger.warn(LogCategory.NETWORK, "Opening network settings for manual connection")
            
            // Пробуем разные способы открыть настройки
            val commands = listOf(
                listOf("gnome-control-center", "wifi"),
                listOf("nm-connection-editor"),
                listOf("wicd-gtk"),
                listOf("xdg-open", "settings://network")
            )
            
            for (command in commands) {
                try {
                    val process = ProcessBuilder(command).start()
                    if (process.isAlive || process.waitFor(1, TimeUnit.SECONDS)) {
                        break
                    }
                } catch (_: Exception) {
                    // Пробуем следующую команду
                }
            }
            
            return false
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.NETWORK, "Failed to switch Linux WiFi", e)
            return false
        }
    }
    
    /**
     * Проверяет, есть ли административные права
     */
    fun hasAdminPrivileges(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        
        return try {
            when {
                os.contains("windows") -> {
                    // На Windows проверяем через whoami
                    val process = ProcessBuilder("cmd.exe", "/c", "net", "session")
                        .redirectErrorStream(true)
                        .start()
                    
                    process.waitFor(2, TimeUnit.SECONDS)
                    process.exitValue() == 0
                }
                os.contains("mac") || os.contains("linux") -> {
                    // На Unix-системах проверяем uid
                    val process = ProcessBuilder("id", "-u")
                        .redirectErrorStream(true)
                        .start()
                    
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor(2, TimeUnit.SECONDS)
                    
                    output == "0" // root имеет uid 0
                }
                else -> false
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.NETWORK, "Failed to check admin privileges", e)
            false
        }
    }
}