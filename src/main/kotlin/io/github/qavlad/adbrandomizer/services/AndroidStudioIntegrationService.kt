package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.ide.DataManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.actionSystem.ActionToolbar
import kotlinx.coroutines.runBlocking
import io.github.qavlad.adbrandomizer.utils.AndroidStudioDetector
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.ConsoleLogger
import io.github.qavlad.adbrandomizer.utils.FileLogger
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.Component
import java.awt.Container
import java.util.concurrent.TimeUnit
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Service for integration with Android Studio specific features like Running Devices
 * This service only loads and operates in Android Studio environment
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
    
    private var mirroringServiceClass: Class<*>? = null
    private var deviceManagerClass: Class<*>? = null
    private var mirroringStateClass: Class<*>? = null
    
    init {
        if (AndroidStudioDetector.isAndroidStudio()) {
            loadAndroidStudioClasses()
        }
    }
    
    /**
     * Loads Android Studio classes using reflection
     */
    private fun loadAndroidStudioClasses() {
        try {
            // Extended list of classes to try for Android Studio 2024.1+
            val mirroringServiceClasses = listOf(
                "com.android.tools.idea.streaming.DeviceMirroringService",
                "com.android.tools.idea.streaming.device.DeviceMirroringService",
                "com.android.tools.idea.streaming.mirroring.DeviceMirroringService",
                "com.android.tools.idea.devicestreaming.DeviceMirroringService",
                "com.android.tools.idea.devicestreaming.DeviceStreamingService",
                "com.android.tools.idea.runningdevices.RunningDevicesService",
                "com.android.tools.idea.runningdevices.RunningDevicesManager",
                "com.android.tools.idea.streaming.RunningDevicesController",
                "com.android.tools.idea.devicestreaming.physical.PhysicalDeviceStreamingService",
                "com.android.tools.idea.runningdevices.physical.PhysicalDeviceController"
            )
            
            val deviceManagerClasses = listOf(
                "com.android.tools.idea.devicemanager.DeviceManagerService",
                "com.android.tools.idea.devicemanager.physical.PhysicalDeviceManager",
                "com.android.tools.idea.devicemanager.DeviceManagerController",
                "com.android.tools.idea.devicemanager.RunningDevicesManager"
            )
            
            val stateClasses = listOf(
                "com.android.tools.idea.streaming.DeviceMirroringState",
                "com.android.tools.idea.streaming.device.DeviceMirroringState",
                "com.android.tools.idea.devicestreaming.StreamingState"
            )
            
            // Try to load mirroring service classes
            for (className in mirroringServiceClasses) {
                try {
                    val clazz = Class.forName(className)
                    mirroringServiceClass = clazz
                    break
                } catch (_: ClassNotFoundException) {
                    // Continue trying other classes
                }
            }
            
            // Try to load device manager classes
            for (className in deviceManagerClasses) {
                try {
                    val clazz = Class.forName(className)
                    deviceManagerClass = clazz
                    break
                } catch (_: ClassNotFoundException) {
                    // Continue trying other classes
                }
            }
            
            // Try to load state classes
            for (className in stateClasses) {
                try {
                    val clazz = Class.forName(className)
                    mirroringStateClass = clazz
                    break
                } catch (_: ClassNotFoundException) {
                    // Continue trying other classes
                }
            }
            
            // Classes loaded - no need to notify user
            
            PluginLogger.info(LogCategory.GENERAL, "Android Studio classes scan completed")
        } catch (e: Exception) {
            ConsoleLogger.logError("Error loading Android Studio classes", e)
        }
    }
    
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
    private fun hasActiveDeviceTab(device: IDevice): Boolean {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            ConsoleLogger.logRunningDevices("No project found")
            return false
        }
        
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow("Running Devices")
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
            
            // Check if this tab matches our device - case-insensitive
            val tabLower = tabTitle.lowercase()
            
            // Try different matching patterns
            val serialLower = deviceSerial.lowercase()
            val displayLower = deviceDisplayName.lowercase()
            
            // Get just the model name without manufacturer
            val model = device.getProperty("ro.product.model")?.lowercase() ?: ""
            val manufacturer = device.getProperty("ro.product.manufacturer")?.lowercase() ?: ""
            
            val matches = tabLower.contains(serialLower) || 
                         tabLower.contains(displayLower) ||
                         (model.isNotEmpty() && tabLower.contains(model)) ||
                         (manufacturer.isNotEmpty() && model.isNotEmpty() && 
                          tabLower.contains("$manufacturer $model"))
            
            if (matches) {
                ConsoleLogger.logRunningDevices("Found device tab: title='$tabTitle', device='$deviceDisplayName'")
                return true
            }
        }
        
        ConsoleLogger.logRunningDevices("No tab found for device '$deviceDisplayName' (serial: $deviceSerial)")
        ConsoleLogger.logRunningDevices("Searched for: serial='$deviceSerial', displayName='$deviceDisplayName'")
        return false
    }
    
    /**
     * Restarts Running Devices mirroring for multiple devices
     * Closes all tabs first, then reopens them
     */
    fun restartRunningDevicesForMultiple(devices: List<IDevice>): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio() || devices.isEmpty()) return false
        
        ConsoleLogger.logRunningDevices("Attempting to restart Running Devices for ${devices.size} devices")
        
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
        val runningDevicesWindow = toolWindowManager.getToolWindow("Running Devices") ?: return false
        
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
        val actionManager = ActionManager.getInstance()
        var closedCount = 0
        val tabsToClose = devicesWithTabs.size
        
        // Close only the device tabs
        for (i in 0 until tabsToClose) {
            val currentContent = runningDevicesWindow.contentManager.selectedContent
            
            // Check if we still have a device tab to close
            if (currentContent != null && currentContent.displayName?.isNotBlank() == true) {
                val closed = executeCloseTabAction(actionManager, project)
                if (closed) {
                    closedCount++
                    Thread.sleep(300) // Small delay between closes
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
        Thread.sleep(500)
        
        // Make sure Running Devices window is still active
        if (!runningDevicesWindow.isActive) {
            runningDevicesWindow.activate(null)
            Thread.sleep(200)
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
            
            if (executeNewTabAction(device)) {
                openedCount++
                // Wait between opens to ensure stability
                if (index < devicesToOpen.size - 1) {
                    Thread.sleep(1000)
                }
            } else {
                ConsoleLogger.logRunningDevices("Failed to open device: ${device.serialNumber}")
            }
        }
        
        ConsoleLogger.logRunningDevices("Successfully reopened $openedCount out of ${devicesToOpen.size} devices")
        return openedCount > 0
    }
    
    /**
     * Restarts Running Devices mirroring for the given device
     */
    fun restartRunningDevices(device: IDevice): Boolean {
        if (!AndroidStudioDetector.isAndroidStudio()) return false
        
        val serialNumber = device.serialNumber
        
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
            // as they might minimize the window
            ConsoleLogger.logRunningDevices("Tab close/open method failed, notifying user")
            notifyUserToRestartMirroring(device)
        } catch (e: Exception) {
            ConsoleLogger.logError("Error restarting Running Devices", e)
            PluginLogger.error("Error restarting Running Devices", e)
            notifyUserToRestartMirroring(device)
        }
    }
    
    /**
     * Checks mirroring state using reflection
     * UNUSED - kept for future reference if we find a non-invasive way to check
     */
    @Suppress("unused")
    private fun checkMirroringState(serialNumber: String): Boolean {
        return try {
            // Method 1: Check through reflection if available
            mirroringServiceClass?.let { serviceClass ->
                val getInstance = serviceClass.getDeclaredMethod("getInstance")
                val service = getInstance.invoke(null)
                
                // Try to find method to check mirroring state
                val methods = serviceClass.declaredMethods
                for (method in methods) {
                    if (method.name.contains("isActive") || 
                        method.name.contains("isMirroring") ||
                        method.name.contains("getState")) {
                        try {
                            val result = method.invoke(service, serialNumber)
                            if (result is Boolean) return result
                        } catch (e: Exception) {
                            // Try next method
                        }
                    }
                }
            }
            
            // Method 2: Check if screenrecord is running (lightweight check)
            // Using dumpsys instead of ps to avoid piping issues
            val process = ProcessBuilder("adb", "-s", serialNumber, "shell", 
                "dumpsys", "activity", "services", "screenrecord")
                .start()
            process.waitFor(1, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            
            // If screenrecord service is mentioned, it might be active
            output.contains("screenrecord") && !output.contains("(nothing)")
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.GENERAL, 
                "Error checking mirroring state: ${e.message}"
            )
            false
        }
    }
    
    /**
     * Attempts to restart mirroring through Android Studio API
     * UNUSED - kept for future reference
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    private fun restartThroughAPI(device: IDevice): Boolean {
        // This would require deep integration with Android Studio internals
        // For now, we'll return false and rely on ADB method
        return false
    }
    
    /**
     * Attempts to restart through Android Studio Actions
     * UNUSED - kept for future reference if we find working action IDs
     */
    @Suppress("unused", "UNUSED_PARAMETER") 
    private fun restartThroughActions(device: IDevice): Boolean {
        return try {
            SwingUtilities.invokeLater {
                try {
                    val actionManager = ActionManager.getInstance()
                    
                    // Try to find Android Studio specific actions
                    val possibleActionIds = listOf(
                        "Android.RunningDevices.StopMirroring",
                        "Android.RunningDevices.StartMirroring",
                        "Android.DeviceManager.ToggleMirroring",
                        "Android.Device.Explorer.Refresh",
                        "DeviceMirroring.Stop",
                        "DeviceMirroring.Start"
                    )
                    
                    for (actionId in possibleActionIds) {
                        val action = actionManager.getAction(actionId)
                        if (action != null) {
                            PluginLogger.debug(LogCategory.GENERAL, 
                                "Found action: %s", actionId
                            )
                            
                            // Create action event with proper context
                            val project = ProjectManager.getInstance().openProjects.firstOrNull()
                            val dataContext = SimpleDataContext.builder()
                                .add(CommonDataKeys.PROJECT, project)
                                .build()
                            
                            val event = AnActionEvent.createFromDataContext(
                                "AdbRandomizer",
                                null,
                                dataContext
                            )
                            
                            // Execute action
                            action.actionPerformed(event)
                        }
                    }
                } catch (e: Exception) {
                    PluginLogger.debug(LogCategory.GENERAL, 
                        "Error executing actions: ${e.message}"
                    )
                }
            }
            
            // Give some time for the action to take effect
            Thread.sleep(500)
            false // We can't reliably know if it worked
        } catch (e: Exception) {
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
     * Attempts to restart by closing and reopening the device tab
     */
    private fun restartThroughTabCloseOpen(device: IDevice): Boolean {
        return try {
            val serialNumber = device.serialNumber
            ConsoleLogger.logRunningDevices("Attempting tab close/open restart for device: $serialNumber")
            
            val actionManager = ActionManager.getInstance()
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            
            // Step 1: Close the current tab
            val closeTabExecuted = executeCloseTabAction(actionManager, project)
            if (!closeTabExecuted) {
                ConsoleLogger.logRunningDevices("Failed to execute close tab action")
                return false
            }
            
            Thread.sleep(500) // Wait for tab to close
            
            // Step 2: Find and execute NewTabAction
            val newTabExecuted = executeNewTabAction(device)
            if (!newTabExecuted) {
                ConsoleLogger.logRunningDevices("Failed to execute new tab action")
                return false
            }
            
            Thread.sleep(1000) // Wait for device to connect
            ConsoleLogger.logRunningDevices("Tab close/open sequence completed")
            true
        } catch (e: Exception) {
            ConsoleLogger.logError("Error in tab close/open restart", e)
            false
        }
    }
    
    /**
     * Executes close tab action
     */
    private fun executeCloseTabAction(actionManager: ActionManager, project: com.intellij.openapi.project.Project?): Boolean {
        ConsoleLogger.logRunningDevices("Attempting to close Running Devices tab...")
        
        // Try different close tab action IDs
        val closeActionIds = listOf(
            "CloseContent",
            "CloseTab", 
            "CloseActiveTab",
            "${'$'}CloseContent",
            "CloseEditor"
        )
        
        for (actionId in closeActionIds) {
            ConsoleLogger.logRunningDevices("Trying close action: $actionId")
            val action = actionManager.getAction(actionId)
            if (action != null) {
                ConsoleLogger.logRunningDevices("Found action: $actionId (${action.javaClass.simpleName})")
                try {
                    var executed = false
                    SwingUtilities.invokeAndWait {
                        // We need to provide the Running Devices content/editor context
                        val toolWindowManager = ToolWindowManager.getInstance(project ?: return@invokeAndWait)
                        val runningDevicesWindow = toolWindowManager.getToolWindow("Running Devices")
                        
                        if (runningDevicesWindow != null && runningDevicesWindow.isVisible) {
                            ConsoleLogger.logRunningDevices("Running Devices window found and visible")
                            
                            // Get the content manager
                            val contentManager = runningDevicesWindow.contentManager
                            val selectedContent = contentManager.selectedContent
                            
                            if (selectedContent != null) {
                                ConsoleLogger.logRunningDevices("Selected content found: ${selectedContent.displayName}")
                                ConsoleLogger.logRunningDevices("Total content count: ${contentManager.contentCount}")
                                
                                // Create a data context that includes the content
                                val dataContext = SimpleDataContext.builder()
                                    .add(CommonDataKeys.PROJECT, project)
                                    .build()
                                
                                // Try to close the content directly
                                if (actionId == "CloseContent" || actionId == "${'$'}CloseContent") {
                                    ConsoleLogger.logRunningDevices("Trying to close content directly")
                                    contentManager.removeContent(selectedContent, true)
                                    executed = true
                                } else {
                                    val event = AnActionEvent.createFromDataContext(
                                        "AdbRandomizer",
                                        null,
                                        dataContext
                                    )
                                    
                                    action.update(event)
                                    if (event.presentation.isEnabled) {
                                        ConsoleLogger.logRunningDevices("Action $actionId is enabled, executing...")
                                        action.actionPerformed(event)
                                        executed = true
                                        
                                        // Return focus to Running Devices window
                                        runningDevicesWindow.activate(null, true, true)
                                        ConsoleLogger.logRunningDevices("Returned focus to Running Devices window")
                                    } else {
                                        ConsoleLogger.logRunningDevices("Action $actionId is disabled")
                                    }
                                }
                            } else {
                                ConsoleLogger.logRunningDevices("No selected content in Running Devices window")
                            }
                        } else {
                            ConsoleLogger.logRunningDevices("Running Devices window not found or not visible")
                        }
                    }
                    
                    if (executed) {
                        ConsoleLogger.logRunningDevices("Successfully executed close action: $actionId")
                        return true
                    }
                } catch (e: Exception) {
                    ConsoleLogger.logRunningDevices("Failed to execute $actionId: ${e.message}")
                }
            } else {
                ConsoleLogger.logRunningDevices("Action not found: $actionId")
            }
        }
        
        ConsoleLogger.logRunningDevices("Failed to close tab - no suitable action found or enabled")
        return false
    }
    
    /**
     * Executes new tab action and selects the device
     */
    private fun executeNewTabAction(device: IDevice): Boolean {
        return try {
            ConsoleLogger.logRunningDevices("Attempting to add new device tab...")
            
            // clickAddDeviceButton now handles device selection internally
            val success = clickAddDeviceButton(device)
            if (success) {
                ConsoleLogger.logRunningDevices("Successfully added new device tab")
                return true
            }
            
            ConsoleLogger.logRunningDevices("Failed to add new device tab")
            false
        } catch (e: Exception) {
            ConsoleLogger.logError("Error executing new tab action", e)
            false
        }
    }
    
    /**
     * Tries to click the Add Device button using multiple approaches
     */
    private fun clickAddDeviceButton(targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("=== Starting comprehensive Add Device button search ===")
        FileLogger.log("Running Devices", "Starting comprehensive Add Device button search")
        
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            ConsoleLogger.logRunningDevices("No project found")
            return false
        }
        
        // First, analyze the tool window structure for debugging
        analyzeToolWindowStructure(project)
        
        // Try multiple approaches in order of likelihood
        val actionManager = ActionManager.getInstance()
        val approaches = listOf(
            { tryToolWindowComponentSearch(project, targetDevice) },  // Move this first as it's most reliable
            { tryActionManagerApproach(actionManager, project, targetDevice) },
            { tryReflectionApproach(project) }
            // ContentManager approach removed as it doesn't work for Running Devices
        )
        
        for ((index, approach) in approaches.withIndex()) {
            ConsoleLogger.logRunningDevices("Trying approach ${index + 1} of ${approaches.size}")
            try {
                if (approach.invoke()) {
                    ConsoleLogger.logRunningDevices("Success with approach ${index + 1}!")
                    return true
                }
            } catch (e: Exception) {
                ConsoleLogger.logRunningDevices("Approach ${index + 1} failed: ${e.message}")
            }
        }
        
        ConsoleLogger.logRunningDevices("All approaches failed to click Add Device button")
        FileLogger.log("Running Devices", "All approaches failed")
        return false
    }
    
    /**
     * Analyzes and logs the tool window structure for debugging
     */
    private fun analyzeToolWindowStructure(project: com.intellij.openapi.project.Project) {
        ConsoleLogger.logRunningDevices("=== Analyzing Tool Window Structure ===")
        
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Running Devices") ?: return
        val component = toolWindow.component
        
        ConsoleLogger.logRunningDevices("Tool window component class: ${component.javaClass.name}")
        ConsoleLogger.logRunningDevices("Component bounds: ${component.bounds}")
        
        // Log component hierarchy
        logDetailedComponentHierarchy(component, 0, 5)
        
        // Check for content manager
        val contentManager = toolWindow.contentManager
        ConsoleLogger.logRunningDevices("Content manager: ${contentManager.javaClass.name}")
        ConsoleLogger.logRunningDevices("Content count: ${contentManager.contentCount}")
        ConsoleLogger.logRunningDevices("Selected content: ${contentManager.selectedContent?.displayName}")
    }
    
    /**
     * Logs detailed component hierarchy
     */
    private fun logDetailedComponentHierarchy(component: Component, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        
        val indent = "  ".repeat(depth)
        val className = component.javaClass.simpleName
        val bounds = "${component.width}x${component.height}"
        
        // Mark interesting components
        val isInteresting = className.contains("Toolbar") || className.contains("Action") || 
                          className.contains("Button") || className.contains("streaming") ||
                          className.contains("device", ignoreCase = true) || 
                          className.contains("Label") || className.contains("List") ||
                          className.contains("Menu") || className.contains("Item") ||
                          component is javax.swing.JList<*>
        
        val marker = if (isInteresting) " <<<" else ""
        
        // Also log text content for labels and buttons
        val textInfo = when (component) {
            is javax.swing.JLabel -> " text='${component.text}'"
            is javax.swing.JMenuItem -> " text='${component.text}'"
            is AbstractButton -> " text='${component.text}'"
            is javax.swing.JList<*> -> {
                val model = component.model
                val itemCount = model.size
                if (itemCount > 0) {
                    val items = (0 until itemCount).map { i -> 
                        "[$i]='${model.getElementAt(i)}'"
                    }
                    " itemCount=$itemCount, items=[${items.joinToString(", ")}]"
                } else {
                    " itemCount=0 (empty list)"
                }
            }
            else -> ""
        }
        
        ConsoleLogger.logRunningDevices("$indent$className [$bounds]$marker$textInfo")
        
        // Log ActionButton details
        if (className == "ActionButton") {
            logActionButtonDetails(component, "$indent  ")
        }
        
        if (component is Container) {
            for (child in component.components) {
                logDetailedComponentHierarchy(child, depth + 1, maxDepth)
            }
        }
    }
    
    /**
     * Logs ActionButton details
     */
    private fun logActionButtonDetails(component: Component, indent: String) {
        try {
            // Get icon
            val iconMethod = component.javaClass.getMethod("getIcon")
            val icon = iconMethod.invoke(component)
            ConsoleLogger.logRunningDevices("${indent}Icon: $icon")
            
            // Get action
            val fields = component.javaClass.declaredFields
            for (field in fields) {
                if (field.name.contains("action", ignoreCase = true)) {
                    field.isAccessible = true
                    val action = field.get(component)
                    if (action != null) {
                        ConsoleLogger.logRunningDevices("${indent}Action field '${field.name}': ${action.javaClass.name}")
                    }
                }
            }
            
            // Get tooltip
            val tooltipMethod = component.javaClass.getMethod("getToolTipText")
            val tooltip = tooltipMethod.invoke(component)
            if (tooltip != null) {
                ConsoleLogger.logRunningDevices("${indent}Tooltip: $tooltip")
            }
        } catch (_: Exception) {
            // Ignore
        }
    }
    
    
    /**
     * Tries to execute NewTab action through ActionManager
     */
    private fun tryActionManagerApproach(actionManager: ActionManager, project: com.intellij.openapi.project.Project, targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("Trying ActionManager approach...")
        
        // Extended list of possible action IDs based on Android Studio internal structure
        val possibleActionIds = listOf(
            "com.android.tools.idea.streaming.core.StreamingToolWindowManager\$NewTabAction",
            "StreamingToolWindowManager.NewTabAction",
            "RunningDevices.NewTab",
            "RunningDevices.AddDevice",
            "StreamingToolWindow.NewTab",
            "StreamingToolWindow.AddDevice",
            "Android.RunningDevices.NewTab",
            "Android.Streaming.NewTab",
            "DeviceMirroring.NewTab",
            "NewTabAction",
            // Based on UI Inspector data
            "StreamingToolWindowManager\$NewTabAction",
            "NewTab"
        )
        
        // Search for actions by pattern in all registered actions
        ConsoleLogger.logRunningDevices("Searching for NewTab actions in all registered actions...")
        val allActionIds = actionManager.getActionIdList("")
        val streamingActions = allActionIds.filter { actionId ->
            val lowerActionId = actionId.lowercase()
            
            // Exclude obvious false positives
            if (lowerActionId.contains("image") || lowerActionId.contains("asset") || 
                lowerActionId.contains("vector") || lowerActionId.contains("cpp") || 
                lowerActionId.contains("rtl") || lowerActionId.contains("component") ||
                lowerActionId.contains("deeplink") || lowerActionId.contains("designer") ||
                lowerActionId.contains("emulator") || lowerActionId.contains("legacy")) {
                return@filter false
            }
            
            // Look for streaming/running devices specific actions
            (lowerActionId.contains("streaming") || lowerActionId.contains("runningdevices") || 
             lowerActionId.contains("devicemirroring")) &&
            (lowerActionId.contains("newtab") || lowerActionId.contains("tab"))
        }
        
        // Also search for exact class names from UI Inspector
        val exactClassActions = allActionIds.filter { actionId ->
            actionId.contains("StreamingToolWindowManager") && actionId.contains("NewTabAction")
        }
        
        // Prioritize actions based on likelihood
        val prioritizedCandidates = (exactClassActions + possibleActionIds + streamingActions).distinct()
            .sortedBy { actionId ->
                when {
                    // Highest priority - exact match from UI Inspector
                    actionId.contains("StreamingToolWindowManager") && actionId.contains("NewTabAction") -> 0
                    actionId == "com.android.tools.idea.streaming.core.StreamingToolWindowManager\$NewTabAction" -> 1
                    // Medium priority - likely candidates
                    actionId.contains("RunningDevices") && actionId.contains("NewTab") -> 2
                    actionId.contains("StreamingToolWindow") && actionId.contains("NewTab") -> 3
                    // Lower priority - generic matches
                    else -> 4
                }
            }
        
        ConsoleLogger.logRunningDevices("Found ${prioritizedCandidates.size} potential NewTab actions (prioritized): ${prioritizedCandidates.take(10).joinToString(", ")}")
        FileLogger.log("Running Devices", "Potential NewTab actions: ${prioritizedCandidates.take(10).joinToString(", ")}")
        
        // Try each possible action with proper context
        for (actionId in prioritizedCandidates) {
            val action = actionManager.getAction(actionId)
            if (action != null) {
                ConsoleLogger.logRunningDevices("clickAddDeviceButton: Found action: $actionId (${action.javaClass.name})")
                FileLogger.log("Running Devices", "Found action: $actionId")
                
                return try {
                    var success = false
                    ApplicationManager.getApplication().invokeAndWait({
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Running Devices")
                        if (toolWindow != null) {
                            val dataContext = DataManager.getInstance().getDataContext(toolWindow.component)
                            val event = AnActionEvent.createFromDataContext(
                                ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                                null,
                                dataContext
                            )
                            
                            ConsoleLogger.logRunningDevices("clickAddDeviceButton: Executing action: $actionId")
                            action.actionPerformed(event)
                            FileLogger.log("Running Devices", "Action $actionId executed")
                            success = true
                        }
                    }, ModalityState.any())
                    
                    if (success) {
                        // Wait for potential dialog/popup
                        Thread.sleep(500)
                        
                        // Check if a dialog or popup appeared
                        val windows = java.awt.Window.getWindows()
                        val hasNewWindow = windows.any { window ->
                            window.isShowing && (window is javax.swing.JDialog || window is javax.swing.JWindow)
                        }
                        
                        if (hasNewWindow) {
                            ConsoleLogger.logRunningDevices("New window/dialog appeared after action execution")
                            // Select the target device or first available
                            runBlocking {
                                val deviceToSelect = targetDevice ?: run {
                                    val devices = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                                    devices.firstOrNull()
                                }
                                if (deviceToSelect != null) {
                                    selectDeviceFromPopup(deviceToSelect)
                                }
                            }
                            return true
                        } else {
                            ConsoleLogger.logRunningDevices("No new window appeared, action might be wrong")
                        }
                    }
                    false
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    // Rethrow ProcessCanceledException as required by IntelliJ Platform
                    throw e
                } catch (e: Exception) {
                    ConsoleLogger.logRunningDevices("clickAddDeviceButton: Error executing action $actionId: ${e.message}")
                    FileLogger.log("Running Devices", "Error executing action $actionId: ${e.message}")
                    // Continue to next action
                    continue
                }
            }
        }
        
        ConsoleLogger.logRunningDevices("ActionManager approach failed")
        return false
    }


    /**
     * Tries to find button through improved component search (old version)
     */
    private fun tryToolWindowComponentSearch(project: com.intellij.openapi.project.Project, targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("Trying improved component search...")
        
        // First, analyze the structure for debugging
        analyzeToolWindowStructure(project)
        
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow("Running Devices")
        
        if (runningDevicesWindow == null || !runningDevicesWindow.isVisible) {
            ConsoleLogger.logRunningDevices("Running Devices window not found or not visible")
            return false
        }
        
        // First, ensure the window has focus - but don't activate if already visible to avoid minimizing
        if (!runningDevicesWindow.isActive) {
            runningDevicesWindow.activate(null, true, false)
            Thread.sleep(200) // Give time for focus to settle
            ConsoleLogger.logRunningDevices("Activated Running Devices window to ensure focus")
        }
        
        // Try to find the toolbar - it's in the TabPanel according to UI Inspector
        // Get the parent of the tool window component to access the decorator
        val windowComponent = runningDevicesWindow.component
        val parent = windowComponent.parent
        
        ConsoleLogger.logRunningDevices("Window component: ${windowComponent.javaClass.name}")
        ConsoleLogger.logRunningDevices("Parent component: ${parent?.javaClass?.name ?: "null"}")
        
        // First, look for TabPanel specifically
        val tabPanels = mutableListOf<Component>()
        findComponentsByClassName(windowComponent, "TabPanel", tabPanels)
        if (parent != null) {
            findComponentsByClassName(parent, "TabPanel", tabPanels)
        }
        
        // Also search the entire window hierarchy
        val rootPane = SwingUtilities.getRootPane(windowComponent)
        if (rootPane != null) {
            findComponentsByClassName(rootPane, "TabPanel", tabPanels)
        }
        
        ConsoleLogger.logRunningDevices("Found ${tabPanels.size} TabPanel components")
        
        // Search for toolbars in TabPanels first
        val toolbars = mutableListOf<ActionToolbar>()
        for (tabPanel in tabPanels) {
            ConsoleLogger.logRunningDevices("Searching in TabPanel: ${tabPanel.javaClass.name}")
            findActionToolbars(tabPanel, toolbars)
            
            // Also log what's inside the TabPanel
            if (tabPanel is Container) {
                ConsoleLogger.logRunningDevices("  TabPanel has ${tabPanel.componentCount} components:")
                for (i in 0 until tabPanel.componentCount) {
                    val comp = tabPanel.getComponent(i)
                    ConsoleLogger.logRunningDevices("    Component $i: ${comp.javaClass.name}")
                }
            }
        }
        
        // If no toolbars found in TabPanel, search everywhere
        if (toolbars.isEmpty()) {
            findActionToolbars(windowComponent, toolbars)
            if (parent != null) {
                findActionToolbars(parent, toolbars)
            }
        }
        
        ConsoleLogger.logRunningDevices("Found ${toolbars.size} toolbars total")

        // Check each toolbar for the add button
        for (toolbar in toolbars) {
            ConsoleLogger.logRunningDevices("Checking toolbar: ${toolbar.javaClass.name}")
            ConsoleLogger.logRunningDevices("  Toolbar location: ${toolbar.component.location}")
            ConsoleLogger.logRunningDevices("  Toolbar parent: ${toolbar.component.parent?.javaClass?.name}")
            
            // Try to get all components from the toolbar
            val toolbarContainer = toolbar.component as Container
            ConsoleLogger.logRunningDevices("  Toolbar has ${toolbarContainer.componentCount} components")

            for (i in 0 until toolbarContainer.componentCount) {
                val comp = toolbarContainer.getComponent(i)
                ConsoleLogger.logRunningDevices("    Component $i: ${comp.javaClass.name}")

                // Check if this is an ActionButton
                if (comp.javaClass.simpleName == "ActionButton") {
                    if (checkAndClickActionButton(comp, targetDevice)) {
                        ConsoleLogger.logRunningDevices("Successfully clicked Add Device button!")
                        return true
                    }
                }
            }

            // Skip if button already clicked

            // Also try the standard way with proper context
            try {
                var actionsRetrieved = false
                var actions: Array<AnAction> = emptyArray()
                
                ApplicationManager.getApplication().invokeAndWait({
                    try {
                        val dataContext = DataManager.getInstance().getDataContext(toolbar.component)
                        val event = AnActionEvent.createFromDataContext(
                            ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                            null,
                            dataContext
                        )
                        actions = toolbar.actionGroup.getChildren(event)
                        actionsRetrieved = true
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        // Rethrow ProcessCanceledException as required by IntelliJ Platform
                        throw e
                    } catch (e: Exception) {
                        ConsoleLogger.logRunningDevices("Error getting toolbar actions: ${e.message}")
                    }
                }, ModalityState.any())
                
                if (!actionsRetrieved) {
                    continue
                }
                
                ConsoleLogger.logRunningDevices("  Got ${actions.size} actions from toolbar")
                
                for (action in actions) {
                    val actionClass = action.javaClass.name
                    val presentation = action.templatePresentation
                    ConsoleLogger.logRunningDevices("    Action: $actionClass (text: '${presentation.text}')")
                    
                    // Check for add/new tab actions
                    if (actionClass.contains("NewTabAction") || 
                        actionClass.contains("StreamingToolWindowManager") ||
                        presentation.text?.contains("Add", ignoreCase = true) == true ||
                        presentation.text?.contains("New", ignoreCase = true) == true ||
                        presentation.text?.contains("+") == true) {
                        
                        ConsoleLogger.logRunningDevices("Found potential add button action: $actionClass")
                        
                        // Execute the action
                        var success = false
                        ApplicationManager.getApplication().invokeAndWait({
                            try {
                                val dataContext = DataManager.getInstance().getDataContext(toolbar.component)
                                val actionEvent = AnActionEvent.createFromDataContext(
                                    ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                                    null,
                                    dataContext
                                )
                                action.actionPerformed(actionEvent)
                                ConsoleLogger.logRunningDevices("Successfully executed add button action")
                                success = true
                            } catch (e: Exception) {
                                ConsoleLogger.logRunningDevices("Error executing action: ${e.message}")
                                success = false
                            }
                        }, ModalityState.any())
                        
                        if (success) {
                            // Wait for the popup and select device
                            Thread.sleep(1000)
                            runBlocking {
                                val deviceToSelect = targetDevice ?: run {
                                    val devices = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                                    devices.firstOrNull()
                                }
                                if (deviceToSelect != null) {
                                    selectDeviceFromPopup(deviceToSelect)
                                }
                            }
                            return true
                        }
                    }
                }
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                // Rethrow ProcessCanceledException as required by IntelliJ Platform
                throw e
            } catch (e: Exception) {
                ConsoleLogger.logRunningDevices("Error getting toolbar actions: ${e.message}")
            }
        }
        
        // If no toolbar found, try to search for ActionButton components
        val buttons = mutableListOf<Component>()
        findComponentsByType(runningDevicesWindow.component, "ActionButton", buttons)
        
        ConsoleLogger.logRunningDevices("Found ${buttons.size} ActionButton components")
        
        for (button in buttons) {
            if (checkAndClickActionButton(button, targetDevice)) {
                return true
            }
        }
        
        ConsoleLogger.logRunningDevices("Component search failed")
        return false
    }
    
    /**
     * Checks and clicks an ActionButton if it's the add button
     */
    private fun checkAndClickActionButton(button: Component, targetDevice: IDevice? = null): Boolean {
        try {
            // Log button details
            ConsoleLogger.logRunningDevices("Checking button: ${button.javaClass.name}")
            
            // Check if button has tooltip or text
            if (button is JComponent) {
                val tooltip = button.toolTipText
                if (tooltip != null) {
                    ConsoleLogger.logRunningDevices("  Button tooltip: '$tooltip'")
                }
            }
            
            // Check for icon
            if (button is AbstractButton) {
                val icon = button.icon
                if (icon != null) {
                    ConsoleLogger.logRunningDevices("  Button has icon: ${icon.javaClass.name}")
                    // Check if icon contains add/plus in the path
                    val iconString = icon.toString()
                    if (iconString.contains("add", ignoreCase = true) || 
                        iconString.contains("plus", ignoreCase = true) ||
                        iconString.contains("new", ignoreCase = true)) {
                        ConsoleLogger.logRunningDevices("  Icon suggests this is an add button: $iconString")
                    }
                }
                
                val text = button.text
                if (text != null) {
                    ConsoleLogger.logRunningDevices("  Button text: '$text'")
                }
            }
            
            // Use reflection to get action info
            val actionField = button.javaClass.getDeclaredField("myAction")
            actionField.isAccessible = true
            val action = actionField.get(button) as? AnAction
            
            if (action != null) {
                val actionClass = action.javaClass.name
                val presentation = action.templatePresentation
                ConsoleLogger.logRunningDevices("  Action: $actionClass")
                ConsoleLogger.logRunningDevices("  Action text: '${presentation.text}'")
                ConsoleLogger.logRunningDevices("  Action description: '${presentation.description}'")
                
                // Check if this is the add button by various criteria
                if (actionClass.contains("NewTabAction") || 
                    actionClass.contains("StreamingToolWindowManager") ||
                    actionClass.contains("AddDevice") ||
                    presentation.text?.contains("Add", ignoreCase = true) == true ||
                    presentation.text?.contains("New", ignoreCase = true) == true ||
                    presentation.text?.contains("+") == true ||
                    presentation.description?.contains("Add", ignoreCase = true) == true) {
                    
                    ConsoleLogger.logRunningDevices("Found the Add Device button!")
                    
                    // Click it in EDT
                    var clicked = false
                    try {
                        ApplicationManager.getApplication().invokeAndWait({
                            try {
                                // ActionButton is not AbstractButton, so we need to click it differently
                                if (button is AbstractButton) {
                                    // Standard Swing button
                                    button.doClick()
                                    ConsoleLogger.logRunningDevices("Clicked Swing button successfully")
                                    clicked = true
                                } else {
                                    // IntelliJ ActionButton - use reflection to click
                                    ConsoleLogger.logRunningDevices("Attempting to click ActionButton via reflection")
                                    
                                    // First try to execute the action directly
                                    ConsoleLogger.logRunningDevices("Executing action directly")
                                    val dataContext = DataManager.getInstance().getDataContext(button)
                                    val event = AnActionEvent.createFromDataContext(
                                        ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                                        null,
                                        dataContext
                                    )
                                    action.actionPerformed(event)
                                    ConsoleLogger.logRunningDevices("Action executed directly")
                                    clicked = true
                                }
                            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                                // Rethrow ProcessCanceledException as required by IntelliJ Platform
                                throw e
                            } catch (e: Exception) {
                                ConsoleLogger.logRunningDevices("Error clicking button: ${e.message}")
                                ConsoleLogger.logError("Failed to click button", e)
                                clicked = false
                            }
                        }, ModalityState.any())
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        // Rethrow ProcessCanceledException from invokeAndWait
                        throw e
                    }
                    
                    if (clicked) {
                        // Wait for the popup and select device
                        Thread.sleep(1000)
                        val project = ProjectManager.getInstance().openProjects.firstOrNull()
                        if (project != null) {
                            runBlocking {
                                val deviceToSelect = targetDevice ?: run {
                                    val devices = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                                    devices.firstOrNull()
                                }
                                if (deviceToSelect != null) {
                                    selectDeviceFromPopup(deviceToSelect)
                                }
                            }
                        }
                    }
                    
                    return clicked
                }
            }
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error analyzing ActionButton: ${e.message}")
        }
        return false
    }
    
    /**
     * Tries reflection-based approach to access internal APIs
     */
    private fun tryReflectionApproach(project: com.intellij.openapi.project.Project): Boolean {
        ConsoleLogger.logRunningDevices("Trying reflection approach...")
        
        return try {
            // Try to find StreamingToolWindowManager through reflection
            val streamingManagerClass = Class.forName("com.android.tools.idea.streaming.core.StreamingToolWindowManager")
            ConsoleLogger.logRunningDevices("Found StreamingToolWindowManager class")
            
            // Try to get instance
            val getInstance = streamingManagerClass.getDeclaredMethod("getInstance", com.intellij.openapi.project.Project::class.java)
            val instance = getInstance.invoke(null, project)
            
            if (instance != null) {
                ConsoleLogger.logRunningDevices("Got StreamingToolWindowManager instance")
                
                // Look for methods that might add a new tab
                val methods = streamingManagerClass.declaredMethods
                for (method in methods) {
                    ConsoleLogger.logRunningDevices("Available method: ${method.name}")
                    if (method.name.contains("newTab", ignoreCase = true) || 
                        method.name.contains("addTab", ignoreCase = true) ||
                        method.name.contains("createTab", ignoreCase = true)) {
                        
                        try {
                            method.isAccessible = true
                            method.invoke(instance)
                            ConsoleLogger.logRunningDevices("Successfully called ${method.name}")
                            return true
                        } catch (e: Exception) {
                            ConsoleLogger.logRunningDevices("Failed to call ${method.name}: ${e.message}")
                        }
                    }
                }
            }
            
            false
        } catch (_: ClassNotFoundException) {
            ConsoleLogger.logRunningDevices("StreamingToolWindowManager class not found")
            false
        } catch (e: Exception) {
            ConsoleLogger.logError("Error in reflection approach", e)
            false
        }
    }

    /**
     * Finds ActionToolbar components specifically
     */
    private fun findActionToolbars(component: Component, result: MutableList<ActionToolbar>) {
        // Check if this component is an ActionToolbar
        if (component is ActionToolbar) {
            result.add(component)
            ConsoleLogger.logRunningDevices("Found ActionToolbar: ${component.javaClass.name}")
        }
        
        if (component is Container) {
            for (child in component.components) {
                findActionToolbars(child, result)
            }
        }
    }
    
    /**
     * Finds components by type name
     */
    private fun findComponentsByType(component: Component, typeName: String, result: MutableList<Component>) {
        if (component.javaClass.simpleName.contains(typeName)) {
            result.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                findComponentsByType(child, typeName, result)
            }
        }
    }
    
    /**
     * Finds components by class name
     */
    private fun findComponentsByClassName(component: Component, className: String, result: MutableList<Component>) {
        if (component.javaClass.name.contains(className) || component.javaClass.simpleName.contains(className)) {
            result.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                findComponentsByClassName(child, className, result)
            }
        }
    }
    
    /**
     * Logs component hierarchy for debugging
     */
    private fun logComponentHierarchy(component: Component, level: Int, maxLevel: Int) {
        if (level > maxLevel) return
        
        val indent = "  ".repeat(level)
        val info = "${component.javaClass.simpleName} (${component.javaClass.name})"
        ConsoleLogger.logRunningDevices("$indent$info")
        
        if (component is Container) {
            ConsoleLogger.logRunningDevices("$indent  Children: ${component.componentCount}")
            for (child in component.components) {
                logComponentHierarchy(child, level + 1, maxLevel)
            }
        }
    }

    /**
     * Finds all toolbars in the component tree
     */
    private fun findToolbars(component: Component): List<javax.swing.JToolBar> {
        val toolbars = mutableListOf<javax.swing.JToolBar>()
        
        if (component is javax.swing.JToolBar) {
            toolbars.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                toolbars.addAll(findToolbars(child))
            }
        }
        
        return toolbars
    }
    
    /**
     * Checks if a component is the Add Device button
     */
    private fun checkIfAddButton(component: Component): AbstractButton? {
        // Check if this is ActionButton (IntelliJ's button type)
        if (component.javaClass.simpleName == "ActionButton") {
            ConsoleLogger.logRunningDevices("Found ActionButton, checking properties...")
            
            try {
                // Try to check if it's the add button by its icon
                val iconMethod = component.javaClass.getMethod("getIcon")
                val icon = iconMethod.invoke(component)
                if (icon != null) {
                    val iconString = icon.toString()
                    ConsoleLogger.logRunningDevices("ActionButton icon: $iconString")
                    if (iconString.contains("add") || iconString.contains("plus") || iconString.contains("+")) {
                        ConsoleLogger.logRunningDevices("Found Add Device button by icon!")
                        return component as? AbstractButton
                    }
                }
                
                // Try to check accessibleName and description
                val accessibleContext = component.accessibleContext
                if (accessibleContext != null) {
                    val accessibleName = accessibleContext.accessibleName
                    val accessibleDescription = accessibleContext.accessibleDescription
                    ConsoleLogger.logRunningDevices("ActionButton accessible name: $accessibleName, description: $accessibleDescription")
                    if ((accessibleName != null && (accessibleName.contains("Add Device") || accessibleName.contains("New Tab"))) ||
                        (accessibleDescription != null && (accessibleDescription.contains("Add Device") || accessibleDescription.contains("New Tab")))) {
                        ConsoleLogger.logRunningDevices("Found Add Device button by accessible info!")
                        return component as? AbstractButton
                    }
                }
                
                // Try to get tooltip text via reflection
                try {
                    val getToolTipTextMethod = component.javaClass.getMethod("getToolTipText")
                    val toolTipText = getToolTipTextMethod.invoke(component) as? String
                    if (toolTipText != null) {
                        ConsoleLogger.logRunningDevices("ActionButton tooltip: $toolTipText")
                        if (toolTipText.contains("Add", ignoreCase = true) || 
                            toolTipText.contains("New", ignoreCase = true) ||
                            toolTipText.contains("Device", ignoreCase = true)) {
                            ConsoleLogger.logRunningDevices("Found Add Device button by tooltip!")
                            return component as? AbstractButton
                        }
                    }
                } catch (_: Exception) {
                    // Ignore if method not found
                }
                
                // Try to get the action associated with the button
                try {
                    val actionField = component.javaClass.getDeclaredField("myAction")
                    actionField.isAccessible = true
                    val action = actionField.get(component)
                    if (action != null) {
                        val actionClassName = action.javaClass.name
                        ConsoleLogger.logRunningDevices("ActionButton action class: $actionClassName")
                        if (actionClassName.contains("NewTabAction") || actionClassName.contains("StreamingToolWindowManager")) {
                            ConsoleLogger.logRunningDevices("Found Add Device button by action class!")
                            return component as? AbstractButton
                        }
                    }
                } catch (_: NoSuchFieldException) {
                    // Try alternative field names
                    ConsoleLogger.logRunningDevices("myAction field not found, trying alternatives...")
                    val fields = component.javaClass.declaredFields
                    for (field in fields) {
                        if (field.name.contains("action", ignoreCase = true)) {
                            try {
                                field.isAccessible = true
                                val action = field.get(component)
                                if (action != null) {
                                    val actionClassName = action.javaClass.name
                                    ConsoleLogger.logRunningDevices("Found action field '${field.name}' with class: $actionClassName")
                                    if (actionClassName.contains("NewTabAction") || actionClassName.contains("StreamingToolWindowManager")) {
                                        ConsoleLogger.logRunningDevices("Found Add Device button by action class!")
                                        return component as? AbstractButton
                                    }
                                }
                            } catch (ex: Exception) {
                                ConsoleLogger.logRunningDevices("Error accessing field ${field.name}: ${ex.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ConsoleLogger.logRunningDevices("Error checking ActionButton: ${e.message}")
            }
        }
        
        // Standard button checks
        if (component is AbstractButton) {
            val icon = component.icon
            if (icon != null) {
                val iconString = icon.toString()
                if (iconString.contains("add") || iconString.contains("plus") || iconString.contains("+")) {
                    ConsoleLogger.logRunningDevices("Found potential add button: ${component.javaClass.simpleName}")
                    return component
                }
            }
            
            // Also check tooltip
            val tooltip = component.toolTipText
            if (tooltip != null && (tooltip.contains("Add") || tooltip.contains("add") || tooltip.contains("New"))) {
                ConsoleLogger.logRunningDevices("Found button by tooltip: $tooltip")
                return component
            }
        }
        
        return null
    }
    
    /**
     * Recursively searches for the Add Device button
     */
    private fun findAddButtonRecursive(component: Component): AbstractButton? {
        val button = checkIfAddButton(component)
        if (button != null) {
            return button
        }
        
        if (component is Container) {
            for (child in component.components) {
                val found = findAddButtonRecursive(child)
                if (found != null) {
                    return found
                }
            }
        }
        
        return null
    }
    
    /**
     * Selects device from the popup menu
     */
    private fun selectDeviceFromPopup(device: IDevice): Boolean {
        val displayName = getDeviceDisplayName(device)
        ConsoleLogger.logRunningDevices("Attempting to select device ${device.serialNumber} (display name: $displayName) from popup")
        
        return try {
            // Wait a bit for popup to fully appear
            Thread.sleep(1000) // Give more time for popup to appear
            
            // Try to find the popup menu
            val windows = java.awt.Window.getWindows()
            for (window in windows) {
                if (window.isShowing && (window is javax.swing.JWindow || window is javax.swing.JDialog)) {
                    ConsoleLogger.logRunningDevices("Found potential popup window: ${window.javaClass.simpleName}")
                    
                    // Log all components in the window for debugging
                    logDetailedComponentHierarchy(window, 0, 3)
                    
                    // Look for menu items in the popup
                    val deviceComponent = findDeviceMenuItem(window, device)
                    if (deviceComponent != null) {
                        ConsoleLogger.logRunningDevices("Found device component (${deviceComponent.javaClass.simpleName}), clicking...")
                        
                        // Click the component - use invokeAndWait for synchronous execution
                        try {
                            ApplicationManager.getApplication().invokeAndWait({
                                try {
                                    when (deviceComponent) {
                                is javax.swing.JMenuItem -> {
                                    deviceComponent.doClick()
                                    ConsoleLogger.logRunningDevices("Clicked menu item")
                                }
                                is AbstractButton -> {
                                    deviceComponent.doClick()
                                    ConsoleLogger.logRunningDevices("Clicked button")
                                }
                                is javax.swing.JList<*> -> {
                                    // For JList, try multiple approaches
                                    ConsoleLogger.logRunningDevices("Handling JList selection")
                                    val selectedIndex = deviceComponent.selectedIndex
                                    if (selectedIndex >= 0) {
                                        val bounds = deviceComponent.getCellBounds(selectedIndex, selectedIndex)
                                        ConsoleLogger.logRunningDevices("Selected index: $selectedIndex, bounds: $bounds")
                                        
                                        // Approach 1: Fire selection event
                                        try {
                                            val selectionModel = deviceComponent.selectionModel
                                            selectionModel.setSelectionInterval(selectedIndex, selectedIndex)
                                            ConsoleLogger.logRunningDevices("Set selection interval to $selectedIndex")
                                        } catch (e: Exception) {
                                            ConsoleLogger.logRunningDevices("Error firing selection event: ${e.message}")
                                        }
                                        
                                        // Approach 2: Simulate Enter key with proper release
                                        try {
                                            val enterPressed = java.awt.event.KeyEvent(
                                                deviceComponent,
                                                java.awt.event.KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                0,
                                                java.awt.event.KeyEvent.VK_ENTER,
                                                '\n'
                                            )
                                            val enterReleased = java.awt.event.KeyEvent(
                                                deviceComponent,
                                                java.awt.event.KeyEvent.KEY_RELEASED,
                                                System.currentTimeMillis(),
                                                0,
                                                java.awt.event.KeyEvent.VK_ENTER,
                                                '\n'
                                            )
                                            
                                            deviceComponent.dispatchEvent(enterPressed)
                                            deviceComponent.dispatchEvent(enterReleased)
                                            ConsoleLogger.logRunningDevices("Dispatched Enter key events")
                                        } catch (e: Exception) {
                                            ConsoleLogger.logRunningDevices("Error dispatching Enter key: ${e.message}")
                                        }
                                        
                                        // Approach 3: Process key bindings directly
                                        try {
                                            val inputMap = deviceComponent.getInputMap(JComponent.WHEN_FOCUSED)
                                            val actionMap = deviceComponent.actionMap
                                            val enterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
                                            val actionKey = inputMap.get(enterKey)
                                            
                                            if (actionKey != null) {
                                                val action = actionMap.get(actionKey)
                                                if (action != null) {
                                                    ConsoleLogger.logRunningDevices("Found Enter action: $actionKey")
                                                    val actionEvent = java.awt.event.ActionEvent(
                                                        deviceComponent,
                                                        java.awt.event.ActionEvent.ACTION_PERFORMED,
                                                        "Enter"
                                                    )
                                                    action.actionPerformed(actionEvent)
                                                    ConsoleLogger.logRunningDevices("Executed Enter action directly")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            ConsoleLogger.logRunningDevices("Error executing action: ${e.message}")
                                        }
                                        
                                        // Approach 4: Double-click simulation
                                        if (bounds != null) {
                                            try {
                                                val x = bounds.x + bounds.width / 2
                                                val y = bounds.y + bounds.height / 2
                                                
                                                // Mouse press
                                                val pressEvent = java.awt.event.MouseEvent(
                                                    deviceComponent,
                                                    java.awt.event.MouseEvent.MOUSE_PRESSED,
                                                    System.currentTimeMillis(),
                                                    0,
                                                    x, y,
                                                    1,
                                                    false,
                                                    java.awt.event.MouseEvent.BUTTON1
                                                )
                                                
                                                // Mouse release
                                                val releaseEvent = java.awt.event.MouseEvent(
                                                    deviceComponent,
                                                    java.awt.event.MouseEvent.MOUSE_RELEASED,
                                                    System.currentTimeMillis(),
                                                    0,
                                                    x, y,
                                                    1,
                                                    false,
                                                    java.awt.event.MouseEvent.BUTTON1
                                                )
                                                
                                                // Mouse click
                                                val clickEvent = java.awt.event.MouseEvent(
                                                    deviceComponent,
                                                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                                                    System.currentTimeMillis(),
                                                    0,
                                                    x, y,
                                                    1,
                                                    false,
                                                    java.awt.event.MouseEvent.BUTTON1
                                                )
                                                
                                                // Double-click
                                                val doubleClickEvent = java.awt.event.MouseEvent(
                                                    deviceComponent,
                                                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                                                    System.currentTimeMillis(),
                                                    0,
                                                    x, y,
                                                    2, // Double-click
                                                    false,
                                                    java.awt.event.MouseEvent.BUTTON1
                                                )
                                                
                                                deviceComponent.dispatchEvent(pressEvent)
                                                deviceComponent.dispatchEvent(releaseEvent)
                                                deviceComponent.dispatchEvent(clickEvent)
                                                Thread.sleep(50)
                                                deviceComponent.dispatchEvent(doubleClickEvent)
                                                
                                                ConsoleLogger.logRunningDevices("Dispatched mouse events at ($x, $y)")
                                            } catch (e: Exception) {
                                                ConsoleLogger.logRunningDevices("Error dispatching mouse events: ${e.message}")
                                            }
                                        }
                                        
                                        ConsoleLogger.logRunningDevices("Completed all JList selection attempts")
                                    }
                                }
                                else -> {
                                    // Try to simulate mouse click for other components
                                    ConsoleLogger.logRunningDevices("Simulating mouse click on ${deviceComponent.javaClass.simpleName}")
                                    val bounds = deviceComponent.bounds
                                    deviceComponent.locationOnScreen
                                    
                                    val mouseEvent = java.awt.event.MouseEvent(
                                        deviceComponent,
                                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                                        System.currentTimeMillis(),
                                        0,
                                        bounds.width / 2,
                                        bounds.height / 2,
                                        1,
                                        false,
                                        java.awt.event.MouseEvent.BUTTON1
                                    )
                                    
                                    // Dispatch mouse events
                                    deviceComponent.dispatchEvent(java.awt.event.MouseEvent(
                                        deviceComponent,
                                        java.awt.event.MouseEvent.MOUSE_PRESSED,
                                        System.currentTimeMillis(),
                                        0,
                                        bounds.width / 2,
                                        bounds.height / 2,
                                        1,
                                        false,
                                        java.awt.event.MouseEvent.BUTTON1
                                    ))
                                    
                                    deviceComponent.dispatchEvent(java.awt.event.MouseEvent(
                                        deviceComponent,
                                        java.awt.event.MouseEvent.MOUSE_RELEASED,
                                        System.currentTimeMillis(),
                                        0,
                                        bounds.width / 2,
                                        bounds.height / 2,
                                        1,
                                        false,
                                        java.awt.event.MouseEvent.BUTTON1
                                    ))
                                    
                                    deviceComponent.dispatchEvent(mouseEvent)
                                    ConsoleLogger.logRunningDevices("Dispatched mouse events")
                                }
                            }
                            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                                // Rethrow ProcessCanceledException as required by IntelliJ Platform
                                throw e
                            } catch (e: Exception) {
                                ConsoleLogger.logRunningDevices("Error clicking device component: ${e.message}")
                            }
                        }, ModalityState.any())
                        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                            // Rethrow ProcessCanceledException from invokeAndWait
                            throw e
                        }
                        return true
                    }
                }
            }
            
            ConsoleLogger.logRunningDevices("No suitable popup window found. Total windows: ${windows.size}")
            for (window in windows) {
                if (window.isShowing) {
                    ConsoleLogger.logRunningDevices("  Window: ${window.javaClass.simpleName} (visible: ${window.isVisible})")
                }
            }
            
            false
        } catch (e: Exception) {
            ConsoleLogger.logError("Error selecting device from popup", e)
            false
        }
    }
    
    /**
     * Finds menu item for the device in the popup
     */
    private fun findDeviceMenuItem(container: Container, device: IDevice): Component? {
        val displayName = getDeviceDisplayName(device)
        ConsoleLogger.logRunningDevices("Looking for device menu item: serial=${device.serialNumber}, display=$displayName")
        
        // First, increase depth for logging to see more components
        if (container is java.awt.Window) {
            ConsoleLogger.logRunningDevices("=== Detailed popup structure ===")
            logDetailedComponentHierarchy(container, 0, 10) // Increase depth to 10
            ConsoleLogger.logRunningDevices("=== End popup structure ===")
        }
        
        // Search for clickable component with device info
        return findDeviceComponent(container, device, displayName)
    }
    
    /**
     * Recursively searches for device component
     */
    private fun findDeviceComponent(container: Container, device: IDevice, displayName: String): Component? {
        for (component in container.components) {
            // Special handling for JList
            if (component is javax.swing.JList<*>) {
                val model = component.model
                val itemCount = model.size
                ConsoleLogger.logRunningDevices("Found JList '${component.javaClass.name}' with $itemCount items")
                
                // Log all items first for debugging
                ConsoleLogger.logRunningDevices("JList contents:")
                for (i in 0 until itemCount) {
                    val item = try {
                        model.getElementAt(i)?.toString() ?: "null"
                    } catch (e: Exception) {
                        "Error getting item: ${e.message}"
                    }
                    ConsoleLogger.logRunningDevices("  Item[$i]: '$item'")
                }
                
                // Now check each item for device match
                for (i in 0 until itemCount) {
                    val item = model.getElementAt(i)?.toString() ?: continue
                    
                    ConsoleLogger.logRunningDevices("Checking item $i against serial=${device.serialNumber}, display=$displayName")
                    
                    if (item.contains(device.serialNumber) || 
                        item.contains(displayName) ||
                        item.contains(displayName, ignoreCase = true) ||
                        displayName.split(" ").any { part -> 
                            part.length > 3 && item.contains(part, ignoreCase = true) 
                        }) {
                        
                        ConsoleLogger.logRunningDevices("MATCH! Found device in JList at index $i: $item")
                        
                        // Select the item and return the list
                        component.selectedIndex = i
                        ConsoleLogger.logRunningDevices("Selected item at index $i")
                        
                        // Return the JList itself - we'll handle clicking it separately
                        return component
                    }
                }
                
                ConsoleLogger.logRunningDevices("Device not found in JList")
            }
            
            // Get text from various component types
            val text = when (component) {
                is javax.swing.JLabel -> component.text
                is javax.swing.JMenuItem -> component.text
                is AbstractButton -> component.text
                else -> null
            }
            
            if (text != null) {
                ConsoleLogger.logRunningDevices("  Checking component ${component.javaClass.simpleName}: '$text'")
                
                // Check if text contains device info
                if (text.contains(device.serialNumber) || 
                    text.contains(displayName) ||
                    // Try case-insensitive match
                    text.contains(displayName, ignoreCase = true) ||
                    // Try individual parts
                    displayName.split(" ").any { part -> 
                        part.length > 3 && text.contains(part, ignoreCase = true) 
                    }) {
                    
                    ConsoleLogger.logRunningDevices("Found device component: $text")
                    
                    // Return clickable component or its parent if it's a label
                    return component as? AbstractButton ?: (// For labels, try to find parent that's clickable
                            findClickableParent(component) ?: component)
                }
            }
            
            // Search in children
            if (component is Container) {
                val found = findDeviceComponent(component, device, displayName)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
    
    /**
     * Finds clickable parent component
     */
    private fun findClickableParent(component: Component): Component? {
        var parent = component.parent
        while (parent != null) {
            if (parent is AbstractButton || parent.javaClass.simpleName.contains("Clickable") || parent.mouseListeners.isNotEmpty()) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }
    
    /**
     * Gets device display name that might be shown in the UI
     */
    private fun getDeviceDisplayName(device: IDevice): String {
        // Android Studio shows device model and API level
        return try {
            val manufacturer = device.getProperty("ro.product.manufacturer") ?: ""
            val model = device.getProperty("ro.product.model") ?: device.serialNumber
            val apiLevel = device.getProperty("ro.build.version.sdk") ?: ""
            
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