package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
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
        while (currentCoroutineContext().isActive) {
            try {
                val devices = AdbService.getConnectedDevices(project).getOrNull() ?: emptyList()
                val deviceInfos = coroutineScope {
                    devices.map { device ->
                        async {
                            // Получаем IP только если устройство онлайн и не является Wi-Fi устройством
                            val ip = if (device.isOnline && !DeviceConnectionUtils.isWifiConnection(device.serialNumber)) {
                                AdbService.getDeviceIpAddress(device).getOrNull()
                            } else {
                                null
                            }
                            DeviceInfo(device, ip)
                        }
                    }.awaitAll()
                }
                emit(deviceInfos)
            } catch (e: Exception) {
                // Логируем ошибку, но не прерываем поток
                PluginLogger.debug("Error in device flow: ${e.message}")
                emit(emptyList())
            }
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
