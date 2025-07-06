package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer

class DevicePollingService(private val project: Project) {
    
    private val deviceIpCache = ConcurrentHashMap<String, String?>()
    private var pollingTimer: Timer? = null
    
    fun startDevicePolling(onDevicesUpdated: (List<DeviceInfo>) -> Unit) {
        stopDevicePolling() // Stop any existing polling
        
        // Initial update
        updateDevices(onDevicesUpdated)
        
        // Start polling timer
        pollingTimer = Timer(5000) {
            updateDevices(onDevicesUpdated)
        }
        pollingTimer?.start()
    }
    
    private fun updateDevices(onDevicesUpdated: (List<DeviceInfo>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val devices = AdbService.getConnectedDevices(project).filter { it.isOnline }
                val deviceInfos = devices.map { device ->
                    val serial = device.serialNumber
                    
                    // Try to get IP from cache first
                    var ip = deviceIpCache[serial]
                    
                    // If not in cache or null, try to get it from device
                    if (ip == null) {
                        ip = AdbService.getDeviceIpAddress(device)
                        if (ip != null) {
                            deviceIpCache[serial] = ip
                            println("ADB_Randomizer: Device ${device.name} IP: $ip")
                        }
                    }
                    
                    DeviceInfo(device, ip)
                }
                
                // Clean up cache for disconnected devices
                val connectedSerials = devices.map { it.serialNumber }.toSet()
                deviceIpCache.keys.removeIf { it !in connectedSerials }
                
                ApplicationManager.getApplication().invokeLater {
                    onDevicesUpdated(deviceInfos)
                }
            } catch (e: Exception) {
                println("ADB_Randomizer: Error polling devices: ${e.message}")
            }
        }
    }
    
    fun stopDevicePolling() {
        pollingTimer?.stop()
        pollingTimer = null
    }
    

}