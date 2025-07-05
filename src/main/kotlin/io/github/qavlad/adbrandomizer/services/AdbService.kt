// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/AdbService.kt

package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object AdbService {

    private var customBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private var lastDeviceCount = -1

    @Suppress("DEPRECATION") // AndroidDebugBridge API deprecated but no modern replacement available
    private fun getOrCreateDebugBridge(): AndroidDebugBridge? {
        if (customBridge != null && customBridge!!.isConnected) {
            return customBridge
        }

        val adbPath = AdbPathResolver.findAdbExecutable()
        if (adbPath == null) {
            println("ADB_Randomizer: ADB executable not found")
            return null
        }

        try {
            // ПРИНУДИТЕЛЬНО запускаем ADB сервер
            println("ADB_Randomizer: Starting ADB server...")
            val startServerCmd = ProcessBuilder(adbPath, "start-server")
            val process = startServerCmd.start()
            process.waitFor(5, TimeUnit.SECONDS)

            // Используем современный API где возможно
            if (!isInitialized) {
                AndroidDebugBridge.initIfNeeded(false)  // Менее deprecated чем init()
                isInitialized = true
                println("ADB_Randomizer: AndroidDebugBridge initialized")
            }

            // Используем createBridge с путем к adb
            // Подавляем deprecation warning так как нет современной альтернативы
            @Suppress("DEPRECATION")
            customBridge = AndroidDebugBridge.createBridge(adbPath, false)

            // Увеличиваем время ожидания
            var attempts = 100  // было 50, стало 100
            while (customBridge != null && !customBridge!!.isConnected && attempts > 0) {
                Thread.sleep(200)  // было 100, стало 200
                attempts--
            }

            if (customBridge != null && customBridge!!.isConnected) {
                println("ADB_Randomizer: Successfully created ADB bridge with $adbPath")
                return customBridge
            } else {
                println("ADB_Randomizer: Failed to connect ADB bridge")
                return null
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Error creating ADB bridge: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun getConnectedDevices(project: Project): List<IDevice> {
        var bridge: AndroidDebugBridge? = null

        ApplicationManager.getApplication().invokeAndWait {
            bridge = getOrCreateDebugBridge()
        }

        if (bridge == null) {
            println("ADB_Randomizer: AndroidDebugBridge is not available")
            return emptyList()
        }

        var attempts = 20
        while (!bridge!!.hasInitialDeviceList() && attempts > 0) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return emptyList()
            }
            attempts--
        }

        val devices = bridge!!.devices.filter { it.isOnline }

        // ЛОГИРУЕМ ТОЛЬКО ПРИ ИЗМЕНЕНИЯХ
        if (devices.size != lastDeviceCount) {
            println("ADB_Randomizer: Found ${devices.size} online devices")
            lastDeviceCount = devices.size
        }

        return devices
    }

    fun getDeviceIpAddress(device: IDevice): String? {
        // Список возможных Wi-Fi интерфейсов для проверки
        val wifiInterfaces = listOf("wlan0", "wlan1", "wlan2", "eth0", "rmnet_data0")

        for (interfaceName in wifiInterfaces) {
            val ip = getIpFromInterface(device, interfaceName)
            if (ip != null) return ip
        }

        // Fallback: пытаемся получить IP через другие методы
        return getIpAddressFallback(device)
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
                        return ip
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getIpAddressFallback(device: IDevice): String? {
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
                        return ip
                    }
                }
            }
            null
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
                        return ip
                    }
                }
            }
            null
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
                return Pair(width, height)
            }
            null
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
                return matcher.group(1).toInt()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun enableTcpIp(device: IDevice, port: Int = 5555) {
        // Валидация порта
        if (!ValidationUtils.isValidAdbPort(port)) {
            throw IllegalArgumentException("Port must be between 1024 and 65535, got: $port")
        }

        // Проверяем, не занят ли порт уже
        if (isPortInUse(port)) {
            println("ADB_Randomizer: Warning - Port $port might be in use")
        }

        device.executeShellCommand("tcpip $port", NullOutputReceiver(), 15, TimeUnit.SECONDS)
        Thread.sleep(1000)
    }

    // Метод для проверки занятости порта
    private fun isPortInUse(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { false }
        } catch (_: java.io.IOException) {
            true
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun connectWifi(project: Project, ipAddress: String, port: Int = 5555): Boolean {
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

            // Подключаемся
            val connectCmd = ProcessBuilder(adbPath, "connect", target)
            connectCmd.redirectErrorStream(true)

            val process = connectCmd.start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)

            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                println("ADB_Randomizer: Connect output: $output")

                val success = (process.exitValue() == 0) &&
                        (output.contains("connected to") || output.contains("already connected")) &&
                        !output.contains("failed")

                return success
            } else {
                process.destroyForcibly()
                return false
            }

        } catch (e: Exception) {
            println("ADB_Randomizer: Connect error: ${e.message}")
            false
        }
    }
}