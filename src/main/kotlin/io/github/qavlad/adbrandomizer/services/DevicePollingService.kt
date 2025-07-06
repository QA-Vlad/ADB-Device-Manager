package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import kotlinx.coroutines.*
import javax.swing.SwingUtilities


class DevicePollingService(private val project: Project) {
    private var pollingJob: Job? = null
    private val pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastUpdateCallback: ((List<DeviceInfo>) -> Unit)? = null

    fun startDevicePolling(onDevicesUpdated: (List<DeviceInfo>) -> Unit) {
        lastUpdateCallback = onDevicesUpdated
        stopDevicePolling()
        
        pollingJob = pollingScope.launch {
            try {
                // 1. Мгновенно получить первый список устройств через suspend-функцию
                val firstDevicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                val firstDevices = firstDevicesRaw.map { DeviceInfo(it, null) }
                SwingUtilities.invokeLater { onDevicesUpdated(firstDevices) }
                
                // 2. Далее — Flow с периодическим обновлением
                AdbServiceAsync.deviceFlow(project, PluginConfig.UI.DEVICE_POLLING_INTERVAL_MS.toLong())
                    .collect { deviceInfos ->
                        SwingUtilities.invokeLater { onDevicesUpdated(deviceInfos) }
                    }
            } catch (e: CancellationException) {
                // Нормальная отмена корутины
                throw e
            } catch (e: Exception) {
                PluginLogger.error("Error in device polling", e)
            }
        }
    }
    
    /**
     * Форсирует немедленное обновление списка устройств
     */
    fun forceUpdate() {
        lastUpdateCallback?.let { callback ->
            pollingScope.launch {
                try {
                    val devicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                    val devices = devicesRaw.map { DeviceInfo(it, null) }
                    SwingUtilities.invokeLater { callback(devices) }
                } catch (e: Exception) {
                    PluginLogger.error("Error in force update", e)
                }
            }
        }
    }

    fun stopDevicePolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    /**
     * Освобождает все ресурсы и отменяет все корутины
     */
    fun dispose() {
        stopDevicePolling()
        pollingScope.cancel()
    }
}