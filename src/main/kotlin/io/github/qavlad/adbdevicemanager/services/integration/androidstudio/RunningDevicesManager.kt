package io.github.qavlad.adbdevicemanager.services.integration.androidstudio

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.constants.AndroidStudioConstants
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.ui.ActionExecutor
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.ui.ComponentFinder
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.ui.DeviceSelector
import io.github.qavlad.adbdevicemanager.ui.dialogs.DialogTracker
import io.github.qavlad.adbdevicemanager.utils.AndroidStudioDetector
import io.github.qavlad.adbdevicemanager.utils.ConsoleLogger
import io.github.qavlad.adbdevicemanager.utils.NotificationUtils
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import javax.swing.SwingUtilities

/**
 * Manager responsible for handling Running Devices operations in Android Studio
 */
class RunningDevicesManager {
    
    private val componentFinder = ComponentFinder()
    private val deviceSelector = DeviceSelector(componentFinder)
    private val actionExecutor = ActionExecutor(componentFinder, deviceSelector)
    
    /**
     * Checks if Running Devices is active for the given device
     */
    fun isRunningDevicesActive(@Suppress("UNUSED_PARAMETER") device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        
        // We cannot reliably check if Running Devices is active without affecting the connection
        // So we'll assume it might be active and let the user decide
        PluginLogger.debug(LogCategory.GENERAL, 
            "Skipping Running Devices active check to avoid connection disruption"
        )
        
        // Always return true in Android Studio to show notification when resolution changes
        return true
    }
    
