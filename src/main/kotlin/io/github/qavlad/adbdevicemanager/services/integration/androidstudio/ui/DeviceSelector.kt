package io.github.qavlad.adbdevicemanager.services.integration.androidstudio.ui

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import io.github.qavlad.adbdevicemanager.services.integration.androidstudio.constants.AndroidStudioConstants
import io.github.qavlad.adbdevicemanager.utils.ConsoleLogger
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke

/**
 * Service responsible for selecting devices from popup menus in Android Studio
 */
class DeviceSelector(
    private val componentFinder: ComponentFinder
) {
    
    /**
     * Selects device from the popup menu
     */
    fun selectDeviceFromPopup(device: IDevice): Boolean {
        val displayName = getDeviceDisplayName(device)
        ConsoleLogger.logRunningDevices("Attempting to select device ${device.serialNumber} (display name: $displayName) from popup")
        
        return try {
            // Wait a bit for popup to fully appear
            Thread.sleep(AndroidStudioConstants.LONG_DELAY)
            
            // Try to find the popup menu
            val windows = java.awt.Window.getWindows()
            for (window in windows) {
                if (window.isShowing && (window is javax.swing.JWindow || window is javax.swing.JDialog)) {
                    ConsoleLogger.logRunningDevices("Found potential popup window: ${window.javaClass.simpleName}")
                    
                    // Log all components in the window for debugging
                    componentFinder.logDetailedComponentHierarchy(window, 0, AndroidStudioConstants.DETAILED_HIERARCHY_DEPTH)
                    
                    // Look for menu items in the popup
                    val deviceComponent = findDeviceMenuItem(window, device)
                    if (deviceComponent != null) {
                        ConsoleLogger.logRunningDevices("Found device component (${deviceComponent.javaClass.simpleName}), clicking...")
                        
                        // Click the component
                        if (clickDeviceComponent(deviceComponent)) {
                            return true
                        }
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
            componentFinder.logDetailedComponentHierarchy(container, 0, AndroidStudioConstants.DETAILED_HIERARCHY_DEPTH)
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
            if (component is JList<*>) {
                val deviceIndex = findDeviceInList(component, device, displayName)
                if (deviceIndex >= 0) {
                    component.selectedIndex = deviceIndex
                    ConsoleLogger.logRunningDevices("Selected item at index $deviceIndex")
                    return component
                }
            }
            
            // Get text from various component types
            val text = getComponentText(component)
            
            if (text != null) {
                ConsoleLogger.logRunningDevices("  Checking component ${component.javaClass.simpleName}: '$text'")
                
                // Check if text contains device info
                if (isDeviceMatch(text, device.serialNumber, displayName)) {
                    ConsoleLogger.logRunningDevices("Found device component: $text")
                    
                    // Return clickable component or its parent if it's a label
                    return when (component) {
                        is AbstractButton -> component
                        else -> componentFinder.findClickableParent(component) ?: component
                    }
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
    
    private fun findDeviceInList(list: JList<*>, device: IDevice, displayName: String): Int {
        val model = list.model
        val itemCount = model.size
        ConsoleLogger.logRunningDevices("Found JList '${list.javaClass.name}' with $itemCount items")
        
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
            
            if (isDeviceMatch(item, device.serialNumber, displayName)) {
                ConsoleLogger.logRunningDevices("MATCH! Found device in JList at index $i: $item")
                return i
            }
        }
        
        ConsoleLogger.logRunningDevices("Device not found in JList")
        return -1
    }
    
    private fun getComponentText(component: Component): String? {
        return when (component) {
            is javax.swing.JLabel -> component.text
            is javax.swing.JMenuItem -> component.text
            is AbstractButton -> component.text
            else -> null
        }
    }
    
    private fun isDeviceMatch(text: String, serialNumber: String, displayName: String): Boolean {
        return text.contains(serialNumber) || 
               text.contains(displayName) ||
               text.contains(displayName, ignoreCase = true) ||
               displayName.split(" ").any { part -> 
                   part.length > 3 && text.contains(part, ignoreCase = true) 
               }
    }
    
    private fun clickDeviceComponent(deviceComponent: Component): Boolean {
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
                        is JList<*> -> {
                            clickJListItem(deviceComponent)
                        }
                        else -> {
                            simulateMouseClick(deviceComponent)
                        }
                    }
                } catch (e: Exception) {
                    ConsoleLogger.logRunningDevices("Error clicking device component: ${e.message}")
                }
            }, ModalityState.any())
            return true
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e  // Rethrow ProcessCanceledException as required
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error in clickDeviceComponent: ${e.message}")
            return false
        }
    }
    
    private fun clickJListItem(list: JList<*>) {
        ConsoleLogger.logRunningDevices("Handling JList selection")
        val selectedIndex = list.selectedIndex
        if (selectedIndex >= 0) {
            val bounds = list.getCellBounds(selectedIndex, selectedIndex)
            ConsoleLogger.logRunningDevices("Selected index: $selectedIndex, bounds: $bounds")
            
            // Try multiple approaches to confirm selection
            
            // Approach 1: Fire selection event
            try {
                val selectionModel = list.selectionModel
                selectionModel.setSelectionInterval(selectedIndex, selectedIndex)
                ConsoleLogger.logRunningDevices("Set selection interval to $selectedIndex")
            } catch (e: Exception) {
                ConsoleLogger.logRunningDevices("Error firing selection event: ${e.message}")
            }
            
            // Approach 2: Simulate Enter key
            simulateEnterKey(list)
            
            // Approach 3: Process key bindings directly
            processEnterKeyBinding(list)
            
            // Approach 4: Double-click simulation
            if (bounds != null) {
                simulateDoubleClick(list, bounds)
            }
            
            ConsoleLogger.logRunningDevices("Completed all JList selection attempts")
        }
    }
    
    private fun simulateEnterKey(component: JComponent) {
        try {
            val enterPressed = KeyEvent(
                component,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_ENTER,
                '\n'
            )
            val enterReleased = KeyEvent(
                component,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_ENTER,
                '\n'
            )
            
            component.dispatchEvent(enterPressed)
            component.dispatchEvent(enterReleased)
            ConsoleLogger.logRunningDevices("Dispatched Enter key events")
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error dispatching Enter key: ${e.message}")
        }
    }
    
    private fun processEnterKeyBinding(component: JComponent) {
        try {
            val inputMap = component.getInputMap(JComponent.WHEN_FOCUSED)
            val actionMap = component.actionMap
            val enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
            val actionKey = inputMap.get(enterKey)
            
            if (actionKey != null) {
                val action = actionMap.get(actionKey)
                if (action != null) {
                    ConsoleLogger.logRunningDevices("Found Enter action: $actionKey")
                    val actionEvent = ActionEvent(
                        component,
                        ActionEvent.ACTION_PERFORMED,
                        "Enter"
                    )
                    action.actionPerformed(actionEvent)
                    ConsoleLogger.logRunningDevices("Executed Enter action directly")
                }
            }
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error executing action: ${e.message}")
        }
    }
    
    private fun simulateDoubleClick(component: Component, bounds: java.awt.Rectangle) {
        try {
            val x = bounds.x + bounds.width / 2
            val y = bounds.y + bounds.height / 2
            
            // Mouse press
            val pressEvent = MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                x, y,
                1,
                false,
                MouseEvent.BUTTON1
            )
            
            // Mouse release
            val releaseEvent = MouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                x, y,
                1,
                false,
                MouseEvent.BUTTON1
            )
            
            // Mouse click
            val clickEvent = MouseEvent(
                component,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                x, y,
                1,
                false,
                MouseEvent.BUTTON1
            )
            
            // Double-click
            val doubleClickEvent = MouseEvent(
                component,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                x, y,
                2, // Double-click
                false,
                MouseEvent.BUTTON1
            )
            
            component.dispatchEvent(pressEvent)
            component.dispatchEvent(releaseEvent)
            component.dispatchEvent(clickEvent)
            Thread.sleep(50)
            component.dispatchEvent(doubleClickEvent)
            
            ConsoleLogger.logRunningDevices("Dispatched mouse events at ($x, $y)")
        } catch (e: Exception) {
            ConsoleLogger.logRunningDevices("Error dispatching mouse events: ${e.message}")
        }
    }
    
    private fun simulateMouseClick(component: Component) {
        ConsoleLogger.logRunningDevices("Simulating mouse click on ${component.javaClass.simpleName}")
        val bounds = component.bounds
        
        val mouseEvent = MouseEvent(
            component,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            bounds.width / 2,
            bounds.height / 2,
            1,
            false,
            MouseEvent.BUTTON1
        )
        
        // Dispatch mouse events
        component.dispatchEvent(MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            bounds.width / 2,
            bounds.height / 2,
            1,
            false,
            MouseEvent.BUTTON1
        ))
        
        component.dispatchEvent(MouseEvent(
            component,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            0,
            bounds.width / 2,
            bounds.height / 2,
            1,
            false,
            MouseEvent.BUTTON1
        ))
        
        component.dispatchEvent(mouseEvent)
        ConsoleLogger.logRunningDevices("Dispatched mouse events")
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