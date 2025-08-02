package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.qavlad.adbrandomizer.services.integration.androidstudio.ClassLoaderService
import io.github.qavlad.adbrandomizer.services.integration.androidstudio.RunningDevicesManager
import io.github.qavlad.adbrandomizer.utils.AndroidStudioDetector

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
     */
    fun isRunningDevicesActive(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        return runningDevicesManager.isRunningDevicesActive(device)
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