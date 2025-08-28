package io.github.qavlad.adbdevicemanager.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbdevicemanager.core.Result
import io.github.qavlad.adbdevicemanager.core.runDeviceOperation
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import io.github.qavlad.adbdevicemanager.config.PluginConfig
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
            // Получаем API уровень устройства
            val apiLevel = device.getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull() ?: 999
            PluginLogger.info(LogCategory.NETWORK, "Device API level: %d", apiLevel)
            
            var ssid: String? = null
            
            // Для старых Android (6 и ниже) используем полный вывод без grep
            if (apiLevel <= 23) {
                PluginLogger.info(LogCategory.NETWORK, "Using full dumpsys for old Android (API %d)", apiLevel)
                
                // Получаем полный вывод dumpsys wifi
                val fullReceiver = CollectingOutputReceiver()
                device.executeShellCommand("dumpsys wifi", fullReceiver, 5, TimeUnit.SECONDS)
                val fullOutput = fullReceiver.output
                PluginLogger.info(LogCategory.NETWORK, "Dumpsys wifi output length: %d chars", fullOutput.length)
                
                // Логируем первые 500 символов для диагностики
                if (fullOutput.length > 0) {
                    val preview = if (fullOutput.length > 500) fullOutput.substring(0, 500) + "..." else fullOutput
                    PluginLogger.info(LogCategory.NETWORK, "Dumpsys wifi preview: %s", preview)
                }
                
                // Ищем SSID в разных форматах, которые использовались в старых Android
                val patterns = listOf(
                    Regex("SSID: \"([^\"]+)\""),           // Стандартный формат
                    Regex("SSID: ([^,\\s]+)"),             // Без кавычек
                    Regex("mWifiInfo.*SSID: ([^,]+)"),     // В строке mWifiInfo
                    Regex("ssid=\"([^\"]+)\""),            // Альтернативный формат
                    Regex("SSID=\"([^\"]+)\""),            // С большими буквами
                    Regex("current.*ssid[: ]+\"?([^\"\\s,]+)\"?", RegexOption.IGNORE_CASE) // current ssid
                )
                
                for (pattern in patterns) {
                    val matches = pattern.findAll(fullOutput)
                    var matchCount = 0
                    for (match in matches) {
                        matchCount++
                        val foundSsid = match.groupValues[1].trim()
                        PluginLogger.info(LogCategory.NETWORK, "Pattern '%s' match #%d: '%s'", pattern.pattern, matchCount, foundSsid)
                        // Пропускаем невалидные SSID
                        if (foundSsid.isNotEmpty() && 
                            foundSsid != "<unknown ssid>" && 
                            foundSsid != "0x" &&
                            foundSsid != "null") {
                            ssid = foundSsid
                            PluginLogger.info(LogCategory.NETWORK, "Selected SSID via pattern '%s': %s", pattern.pattern, ssid)
                            break
                        } else {
                            PluginLogger.info(LogCategory.NETWORK, "Skipped invalid SSID: '%s'", foundSsid)
                        }
                    }
                    if (matchCount == 0) {
                        PluginLogger.debug(LogCategory.NETWORK, "Pattern '%s' found no matches", pattern.pattern)
                    }
                    if (ssid != null) break
                }
            } else {
                // Для новых Android используем grep как раньше
                val receiver = CollectingOutputReceiver()
                device.executeShellCommand("dumpsys wifi | grep -E \"mWifiInfo SSID|current SSID\"", receiver, 
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                val output = receiver.output.trim()
                PluginLogger.debug(LogCategory.NETWORK, "Device WiFi info output: %s", output)
                
                // Пробуем извлечь SSID из вывода dumpsys wifi
                if (output.isNotBlank()) {
                    val ssidPattern = Regex("SSID: \"([^\"]+)\"")
                    val match = ssidPattern.find(output)
                    ssid = match?.groupValues?.getOrNull(1)
                }
            }
            
            // Способ 2: через dumpsys connectivity
            if (ssid == null) {
                if (apiLevel <= 23) {
                    // Для старых Android получаем полный вывод
                    val connReceiver = CollectingOutputReceiver()
                    device.executeShellCommand("dumpsys connectivity", connReceiver,
                        PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    
                    val output = connReceiver.output
                    // Ищем различные паттерны Wi-Fi информации
                    val patterns = listOf(
                        Regex("extra: \"([^\"]+)\""),           // extra field часто содержит SSID
                        Regex("SSID: \"([^\"]+)\""),
                        Regex("SSID: ([^,\\s]+)"),
                        Regex("networkId=\"([^\"]+)\""),
                        Regex("\"([^\"]+)\".*state: CONNECTED", RegexOption.IGNORE_CASE)
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(output)
                        for (match in matches) {
                            val foundSsid = match.groupValues[1].trim()
                            if (foundSsid.isNotEmpty() && 
                                foundSsid != "<unknown ssid>" && 
                                foundSsid != "0x" &&
                                foundSsid != "null" &&
                                !foundSsid.startsWith("(") &&  // Пропускаем (unspecified) и подобное
                                foundSsid.length < 33) {  // Максимальная длина SSID - 32 символа
                                ssid = foundSsid
                                PluginLogger.debug(LogCategory.NETWORK, "Found SSID via connectivity pattern: %s", ssid)
                                break
                            }
                        }
                        if (ssid != null) break
                    }
                } else {
                    // Для новых Android используем grep
                    val connReceiver = CollectingOutputReceiver()
                    device.executeShellCommand("dumpsys connectivity | grep -i \"wifi.*ssid\"", connReceiver,
                        PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    
                    val output = connReceiver.output.trim()
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
            }
            
            // Способ 3: через dumpsys netstats (для Android 5-6)
            if (ssid == null && apiLevel in 21..23) {
                val netstatsReceiver = CollectingOutputReceiver()
                device.executeShellCommand("dumpsys netstats", netstatsReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                val output = netstatsReceiver.output
                // Ищем информацию о Wi-Fi интерфейсе
                val patterns = listOf(
                    Regex("iface=wlan[0-9]*.*networkId=\"([^\"]+)\""),
                    Regex("Active interfaces:.*wlan.*\"([^\"]+)\""),
                    Regex("networkId=\"([^\"]+)\".*type=WIFI", RegexOption.IGNORE_CASE)
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(output)
                    if (match != null) {
                        val foundSsid = match.groupValues[1].trim()
                        if (foundSsid.isNotEmpty() && foundSsid != "0x") {
                            ssid = foundSsid
                            PluginLogger.debug(LogCategory.NETWORK, "Found SSID via netstats: %s", ssid)
                            break
                        }
                    }
                }
            }
            
            // Способ 4: через wpa_cli (для root устройств)
            if (ssid == null) {
                val wpaReceiver = CollectingOutputReceiver()
                device.executeShellCommand("su -c 'wpa_cli status 2>/dev/null | grep ^ssid='", wpaReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                val output = wpaReceiver.output.trim()
                if (output.startsWith("ssid=")) {
                    ssid = output.substring(5)
                }
            }
            
            // Способ 5: через cmd wifi status (Android 10+)
            if (ssid == null) {
                val cmdReceiver = CollectingOutputReceiver()
                device.executeShellCommand("cmd wifi status", cmdReceiver,
                    PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                
                val cmdOutput = cmdReceiver.output
                if (cmdOutput.contains("Wifi is enabled")) {
                    val lines = cmdOutput.lines()
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
            
            PluginLogger.info(LogCategory.NETWORK, "Final detected WiFi SSID: %s", ssid ?: "not connected")
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
        // Способ 1: Через airport утилиту (старый путь)
        try {
            PluginLogger.debug(LogCategory.NETWORK, "Trying airport utility method...")
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
            if (ssid != null) {
                PluginLogger.info(LogCategory.NETWORK, "Mac WiFi SSID found via airport: %s", ssid)
                return ssid
            }
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.NETWORK, "Airport utility failed: %s", e.message)
        }
        
        // Способ 2: Через networksetup с поиском правильного интерфейса
        try {
            PluginLogger.debug(LogCategory.NETWORK, "Trying networksetup method...")
            
            // Сначала находим правильное имя Wi-Fi интерфейса
            val interfaceProcess = ProcessBuilder("networksetup", "-listallhardwareports")
                .redirectErrorStream(true)
                .start()
            
            val interfaceOutput = interfaceProcess.inputStream.bufferedReader().readText()
            interfaceProcess.waitFor(2, TimeUnit.SECONDS)
            
            // Ищем Wi-Fi интерфейс
            var wifiInterface: String? = null
            val lines = interfaceOutput.lines()
            for (i in lines.indices) {
                if (lines[i].contains("Hardware Port: Wi-Fi")) {
                    // Device на следующей строке
                    if (i + 1 < lines.size) {
                        val deviceLine = lines[i + 1]
                        if (deviceLine.contains("Device:")) {
                            wifiInterface = deviceLine.substringAfter("Device:").trim()
                            break
                        }
                    }
                }
            }
            
            if (wifiInterface != null) {
                PluginLogger.debug(LogCategory.NETWORK, "Found Wi-Fi interface: %s", wifiInterface)
                
                val process = ProcessBuilder("networksetup", "-getairportnetwork", wifiInterface)
                    .redirectErrorStream(true)
                    .start()
                
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor(5, TimeUnit.SECONDS)
                
                PluginLogger.debug(LogCategory.NETWORK, "networksetup output: %s", output)
                
                // Формат: "Current Wi-Fi Network: NetworkName"
                if (output.contains("Current Wi-Fi Network:")) {
                    val ssid = output.substringAfter("Current Wi-Fi Network:").trim()
                    if (ssid.isNotEmpty() && !ssid.contains("not associated")) {
                        PluginLogger.info(LogCategory.NETWORK, "Mac WiFi SSID found via networksetup: %s", ssid)
                        return ssid
                    }
                }
            }
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.NETWORK, "networksetup failed: %s", e.message)
        }
        
        // Способ 3: Через system_profiler (медленнее, но надёжнее)
        try {
            PluginLogger.debug(LogCategory.NETWORK, "Trying system_profiler method...")
            val process = ProcessBuilder("system_profiler", "SPAirPortDataType")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)
            
            PluginLogger.debug(LogCategory.NETWORK, "system_profiler output length: %d", output.length)
            
            // Ищем текущую сеть - формат: "NetworkName:"
            val lines = output.lines()
            for (i in lines.indices) {
                if (lines[i].contains("Current Network Information:")) {
                    // SSID на следующей строке с двоеточием в конце
                    for (j in i+1..minOf(i+5, lines.size-1)) {
                        val line = lines[j].trim()
                        // Проверяем, что это строка с SSID (имеет : в конце и не содержит других метаданных)
                        if (line.endsWith(":") && 
                            !line.contains("PHY Mode") && 
                            !line.contains("Channel") && 
                            !line.contains("Network Type") && 
                            !line.contains("Security") && 
                            !line.contains("BSSID") &&
                            !line.equals("Current Network Information:")) {
                            // Убираем двоеточие в конце
                            val ssid = line.dropLast(1).trim()
                            if (ssid.isNotEmpty()) {
                                PluginLogger.info(LogCategory.NETWORK, "Mac WiFi SSID found via system_profiler: %s", ssid)
                                return ssid
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.NETWORK, "system_profiler failed: %s", e.message)
        }
        
        PluginLogger.warn(LogCategory.NETWORK, "Failed to get Mac WiFi SSID using all methods")
        return null
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