package io.github.qavlad.adbrandomizer.services.integration.androidstudio.ui

import com.android.ddmlib.IDevice
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import io.github.qavlad.adbrandomizer.services.AdbServiceAsync
import io.github.qavlad.adbrandomizer.services.integration.androidstudio.constants.AndroidStudioConstants
import io.github.qavlad.adbrandomizer.utils.ConsoleLogger
import io.github.qavlad.adbrandomizer.utils.FileLogger
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Service responsible for executing UI actions in Android Studio
 */
class ActionExecutor(
    private val componentFinder: ComponentFinder,
    private val deviceSelector: DeviceSelector
) {
    
    /**
     * Executes close tab action
     */
    fun executeCloseTabAction(project: Project?): Boolean {
        ConsoleLogger.logRunningDevices("Attempting to close Running Devices tab...")
        
        val actionManager = ActionManager.getInstance()
        
        for (actionId in AndroidStudioConstants.CLOSE_TAB_ACTION_IDS) {
            ConsoleLogger.logRunningDevices("Trying close action: $actionId")
            val action = actionManager.getAction(actionId)
            if (action != null) {
                ConsoleLogger.logRunningDevices("Found action: $actionId (${action.javaClass.simpleName})")
                
                try {
                    var executed = false
                    SwingUtilities.invokeAndWait {
                        val toolWindowManager = ToolWindowManager.getInstance(project ?: return@invokeAndWait)
                        val runningDevicesWindow = toolWindowManager.getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW)
                        
                        if (runningDevicesWindow != null && runningDevicesWindow.isVisible) {
                            ConsoleLogger.logRunningDevices("Running Devices window found and visible")
                            
                            val contentManager = runningDevicesWindow.contentManager
                            val selectedContent = contentManager.selectedContent
                            
                            if (selectedContent != null) {
                                ConsoleLogger.logRunningDevices("Selected content found: ${selectedContent.displayName}")
                                ConsoleLogger.logRunningDevices("Total content count: ${contentManager.contentCount}")
                                
                                val dataContext = SimpleDataContext.builder()
                                    .add(CommonDataKeys.PROJECT, project)
                                    .build()
                                
                                // Try to close the content directly
                                if (actionId == "CloseContent" || actionId == "\$CloseContent") {
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
    fun executeNewTabAction(device: IDevice): Boolean {
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
    fun clickAddDeviceButton(targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("=== Starting comprehensive Add Device button search ===")
        FileLogger.log("Running Devices", "Starting comprehensive Add Device button search")
        
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            ConsoleLogger.logRunningDevices("No project found")
            return false
        }
        
        // First, analyze the tool window structure for debugging
        componentFinder.analyzeToolWindowStructure(project)
        
        // Try multiple approaches in order of likelihood
        val actionManager = ActionManager.getInstance()
        val approaches = listOf(
            { tryToolWindowComponentSearch(project, targetDevice) },
            { tryActionManagerApproach(actionManager, project, targetDevice) },
            { tryReflectionApproach(project) }
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
    
    private fun tryToolWindowComponentSearch(project: Project, targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("Trying improved component search...")
        
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW)
        
        if (runningDevicesWindow == null || !runningDevicesWindow.isVisible) {
            ConsoleLogger.logRunningDevices("Running Devices window not found or not visible")
            return false
        }
        
        // Ensure the window has focus
        if (!runningDevicesWindow.isActive) {
            runningDevicesWindow.activate(null, true, false)
            Thread.sleep(AndroidStudioConstants.SHORT_DELAY)
            ConsoleLogger.logRunningDevices("Activated Running Devices window to ensure focus")
        }
        
        // Find TabPanels and toolbars
        val tabPanels = componentFinder.findTabPanels(project)
        ConsoleLogger.logRunningDevices("Found ${tabPanels.size} TabPanel components")
        
        // Search for toolbars in TabPanels first
        val toolbars = mutableListOf<ActionToolbar>()
        for (tabPanel in tabPanels) {
            toolbars.addAll(componentFinder.findActionToolbars(tabPanel))
        }
        
        // If no toolbars found in TabPanel, search everywhere
        if (toolbars.isEmpty()) {
            val windowComponent = runningDevicesWindow.component
            toolbars.addAll(componentFinder.findActionToolbars(windowComponent))
            windowComponent.parent?.let { parent ->
                toolbars.addAll(componentFinder.findActionToolbars(parent))
            }
        }
        
        ConsoleLogger.logRunningDevices("Found ${toolbars.size} toolbars total")
        
        // Check each toolbar for the add button
        for (toolbar in toolbars) {
            if (checkToolbarForAddButton(toolbar, project, targetDevice)) {
                return true
            }
        }
        
        // If no toolbar found, try to search for ActionButton components
        val buttons = componentFinder.findComponentsByType(
            runningDevicesWindow.component, 
            AndroidStudioConstants.ACTION_BUTTON_CLASS_NAME
        )
        
        ConsoleLogger.logRunningDevices("Found ${buttons.size} ActionButton components")
        
        for (button in buttons) {
            if (checkAndClickActionButton(button, targetDevice)) {
                return true
            }
        }
        
        ConsoleLogger.logRunningDevices("Component search failed")
        return false
    }
    
    private fun checkToolbarForAddButton(toolbar: ActionToolbar, project: Project, targetDevice: IDevice?): Boolean {
        ConsoleLogger.logRunningDevices("Checking toolbar: ${toolbar.javaClass.name}")
        
        // Try to get all components from the toolbar
        val toolbarContainer = toolbar.component as Container
        ConsoleLogger.logRunningDevices("  Toolbar has ${toolbarContainer.componentCount} components")
        
        for (i in 0 until toolbarContainer.componentCount) {
            val comp = toolbarContainer.getComponent(i)
            ConsoleLogger.logRunningDevices("    Component $i: ${comp.javaClass.name}")
            
            // Check if this is an ActionButton
            if (comp.javaClass.simpleName == AndroidStudioConstants.ACTION_BUTTON_CLASS_NAME) {
                if (checkAndClickActionButton(comp, targetDevice)) {
                    ConsoleLogger.logRunningDevices("Successfully clicked Add Device button!")
                    return true
                }
            }
        }
        
        // Also try the standard way with proper context
        return checkToolbarActions(toolbar, project, targetDevice)
    }
    
    private fun checkToolbarActions(toolbar: ActionToolbar, project: Project, targetDevice: IDevice?): Boolean {
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
                } catch (e: Exception) {
                    ConsoleLogger.logRunningDevices("Error getting toolbar actions: ${e.message}")
                }
            }, ModalityState.any())
            
            if (!actionsRetrieved) {
                return false
            }
            
            ConsoleLogger.logRunningDevices("  Got ${actions.size} actions from toolbar")
            
            for (action in actions) {
                if (isAddButtonAction(action)) {
                    return executeAction(action, toolbar, project, targetDevice)
                }
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e  // Rethrow ProcessCanceledException as required
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error getting toolbar actions: ${e.message}")
        }
        
        return false
    }
    
    private fun isAddButtonAction(action: AnAction): Boolean {
        val actionClass = action.javaClass.name
        val presentation = action.templatePresentation
        
        ConsoleLogger.logRunningDevices("    Action: $actionClass (text: '${presentation.text}')")
        
        return actionClass.contains("NewTabAction") || 
               actionClass.contains("StreamingToolWindowManager") ||
               presentation.text?.contains("Add", ignoreCase = true) == true ||
               presentation.text?.contains("New", ignoreCase = true) == true ||
               presentation.text?.contains("+") == true
    }
    
    private fun executeAction(action: AnAction, toolbar: ActionToolbar, project: Project, targetDevice: IDevice?): Boolean {
        ConsoleLogger.logRunningDevices("Found potential add button action: ${action.javaClass.name}")
        
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
            Thread.sleep(AndroidStudioConstants.LONG_DELAY)
            runBlocking {
                val deviceToSelect = targetDevice ?: run {
                    val devices = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                    devices.firstOrNull()
                }
                if (deviceToSelect != null) {
                    deviceSelector.selectDeviceFromPopup(deviceToSelect)
                }
            }
            return true
        }
        
        return false
    }
    
    private fun checkAndClickActionButton(button: Component, targetDevice: IDevice? = null): Boolean {
        try {
            ConsoleLogger.logRunningDevices("Checking button: ${button.javaClass.name}")
            
            // Check if button has tooltip or text
            if (button is JComponent) {
                val tooltip = button.toolTipText
                if (tooltip != null) {
                    ConsoleLogger.logRunningDevices("  Button tooltip: '$tooltip'")
                }
            }
            
            // Use reflection to get action info
            val actionField = button.javaClass.getDeclaredField(AndroidStudioConstants.ACTION_FIELD_NAME)
            actionField.isAccessible = true
            val action = actionField.get(button) as? AnAction
            
            if (action != null && isAddButtonAction(action)) {
                ConsoleLogger.logRunningDevices("Found the Add Device button!")
                
                // Click it in EDT
                var clicked = false
                ApplicationManager.getApplication().invokeAndWait({
                    try {
                        if (button is AbstractButton) {
                            button.doClick()
                            ConsoleLogger.logRunningDevices("Clicked Swing button successfully")
                            clicked = true
                        } else {
                            // IntelliJ ActionButton - execute the action directly
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
                    } catch (e: Exception) {
                        ConsoleLogger.logRunningDevices("Error clicking button: ${e.message}")
                        clicked = false
                    }
                }, ModalityState.any())
                
                if (clicked) {
                    // Wait for the popup and select device
                    Thread.sleep(AndroidStudioConstants.LONG_DELAY)
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project != null) {
                        runBlocking {
                            val deviceToSelect = targetDevice ?: run {
                                val devices = AdbServiceAsync.getConnectedDevicesAsync(project).getOrNull() ?: emptyList()
                                devices.firstOrNull()
                            }
                            if (deviceToSelect != null) {
                                deviceSelector.selectDeviceFromPopup(deviceToSelect)
                            }
                        }
                    }
                }
                
                return clicked
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e  // Rethrow ProcessCanceledException as required
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error analyzing ActionButton: ${e.message}")
        }
        return false
    }
    
    private fun tryActionManagerApproach(actionManager: ActionManager, project: Project, targetDevice: IDevice? = null): Boolean {
        ConsoleLogger.logRunningDevices("Trying ActionManager approach...")
        
        // Search for actions by pattern in all registered actions
        val allActionIds = actionManager.getActionIdList("").toList()
        val streamingActions = findStreamingActions(allActionIds)
        
        // Prioritize actions based on likelihood
        val prioritizedCandidates = prioritizeActionCandidates(streamingActions)
        
        ConsoleLogger.logRunningDevices("Found ${prioritizedCandidates.size} potential NewTab actions")
        FileLogger.log("Running Devices", "Potential NewTab actions: ${prioritizedCandidates.take(10).joinToString(", ")}")
        
        // Try each possible action with proper context
        for (actionId in prioritizedCandidates) {
            if (tryExecuteActionById(actionId, actionManager, project, targetDevice)) {
                return true
            }
        }
        
        ConsoleLogger.logRunningDevices("ActionManager approach failed")
        return false
    }
    
    private fun findStreamingActions(allActionIds: List<String>): List<String> {
        return allActionIds.filter { actionId ->
            val lowerActionId = actionId.lowercase()
            
            // Exclude obvious false positives
            val excludePatterns = listOf(
                "image", "asset", "vector", "cpp", "rtl", "component",
                "deeplink", "designer", "emulator", "legacy"
            )
            
            if (excludePatterns.any { lowerActionId.contains(it) }) {
                return@filter false
            }
            
            // Look for streaming/running devices specific actions
            (lowerActionId.contains("streaming") || 
             lowerActionId.contains("runningdevices") || 
             lowerActionId.contains("devicemirroring")) &&
            (lowerActionId.contains("newtab") || lowerActionId.contains("tab"))
        }
    }
    
    private fun prioritizeActionCandidates(streamingActions: List<String>): List<String> {
        val exactClassActions = streamingActions.filter { actionId ->
            actionId.contains("StreamingToolWindowManager") && actionId.contains("NewTabAction")
        }
        
        return (exactClassActions + AndroidStudioConstants.NEW_TAB_ACTION_IDS + streamingActions)
            .distinct()
            .sortedBy { actionId ->
                when {
                    actionId.contains("StreamingToolWindowManager") && actionId.contains("NewTabAction") -> 0
                    actionId == "com.android.tools.idea.streaming.core.StreamingToolWindowManager\$NewTabAction" -> 1
                    actionId.contains("RunningDevices") && actionId.contains("NewTab") -> 2
                    actionId.contains("StreamingToolWindow") && actionId.contains("NewTab") -> 3
                    else -> 4
                }
            }
    }
    
    private fun tryExecuteActionById(actionId: String, actionManager: ActionManager, project: Project, targetDevice: IDevice?): Boolean {
        val action = actionManager.getAction(actionId) ?: return false
        
        ConsoleLogger.logRunningDevices("Found action: $actionId (${action.javaClass.name})")
        FileLogger.log("Running Devices", "Found action: $actionId")
        
        return try {
            var success = false
            ApplicationManager.getApplication().invokeAndWait({
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW)
                if (toolWindow != null) {
                    val dataContext = DataManager.getInstance().getDataContext(toolWindow.component)
                    val event = AnActionEvent.createFromDataContext(
                        ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                        null,
                        dataContext
                    )
                    
                    ConsoleLogger.logRunningDevices("Executing action: $actionId")
                    action.actionPerformed(event)
                    FileLogger.log("Running Devices", "Action $actionId executed")
                    success = true
                }
            }, ModalityState.any())
            
            if (success) {
                // Wait for potential dialog/popup
                Thread.sleep(AndroidStudioConstants.MEDIUM_DELAY)
                
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
                            deviceSelector.selectDeviceFromPopup(deviceToSelect)
                        }
                    }
                    return true
                } else {
                    ConsoleLogger.logRunningDevices("No new window appeared, action might be wrong")
                }
            }
            false
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e  // Rethrow ProcessCanceledException as required
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error executing action $actionId: ${e.message}")
            FileLogger.log("Running Devices", "Error executing action $actionId: ${e.message}")
            false
        }
    }
    
    private fun tryReflectionApproach(project: Project): Boolean {
        ConsoleLogger.logRunningDevices("Trying reflection approach...")
        
        return try {
            // Try to find StreamingToolWindowManager through reflection
            val streamingManagerClass = Class.forName("com.android.tools.idea.streaming.core.StreamingToolWindowManager")
            ConsoleLogger.logRunningDevices("Found StreamingToolWindowManager class")
            
            // Try to get instance
            val getInstance = streamingManagerClass.getDeclaredMethod("getInstance", Project::class.java)
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
}