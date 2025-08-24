package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.exceptions.ManualWifiSwitchRequiredException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import io.github.qavlad.adbrandomizer.core.runDeviceOperation
import io.github.qavlad.adbrandomizer.core.runAdbOperation

object AdbService {

    fun getConnectedDevices(@Suppress("UNUSED_PARAMETER") project: Project): Result<List<IDevice>> {
        return runBlocking {
            AdbConnectionManager.getConnectedDevices()
        }
    }

    // Overload для обратной совместимости
    fun getConnectedDevices(): Result<List<IDevice>> {
        return runBlocking {
            AdbConnectionManager.getConnectedDevices()
        }
    }

    fun getDeviceIpAddress(device: IDevice): Result<String> {
        // Проверяем, что устройство подключено и готово
        if (!device.isOnline) {
            return Result.Error(Exception("Device ${device.name} is not online"))
        }
        
        return runDeviceOperation(device.name, "get IP address") {
            val receiver = CollectingOutputReceiver()
            try {
                device.executeShellCommand("ip route", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Если произошла ошибка соединения, возвращаем ошибку без логирования warning
                if (e.message?.contains("Connection reset") == true || 
                    e.message?.contains("device offline") == true ||
                    e.message?.contains("device not found") == true) {
                    throw Exception("Device connection lost during IP retrieval")
                }
                throw e
            }
            
            val output = receiver.output
            PluginLogger.debug("IP route output for device %s: %s", device.name, output)
            if (output.isBlank()) {
                throw Exception("Device is not connected to Wi-Fi network. Please connect the device to Wi-Fi first.")
            }
            
            // Паттерн для поиска IP адреса в выводе ip route
            // Пример строки: "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.20"
            val pattern = Pattern.compile(""".*\bdev\s*(\S+)\s*.*\bsrc\s*(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b.*""")
            
            val addresses = mutableListOf<Pair<String, String>>() // interface -> IP
            
            output.lines().forEach { line ->
                val matcher = pattern.matcher(line)
                if (matcher.matches()) {
                    val interfaceName = matcher.group(1)
                    val ip = matcher.group(2)
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        addresses.add(interfaceName to ip)
                    }
                }
            }
            
            PluginLogger.debug("Found IP addresses for device %s: %s", device.name, addresses)
            
            // Приоритет интерфейсов: wlan > rmnet_data > eth
            val selectedIp = addresses.minByOrNull { (interfaceName, _) ->
                when {
                    interfaceName.startsWith("wlan") -> 0
                    interfaceName.startsWith("rmnet_data") -> 1
                    interfaceName.startsWith("eth") -> 2
                    else -> 3
                }
            }?.second
            
            if (selectedIp != null) {
                PluginLogger.debug("IP address found via ip route: %s for device %s", selectedIp, device.name)
                selectedIp
            } else {
                throw Exception("Could not determine device IP address. Make sure the device is connected to Wi-Fi network.")
            }
        }.onError { exception, message ->
            // Не логируем warning для ожидаемых ошибок соединения
            val errorMsg = message ?: exception.message ?: ""
            when {
                errorMsg.contains("connection lost", ignoreCase = true) ||
                errorMsg.contains("connection reset", ignoreCase = true) ||
                errorMsg.contains("device offline", ignoreCase = true) ||
                errorMsg.contains("device not found", ignoreCase = true) ||
                errorMsg.contains("is not online", ignoreCase = true) -> {
                    PluginLogger.debug("Device %s disconnected during IP retrieval", device.name)
                }
                errorMsg.contains("not connected to Wi-Fi", ignoreCase = true) ||
                errorMsg.contains("Could not determine device IP", ignoreCase = true) -> {
                    // Используем rate limiter для Wi-Fi ошибок с ключом по серийному номеру устройства
                    PluginLogger.infoWithRateLimit(
                        LogCategory.GENERAL,
                        "wifi_error_${device.serialNumber}",
                        "Device %s is not connected to Wi-Fi network",
                        device.name
                    )
                }
                else -> {
                    // Для остальных ошибок тоже используем rate limiter
                    PluginLogger.warnWithRateLimit(
                        LogCategory.GENERAL,
                        "ip_route_error_${device.serialNumber}",
                        "IP route method failed for device %s: %s",
                        device.name,
                        errorMsg
                    )
                }
            }
        }.flatMap {
            // Fallback на старый метод
            getDeviceIpAddressFallback(device)
        }
    }
    
    private fun getDeviceIpAddressFallback(device: IDevice): Result<String> {
        // Проверяем состояние устройства перед попыткой получить IP
        if (!device.isOnline) {
            return Result.Error(Exception("Device ${device.name} is not online"))
        }
        
        return runDeviceOperation(device.name, "get IP address fallback") {
            // Список возможных Wi-Fi интерфейсов для проверки
            val wifiInterfaces = PluginConfig.Network.WIFI_INTERFACES

            for (interfaceName in wifiInterfaces) {
                val ipResult = getIpFromInterface(device, interfaceName)
                if (ipResult.isSuccess()) {
                    return@runDeviceOperation ipResult.getOrThrow()
                }
            }

            // Fallback: пытаемся получить IP через другие методы
            getIpAddressFallbackOld(device).getOrThrow()
        }.onError { exception, message ->
            // Не логируем warning для ожидаемых ошибок соединения
            val errorMsg = message ?: exception.message ?: ""
            if (!errorMsg.contains("connection lost", ignoreCase = true) &&
                !errorMsg.contains("connection reset", ignoreCase = true) &&
                !errorMsg.contains("device offline", ignoreCase = true) &&
                !errorMsg.contains("device not found", ignoreCase = true) &&
                !errorMsg.contains("is not online", ignoreCase = true)) {
                PluginLogger.warn("IP fallback method failed for device %s: %s", device.name, errorMsg)
            }
        }
    }

