package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
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
            val devices = AdbService.getConnectedDevices(project).getOrNull() ?: emptyList()
            val deviceInfos = coroutineScope {
                devices.map { device ->
                    async {
                        val ip = AdbService.getDeviceIpAddress(device).getOrNull()
                        DeviceInfo(device, ip)
                    }
                }.awaitAll()
            }
            emit(deviceInfos)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun applyPresetToAllDevicesAsync(
        devices: List<IDevice>,
        preset: DevicePreset,
        setSize: Boolean = true,
        setDpi: Boolean = true
    ): Result<List<Pair<IDevice, Result<Unit>>>> = withContext(Dispatchers.IO) {
        try {
            Result.Success(coroutineScope {
                devices.map { device ->
                    async { device to applyPresetToDevice(device, preset, setSize, setDpi) }
                }.awaitAll()
            })
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private suspend fun applyPresetToDevice(
        device: IDevice,
        preset: DevicePreset,
        setSize: Boolean,
        setDpi: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (setSize && preset.size.isNotBlank()) {
                val (width, height) = ValidationUtils.parseSize(preset.size)
                    ?: throw IllegalArgumentException("Invalid size format: ${preset.size}")
                AdbService.setSize(device, width, height)
            }
            if (setDpi && preset.dpi.isNotBlank()) {
                val dpiValue = ValidationUtils.parseDpi(preset.dpi)
                    ?: throw IllegalArgumentException("Invalid DPI format: ${preset.dpi}")
                AdbService.setDpi(device, dpiValue)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun resetAllDevicesAsync(
        devices: List<IDevice>,
        resetSize: Boolean = true,
        resetDpi: Boolean = true
    ): Result<Map<IDevice, ResetResult>> = withContext(Dispatchers.IO) {
        try {
            Result.Success(devices.associateWith { device ->
                ResetResult(
                    sizeReset = if (resetSize) resetSizeAsync(device) else Result.Success(Unit),
                    dpiReset = if (resetDpi) resetDpiAsync(device) else Result.Success(Unit)
                )
            })
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun connectWifiWithRetry(
        project: Project?,
        ipAddress: String,
        port: Int = 5555,
        maxAttempts: Int = 3,
        delayMs: Long = 2000
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            var success = false
            repeat(maxAttempts) { attempt ->
                val result = AdbService.connectWifi(project, ipAddress, port).getOrNull() ?: false
                if (result) {
                    success = true
                    return@repeat
                }
                if (attempt < maxAttempts - 1) delay(delayMs)
            }
            Result.Success(success)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

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

    private suspend fun resetSizeAsync(device: IDevice): Result<Unit> = withContext(Dispatchers.IO) {
        AdbService.resetSize(device)
    }

    private suspend fun resetDpiAsync(device: IDevice): Result<Unit> = withContext(Dispatchers.IO) {
        AdbService.resetDpi(device)
    }

    fun dispose() {
        scope.cancel()
    }

    data class ResetResult(
        val sizeReset: Result<Unit>,
        val dpiReset: Result<Unit>
    )
}

fun CoroutineScope.launchWithProgress(
    project: Project,
    title: String,
    canBeCancelled: Boolean = true,
    action: suspend CoroutineScope.() -> Unit
) {
    launch {
        withContext(Dispatchers.Main) {
            object : com.intellij.openapi.progress.Task.Backgroundable(project, title, canBeCancelled) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    runBlocking { action() }
                }
            }.queue()
        }
    }
} 