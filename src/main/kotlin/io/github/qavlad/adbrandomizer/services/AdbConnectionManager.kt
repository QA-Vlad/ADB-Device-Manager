package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.core.runAdbOperation
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Управляет подключением к ADB.
 * Этот класс теперь является внутренним помощником для AdbService.
 */
internal object AdbConnectionManager {

    private var customBridge: AndroidDebugBridge? = null
    private var isInitialized = false
    private var lastDeviceCount = -1
    private var lastDeviceSerials = emptySet<String>()

    @Suppress("DEPRECATION")
    suspend fun getOrCreateDebugBridge(): Result<AndroidDebugBridge> = withContext(Dispatchers.IO) {
        if (customBridge != null && customBridge!!.isConnected) {
            return@withContext Result.Success(customBridge!!)
        }

        val adbPath = AdbPathResolver.findAdbExecutable()
        if (adbPath == null) {
            PluginLogger.warn(LogCategory.ADB_CONNECTION, "ADB executable not found")
            return@withContext Result.Error(Exception("ADB executable not found"), "ADB executable not found")
        }

        runAdbOperation("create debug bridge") {
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
                delay(PluginConfig.Adb.BRIDGE_CONNECTION_DELAY_MS)
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

    suspend fun getConnectedDevices(): Result<List<IDevice>> = withContext(Dispatchers.IO) {
        runAdbOperation("get connected devices") {
            val bridgeResult = getOrCreateDebugBridge()
            val bridge = when (bridgeResult) {
                is Result.Success -> bridgeResult.data
                is Result.Error -> {
                    PluginLogger.warn(LogCategory.ADB_CONNECTION, "Failed to create debug bridge: %s", bridgeResult.exception.message)
                    null
                }
            }

            if (bridge == null) {
                throw Exception("AndroidDebugBridge is not available")
            }

            var attempts = PluginConfig.Adb.DEVICE_LIST_WAIT_ATTEMPTS
            while (!bridge.hasInitialDeviceList() && attempts > 0) {
                try {
                    delay(PluginConfig.Adb.DEVICE_LIST_WAIT_DELAY_MS)
                } catch (e: CancellationException) {
                    // Rethrow CancellationException to properly handle coroutine cancellation
                    throw e
                }
                attempts--
            }

            val devices = bridge.devices.filter { it.isOnline }
            val currentSerials = devices.map { it.serialNumber }.toSet()

            // Логируем только если действительно изменился список устройств
            if (currentSerials != lastDeviceSerials) {
                PluginLogger.info("Connected devices changed: %d devices", devices.size)
                
                // Находим добавленные и удалённые устройства
                val added = currentSerials - lastDeviceSerials
                val removed = lastDeviceSerials - currentSerials
                
                added.forEach { serial ->
                    val device = devices.find { it.serialNumber == serial }
                    if (device != null) {
                        PluginLogger.deviceConnected(device.name, serial)
                    }
                }
                
                removed.forEach { serial ->
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "Device disconnected: %s", serial)
                }
                
                lastDeviceCount = devices.size
                lastDeviceSerials = currentSerials
            }

            devices
        }
    }


}