    private fun getIpFromInterface(device: IDevice, interfaceName: String): Result<String> {
        return runDeviceOperation(device.name, "get IP from interface $interfaceName") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("ip -f inet addr show $interfaceName", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.isNotBlank() && !output.contains("does not exist")) {
                val ipPattern = Pattern.compile("""inet (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = ipPattern.matcher(output)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    // Используем ValidationUtils для проверки IP
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        PluginLogger.debug("IP found on interface %s: %s for device %s", interfaceName, ip, device.name)
                        ip
                    } else {
                        throw Exception("Invalid IP address found: $ip")
                    }
                } else {
                    throw Exception("No IP address found in interface $interfaceName output")
                }
            } else {
                throw Exception("Interface $interfaceName does not exist or has no output")
            }
        }
    }

    private fun getIpAddressFallbackOld(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "get IP address fallback old") {
            // Метод 1: через netcfg (старые устройства)
            val netcfgResult = getIpFromNetcfg(device)
            if (netcfgResult.isSuccess()) {
                return@runDeviceOperation netcfgResult.getOrThrow()
            }

            // Метод 2: через ifconfig (если доступен)
            getIpFromIfconfig(device).getOrThrow()
        }
    }

    private fun getIpFromNetcfg(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "get IP from netcfg") {
            val netcfgReceiver = CollectingOutputReceiver()
            device.executeShellCommand("netcfg", netcfgReceiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val netcfgOutput = netcfgReceiver.output
            if (netcfgOutput.isNotBlank()) {
                // Ищем строки вида: wlan0 UP 192.168.1.100/24
                val netcfgPattern = Pattern.compile(PluginConfig.Patterns.NETCFG_PATTERN)
                val matcher = netcfgPattern.matcher(netcfgOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        PluginLogger.debug("IP found via netcfg: %s for device %s", ip, device.name)
                        ip
                    } else {
                        throw Exception("Invalid IP address found in netcfg: $ip")
                    }
                } else {
                    throw Exception("No IP address found in netcfg output")
                }
            } else {
                throw Exception("Empty netcfg output")
            }
        }
    }

    private fun getIpFromIfconfig(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "get IP from ifconfig") {
            val ifconfigReceiver = CollectingOutputReceiver()
            device.executeShellCommand("ifconfig wlan0", ifconfigReceiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val ifconfigOutput = ifconfigReceiver.output
            if (ifconfigOutput.isNotBlank() && !ifconfigOutput.contains("not found")) {
                val ifconfigPattern = Pattern.compile(PluginConfig.Patterns.IFCONFIG_PATTERN)
                val matcher = ifconfigPattern.matcher(ifconfigOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        PluginLogger.debug("IP found via ifconfig: %s for device %s", ip, device.name)
                        ip
                    } else {
                        throw Exception("Invalid IP address found in ifconfig: $ip")
                    }
                } else {
                    throw Exception("No IP address found in ifconfig output")
                }
            } else {
                throw Exception("ifconfig wlan0 not found or has no output")
            }
        }
    }

    fun resetSize(device: IDevice): Result<Unit> {
        return runDeviceOperation(device.name, "reset size") {
            device.executeShellCommand("wm size reset", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted("wm size reset", device.name, true)
        }
    }

    fun resetDpi(device: IDevice): Result<Unit> {
        return runDeviceOperation(device.name, "reset DPI") {
            device.executeShellCommand("wm density reset", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted("wm density reset", device.name, true)
        }
    }

    fun setSize(device: IDevice, width: Int, height: Int): Result<Unit> {
        return runDeviceOperation(device.name, "set size ${width}x${height}") {
            val command = "wm size ${width}x${height}"
            device.executeShellCommand(command, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(command, device.name, true)
        }
    }

    fun setDpi(device: IDevice, dpi: Int): Result<Unit> {
        return runDeviceOperation(device.name, "set DPI $dpi") {
            val command = "wm density $dpi"
            device.executeShellCommand(command, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(command, device.name, true)
        }
    }
    
    fun getCurrentSize(device: IDevice): Result<Pair<Int, Int>> {
        return runDeviceOperation(device.name, "get current size") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm size", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            PluginLogger.debug("wm size output for device %s: %s", device.name, output)
            
            // Сначала проверяем, есть ли Override size (кастомный размер)
            val overridePattern = Pattern.compile("Override size: (\\d+)x(\\d+)")
            val overrideMatcher = overridePattern.matcher(output)
            
            if (overrideMatcher.find()) {
                val width = overrideMatcher.group(1).toInt()
                val height = overrideMatcher.group(2).toInt()
                PluginLogger.debug("Found override size for device %s: %dx%d", device.name, width, height)
                return@runDeviceOperation Pair(width, height)
            }
            
            // Если нет Override size, берём Physical size
            val physicalPattern = Pattern.compile("Physical size: (\\d+)x(\\d+)")
            val physicalMatcher = physicalPattern.matcher(output)
            
            if (physicalMatcher.find()) {
                val width = physicalMatcher.group(1).toInt()
                val height = physicalMatcher.group(2).toInt()
                PluginLogger.debug("Using physical size for device %s: %dx%d", device.name, width, height)
                return@runDeviceOperation Pair(width, height)
            }
            
            throw Exception("Could not parse size from output: $output")
        }
    }
    
    fun getDefaultSize(device: IDevice): Result<Pair<Int, Int>> {
        return runDeviceOperation(device.name, "get default size") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm size", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            // Ищем строку с Physical size, которая показывает дефолтный размер
            val physicalSizePattern = Pattern.compile("Physical size: (\\d+)x(\\d+)")
            val matcher = physicalSizePattern.matcher(output)
            
            if (matcher.find()) {
                val width = matcher.group(1).toInt()
                val height = matcher.group(2).toInt()
                PluginLogger.debug("Default (physical) size for device %s: %dx%d", device.name, width, height)
                Pair(width, height)
            } else {
                throw Exception("Could not parse physical size from output: $output")
            }
        }
    }
    
    fun getCurrentDpi(device: IDevice): Result<Int> {
        return runDeviceOperation(device.name, "get current DPI") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm density", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            PluginLogger.debug(LogCategory.ADB_CONNECTION, "wm density output for device %s: %s", device.name, output)
            
            // Формат вывода может содержать несколько строк:
            // "Physical density: 480"
            // "Override density: 560"
            // Если есть Override, используем его, иначе Physical
            
            // Сначала ищем Override density
            val overridePattern = Pattern.compile("Override density: (\\d+)")
            val overrideMatcher = overridePattern.matcher(output)
            
            if (overrideMatcher.find()) {
                val dpi = overrideMatcher.group(1).toInt()
                PluginLogger.debug("Found override DPI for device %s: %d", device.name, dpi)
                return@runDeviceOperation dpi
            }
            
            // Если нет Override, ищем Physical
            val physicalPattern = Pattern.compile("Physical density: (\\d+)")
            val physicalMatcher = physicalPattern.matcher(output)
            
            if (physicalMatcher.find()) {
                val dpi = physicalMatcher.group(1).toInt()
                PluginLogger.debug(LogCategory.ADB_CONNECTION, "Found physical DPI for device %s: %d", device.name, dpi)
                dpi
            } else {
                throw Exception("Could not parse density from output: $output")
            }
        }
    }
    
    fun setOrientationToPortrait(device: IDevice): Result<Pair<Unit, Boolean>> {
        return runDeviceOperation(device.name, "set orientation to portrait") {
            // Сначала проверяем текущее состояние автоповорота
            val wasAutoRotationEnabled = isAutoRotationEnabled(device).getOrNull() ?: false
            PluginLogger.debug("Auto-rotation was enabled before orientation change: %s", wasAutoRotationEnabled)
            
            // Отключаем автоповорот экрана только если он был включен
            if (wasAutoRotationEnabled) {
                val disableRotationCommand = "settings put system accelerometer_rotation 0"
                device.executeShellCommand(disableRotationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                PluginLogger.commandExecuted(disableRotationCommand, device.name, true)
            }
            
            // Устанавливаем портретную ориентацию (0)
            val setOrientationCommand = "settings put system user_rotation 0"
            device.executeShellCommand(setOrientationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(setOrientationCommand, device.name, true)
            
            // Дополнительно форсируем ориентацию через wm
            val wmOrientationCommand = "wm set-user-rotation lock 0"
            device.executeShellCommand(wmOrientationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(wmOrientationCommand, device.name, true)
            
            // Возвращаем Unit и флаг, был ли автоповорот включен
            Pair(Unit, wasAutoRotationEnabled)
        }
    }
    
    fun setOrientationToLandscape(device: IDevice): Result<Pair<Unit, Boolean>> {
        return runDeviceOperation(device.name, "set orientation to landscape") {
            // Сначала проверяем текущее состояние автоповорота
            val wasAutoRotationEnabled = isAutoRotationEnabled(device).getOrNull() ?: false
            PluginLogger.debug("Auto-rotation was enabled before orientation change: %s", wasAutoRotationEnabled)
            
            // Отключаем автоповорот экрана только если он был включен
            if (wasAutoRotationEnabled) {
                val disableRotationCommand = "settings put system accelerometer_rotation 0"
                device.executeShellCommand(disableRotationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                PluginLogger.commandExecuted(disableRotationCommand, device.name, true)
            }
            
            // Устанавливаем горизонтальную ориентацию (1 - поворот на 90 градусов по часовой)
            val setOrientationCommand = "settings put system user_rotation 1"
            device.executeShellCommand(setOrientationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(setOrientationCommand, device.name, true)
            
            // Дополнительно форсируем ориентацию через wm
            val wmOrientationCommand = "wm set-user-rotation lock 1"
            device.executeShellCommand(wmOrientationCommand, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(wmOrientationCommand, device.name, true)
            
            // Возвращаем Unit и флаг, был ли автоповорот включен
            Pair(Unit, wasAutoRotationEnabled)
        }
    }
    
    fun getCurrentOrientation(device: IDevice): Result<Int> {
        return runDeviceOperation(device.name, "get current orientation") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("settings get system user_rotation", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            val rotation = output.toIntOrNull() ?: 0
            PluginLogger.debug("Current orientation for device %s: %d", device.name, rotation)
            rotation
        }
    }
    
    fun isAutoRotationEnabled(device: IDevice): Result<Boolean> {
        return runDeviceOperation(device.name, "get auto-rotation status") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("settings get system accelerometer_rotation", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            val isEnabled = output == "1"
            PluginLogger.debug("Auto-rotation enabled for device %s: %s", device.name, isEnabled)
            isEnabled
        }
    }
    
    fun setAutoRotation(device: IDevice, enabled: Boolean): Result<Unit> {
        return runDeviceOperation(device.name, "set auto-rotation to $enabled") {
            val value = if (enabled) "1" else "0"
            val command = "settings put system accelerometer_rotation $value"
            device.executeShellCommand(command, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(command, device.name, true)
            PluginLogger.debug("Auto-rotation set to %s for device %s", enabled, device.name)
        }
    }
    
    fun getNaturalOrientation(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "get natural orientation") {
            // Сначала получаем физические размеры экрана
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm size", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            val sizePattern = Pattern.compile("Physical size: (\\d+)x(\\d+)")
            val matcher = sizePattern.matcher(output)
            
            if (matcher.find()) {
                val physicalWidth = matcher.group(1).toInt()
                val physicalHeight = matcher.group(2).toInt()
                
                // Для большинства телефонов естественная ориентация - портретная (высота > ширины)
                // Для планшетов - горизонтальная (ширина > высоты)
                val naturalOrientation = if (physicalHeight > physicalWidth) "portrait" else "landscape"
                PluginLogger.debug("Device %s natural orientation: %s (physical: %dx%d)", 
                    device.name, naturalOrientation, physicalWidth, physicalHeight)
                naturalOrientation
            } else {
                // По умолчанию считаем телефон (портретная ориентация)
                PluginLogger.debug("Could not determine natural orientation for device %s, defaulting to portrait", device.name)
                "portrait"
            }
        }
    }

    /**
     * Проверяет, включён ли TCP/IP режим на устройстве
     */
    fun isTcpIpEnabled(device: IDevice): Result<Boolean> {
        return runDeviceOperation(device.name, "check TCP/IP status") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("getprop service.adb.tcp.port", receiver, 2, TimeUnit.SECONDS)
            val output = receiver.output.trim()
            
            // Если порт установлен и не пустой и не 0 - TCP/IP включён
            val portValue = output.toIntOrNull()
            val isEnabled = portValue != null && portValue > 0
            
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] TCP/IP status check for device %s: %s (port value: %s)", 
                device.name, if (isEnabled) "ENABLED" else "DISABLED", output.ifEmpty { "empty" })
            
            isEnabled
        }
    }
    
    /**
     * Отключает TCP/IP режим и возвращает устройство в USB режим
     */
    fun disableTcpIp(serialNumber: String): Result<Unit> {
        return runAdbOperation("disable TCP/IP and return to USB mode") {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Disabling TCP/IP mode for device %s", serialNumber)
            
            val adbPath = AdbPathResolver.findAdbExecutable() ?: throw Exception("ADB not found")
            
            // Команда adb usb возвращает устройство в USB режим
            val command = listOf(adbPath, "-s", serialNumber, "usb")
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Executing: %s", command.joinToString(" "))
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.exitValue()
                
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] USB mode command output: '%s' (exit code: %d)", output, exitCode)
                
                if (exitCode == 0 || output.contains("restarting in USB mode")) {
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Successfully returned to USB mode")
                    Thread.sleep(2000) // Даём время на переподключение
                }
            } else {
                process.destroyForcibly()
                PluginLogger.warn(LogCategory.ADB_CONNECTION, "[TCPIP] USB mode command timed out")
            }
        }
    }
    
    fun enableTcpIp(device: IDevice, port: Int = 5555): Result<Unit> {
        return runAdbOperation("enable TCP/IP on port $port") {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Starting enableTcpIp for device %s on port %d", device.serialNumber, port)
            
            // Валидация порта
            if (!ValidationUtils.isValidAdbPort(port)) {
                PluginLogger.error(LogCategory.ADB_CONNECTION, "[TCPIP] Invalid port: %d", null, port)
                throw IllegalArgumentException("Port must be between ${PluginConfig.Network.MIN_ADB_PORT} and ${PluginConfig.Network.MAX_PORT}, got: $port")
            }

            val serialNumber = device.serialNumber
            
            // Сначала проверяем, не включен ли уже TCP/IP режим
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Checking if TCP/IP is already enabled on device %s", serialNumber)
            val tcpipCheckResult = isTcpIpEnabled(device)
            
            if (tcpipCheckResult.isSuccess() && tcpipCheckResult.getOrNull() == true) {
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] TCP/IP mode is already enabled on device %s, skipping", serialNumber)
                return@runAdbOperation // Уже включен, ничего делать не нужно
            }
            
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] TCP/IP is not enabled, enabling on port %d for device %s", port, serialNumber)
            
            // Используем прямую команду adb tcpip как у конкурента
            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                PluginLogger.error(LogCategory.ADB_CONNECTION, "[TCPIP] ADB executable not found")
                throw IllegalStateException("ADB executable not found")
            }
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Using ADB path: %s", adbPath)
            
            // Запускаем команду: adb -s SERIAL tcpip PORT
            val command = listOf(adbPath, "-s", serialNumber, "tcpip", port.toString())
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Executing command: %s", command.joinToString(" "))
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Process started, waiting for completion...")
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.exitValue()
                
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Command completed with exit code: %d", exitCode)
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Command output: '%s'", output)
                
                // Проверяем успешность выполнения
                if (exitCode == 0 || output.contains("restarting in TCP mode")) {
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] TCP/IP mode enabled successfully on port %d for device %s", port, serialNumber)
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Waiting for ADB to restart in TCP mode...")
                    
                    // На некоторых устройствах (особенно старых Android) включение TCP/IP временно отключает USB
                    // Даём время устройству перезапустить ADB и переподключиться
                    Thread.sleep(2000)
                    
                    // Проверяем, что устройство всё ещё доступно (может быть по USB или уже по Wi-Fi)
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Checking device availability after TCP/IP enable...")
                    val checkCmd = ProcessBuilder(adbPath, "devices")
                    val checkProcess = checkCmd.start()
                    val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }
                    
                    if (!checkOutput.contains(serialNumber)) {
                        PluginLogger.warn(LogCategory.ADB_CONNECTION, "[TCPIP] Device %s disconnected after TCP/IP enable, waiting for reconnection...", serialNumber)
                        // Ждём ещё немного для переподключения
                        Thread.sleep(3000)
                    }
                    
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[TCPIP] Wait completed")
                } else {
                    PluginLogger.error(LogCategory.ADB_CONNECTION, "[TCPIP] Failed to enable TCP/IP. Exit code: %d, Output: %s", null, exitCode, output)
                    throw Exception("Failed to enable TCP/IP mode: $output")
                }
            } else {
                PluginLogger.error(LogCategory.ADB_CONNECTION, "[TCPIP] Command timed out after 5 seconds", null)
                process.destroyForcibly()
                throw Exception("TCP/IP command timed out after 5 seconds")
            }
        }
    }

    fun connectWifi(@Suppress("UNUSED_PARAMETER") project: Project?, ipAddress: String, port: Int = 5555): Result<Boolean> {
        return runAdbOperation("connect to Wi-Fi device $ipAddress:$port") {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] ========== START WIFI CONNECTION ATTEMPT ==========")
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Target IP: %s, Port: %d", ipAddress, port)
            
            val adbPath = AdbPathResolver.findAdbExecutable() ?: throw Exception("ADB path not found")
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] ADB executable path: %s", adbPath)
            
            // Специальный простой метод для проблемных устройств
            // Просто выполняем connect без всяких проверок и disconnect
            val useSimpleConnect = true // Можно сделать настройкой
            
            if (useSimpleConnect) {
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Using simple connect method")
                val target = "$ipAddress:$port"
                
                // Просто выполняем connect
                val connectCmd = ProcessBuilder(adbPath, "connect", target)
                val process = connectCmd.start()
                
                // Ждём немного и читаем вывод
                val completed = process.waitFor(2, TimeUnit.SECONDS)
                
                if (completed) {
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    val exitCode = process.exitValue()
                    
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Connect result: exit=%d, output='%s'", exitCode, output)
                    
                    // Проверяем результат
                    val success = output.contains("connected to") || output.contains("already connected")
                    
                    if (success) {
                        PluginLogger.wifiConnectionSuccess(ipAddress, port)
                        return@runAdbOperation true
                    } else {
                        // Даём ещё один шанс - проверяем через devices
                        Thread.sleep(1000)
                        val checkCmd = ProcessBuilder(adbPath, "devices")
                        val checkProcess = checkCmd.start()
                        val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }
                        
                        if (checkOutput.contains(target)) {
                            PluginLogger.wifiConnectionSuccess(ipAddress, port)
                            return@runAdbOperation true
                        }
                    }
                } else {
                    // Процесс зависает - убиваем и проверяем
                    process.destroyForcibly()
                    Thread.sleep(1000)
                    
                    // Проверяем подключение через devices
                    val checkCmd = ProcessBuilder(adbPath, "devices")
                    val checkProcess = checkCmd.start()
                    val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }
                    
                    if (checkOutput.contains(target)) {
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Device connected despite timeout")
                        PluginLogger.wifiConnectionSuccess(ipAddress, port)
                        return@runAdbOperation true
                    }
                }
                
                PluginLogger.wifiConnectionFailed(ipAddress, port, Exception("Simple connect failed"))
                return@runAdbOperation false
            }

            // Проверяем настройку автопереключения Wi-Fi
            val settings = PluginSettings.instance
            var actualIpAddress = ipAddress
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Auto-switch to host WiFi setting: %s", settings.autoSwitchToHostWifi)
            
            if (settings.autoSwitchToHostWifi) {
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Attempting auto WiFi switch...")
                try {
                    // Пытаемся переключить устройство на ту же Wi-Fi сеть, что и ПК
                    val newIp = tryAutoSwitchWifi(ipAddress)
                    if (newIp != null) {
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Auto switch successful! New IP: %s (was: %s)", newIp, ipAddress)
                        actualIpAddress = newIp
                    } else {
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Auto switch returned null, using original IP: %s", ipAddress)
                    }
                } catch (e: ManualWifiSwitchRequiredException) {
                    // Требуется ручное переключение - прерываем подключение
                    PluginLogger.warn(LogCategory.ADB_CONNECTION, "[WIFI] Manual WiFi switch required - aborting connection")
                    throw e // Пробрасываем исключение дальше
                } catch (e: Exception) {
                    // Любая другая ошибка - логируем, но продолжаем
                    PluginLogger.warn(LogCategory.ADB_CONNECTION, "[WIFI] Auto WiFi switch error: %s", e, e.message)
                }
            } else {
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Auto WiFi switch is disabled")
            }

            if (!ValidationUtils.isValidIpAddress(actualIpAddress)) {
                PluginLogger.error(LogCategory.ADB_CONNECTION, "[WIFI] Invalid IP address: %s", null, actualIpAddress)
                throw Exception("Invalid IP address: $actualIpAddress")
            }

            val target = "$actualIpAddress:$port"
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Final connection target: %s", target)
            PluginLogger.wifiConnectionAttempt(actualIpAddress, port)

            // Сначала проверяем, не подключены ли уже
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Checking if already connected...")
            val checkCmd = ProcessBuilder(adbPath, "devices")
            val checkProcess = checkCmd.start()
            val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }
            PluginLogger.debug(LogCategory.ADB_CONNECTION, "[WIFI] Current devices output: %s", checkOutput)

            if (checkOutput.contains(target)) {
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Device %s already connected", target)
                return@runAdbOperation true
            }

            // НЕ отключаемся перед подключением - это может вызывать проблемы
            // Просто пытаемся подключиться

            // Подключаемся
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Executing: %s connect %s", adbPath, target)
            val connectCmd = ProcessBuilder(adbPath, "connect", target)
            connectCmd.redirectErrorStream(true)

            val process = connectCmd.start()
            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Process started, waiting %d ms for completion...", PluginConfig.Adb.CONNECTION_TIMEOUT_MS)
            
            // Читаем output в отдельном потоке, чтобы избежать блокировки
            val outputBuilder = StringBuilder()
            val outputReader = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            outputBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    PluginLogger.debug("Error reading output: ${e.message}")
                }
            }
            outputReader.start()
            
            val completed = process.waitFor(PluginConfig.Adb.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            outputReader.join(500) // Ждём максимум 500мс чтобы поток закончил чтение

            if (completed) {
                val output = outputBuilder.toString().trim()
                val exitCode = process.exitValue()
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Command completed. Exit code: %d", exitCode)
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Command output: '%s'", output)

                // Проверяем разные варианты успешного подключения
                // ADB может возвращать успешный код выхода даже при неудачном подключении
                val success = when {
                    output.contains("connected to") && !output.contains("cannot") && !output.contains("failed") -> true
                    output.contains("already connected") -> true
                    output.contains("failed to connect") -> false
                    output.contains("cannot connect") -> false
                    output.contains("Connection refused") -> false
                    output.contains("unable to connect") -> false
                    output.contains("no route to host") -> false
                    else -> exitCode == 0 && output.isNotEmpty() && !output.contains("failed")
                }

                if (success) {
                    // Дополнительная проверка через devices
                    Thread.sleep(PluginConfig.Network.CONNECTION_VERIFY_DELAY_MS)
                    val verifyCmd = ProcessBuilder(adbPath, "devices")
                    val verifyProcess = verifyCmd.start()
                    val verifyOutput = verifyProcess.inputStream.bufferedReader().use { it.readText() }
                    val verified = verifyOutput.contains(target)
                    PluginLogger.debug("Connection verified: %s", verified)
                    
                    if (verified) {
                        PluginLogger.wifiConnectionSuccess(ipAddress, port)
                    } else {
                        PluginLogger.wifiConnectionFailedDebug(ipAddress, port, Exception("Device not found in 'adb devices' list after connection"))
                    }
                    
                    return@runAdbOperation verified
                }

                // Логируем вывод для отладки
                PluginLogger.debug("Connect command output: %s", output)
                
                // Если подключение не удалось и есть ошибка "Connection refused" или код 10061
                if (output.contains("10061") || output.contains("Connection refused") || 
                    output.contains("отверг запрос на подключение")) {
                    
                    PluginLogger.warn(LogCategory.ADB_CONNECTION, "[WIFI] Connection refused (error 10061). Device may have lost TCP/IP mode after USB reconnection")
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Checking for USB devices to re-enable TCP/IP...")
                    
                    // Проверяем, есть ли устройство подключенное по USB
                    val devicesCmd = ProcessBuilder(adbPath, "devices")
                    val devicesProcess = devicesCmd.start()
                    val devicesOutput = devicesProcess.inputStream.bufferedReader().use { it.readText() }
                    
                    // Парсим список устройств
                    val usbDevices = devicesOutput.lines()
                        .filter { it.contains("\tdevice") && !it.contains(":5555") }
                        .map { it.split("\t")[0] }
                    
                    if (usbDevices.isNotEmpty()) {
                        val usbDevice = usbDevices.first()
                        PluginLogger.info("Found USB device: %s, enabling TCP/IP mode...", usbDevice)
                        
                        // Включаем TCP/IP режим на порту 5555
                        val tcpipCmd = ProcessBuilder(adbPath, "-s", usbDevice, "tcpip", port.toString())
                        val tcpipProcess = tcpipCmd.start()
                        val tcpipCompleted = tcpipProcess.waitFor(5, TimeUnit.SECONDS)
                        
                        if (tcpipCompleted && tcpipProcess.exitValue() == 0) {
                            val tcpipOutput = tcpipProcess.inputStream.bufferedReader().use { it.readText() }
                            PluginLogger.info("TCP/IP mode enabled: %s", tcpipOutput.trim())
                            
                            // Ждем немного, чтобы устройство перезапустило ADB
                            Thread.sleep(2000)
                            
                            // Пробуем подключиться снова
                            PluginLogger.info("Retrying connection to %s...", target)
                            val retryCmd = ProcessBuilder(adbPath, "connect", target)
                            retryCmd.redirectErrorStream(true)
                            val retryProcess = retryCmd.start()
                            val retryCompleted = retryProcess.waitFor(PluginConfig.Adb.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            
                            if (retryCompleted) {
                                val retryOutput = retryProcess.inputStream.bufferedReader().use { it.readText() }.trim()
                                val retrySuccess = retryOutput.contains("connected to") || 
                                                  (retryProcess.exitValue() == 0 && !retryOutput.contains("failed"))
                                
                                if (retrySuccess) {
                                    // Финальная проверка
                                    Thread.sleep(500)
                                    val finalCheck = ProcessBuilder(adbPath, "devices").start()
                                    val finalOutput = finalCheck.inputStream.bufferedReader().use { it.readText() }
                                    
                                    if (finalOutput.contains(target)) {
                                        PluginLogger.wifiConnectionSuccess(actualIpAddress, port)
                                        return@runAdbOperation true
                                    }
                                }
                            }
                        } else {
                            PluginLogger.warn("Failed to enable TCP/IP mode on device %s", usbDevice)
                        }
                    } else {
                        PluginLogger.info("No USB devices found to enable TCP/IP mode")
                    }
                }

                // Используем warn только для реальных ошибок (не таймаутов)
                if (output.contains("10061") || output.contains("Connection refused") || 
                    output.contains("отверг запрос на подключение")) {
                    // Это ожидаемая ошибка - устройство есть, но TCP/IP выключен
                    PluginLogger.debug("Connection refused for %s (TCP/IP disabled)", target)
                    PluginLogger.wifiConnectionFailedDebug(ipAddress, port, Exception("TCP/IP disabled: $output"))
                } else {
                    // Другие ошибки логируем как warn
                    PluginLogger.wifiConnectionFailed(ipAddress, port, Exception("ADB connect command failed: $output"))
                }
                return@runAdbOperation false
            } else {
                // Процесс зависает - это часто случается на Android 6
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Connection process timed out, killing process...")
                process.destroyForcibly()
                
                // Даём время процессу умереть
                Thread.sleep(500)
                
                // На Android 6 команда connect может зависнуть, но подключение могло установиться
                // Проверяем несколько раз с задержками
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Checking if device connected despite timeout...")
                
                for (attempt in 1..3) {
                    Thread.sleep(1000) // Ждём секунду перед каждой проверкой
                    
                    val devicesCmd = ProcessBuilder(adbPath, "devices")
                    val devicesProcess = devicesCmd.start()
                    val checkCompleted = devicesProcess.waitFor(2, TimeUnit.SECONDS)
                    
                    if (checkCompleted) {
                        val devicesOutput = devicesProcess.inputStream.bufferedReader().use { it.readText() }
                        PluginLogger.debug(LogCategory.ADB_CONNECTION, "[WIFI] Check attempt %d, devices output: %s", attempt, devicesOutput)
                        
                        if (devicesOutput.contains(target)) {
                            PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Device connected on attempt %d despite timeout (Android 6 workaround)", attempt)
                            PluginLogger.wifiConnectionSuccess(ipAddress, port)
                            return@runAdbOperation true
                        }
                    } else {
                        devicesProcess.destroyForcibly()
                    }
                }
                
                // Если после трёх попыток устройство не появилось - пробуем альтернативный метод
                // Попробуем подключиться без disconnect перед этим (может быть проблема в disconnect)
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Trying alternative connection method without pre-disconnect...")
                
                val altCmd = ProcessBuilder(adbPath, "connect", target)
                altCmd.redirectErrorStream(true)
                val altProcess = altCmd.start()
                
                // Для альтернативной попытки используем короткий таймаут
                val altCompleted = altProcess.waitFor(3, TimeUnit.SECONDS)
                
                if (altCompleted) {
                    val altOutput = altProcess.inputStream.bufferedReader().use { it.readText() }.trim()
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[WIFI] Alternative connect output: %s", altOutput)
                    
                    if (altOutput.contains("connected to") || altOutput.contains("already connected")) {
                        PluginLogger.wifiConnectionSuccess(ipAddress, port)
                        return@runAdbOperation true
                    }
                } else {
                    altProcess.destroyForcibly()
                }
                
                PluginLogger.wifiConnectionFailedDebug(ipAddress, port, Exception("Connection failed after all attempts"))
                return@runAdbOperation false
            }
        }
    }
    
    // Тестовый метод для отладки проблемы с определением активного приложения при включенном scrcpy
    fun debugGetCurrentApp(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "debug get current app") {
            println("ADB_Randomizer: ====== DEBUG GET CURRENT APP TEST ======")
            println("ADB_Randomizer: Testing both methods for device: ${device.serialNumber}")
            
            // Метод 1: mCurrentFocus
            val receiver1 = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys window | grep mCurrentFocus", receiver1, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val output1 = receiver1.output.trim()
            println("ADB_Randomizer: Method 1 (mCurrentFocus): [$output1]")
            
            // Метод 2: mResumedActivity
            val receiver2 = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys activity activities | grep mResumedActivity", receiver2, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val output2 = receiver2.output.trim()
            println("ADB_Randomizer: Method 2 (mResumedActivity): [$output2]")
            
            // Метод 3: mFocusedApp
            val receiver3 = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys window | grep mFocusedApp", receiver3, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val output3 = receiver3.output.trim()
            println("ADB_Randomizer: Method 3 (mFocusedApp): [$output3]")
            
            // Метод 4: Recent tasks
            val receiver4 = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys activity recents | grep 'Recent #0' -A 2", receiver4, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val output4 = receiver4.output.trim()
            println("ADB_Randomizer: Method 4 (Recent tasks): [$output4]")
            
            println("ADB_Randomizer: ====== END DEBUG ======")
            
            "Debug output printed to console"
        }
    }
    
    /**
     * Проверяет, является ли приложение критически важным системным приложением,
     * которое не следует пытаться перезапускать
     */
    private fun isEssentialSystemApp(packageName: String): Boolean {
        return packageName == "com.android.systemui" ||
               packageName == "com.android.launcher" ||
               packageName == "com.sec.android.app.launcher" ||
               packageName == "com.miui.home" || // MIUI launcher
               packageName == "com.android.settings" ||
               packageName == "com.android.phone" ||
               packageName == "com.android.contacts" ||
               packageName == "com.android.mms" ||
               packageName == "com.android.dialer"
    }
    
    /**
     * Получает список всех подключенных устройств (только serial numbers)
     */
    fun getAllDeviceSerials(): Result<List<String>> {
        return runAdbOperation("get all device serials") {
            val adbExecutable = AdbPathResolver.findAdbExecutable() ?: throw Exception("ADB not found")
            val command = listOf(adbExecutable, "devices")
            val process = ProcessBuilder(command).start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!exitCode) {
                throw Exception("ADB devices command timed out")
            }
            
            val devices = mutableListOf<String>()
            val lines = output.lines()
            
            for (line in lines) {
                // Пропускаем заголовок и пустые строки
                if (line.startsWith("List of devices") || line.isBlank()) continue
                
                // Формат: serialnumber\tdevice
                val parts = line.split("\t")
                if (parts.size >= 2 && parts[1].contains("device")) {
                    devices.add(parts[0])
                }
            }
            
            PluginLogger.debug(LogCategory.ADB_CONNECTION, "Found devices: %s", devices.joinToString(", "))
            devices
        }
    }
    
    /**
     * Получает информацию об устройстве по serial number
     */
    fun getDeviceInfo(serialNumber: String): Result<DeviceInfo?> {
        return runAdbOperation("get device info") {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            if (project == null) {
                PluginLogger.warn(LogCategory.ADB_CONNECTION, "No open project found for getting device info")
                return@runAdbOperation null
            }
            
            // Получаем все устройства через AdbServiceAsync
            val devicesRaw = runBlocking {
                AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
            }
            
            // Преобразуем в DeviceInfo и ищем нужное устройство
            val devices = devicesRaw.map { device ->
                DeviceInfo(device, null)
            }
            
            // Ищем устройство по serial number
            devices.find { device ->
                device.logicalSerialNumber == serialNumber || 
                device.displaySerialNumber == serialNumber
            }
        }
    }
    
    fun getRecentTask(device: IDevice): Result<Pair<String, String>?> {
        return runDeviceOperation(device.name, "get recent task") {
            val receiver = CollectingOutputReceiver()
            // Получаем несколько последних задач для анализа
            device.executeShellCommand("dumpsys activity recents | grep -E 'Recent #[0-2]' -A 5", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            
            println("ADB_Randomizer: === GET RECENT TASK ===")
            println("ADB_Randomizer: Device: ${device.name} (${device.serialNumber})")
            println("ADB_Randomizer: Raw output: [$output]")
            
            if (output.isBlank()) {
                println("ADB_Randomizer: No recent task found")
                return@runDeviceOperation null
            }
            
            // Разбиваем вывод на задачи
            val tasks = output.split("Recent #").filter { it.isNotBlank() }
            
            // Ищем первую не-системную задачу
            for (task in tasks) {
                // Ищем affinity в выводе - это обычно package name
                // Пример: affinity=10295:org.coursera.android
                val affinityPattern = Pattern.compile("affinity=(?:\\d+:)?([a-zA-Z0-9._]+)")
                val affinityMatcher = affinityPattern.matcher(task)
                
                if (affinityMatcher.find()) {
                    val packageName = affinityMatcher.group(1)
                    println("ADB_Randomizer: Checking recent task package: $packageName")
                    
                    // Пропускаем только критически важные системные приложения
                    // Chrome, YouTube Music, Gemini и т.д. - это обычные приложения!
                    if (!isEssentialSystemApp(packageName)) {
                        println("ADB_Randomizer: Found suitable recent task: $packageName")
                        
                        // Пытаемся получить главную активность приложения
                        val launcherReceiver = CollectingOutputReceiver()
                        device.executeShellCommand(
                            "cmd package resolve-activity --brief $packageName | tail -n 1",
                            launcherReceiver,
                            PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                        
                        val launcherOutput = launcherReceiver.output.trim()
                        
                        // Парсим вывод для получения активности
                        // Формат: packageName/activityName
                        if (launcherOutput.contains("/")) {
                            val parts = launcherOutput.split("/")
                            if (parts.size == 2) {
                                val activityName = parts[1]
                                println("ADB_Randomizer: Found launcher activity: $activityName")
                                return@runDeviceOperation Pair(packageName, activityName)
                            }
                        }
                        
                        // Если не удалось получить активность, возвращаем с дефолтной
                        println("ADB_Randomizer: Using default launcher activity")
                        return@runDeviceOperation Pair(packageName, ".MainActivity")
                    } else {
                        println("ADB_Randomizer: Skipping system task: $packageName")
                    }
                }
            }
            
            println("ADB_Randomizer: No suitable recent task found")
            return@runDeviceOperation null
        }
    }
    
    fun getTopActivity(device: IDevice): Result<Pair<String, String>?> {
        return runDeviceOperation(device.name, "get top activity") {
            val receiver = CollectingOutputReceiver()
            // Используем dumpsys activity для получения топ активности
            device.executeShellCommand("dumpsys activity activities | grep mResumedActivity", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            
            println("ADB_Randomizer: === GET TOP ACTIVITY (Alternative Method) ===")
            println("ADB_Randomizer: Device: ${device.name} (${device.serialNumber})")
            println("ADB_Randomizer: Raw output from 'dumpsys activity activities | grep mResumedActivity': [$output]")
            
            if (output.isBlank()) {
                println("ADB_Randomizer: No resumed activity found")
                return@runDeviceOperation null
            }
            
            // Паттерн для извлечения package и activity из mResumedActivity
            // Пример: mResumedActivity: ActivityRecord{1234567 u0 com.example.app/.MainActivity t123}
            val pattern = Pattern.compile("ActivityRecord\\{[^}]+\\s+u\\d+\\s+([^/]+)/(\\S+)")
            val matcher = pattern.matcher(output)
            
            if (matcher.find()) {
                val packageName = matcher.group(1)
                val activityName = matcher.group(2)
                println("ADB_Randomizer: Found top activity: $packageName/$activityName")
                Pair(packageName, activityName)
            } else {
                println("ADB_Randomizer: Pattern did not match for mResumedActivity")
                null
            }
        }
    }
    
    fun getCurrentFocusedApp(device: IDevice): Result<Pair<String, String>?> {
        return runDeviceOperation(device.name, "get current focused app") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("dumpsys window | grep mCurrentFocus", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            
            // Добавляем детальное логирование
            println("ADB_Randomizer: === GET CURRENT FOCUSED APP ===")
            println("ADB_Randomizer: Device: ${device.name} (${device.serialNumber})")
            println("ADB_Randomizer: Raw output from 'dumpsys window | grep mCurrentFocus': [$output]")
            println("ADB_Randomizer: Output length: ${output.length}")
            println("ADB_Randomizer: Output is blank: ${output.isBlank()}")
            
            PluginLogger.info(LogCategory.ADB_CONNECTION, "Current focus output for device %s: %s", device.name, output)
            
            if (output.isBlank()) {
                println("ADB_Randomizer: No focused app found (output is blank)")
                PluginLogger.info(LogCategory.ADB_CONNECTION, "No focused app found (output is blank)")
                return@runDeviceOperation null
            }
            
            // Обрабатываем многострочный вывод - берем последнюю непустую строку, которая содержит Window
            val lines = output.lines()
            val validLine = lines.lastOrNull { line -> 
                line.contains("Window{") && !line.contains("mCurrentFocus=null")
            }
            
            if (validLine == null) {
                println("ADB_Randomizer: No valid mCurrentFocus line found")
                println("ADB_Randomizer: All lines: ${lines.joinToString(" | ")}")
                // Пробуем альтернативный метод
                println("ADB_Randomizer: Falling back to getTopActivity method...")
                val topActivityResult = getTopActivity(device)
                val topActivity = topActivityResult.getOrNull()
                if (topActivity != null) {
                    println("ADB_Randomizer: Successfully got activity from alternative method: ${topActivity.first}/${topActivity.second}")
                    return@runDeviceOperation topActivity
                } else {
                    println("ADB_Randomizer: Alternative method also failed to get activity")
                    return@runDeviceOperation null
                }
            }
            
            println("ADB_Randomizer: Processing line: $validLine")
            
            // Паттерн для извлечения package и activity из mCurrentFocus
            // Пример: mCurrentFocus=Window{1234567 u0 com.example.app/.MainActivity}
            val pattern = Pattern.compile("Window\\{[^}]+\\s+u\\d+\\s+([^/]+)/([^}\\s]+)}")
            val matcher = pattern.matcher(validLine)
            
            if (matcher.find()) {
                val packageName = matcher.group(1)
                val activityName = matcher.group(2)
                println("ADB_Randomizer: Pattern matched! Package: $packageName, Activity: $activityName")
                
                // Проверяем только специфические системные диалоги и оверлеи
                // НЕ проверяем Chrome, YouTube и другие обычные приложения!
                if (packageName == "com.android.vending" || // Google Play Store dialogs
                    packageName == "com.google.android.gms" || // Google Services
                    packageName == "com.android.systemui") { // System UI overlays
                    
                    println("ADB_Randomizer: Detected system overlay/dialog: $packageName")
                    println("ADB_Randomizer: Looking for actual app behind the dialog...")
                    
                    // Пытаемся получить реальное приложение из Recent tasks
                    val recentTaskResult = getRecentTask(device)
                    val recentTask = recentTaskResult.getOrNull()
                    
                    if (recentTask != null) {
                        println("ADB_Randomizer: Found actual app from recent tasks: ${recentTask.first}/${recentTask.second}")
                        return@runDeviceOperation recentTask
                    } else {
                        println("ADB_Randomizer: Could not find actual app from recent tasks")
                        // Возвращаем системное приложение, оно будет отфильтровано позже
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "Found focused app: %s/%s", packageName, activityName)
                        return@runDeviceOperation Pair(packageName, activityName)
                    }
                }
                
                PluginLogger.info(LogCategory.ADB_CONNECTION, "Found focused app: %s/%s", packageName, activityName)
                Pair(packageName, activityName)
            } else {
                println("ADB_Randomizer: Pattern did not match, trying alternative pattern...")
                // Альтернативный паттерн для других форматов вывода
                val altPattern = Pattern.compile("([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)")
                val altMatcher = altPattern.matcher(validLine)
                if (altMatcher.find()) {
                    val packageName = altMatcher.group(1)
                    val activityName = altMatcher.group(2)
                    println("ADB_Randomizer: Alternative pattern matched! Package: $packageName, Activity: $activityName")
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "Found focused app (alt pattern): %s/%s", packageName, activityName)
                    Pair(packageName, activityName)
                } else {
                    println("ADB_Randomizer: Neither pattern matched the line: $validLine")
                    println("ADB_Randomizer: Falling back to getTopActivity method...")
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "No focused app found - patterns did not match line: %s", validLine)
                    
                    // Если не удалось получить фокусное приложение через mCurrentFocus,
                    // пробуем альтернативный метод через mResumedActivity
                    val topActivityResult = getTopActivity(device)
                    val topActivity = topActivityResult.getOrNull()
                    if (topActivity != null) {
                        println("ADB_Randomizer: Successfully got activity from alternative method: ${topActivity.first}/${topActivity.second}")
                        topActivity
                    } else {
                        println("ADB_Randomizer: Alternative method also failed to get activity")
                        null
                    }
                }
            }
        }
    }
    
    fun stopApp(device: IDevice, packageName: String): Result<Unit> {
        return runDeviceOperation(device.name, "stop app $packageName") {
            val command = "am force-stop $packageName"
            PluginLogger.info(LogCategory.ADB_CONNECTION, "Stopping app %s on device %s", packageName, device.name)
            device.executeShellCommand(command, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(command, device.name, true)
            PluginLogger.info(LogCategory.ADB_CONNECTION, "App %s stopped on device %s", packageName, device.name)
        }
    }
    
    fun startApp(device: IDevice, packageName: String, activityName: String): Result<Unit> {
        return runDeviceOperation(device.name, "start app $packageName/$activityName") {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "Starting app %s/%s on device %s", packageName, activityName, device.name)
            val fullActivityName = if (activityName.startsWith(".")) {
                "$packageName$activityName"
            } else {
                activityName
            }
            val command = "am start -n $packageName/$fullActivityName"
            device.executeShellCommand(command, NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            PluginLogger.commandExecuted(command, device.name, true)
            PluginLogger.info(LogCategory.ADB_CONNECTION, "App %s started on device %s", packageName, device.name)
        }
    }
    
    fun isSystemApp(device: IDevice, packageName: String): Result<Boolean> {
        return runDeviceOperation(device.name, "check if system app $packageName") {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "Checking if %s is a system app on device %s", packageName, device.name)
            // Проверяем, является ли приложение системным
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("pm list packages -s | grep $packageName", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            val isSystem = output.contains("package:$packageName")
            
            // Дополнительная проверка на известные лаунчеры
            val knownLaunchers = listOf(
                "com.android.launcher",
                "com.android.launcher2",
                "com.android.launcher3",
                "com.google.android.launcher",
                "com.sec.android.app.launcher",
                "com.miui.home",
                "com.oppo.launcher",
                "com.oneplus.launcher",
                "com.huawei.android.launcher"
            )
            
            val result = isSystem || knownLaunchers.contains(packageName)
            PluginLogger.info(LogCategory.ADB_CONNECTION, 
                "App %s is %s (system check: %s, launcher check: %s)", 
                packageName, 
                if (result) "a system app" else "NOT a system app",
                isSystem,
                knownLaunchers.contains(packageName)
            )
            result
        }
    }
    
    private fun tryAutoSwitchWifi(targetDeviceIp: String): String? {
        try {
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] ===== Starting auto WiFi switch =====")
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Target device IP: %s", targetDeviceIp)
            
            // Получаем SSID Wi-Fi сети на ПК
            val hostSSIDResult = WifiNetworkServiceOptimized.getHostWifiSSID()
            val hostSSID = hostSSIDResult.getOrNull()
            
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Host SSID detection result: %s", 
                if (hostSSIDResult.isSuccess()) "Success" else "Failed")
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Host SSID: %s", hostSSID ?: "null")
            
            if (hostSSID == null) {
                PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Host is not connected to WiFi, skipping auto-switch")
                return null
            }
            
            // Пытаемся найти устройство через USB для переключения Wi-Fi
            val devicesResult = getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: emptyList()
            
            // Ищем USB подключенное устройство с таким же IP
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Found %d connected devices", devices.size)
            PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Looking for USB device with IP %s", targetDeviceIp)
            
            for (device in devices) {
                val isWifi = DeviceConnectionUtils.isWifiConnection(device.serialNumber)
                PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Device %s: isWifi=%s", device.serialNumber, isWifi)
                
                if (!isWifi) {
                    // Это USB устройство, проверяем его IP
                    PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Checking IP for USB device: %s", device.serialNumber)
                    val deviceIpResult = getDeviceIpAddress(device)
                    var deviceIp = deviceIpResult.getOrNull()
                    
                    PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Device %s IP: %s", device.serialNumber, deviceIp ?: "not detected")
                    
                    // Если не удалось получить IP, пробуем еще раз с задержкой
                    if (deviceIp == null) {
                        Thread.sleep(1000)
                        val retryResult = getDeviceIpAddress(device)
                        deviceIp = retryResult.getOrNull()
                    }
                    
                    if (deviceIp == targetDeviceIp) {
                        // Нашли нужное устройство
                        PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] FOUND! USB device %s has target IP %s", device.serialNumber, targetDeviceIp)
                        
                        // Проверяем текущий Wi-Fi на устройстве
                        PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Getting device WiFi SSID...")
                        val deviceSSIDResult = WifiNetworkServiceOptimized.getDeviceWifiSSID(device)
                        val deviceSSID = deviceSSIDResult.getOrNull()
                        
                        PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Device SSID detection result: %s", 
                            if (deviceSSIDResult.isSuccess()) "Success" else "Failed")
                        PluginLogger.info(LogCategory.NETWORK, "[AUTO-SWITCH] Device SSID: '%s'", deviceSSID ?: "null")
                        
                        if (!deviceSSIDResult.isSuccess()) {
                            deviceSSIDResult.onError { exception, message ->
                                PluginLogger.warn(LogCategory.NETWORK, "[AUTO-SWITCH] SSID detection error: %s", 
                                    exception, message ?: exception.message)
                            }
                        }
                        
                        // Получаем API уровень для проверки старых устройств
                        // Если не удалось определить - считаем устройство старым (API 23)
                        val apiLevel = device.getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull() ?: 23
                        
                        // Если SSID не удалось определить на старом устройстве - не блокируем
                        if (deviceSSID == null && apiLevel <= 23) {
                            PluginLogger.info("Could not detect SSID on old Android device (API %d), skipping network check", apiLevel)
                            NotificationUtils.showWarning(
                                "Wi-Fi Check Skipped",
                                "Could not detect Wi-Fi network on Android ${device.getProperty(IDevice.PROP_BUILD_VERSION) ?: "6 or older"}. Attempting connection anyway."
                            )
                            // Продолжаем попытку подключения без проверки сети
                        } else if (deviceSSID != hostSSID) {
                            // Нужно переключить сеть - проверяем root доступ
                            val hasRoot = WifiNetworkServiceOptimized.hasRootAccess(device)
                            
                            if (!hasRoot) {
                                // Нет root - требуется ручное переключение (Shizuku не помогает с Wi-Fi)
                                PluginLogger.info("Device needs manual WiFi switch from '%s' to '%s' (no root access)", 
                                    deviceSSID ?: "unknown", hostSSID)
                                
                                // Для устройств где не удалось определить SSID
                                if (deviceSSID == null) {
                                    NotificationUtils.showWarning(
                                        "Wi-Fi Network Check",
                                        String.format("Please ensure device is on '%s' network. Could not detect current network.", hostSSID)
                                    )
                                    // Если мы здесь, значит apiLevel > 23 (иначе бы сработал блок выше)
                                    throw ManualWifiSwitchRequiredException(
                                        "Manual switch to '$hostSSID' network required (current network unknown)"
                                    )
                                } else {
                                    NotificationUtils.showWarning(
                                        "Manual Wi-Fi Switch Required",
                                        String.format("Please switch device to '%s' network (currently on '%s'). Root access required for automatic switching.", 
                                            hostSSID, deviceSSID)
                                    )
                                    // Выбрасываем специальное исключение для прерывания подключения
                                    throw ManualWifiSwitchRequiredException(
                                        "Manual switch to '$hostSSID' network required (currently on '$deviceSSID')"
                                    )
                                }
                            }
                            
                            // Есть root - автоматически переключаем
                            PluginLogger.info("Switching device to host WiFi network '%s' using root access", hostSSID)
                            val switchResult = WifiNetworkServiceOptimized.switchDeviceToWifiFast(device, hostSSID)
                            
                            switchResult.onError { exception, message ->
                                PluginLogger.warn("Failed to auto-switch WiFi: %s", message ?: exception.message)
                            }
                            
                            if (switchResult.isSuccess()) {
                                PluginLogger.info("Successfully initiated WiFi switch to: %s", hostSSID)
                                // Даем время на переключение сети
                                Thread.sleep(2000) // Уменьшаем начальную задержку
                                
                                // Ждём пока устройство получит новый IP после переключения сети
                                var newIp: String?
                                var attempts = 0
                                val maxAttempts = 6 // Ещё уменьшаем количество попыток
                                
                                PluginLogger.info("Waiting for device to get new IP address after WiFi switch...")
                                
                                // Сначала проверяем кеш (параллельная проверка могла уже сохранить IP)
                                Thread.sleep(1000)
                                newIp = WifiNetworkServiceOptimized.getDeviceIpWithCache(device)
                                
                                while (attempts < maxAttempts && newIp == null) {
                                    Thread.sleep(1000) // Ещё уменьшаем интервал
                                    
                                    // Пытаемся получить новый IP (с кешем)
                                    newIp = WifiNetworkServiceOptimized.getDeviceIpWithCache(device)
                                    
                                    if (newIp != null && ValidationUtils.isUsableIpAddress(newIp)) {
                                        PluginLogger.info("Device got new IP address: %s (was: %s)", newIp, targetDeviceIp)
                                        break
                                    }
                                    
                                    attempts++
                                    PluginLogger.debug("Attempt %d/%d: waiting for IP...", attempts, maxAttempts)
                                }
                                
                                if (newIp == null) {
                                    PluginLogger.warn("Device did not get IP address after WiFi switch")
                                    // Попробуем включить TCP/IP ещё раз
                                    PluginLogger.info("Re-enabling TCP/IP after network switch")
                                    enableTcpIp(device, 5555)
                                    Thread.sleep(3000)
                                    
                                    // Последняя попытка получить IP
                                    val finalIpResult = getDeviceIpAddress(device)
                                    newIp = finalIpResult.getOrNull()
                                }
                                
                                // Возвращаем новый IP если он изменился
                                if (newIp != null && newIp != targetDeviceIp) {
                                    PluginLogger.info("Will use new IP for connection: %s", newIp)
                                    return newIp
                                }
                            }
                        } else {
                            PluginLogger.debug("Device already on the same WiFi network")
                        }
                        
                        return null
                    }
                }
            }
        } catch (e: ManualWifiSwitchRequiredException) {
            // Пробрасываем это исключение дальше - нужно прервать подключение
            // Не логируем как ошибку, так как это ожидаемое поведение
            throw e
        } catch (e: Exception) {
            PluginLogger.warn("Error during WiFi check: %s", e.message)
            // Продолжаем с обычным подключением даже если проверка не удалась
        }
        return null
    }
    
    /**
     * Получает дефолтный DPI устройства
     * 
     * @param device устройство
     * @return Result с дефолтным DPI или ошибкой
     */
    fun getDefaultDpi(device: IDevice): Result<Int> {
        return runDeviceOperation(device.name, "get default DPI") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm density", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            // Вывод обычно выглядит как:
            // Physical density: 420
            // Override density: 480
            // Ищем физическую плотность (это дефолтная)
            val physicalDensityPattern = Pattern.compile("Physical density: (\\d+)")
            val matcher = physicalDensityPattern.matcher(output)
            
            if (matcher.find()) {
                val dpi = matcher.group(1).toInt()
                PluginLogger.debug("Default (physical) DPI for device %s: %d", device.name, dpi)
                dpi
            } else {
                // Если нет физической плотности, пробуем найти просто плотность
                val simpleDensityPattern = Pattern.compile("(\\d+)")
                val simpleMatcher = simpleDensityPattern.matcher(output)
                if (simpleMatcher.find()) {
                    val dpi = simpleMatcher.group(0).toInt()
                    PluginLogger.debug("Default DPI for device %s (fallback): %d", device.name, dpi)
                    dpi
                } else {
                    throw Exception("Could not parse default DPI from output: $output")
                }
            }
        }
    }
    
    /**
     * Отключает Wi-Fi устройство
     * 
     * @param ipAddress IP адрес устройства (без порта)
     * @return Result успешности отключения
     */
    fun disconnectWifi(ipAddress: String): Result<Boolean> {
        return runAdbOperation("Disconnect Wi-Fi") {
            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                PluginLogger.warn(LogCategory.ADB_CONNECTION, "ADB executable not found")
                return@runAdbOperation false
            }
            
            // Формируем полный адрес с портом
            val target = if (ipAddress.contains(":")) ipAddress else "$ipAddress:5555"
            
            PluginLogger.info("Disconnecting from %s", target)
            
            val disconnectCmd = ProcessBuilder(adbPath, "disconnect", target)
            disconnectCmd.redirectErrorStream(true)
            
            val process = disconnectCmd.start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.exitValue()
                PluginLogger.info("ADB disconnect output: '%s' (exit code: %d)", output, exitCode)
                
                // Проверяем результат
                val success = output.contains("disconnected") || 
                             output.contains("no such device") || // Уже отключено
                             exitCode == 0
                
                if (success) {
                    PluginLogger.info("Successfully disconnected from %s", target)
                } else {
                    PluginLogger.warn("Failed to disconnect from %s", target)
                }
                
                return@runAdbOperation success
            } else {
                process.destroyForcibly()
                PluginLogger.error("Disconnect command timed out")
                return@runAdbOperation false
            }
        }
    }
}