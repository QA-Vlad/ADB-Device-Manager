package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import java.util.concurrent.TimeUnit

/**
 * Управляет подключением к ADB.
 * Этот класс теперь является внутренним помощником для AdbService.
 */
internal object AdbConnectionManager {

    private var customBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private var lastDeviceCount = -1

    @Suppress("DEPRECATION")
    fun getOrCreateDebugBridge(): AndroidDebugBridge? {
        if (customBridge != null && customBridge!!.isConnected) {
            return customBridge
        }

        val adbPath = AdbPathResolver.findAdbExecutable()
        if (adbPath == null) {
            println("ADB_Randomizer: ADB executable not found")
            return null
        }

        try {
            println("ADB_Randomizer: Starting ADB server...")
            val startServerCmd = ProcessBuilder(adbPath, "start-server")
            val process = startServerCmd.start()
            process.waitFor(PluginConfig.Adb.SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!isInitialized) {
                AndroidDebugBridge.initIfNeeded(false)
                isInitialized = true
                println("ADB_Randomizer: AndroidDebugBridge initialized")
            }

            @Suppress("DEPRECATION")
            customBridge = AndroidDebugBridge.createBridge(adbPath, false)

            var attempts = PluginConfig.Adb.BRIDGE_CONNECTION_ATTEMPTS
            while (customBridge != null && !customBridge!!.isConnected && attempts > 0) {
                Thread.sleep(PluginConfig.Adb.BRIDGE_CONNECTION_DELAY_MS)
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

    fun getConnectedDevices(): List<IDevice> {
        var bridge: AndroidDebugBridge? = null

        ApplicationManager.getApplication().invokeAndWait {
            bridge = getOrCreateDebugBridge()
        }

        if (bridge == null) {
            println("ADB_Randomizer: AndroidDebugBridge is not available")
            return emptyList()
        }

        var attempts = PluginConfig.Adb.DEVICE_LIST_WAIT_ATTEMPTS
        while (!bridge!!.hasInitialDeviceList() && attempts > 0) {
            try {
                Thread.sleep(PluginConfig.Adb.DEVICE_LIST_WAIT_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return emptyList()
            }
            attempts--
        }

        val devices = bridge!!.devices.filter { it.isOnline }

        if (devices.size != lastDeviceCount) {
            println("ADB_Randomizer: Connected devices count changed: ${devices.size}")
            lastDeviceCount = devices.size
            
            devices.forEach { device ->
                println("ADB_Randomizer: Device: ${device.name} (${device.serialNumber})")
            }
        }

        return devices
    }


}