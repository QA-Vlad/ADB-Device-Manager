// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/AdbService.kt

package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object AdbService {

    private var customBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private var lastDeviceCount = -1

    private fun findAdbExecutable(): String? {
        val adbName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"

        // Стандартные пути для разных ОС
        val standardPaths = when {
            System.getProperty("os.name").contains("Mac") -> listOf(
                System.getProperty("user.home") + "/Library/Android/sdk/platform-tools/adb",
                "/usr/local/bin/adb",
                "/opt/homebrew/bin/adb"
            )
            System.getProperty("os.name").startsWith("Windows") -> listOf(
                System.getenv("LOCALAPPDATA") + "\\Android\\Sdk\\platform-tools\\adb.exe",
                System.getenv("USERPROFILE") + "\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
            )
            else -> listOf( // Linux
                System.getProperty("user.home") + "/Android/Sdk/platform-tools/adb",
                "/usr/bin/adb"
            )
        }

        // Проверяем стандартные пути
        for (path in standardPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                println("ADB_Randomizer: Found ADB at: $path")
                return path
            }
        }

        // Ищем в PATH
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val file = File(dir, adbName)
            if (file.exists() && file.canExecute()) {
                println("ADB_Randomizer: Found ADB in PATH at: ${file.absolutePath}")
                return file.absolutePath
            }
        }

        println("ADB_Randomizer: ADB not found in standard locations or PATH")
        return null
    }

    private fun getOrCreateDebugBridge(): AndroidDebugBridge? {
        if (customBridge != null && customBridge!!.isConnected) {
            return customBridge
        }

        val adbPath = findAdbExecutable()
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

            if (!isInitialized) {
                AndroidDebugBridge.init(false)
                isInitialized = true
                println("ADB_Randomizer: AndroidDebugBridge initialized")
            }

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
            try {
                val receiver = CollectingOutputReceiver()
                device.executeShellCommand("ip -f inet addr show $interfaceName", receiver, 5, TimeUnit.SECONDS)

                val output = receiver.output
                if (output.isNotBlank() && !output.contains("does not exist")) {
                    val ipPattern = Pattern.compile("""inet (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                    val matcher = ipPattern.matcher(output)

                    if (matcher.find()) {
                        val ip = matcher.group(1)
                        // Проверяем, что это не localhost и не служебный адрес
                        if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            return ip
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Fallback: пытаемся получить IP через другие методы
        return getIpAddressFallback(device)
    }

    private fun getIpAddressFallback(device: IDevice): String? {
        try {
            // Метод 1: через netcfg (старые устройства)
            val netcfgReceiver = CollectingOutputReceiver()
            device.executeShellCommand("netcfg", netcfgReceiver, 5, TimeUnit.SECONDS)

            val netcfgOutput = netcfgReceiver.output
            if (netcfgOutput.isNotBlank()) {
                // Ищем строки вида: wlan0 UP 192.168.1.100/24
                val netcfgPattern = Pattern.compile("""wlan\d+\s+UP\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = netcfgPattern.matcher(netcfgOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        return ip
                    }
                }
            }

            // Метод 2: через ifconfig (если доступен)
            val ifconfigReceiver = CollectingOutputReceiver()
            device.executeShellCommand("ifconfig wlan0", ifconfigReceiver, 5, TimeUnit.SECONDS)

            val ifconfigOutput = ifconfigReceiver.output
            if (ifconfigOutput.isNotBlank() && !ifconfigOutput.contains("not found")) {
                val ifconfigPattern = Pattern.compile("""inet addr:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
                val matcher = ifconfigPattern.matcher(ifconfigOutput)

                if (matcher.find()) {
                    val ip = matcher.group(1)
                    if (ip != null && !ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        return ip
                    }
                }
            }

        } catch (e: Exception) {
            println("ADB_Randomizer: Error in fallback IP detection: ${e.message}")
        }

        return null
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

    fun enableTcpIp(device: IDevice, port: Int = 5555) {
        // Валидация порта
        if (port < 1024 || port > 65535) {
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

    fun connectWifi(project: Project, ipAddress: String, port: Int = 5555): Boolean {
        return try {
            val adbPath = findAdbExecutable()
            if (adbPath == null) {
                println("ADB_Randomizer: ADB path not found.")
                return false
            }

            if (!isValidIpAddress(ipAddress)) {
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

    // Валидация IP адреса
    private fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (_: NumberFormatException) {
                false
            }
        }
    }
}