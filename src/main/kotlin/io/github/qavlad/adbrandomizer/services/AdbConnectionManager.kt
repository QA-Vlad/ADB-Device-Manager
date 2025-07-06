package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.core.runAdbOperation
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
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
    fun getOrCreateDebugBridge(): Result<AndroidDebugBridge> {
        if (customBridge != null && customBridge!!.isConnected) {
            return Result.Success(customBridge!!)
        }

        val adbPath = AdbPathResolver.findAdbExecutable()
        if (adbPath == null) {
            PluginLogger.error("ADB executable not found")
            return Result.Error(Exception("ADB executable not found"), "ADB executable not found")
        }

        return runAdbOperation("create debug bridge") {
            PluginLogger.info("Starting ADB server...")
            val startServerCmd = ProcessBuilder(adbPath, "start-server")
            val process = startServerCmd.start()
            process.waitFor(PluginConfig.Adb.SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!isInitialized) {
                AndroidDebugBridge.initIfNeeded(false)
                isInitialized = true
                PluginLogger.info("AndroidDebugBridge initialized")
            }

            @Suppress("DEPRECATION")
            customBridge = AndroidDebugBridge.createBridge(adbPath, false)

            var attempts = PluginConfig.Adb.BRIDGE_CONNECTION_ATTEMPTS
            while (customBridge != null && !customBridge!!.isConnected && attempts > 0) {
                Thread.sleep(PluginConfig.Adb.BRIDGE_CONNECTION_DELAY_MS)
                attempts--
            }

            if (customBridge != null && customBridge!!.isConnected) {
                PluginLogger.info("Successfully created ADB bridge with %s", adbPath)
                customBridge!!
            } else {
                throw Exception("Failed to connect ADB bridge after ${PluginConfig.Adb.BRIDGE_CONNECTION_ATTEMPTS} attempts")
            }
        }
    }

    fun getConnectedDevices(): Result<List<IDevice>> {
        return runAdbOperation("get connected devices") {
            var bridge: AndroidDebugBridge? = null

            ApplicationManager.getApplication().invokeAndWait {
                val bridgeResult = getOrCreateDebugBridge()
                bridge = when (bridgeResult) {
                    is Result.Success -> bridgeResult.data
                    is Result.Error -> {
                        PluginLogger.error("Failed to create debug bridge", bridgeResult.exception)
                        null
                    }
                }
            }

            if (bridge == null) {
                throw Exception("AndroidDebugBridge is not available")
            }

            var attempts = PluginConfig.Adb.DEVICE_LIST_WAIT_ATTEMPTS
            while (!bridge!!.hasInitialDeviceList() && attempts > 0) {
                try {
                    Thread.sleep(PluginConfig.Adb.DEVICE_LIST_WAIT_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw Exception("Device list wait interrupted")
                }
                attempts--
            }

            val devices = bridge!!.devices.filter { it.isOnline }

            if (devices.size != lastDeviceCount) {
                PluginLogger.info("Connected devices count changed: %d", devices.size)
                lastDeviceCount = devices.size
                
                devices.forEach { device ->
                    PluginLogger.deviceConnected(device.name, device.serialNumber)
                }
            }

            devices
        }
    }


}