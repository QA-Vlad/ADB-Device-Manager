package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit

/**
 * Асинхронная версия AdbService с поддержкой Kotlin Coroutines.
 * Все длительные операции выполняются в IO dispatcher.
 */
object AdbServiceAsync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getConnectedDevicesAsync(project: Project): Result<List<IDevice>> = withContext(Dispatchers.IO) {
        try {
            Result.Success(AdbService.getConnectedDevices(project).getOrNull() ?: emptyList())
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    fun deviceFlow(project: Project, intervalMs: Long = 2000): Flow<List<DeviceInfo>> = flow {
        // Первый раз получаем устройства сразу, без задержки
        var isFirstEmit = true
        
        PluginLogger.warn(LogCategory.DEVICE_POLLING, "deviceFlow started with interval %d ms", intervalMs)
        
        while (currentCoroutineContext().isActive) {
            try {
                PluginLogger.warn(LogCategory.DEVICE_POLLING, "deviceFlow: Getting connected devices...")
                val startTime = System.currentTimeMillis()
                val devices = AdbService.getConnectedDevices(project).getOrNull() ?: emptyList()
                val getDevicesTime = System.currentTimeMillis() - startTime
                PluginLogger.warn(LogCategory.DEVICE_POLLING, "deviceFlow: Got %d devices in %d ms", devices.size, getDevicesTime)
                
                val ipStartTime = System.currentTimeMillis()
                val deviceInfos = coroutineScope {
                    devices.map { device ->
                        async {
                            // Получаем IP только если устройство онлайн и не является Wi-Fi устройством
                            val ip = if (device.isOnline && !DeviceConnectionUtils.isWifiConnection(device.serialNumber)) {
                                val ipResult = AdbService.getDeviceIpAddress(device)
                                if (ipResult is Result.Error) {
                                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "Failed to get IP for %s", device.serialNumber)
                                }
                                ipResult.getOrNull()
                            } else {
                                null
                            }
                            DeviceInfo(device, ip)
                        }
                    }.awaitAll()
                }
                val ipTime = System.currentTimeMillis() - ipStartTime
                PluginLogger.warn(LogCategory.DEVICE_POLLING, "deviceFlow: Got IPs for devices in %d ms", ipTime)
                
                emit(deviceInfos)
                
                if (isFirstEmit) {
                    PluginLogger.warn(LogCategory.DEVICE_POLLING, "First device flow emit: %d devices (total time: %d ms)", 
                        deviceInfos.size, System.currentTimeMillis() - startTime)
                    isFirstEmit = false
                }
            } catch (e: Exception) {
                // Логируем ошибку, но не прерываем поток
                PluginLogger.warn(LogCategory.DEVICE_POLLING, "Error in device flow: %s", e.message)
                emit(emptyList())
            }
            
            // Задержка только после первой эмиссии
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    @Suppress("unused")
    suspend fun executeShellCommandAsync(
        device: IDevice,
        command: String,
        timeoutSeconds: Long = 10
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val receiver = com.android.ddmlib.CollectingOutputReceiver()
            withTimeout(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
                device.executeShellCommand(command, receiver, timeoutSeconds, TimeUnit.SECONDS)
            }
            Result.Success(receiver.output)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