    /**
     * Checks if Running Devices has an active tab for the given device
     */
    fun hasActiveDeviceTab(device: IDevice): Boolean {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            ConsoleLogger.logRunningDevices("No project found")
            return false
        }
        
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW)
        if (runningDevicesWindow == null) {
            ConsoleLogger.logRunningDevices("Running Devices window not found")
            return false
        }
        
        // First check if window is visible
        if (!runningDevicesWindow.isVisible) {
            ConsoleLogger.logRunningDevices("Running Devices window is not visible")
        }
        
        val contentManager = runningDevicesWindow.contentManager
        val contents = contentManager.contents
        
        ConsoleLogger.logRunningDevices("Content manager: ${contentManager.javaClass.simpleName}, contents size: ${contents.size}")
        
        if (contents.isEmpty()) {
            ConsoleLogger.logRunningDevices("No tabs in Running Devices")
            
            // Try to get component hierarchy to debug
            val component = runningDevicesWindow.component
            ConsoleLogger.logRunningDevices("Window component: ${component.javaClass.simpleName}")
            
            return false
        }
        
        val deviceDisplayName = getDeviceDisplayName(device)
        val deviceSerial = device.serialNumber
        
        // Log all available tabs for debugging
        ConsoleLogger.logRunningDevices("Available tabs in Running Devices (${contents.size} total):")
        for ((index, content) in contents.withIndex()) {
            val tabTitle = content.displayName ?: ""
            val tabDescription = content.description ?: ""
            val tabClass = content.component.javaClass.simpleName ?: "null"
            ConsoleLogger.logRunningDevices("  Tab $index: title='$tabTitle', desc='$tabDescription', component=$tabClass")
        }
        
        // Check all tabs for our device
        for (content in contents) {
            val tabTitle = content.displayName ?: ""
            
            // Skip empty titles
            if (tabTitle.isBlank()) continue
            
            // Check if this tab matches our device
            if (isTabForDevice(tabTitle, device, deviceSerial, deviceDisplayName)) {
                ConsoleLogger.logRunningDevices("Found device tab: title='$tabTitle', device='$deviceDisplayName'")
                return true
            }
        }
        
        ConsoleLogger.logRunningDevices("No tab found for device '$deviceDisplayName' (serial: $deviceSerial)")
        ConsoleLogger.logRunningDevices("Searched for: serial='$deviceSerial', displayName='$deviceDisplayName'")
        return false
    }
    
    private fun isTabForDevice(tabTitle: String, device: IDevice, deviceSerial: String, deviceDisplayName: String): Boolean {
        val tabLower = tabTitle.lowercase()
        val serialLower = deviceSerial.lowercase()
        val displayLower = deviceDisplayName.lowercase()
        
        // Get just the model name without manufacturer
        val model = device.getProperty(AndroidStudioConstants.DEVICE_PROP_MODEL)?.lowercase() ?: ""
        val manufacturer = device.getProperty(AndroidStudioConstants.DEVICE_PROP_MANUFACTURER)?.lowercase() ?: ""
        
        return tabLower.contains(serialLower) || 
               tabLower.contains(displayLower) ||
               (model.isNotEmpty() && tabLower.contains(model)) ||
               (manufacturer.isNotEmpty() && model.isNotEmpty() && 
                tabLower.contains("$manufacturer $model"))
    }
    
    /**
     * Restarts Running Devices mirroring for the given device
     */
    fun restartRunningDevices(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        
        val serialNumber = device.serialNumber
        
        // Логируем начало процесса перезапуска
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: Starting restart process for device: %s", serialNumber
        )
        ConsoleLogger.logRunningDevices("Starting restart process for device: $serialNumber")
        
        // Проверяем, открыт ли диалог пресетов
        val isDialogOpen = DialogTracker.isPresetsDialogOpen()
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: PresetsDialog is open: %s", isDialogOpen
        )
        
        // Закрываем диалог пресетов, если он открыт
        if (DialogTracker.closePresetsDialogIfOpen()) {
            PluginLogger.info(LogCategory.ANDROID_STUDIO, 
                "RUNNING_DEVICES: Successfully closed PresetsDialog before restart"
            )
            ConsoleLogger.logRunningDevices("Closed PresetsDialog before Running Devices restart")
            // Даем немного времени для завершения закрытия
            Thread.sleep(AndroidStudioConstants.SHORT_DELAY)
        } else {
            PluginLogger.info(LogCategory.ANDROID_STUDIO, 
                "RUNNING_DEVICES: PresetsDialog was not open or already closed"
            )
        }
        
        // First check if there's an active tab for this device
        if (!hasActiveDeviceTab(device)) {
            ConsoleLogger.logRunningDevices("No active tab for device $serialNumber in Running Devices, skipping restart")
            PluginLogger.info(LogCategory.GENERAL, 
                "Skipping Running Devices restart - no active tab for device: %s", serialNumber
            )
            return false
        }
        
        ConsoleLogger.logRunningDevices("Attempting to restart Running Devices mirroring for device: $serialNumber")
        PluginLogger.info(LogCategory.GENERAL, 
            "Attempting to restart Running Devices mirroring for device: %s", serialNumber
        )
        
        return try {
            // Only try the tab close/open approach to avoid minimizing the window
            ConsoleLogger.logRunningDevices("Trying tab close/open method...")
            val tabRestarted = restartThroughTabCloseOpen(device)
            if (tabRestarted) {
                ConsoleLogger.logRunningDevices("Successfully restarted through tab close/open!")
                PluginLogger.info(LogCategory.GENERAL, 
                    "Successfully restarted Running Devices through tab close/open for device: %s", 
                    serialNumber
                )
                return true
            }
            
            // If failed, just notify the user without trying other methods
            ConsoleLogger.logRunningDevices("Tab close/open method failed, notifying user")
            notifyUserToRestartMirroring(device)
        } catch (e: Exception) {
            ConsoleLogger.logError("Error restarting Running Devices", e)
            PluginLogger.error("Error restarting Running Devices", e)
            notifyUserToRestartMirroring(device)
        }
    }
    
    /**
     * Restarts Running Devices mirroring for multiple devices
     * Closes all tabs first, then reopens them
     */
    fun restartRunningDevicesForMultiple(devices: List<IDevice>): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio() || devices.isEmpty()) return false
        
        // Логируем начало процесса
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: Starting restart for %d devices", devices.size
        )
        ConsoleLogger.logRunningDevices("Attempting to restart Running Devices for ${devices.size} devices")
        
        // Проверяем и закрываем диалог пресетов
        val isDialogOpen = DialogTracker.isPresetsDialogOpen()
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: PresetsDialog is open: %s", isDialogOpen
        )
        
        // Всегда пытаемся закрыть, независимо от isDialogOpen
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: Calling closePresetsDialogIfOpen()"
        )
        val dialogClosed = DialogTracker.closePresetsDialogIfOpen()
        PluginLogger.info(LogCategory.ANDROID_STUDIO, 
            "RUNNING_DEVICES: closePresetsDialogIfOpen() returned: %s", dialogClosed
        )
        
        if (dialogClosed) {
            PluginLogger.info(LogCategory.ANDROID_STUDIO, 
                "RUNNING_DEVICES: Successfully closed PresetsDialog before multiple devices restart"
            )
            ConsoleLogger.logRunningDevices("Closed PresetsDialog before Running Devices restart")
            // Даем немного времени для завершения закрытия
            Thread.sleep(AndroidStudioConstants.MEDIUM_DELAY) // Больше времени для множественного перезапуска
        } else {
            PluginLogger.info(LogCategory.ANDROID_STUDIO, 
                "RUNNING_DEVICES: PresetsDialog was not closed (was not open or failed to close)"
            )
        }
        
        // Filter devices that have active tabs
        val devicesWithTabs = devices.filter { device ->
            hasActiveDeviceTab(device)
        }
        
        if (devicesWithTabs.isEmpty()) {
            ConsoleLogger.logRunningDevices("No devices have active tabs in Running Devices")
            return false
        }
        
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return false
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW) ?: return false
        
        // Remember which device was active
        val selectedContent = runningDevicesWindow.contentManager.selectedContent
        val selectedTitle = selectedContent?.displayName ?: ""
        var activeDevice: IDevice? = null
        
        if (selectedTitle.isNotBlank()) {
            activeDevice = devicesWithTabs.find { device ->
                val deviceDisplayName = getDeviceDisplayName(device)
                selectedTitle.contains(device.serialNumber) || 
                selectedTitle.contains(deviceDisplayName)
            }
        }
        
        ConsoleLogger.logRunningDevices("Active device: ${activeDevice?.serialNumber ?: "none"}")
        ConsoleLogger.logRunningDevices("Closing all ${devicesWithTabs.size} device tabs...")
        
        // Step 1: Close all device tabs (but not the empty state)
        var closedCount = 0
        val tabsToClose = devicesWithTabs.size
        
        // Close only the device tabs
        for (i in 0 until tabsToClose) {
            val currentContent = runningDevicesWindow.contentManager.selectedContent
            
            // Check if we still have a device tab to close
            if (currentContent != null && currentContent.displayName?.isNotBlank() == true) {
                val closed = actionExecutor.executeCloseTabAction(project)
                if (closed) {
                    closedCount++
                    Thread.sleep(AndroidStudioConstants.TAB_CLOSE_DELAY)
                } else {
                    ConsoleLogger.logRunningDevices("Failed to close tab at index $i")
                    break
                }
            } else {
                ConsoleLogger.logRunningDevices("No more device tabs to close")
                break
            }
        }
        
        ConsoleLogger.logRunningDevices("Closed $closedCount tabs")
        
        // Wait a bit for everything to settle
        Thread.sleep(AndroidStudioConstants.MEDIUM_DELAY)
        
        // Make sure Running Devices window is still active
        if (!runningDevicesWindow.isActive) {
            runningDevicesWindow.activate(null)
            Thread.sleep(AndroidStudioConstants.SHORT_DELAY)
        }
        
        // Check if window is still available
        if (!runningDevicesWindow.isAvailable) {
            ConsoleLogger.logRunningDevices("Running Devices window is not available after closing tabs")
            return false
        }
        
        // Step 2: Reopen all devices, starting with the previously active one
        val devicesToOpen = if (activeDevice != null) {
            listOf(activeDevice) + devicesWithTabs.filter { it != activeDevice }
        } else {
            devicesWithTabs
        }
        
        ConsoleLogger.logRunningDevices("Reopening ${devicesToOpen.size} devices...")
        
        var openedCount = 0
        for ((index, device) in devicesToOpen.withIndex()) {
            ConsoleLogger.logRunningDevices("Opening device ${index + 1}/${devicesToOpen.size}: ${device.serialNumber}")
            
            if (actionExecutor.executeNewTabAction(device)) {
                openedCount++
                // Wait between opens to ensure stability
                if (index < devicesToOpen.size - 1) {
                    Thread.sleep(AndroidStudioConstants.LONG_DELAY)
                }
            } else {
                ConsoleLogger.logRunningDevices("Failed to open device: ${device.serialNumber}")
            }
        }
        
        ConsoleLogger.logRunningDevices("Successfully reopened $openedCount out of ${devicesToOpen.size} devices")
        return openedCount > 0
    }
    
    /**
     * Attempts to restart by closing and reopening the device tab
     */
    private fun restartThroughTabCloseOpen(device: IDevice): Boolean {
        return try {
            val serialNumber = device.serialNumber
            ConsoleLogger.logRunningDevices("Attempting tab close/open restart for device: $serialNumber")
            
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            
            // Step 1: Close the current tab
            val closeTabExecuted = actionExecutor.executeCloseTabAction(project)
            if (!closeTabExecuted) {
                ConsoleLogger.logRunningDevices("Failed to execute close tab action")
                return false
            }
            
            Thread.sleep(AndroidStudioConstants.MEDIUM_DELAY)
            
            // Step 2: Find and execute NewTabAction
            val newTabExecuted = actionExecutor.executeNewTabAction(device)
            if (!newTabExecuted) {
                ConsoleLogger.logRunningDevices("Failed to execute new tab action")
                return false
            }
            
            Thread.sleep(AndroidStudioConstants.LONG_DELAY)
            ConsoleLogger.logRunningDevices("Tab close/open sequence completed")
            true
        } catch (e: Exception) {
            ConsoleLogger.logError("Error in tab close/open restart", e)
            false
        }
    }
    
    /**
     * Notify user to manually restart mirroring
     */
    private fun notifyUserToRestartMirroring(device: IDevice): Boolean {
        return try {
            SwingUtilities.invokeLater {
                val project = ProjectManager.getInstance().openProjects.firstOrNull()
                if (project != null) {
                    NotificationUtils.showInfo(
                        project,
                        "Screen resolution changed. Please restart Running Devices mirroring for device ${device.name} to see the changes."
                    )
                } else {
                    PluginLogger.info(LogCategory.GENERAL, 
                        "Running Devices needs manual restart for device: %s", 
                        device.serialNumber
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
    
    /**
     * Gets device display name that might be shown in the UI
     */
    private fun getDeviceDisplayName(device: IDevice): String {
        // Android Studio shows device model and API level
        return try {
            val manufacturer = device.getProperty(AndroidStudioConstants.DEVICE_PROP_MANUFACTURER) ?: ""
            val model = device.getProperty(AndroidStudioConstants.DEVICE_PROP_MODEL) ?: device.serialNumber
            val apiLevel = device.getProperty(AndroidStudioConstants.DEVICE_PROP_API_LEVEL) ?: ""
            
            val displayName = if (manufacturer.isNotBlank() && model.isNotBlank()) {
                "$manufacturer $model"
            } else {
                model
            }
            
            if (apiLevel.isNotBlank()) {
                "$displayName API $apiLevel"
            } else {
                displayName
            }
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error getting device display name: ${e.message}")
            device.serialNumber
        }
    }
}