// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/AdbService.kt

package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object AdbService {

    fun getConnectedDevices(project: Project): List<IDevice> {
        var bridge: AndroidDebugBridge? = null

        ApplicationManager.getApplication().invokeAndWait {
            bridge = AndroidSdkUtils.getDebugBridge(project)
        }

        if (bridge == null) {
            println("ADB_Randomizer: AndroidDebugBridge is not available. ADB might not be started.")
            return emptyList()
        }

        var attempts = 10
        while (!bridge!!.hasInitialDeviceList() && attempts > 0) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                // Игнорируем
            }
            attempts--
        }

        return bridge!!.devices.filter { it.isOnline }
    }

    fun getDeviceIpAddress(device: IDevice): String? {
        try {
            val receiver = CollectingOutputReceiver()
            device.executeShellCommand("ip -f inet addr show wlan0", receiver, 5, TimeUnit.SECONDS)

            val ipPattern = Pattern.compile("""inet (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})""")
            val matcher = ipPattern.matcher(receiver.output)

            return if (matcher.find()) {
                matcher.group(1)
            } else {
                null
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Error getting IP address: ${e.message}")
            return null
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

    fun enableTcpIp(device: IDevice, port: Int = 5555) {
        device.executeShellCommand("tcpip $port", NullOutputReceiver(), 15, TimeUnit.SECONDS)
        Thread.sleep(1000)
    }

    /**
     * Подключается к устройству по Wi-Fi, используя adb connect.
     * Предупреждение об устаревании getAdb() подавлено, т.к. новый публичный метод недоступен.
     * Это осознанное и на данный момент единственное рабочее решение.
     */
    @Suppress("DEPRECATION")
    fun connectWifi(project: Project, ipAddress: String, port: Int = 5555): Boolean {
        return try {
            val adbPath = AndroidSdkUtils.getAdb(project)?.absolutePath
            if (adbPath == null) {
                println("ADB_Randomizer: ADB path not found. Make sure Android SDK is configured correctly.")
                return false
            }

            val processBuilder = ProcessBuilder(adbPath, "connect", "$ipAddress:$port")
            val process = processBuilder.start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)

            if (completed) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                (output.contains("connected to") || output.contains("already connected")) && !output.contains("failed")
            } else {
                process.destroyForcibly()
                false
            }
        } catch (e: Exception) {
            println("ADB_Randomizer: Failed to connect via Wi-Fi: ${e.message}")
            false
        }
    }
}