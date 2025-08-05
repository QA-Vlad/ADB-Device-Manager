package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import io.github.qavlad.adbrandomizer.core.runDeviceOperation
import io.github.qavlad.adbrandomizer.core.runAdbOperation

object AdbService {

    fun getConnectedDevices(@Suppress("UNUSED_PARAMETER") project: Project): Result<List<IDevice>> {
        return AdbConnectionManager.getConnectedDevices()
    }

    // Overload для обратной совместимости
    fun getConnectedDevices(): Result<List<IDevice>> {
        return AdbConnectionManager.getConnectedDevices()
    }
    
    fun isDeviceAuthorized(serialNumber: String): Boolean {
        return try {
            val devicesResult = getConnectedDevices()
            if (devicesResult is Result.Success) {
                devicesResult.data.any { device ->
                    device.serialNumber == serialNumber && device.isOnline
                }
            } else {
                false
            }
        } catch (e: Exception) {
            PluginLogger.error("Error checking device authorization", e)
            false
        }
    }

    fun getDeviceIpAddress(device: IDevice): Result<String> {
        return runDeviceOperation(device.name, "get IP address") {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("ip route", receiver, PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val output = receiver.output
            if (output.isBlank()) {
                throw Exception("Empty ip route output")
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
                throw Exception("No usable IP address found in ip route output")
            }
        }.onError { exception, message ->
            PluginLogger.warn("IP route method failed for device %s: %s", device.name, message ?: exception.message)
        }.flatMap {
            // Fallback на старый метод
            getDeviceIpAddressFallback(device)
        }
    }
    
    private fun getDeviceIpAddressFallback(device: IDevice): Result<String> {
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
            // Формат вывода: "Physical density: 480" или "Override density: 480"
            val dpiPattern = Pattern.compile(PluginConfig.Patterns.DPI_OUTPUT_PATTERN)
            val matcher = dpiPattern.matcher(output)
            
            if (matcher.find()) {
                val dpi = matcher.group(1).toInt()
                PluginLogger.debug("Current DPI for device %s: %d", device.name, dpi)
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

    fun enableTcpIp(device: IDevice, port: Int = 5555): Result<Unit> {
        return runDeviceOperation(device.name, "enable TCP/IP on port $port") {
            // Валидация порта
            if (!ValidationUtils.isValidAdbPort(port)) {
                throw IllegalArgumentException("Port must be between ${PluginConfig.Network.MIN_ADB_PORT} and ${PluginConfig.Network.MAX_PORT}, got: $port")
            }

            PluginLogger.info("Enabling TCP/IP mode on port %d for device %s", port, device.serialNumber)
            
            try {
                // Включаем TCP/IP режим
                device.executeShellCommand("setprop service.adb.tcp.port $port", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Thread.sleep(500)
                
                // Перезапускаем ADB сервис на устройстве
                device.executeShellCommand("stop adbd", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Thread.sleep(500)
                device.executeShellCommand("start adbd", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Thread.sleep(1500)
                
                PluginLogger.info("TCP/IP mode enabled on port %d for device %s", port, device.name)
            } catch (e: Exception) {
                // Fallback на стандартный метод
                PluginLogger.warn("Failed to enable TCP/IP via setprop, trying standard method: %s", e.message)
                device.executeShellCommand("tcpip $port", NullOutputReceiver(), PluginConfig.Adb.COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Thread.sleep(2000)
            }
        }
    }

    fun connectWifi(@Suppress("UNUSED_PARAMETER") project: Project?, ipAddress: String, port: Int = 5555): Result<Boolean> {
        return runAdbOperation("connect to Wi-Fi device $ipAddress:$port") {
            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                throw Exception("ADB path not found")
            }

            if (!ValidationUtils.isValidIpAddress(ipAddress)) {
                throw Exception("Invalid IP address: $ipAddress")
            }

            val target = "$ipAddress:$port"
            PluginLogger.wifiConnectionAttempt(ipAddress, port)

            // Сначала проверяем, не подключены ли уже
            val checkCmd = ProcessBuilder(adbPath, "devices")
            val checkProcess = checkCmd.start()
            val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }

            if (checkOutput.contains(target)) {
                PluginLogger.info("Device %s already connected", target)
                return@runAdbOperation true
            }

            // Отключаемся от всех устройств перед новым подключением
            val disconnectCmd = ProcessBuilder(adbPath, "disconnect")
            disconnectCmd.start().waitFor(2, TimeUnit.SECONDS)
            Thread.sleep(PluginConfig.Network.DISCONNECT_WAIT_MS)

            // Подключаемся
            PluginLogger.info("Connecting to %s...", target)
            val connectCmd = ProcessBuilder(adbPath, "connect", target)
            connectCmd.redirectErrorStream(true)

            val process = connectCmd.start()
            val completed = process.waitFor(PluginConfig.Adb.CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                PluginLogger.debug("Connect output: %s", output)

                // Проверяем разные варианты успешного подключения
                val success = (process.exitValue() == 0) &&
                        (output.contains("connected to $ipAddress:$port") || 
                         output.contains("connected to $target") ||
                         output.contains("already connected to $target")) &&
                        !output.contains("failed") &&
                        !output.contains("cannot connect") &&
                        !output.contains("Connection refused")

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
                        PluginLogger.wifiConnectionFailed(ipAddress, port)
                    }
                    
                    return@runAdbOperation verified
                }

                PluginLogger.wifiConnectionFailed(ipAddress, port)
                return@runAdbOperation false
            } else {
                process.destroyForcibly()
                PluginLogger.warn("Connection timed out for %s", target)
                return@runAdbOperation false
            }
        }
    }
}