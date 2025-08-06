package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.core.runDeviceOperation
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.config.PluginConfig
import com.android.ddmlib.CollectingOutputReceiver
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import java.io.InputStreamReader

object WifiNetworkService {

    /**
     * Получает текущий SSID Wi-Fi сети на ПК
     */
    fun getHostWifiSSID(): Result<String?> {
        return try {
            val os = System.getProperty("os.name").lowercase()
            val ssid = when {
                os.contains("windows") -> getWindowsWifiSSID()
                os.contains("mac") -> getMacWifiSSID()
                os.contains("linux") -> getLinuxWifiSSID()
                else -> null
            }
            
            PluginLogger.debug(LogCategory.NETWORK, "Host WiFi SSID: %s", ssid ?: "Not connected")
            Result.Success(ssid)
        } catch (e: Exception) {
            PluginLogger.error("Failed to get host WiFi SSID", e)
            Result.Error(e, "Failed to get host WiFi SSID")
        }
    }
    
    /**
     * Получает текущий SSID Wi-Fi сети на Android устройстве
     */
    fun getDeviceWifiSSID(device: IDevice): Result<String?> {
        return runDeviceOperation(device.name, "get device WiFi SSID") {
            // Способ 1: через dumpsys wifi (работает на большинстве устройств)
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys wifi | grep -E \"mWifiInfo SSID|current SSID\"", receiver, 
                PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            var output = receiver.output.trim()
            PluginLogger.debug(LogCategory.NETWORK, "Device WiFi info output: %s", output)
            
            // Пробуем извлечь SSID из вывода dumpsys wifi
            var ssid: String? = null
            if (output.isNotBlank()) {
                val ssidPattern = Regex("SSID: \"([^\"]+)\"")
                val match = ssidPattern.find(output)
                ssid = match?.groupValues?.getOrNull(1)
            }
            
            // Способ 2: через dumpsys connectivity
            if (ssid == null) {
                val connReceiver = CollectingOutputReceiver()
                device.executeShellCommand("dumpsys connectivity | grep -i \"wifi.*ssid\"", connReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                output = connReceiver.output.trim()
                if (output.isNotBlank()) {
                    // Ищем SSID в различных форматах
                    val patterns = listOf(
                        Regex("SSID: \"([^\"]+)\""),
                        Regex("SSID: ([^,\\s]+)"),
                        Regex("\"([^\"]+)\"")
                    )
                    
                    for (pattern in patterns) {
                        val match = pattern.find(output)
                        if (match != null) {
                            ssid = match.groupValues[1]
                            break
                        }
                    }
                }
            }
            
            // Способ 3: через wpa_cli (для root устройств)
            if (ssid == null) {
                val wpaReceiver = CollectingOutputReceiver()
                device.executeShellCommand("su -c 'wpa_cli status 2>/dev/null | grep ^ssid='", wpaReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                output = wpaReceiver.output.trim()
                if (output.startsWith("ssid=")) {
                    ssid = output.substring(5)
                }
            }
            
            // Способ 4: через cmd wifi status (Android 10+)
            if (ssid == null) {
                val cmdReceiver = CollectingOutputReceiver()
                device.executeShellCommand("cmd wifi status", cmdReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                output = cmdReceiver.output
                if (output.contains("Wifi is enabled")) {
                    val lines = output.lines()
                    for (line in lines) {
                        if (line.contains("SSID:")) {
                            ssid = line.substringAfter("SSID:").trim().trim('"')
                            // Очищаем SSID от дополнительной информации (BSSID, MAC и т.д.)
                            if (ssid.contains(",")) {
                                ssid = ssid.substringBefore(",").trim()
                            }
                            break
                        }
                    }
                }
            }
            
            // Финальная очистка SSID от возможных артефактов
            if (ssid != null && ssid.contains(",")) {
                ssid = ssid.substringBefore(",").trim()
            }
            
            PluginLogger.debug(LogCategory.NETWORK, "Detected WiFi SSID: %s", ssid ?: "not connected")
            ssid
        }
    }

    private fun getWindowsWifiSSID(): String? {
        return try {
            val process = ProcessBuilder("cmd", "/c", "netsh wlan show interfaces")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var ssid: String? = null
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("SSID")) {
                        ssid = line.substringAfter(":").trim()
                        return@forEach
                    }
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS)
            ssid
        } catch (e: Exception) {
            PluginLogger.error("Failed to get Windows WiFi SSID", e)
            null
        }
    }
    
    private fun getMacWifiSSID(): String? {
        return try {
            val process = ProcessBuilder("/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport", "-I")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var ssid: String? = null
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().startsWith("SSID:")) {
                        ssid = line.substringAfter(":").trim()
                        return@forEach
                    }
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS)
            ssid
        } catch (e: Exception) {
            PluginLogger.error("Failed to get Mac WiFi SSID", e)
            null
        }
    }
    
    private fun getLinuxWifiSSID(): String? {
        return try {
            // Попробуем через nmcli
            val process = ProcessBuilder("nmcli", "-t", "-f", "active,ssid", "dev", "wifi")
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var ssid: String? = null
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("yes:")) {
                        ssid = line.substringAfter(":").trim()
                        return@forEach
                    }
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS)
            
            // Если nmcli не сработал, пробуем iwgetid
            if (ssid == null) {
                val iwProcess = ProcessBuilder("iwgetid", "-r")
                    .redirectErrorStream(true)
                    .start()
                
                ssid = BufferedReader(InputStreamReader(iwProcess.inputStream)).use { it.readLine()?.trim() }
                iwProcess.waitFor(5, TimeUnit.SECONDS)
            }
            
            ssid
        } catch (e: Exception) {
            PluginLogger.error("Failed to get Linux WiFi SSID", e)
            null
        }
    }
}