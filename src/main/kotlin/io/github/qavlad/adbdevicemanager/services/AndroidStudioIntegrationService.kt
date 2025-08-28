package io.github.qavlad.adbdevicemanager.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.ClassLoaderService
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.RunningDevicesManager
import io.github.qavlad.adbdevicemanager.utils.AndroidStudioDetector

/**
 * Service for integration with Android Studio specific features like Running Devices
 * This service acts as a facade and only loads in Android Studio environment
 */
@Service(Service.Level.APP)
class AndroidStudioIntegrationService {
    
    companion object {
        val instance: AndroidStudioIntegrationService?
            get() = if (AndroidStudioDetector.isAndroidStudio()) {
                service()
            } else {
                null
            }
    }
    
    private val classLoaderService = ClassLoaderService()
    private val runningDevicesManager = RunningDevicesManager()
    
    init {
        if (AndroidStudioDetector.isAndroidStudio()) {
            classLoaderService.loadAndroidStudioClasses()
        }
    }
    
    /**
     * Checks if Running Devices is active for the given device
     * @deprecated Use hasActiveDeviceTab() for more accurate check
     */
    fun isRunningDevicesActive(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        return runningDevicesManager.isRunningDevicesActive(device)
    }
    
    /**
     * Checks if Running Devices has an active tab for the given device
     */
    fun hasActiveDeviceTab(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        return runningDevicesManager.hasActiveDeviceTab(device)
    }
    
    /**
     * Restarts Running Devices mirroring for the given device
     */
    fun restartRunningDevices(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        return runningDevicesManager.restartRunningDevices(device)
    }
    
    /**
     * Restarts Running Devices mirroring for multiple devices
     * Closes all tabs first, then reopens them
     */
    fun restartRunningDevicesForMultiple(devices: List<IDevice>): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio() || devices.isEmpty()) return false
        return runningDevicesManager.restartRunningDevicesForMultiple(devices)
    }
    
}