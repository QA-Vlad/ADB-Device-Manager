package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object AdbService {

    fun getConnectedDevices(@Suppress("UNUSED_PARAMETER") project: Project): List<IDevice> {
        return AdbConnectionManager.getConnectedDevices()
    }

    // Overload для обратной совместимости
    fun getConnectedDevices(): List<IDevice> {
        return AdbConnectionManager.getConnectedDevices()
    }

    fun getDeviceIpAddress(device: IDevice): String? {
        return try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("ip route", receiver, 5, TimeUnit.SECONDS)
            
            val output = receiver.output
            if (output.isBlank()) return null
            
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
            addresses.minByOrNull { (interfaceName, _) ->
                when {
                    interfaceName.startsWith("wlan") -> 0
                    interfaceName.startsWith("rmnet_data") -> 1
                    interfaceName.startsWith("eth") -> 2
                    else -> 3
                }
            }?.second
            
        } catch (e: Exception) {
            println("ADB_Randomizer: Error getting device IP: ${e.message}")
            // Fallback на старый метод
            getDeviceIpAddressFallback(device)
        }
    }
    
    private fun getDeviceIpAddressFallback(device: IDevice): String? {
        // Список возможных Wi-Fi интерфейсов для проверки
        val wifiInterfaces = listOf("wlan0", "wlan1", "wlan2", "eth0", "rmnet_data0")

        for (interfaceName in wifiInterfaces) {
            val ip = getIpFromInterface(device, interfaceName)
            if (ip != null) return ip
        }

        // Fallback: пытаемся получить IP через другие методы
        return getIpAddressFallbackOld(device)
    }

    private fun getIpFromInterface(device: IDevice, interfaceName: String): String? {
        return try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("ip -f inet addr show $interfaceName", receiver, 5, TimeUnit.SECONDS)

            val output = receiver.output
            if (output.isNotBlank() && !output.contains("does not exist")) {
                val ipPattern = Pattern.compile("""inet (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = ipPattern.matcher(output)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    // Используем ValidationUtils для проверки IP
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        ip
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getIpAddressFallbackOld(device: IDevice): String? {
        // Метод 1: через netcfg (старые устройства)
        val netcfgIp = getIpFromNetcfg(device)
        if (netcfgIp != null) return netcfgIp

        // Метод 2: через ifconfig (если доступен)
        return getIpFromIfconfig(device)
    }

    private fun getIpFromNetcfg(device: IDevice): String? {
        return try {
            val netcfgReceiver = CollectingOutputReceiver()
            device.executeShellCommand("netcfg", netcfgReceiver, 5, TimeUnit.SECONDS)

            val netcfgOutput = netcfgReceiver.output
            if (netcfgOutput.isNotBlank()) {
                // Ищем строки вида: wlan0 UP 192.168.1.100/24
                val netcfgPattern = Pattern.compile("""wlan\d+\s+UP\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = netcfgPattern.matcher(netcfgOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        ip
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Error in netcfg IP detection: ${e.message}")
            null
        }
    }

    private fun getIpFromIfconfig(device: IDevice): String? {
        return try {
            val ifconfigReceiver = CollectingOutputReceiver()
            device.executeShellCommand("ifconfig wlan0", ifconfigReceiver, 5, TimeUnit.SECONDS)

            val ifconfigOutput = ifconfigReceiver.output
            if (ifconfigOutput.isNotBlank() && !ifconfigOutput.contains("not found")) {
                val ifconfigPattern = Pattern.compile("""inet addr:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = ifconfigPattern.matcher(ifconfigOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ValidationUtils.isUsableIpAddress(ip)) {
                        ip
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Error in ifconfig IP detection: ${e.message}")
            null
        }
    }

    fun resetSize(device: IDevice) {
        device.executeShellCommand("wm size reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    fun resetDpi(device: IDevice) {
        device.executeShellCommand("wm density reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    fun setSize(device: IDevice, width: Int, height: Int) {
        val command = "wm size ${width}x${height}"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    fun setDpi(device: IDevice, dpi: Int) {
        val command = "wm density $dpi"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }
    
    fun getCurrentSize(device: IDevice): Pair<Int, Int>? {
        return try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm size", receiver, 10, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            // Формат вывода: "Physical size: 1080x1920" или "Override size: 1080x1920"
            val sizePattern = Pattern.compile("""(?:Physical|Override) size: (\d+)x(\d+)""")
            val matcher = sizePattern.matcher(output)
            
            if (matcher.find()) {
                val width = matcher.group(1).toInt()
                val height = matcher.group(2).toInt()
                Pair(width, height)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    
    fun getCurrentDpi(device: IDevice): Int? {
        return try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("wm density", receiver, 10, TimeUnit.SECONDS)
            
            val output = receiver.output.trim()
            // Формат вывода: "Physical density: 480" или "Override density: 480"
            val dpiPattern = Pattern.compile("""(?:Physical|Override) density: (\d+)""")
            val matcher = dpiPattern.matcher(output)
            
            if (matcher.find()) {
                matcher.group(1).toInt()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun enableTcpIp(device: IDevice, port: Int = 5555) {
        // Валидация порта
        if (!ValidationUtils.isValidAdbPort(port)) {
            throw IllegalArgumentException("Port must be between 1024 and 65535, got: $port")
        }

        println("ADB_Randomizer: Enabling TCP/IP mode on port $port for device ${device.serialNumber}")
        
        try {
            // Включаем TCP/IP режим
            device.executeShellCommand("setprop service.adb.tcp.port $port", NullOutputReceiver(), 5, TimeUnit.SECONDS)
            Thread.sleep(500)
            
            // Перезапускаем ADB сервис на устройстве
            device.executeShellCommand("stop adbd", NullOutputReceiver(), 5, TimeUnit.SECONDS)
            Thread.sleep(500)
            device.executeShellCommand("start adbd", NullOutputReceiver(), 5, TimeUnit.SECONDS)
            Thread.sleep(1500)
            
            println("ADB_Randomizer: TCP/IP mode should be enabled on port $port")
        } catch (e: Exception) {
            // Fallback на стандартный метод
            println("ADB_Randomizer: Failed to enable TCP/IP via setprop, trying standard method: ${e.message}")
            device.executeShellCommand("tcpip $port", NullOutputReceiver(), 15, TimeUnit.SECONDS)
            Thread.sleep(2000)
        }
    }

    // Метод для проверки занятости порта
    @Suppress("UNUSED")
    private fun isPortInUse(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { false }
        } catch (_: java.io.IOException) {
            true
        }
    }

    fun connectWifi(@Suppress("UNUSED_PARAMETER") project: Project?, ipAddress: String, port: Int = 5555): Boolean {
        return try {
            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                println("ADB_Randomizer: ADB path not found.")
                return false
            }

            if (!ValidationUtils.isValidIpAddress(ipAddress)) {
                println("ADB_Randomizer: Invalid IP address: $ipAddress")
                return false
            }

            val target = "$ipAddress:$port"

            // Сначала проверяем, не подключены ли уже
            val checkCmd = ProcessBuilder(adbPath, "devices")
            val checkProcess = checkCmd.start()
            val checkOutput = checkProcess.inputStream.bufferedReader().use { it.readText() }

            if (checkOutput.contains(target)) {
                println("ADB_Randomizer: Device $target already connected")
                return true
            }

            // Отключаемся от всех устройств перед новым подключением
            val disconnectCmd = ProcessBuilder(adbPath, "disconnect")
            disconnectCmd.start().waitFor(2, TimeUnit.SECONDS)
            Thread.sleep(500)

            // Подключаемся
            println("ADB_Randomizer: Connecting to $target...")
            val connectCmd = ProcessBuilder(adbPath, "connect", target)
            connectCmd.redirectErrorStream(true)

            val process = connectCmd.start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)

            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                println("ADB_Randomizer: Connect output: $output")

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
                    Thread.sleep(1000)
                    val verifyCmd = ProcessBuilder(adbPath, "devices")
                    val verifyProcess = verifyCmd.start()
                    val verifyOutput = verifyProcess.inputStream.bufferedReader().use { it.readText() }
                    val verified = verifyOutput.contains(target)
                    println("ADB_Randomizer: Connection verified: $verified")
                    return verified
                }

                return false
            } else {
                process.destroyForcibly()
                println("ADB_Randomizer: Connection timed out")
                return false
            }

        } catch (e: Exception) {
            println("ADB_Randomizer: Connect error: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}