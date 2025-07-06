package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import kotlinx.coroutines.*
import javax.swing.SwingUtilities


class DevicePollingService(private val project: Project) {
    private var pollingJob: Job? = null

    fun startDevicePolling(onDevicesUpdated: (List<DeviceInfo>) -> Unit) {
        stopDevicePolling()
        pollingJob = null
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            runBlocking {
                // 1. Мгновенно получить первый список устройств через suspend-функцию
                val firstDevicesRaw = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                val firstDevices = firstDevicesRaw.map { DeviceInfo(it, null) }
                SwingUtilities.invokeLater { onDevicesUpdated(firstDevices) }
                // 2. Далее — Flow с периодическим обновлением
                AdbServiceAsync.deviceFlow(project, PluginConfig.UI.DEVICE_POLLING_INTERVAL_MS.toLong()).collect { deviceInfos ->
                    SwingUtilities.invokeLater { onDevicesUpdated(deviceInfos) }
                }
            }
        }
    }

    fun stopDevicePolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}