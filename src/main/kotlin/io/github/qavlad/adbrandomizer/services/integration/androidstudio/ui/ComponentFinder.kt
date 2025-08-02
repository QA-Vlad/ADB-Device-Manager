package io.github.qavlad.adbrandomizer.services.integration.androidstudio.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.github.qavlad.adbrandomizer.services.integration.androidstudio.constants.AndroidStudioConstants
import io.github.qavlad.adbrandomizer.utils.ConsoleLogger
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Service responsible for finding UI components in Android Studio
 */
class ComponentFinder {
    
    /**
     * Finds ActionToolbar components within a container
     */
    fun findActionToolbars(component: Component): List<ActionToolbar> {
        val result = mutableListOf<ActionToolbar>()
        findActionToolbarsRecursive(component, result)
        return result
    }
    
    private fun findActionToolbarsRecursive(component: Component, result: MutableList<ActionToolbar>) {
        if (component is ActionToolbar) {
            result.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                findActionToolbarsRecursive(child, result)
            }
        }
    }
    
    /**
     * Finds components by type name (e.g., "ActionButton")
     */
    fun findComponentsByType(component: Component, typeName: String): List<Component> {
        val result = mutableListOf<Component>()
        findComponentsByTypeRecursive(component, typeName, result)
        return result
    }
    
    private fun findComponentsByTypeRecursive(component: Component, typeName: String, result: MutableList<Component>) {
        if (component.javaClass.simpleName.contains(typeName)) {
            result.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                findComponentsByTypeRecursive(child, typeName, result)
            }
        }
    }
    
    /**
     * Finds components by class name (full or simple)
     */
    fun findComponentsByClassName(component: Component, className: String): List<Component> {
        val result = mutableListOf<Component>()
        findComponentsByClassNameRecursive(component, className, result)
        return result
    }
    
    private fun findComponentsByClassNameRecursive(component: Component, className: String, result: MutableList<Component>) {
        if (component.javaClass.name.contains(className) || component.javaClass.simpleName.contains(className)) {
            result.add(component)
        }
        
        if (component is Container) {
            for (child in component.components) {
                findComponentsByClassNameRecursive(child, className, result)
            }
        }
    }
    
    /**
     * Finds the TabPanel components in the tool window hierarchy
     */
    fun findTabPanels(project: Project): List<Component> {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val runningDevicesWindow = toolWindowManager.getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW)
            ?: return emptyList()
        
        val windowComponent = runningDevicesWindow.component
        val parent = windowComponent.parent
        
        val tabPanels = mutableListOf<Component>()
        
        // Search in the window component
        tabPanels.addAll(findComponentsByClassName(windowComponent, AndroidStudioConstants.TAB_PANEL_CLASS_NAME))
        
        // Search in parent if available
        if (parent != null) {
            tabPanels.addAll(findComponentsByClassName(parent, AndroidStudioConstants.TAB_PANEL_CLASS_NAME))
        }
        
        // Also search the entire window hierarchy
        val rootPane = SwingUtilities.getRootPane(windowComponent)
        if (rootPane != null) {
            tabPanels.addAll(findComponentsByClassName(rootPane, AndroidStudioConstants.TAB_PANEL_CLASS_NAME))
        }
        
        return tabPanels
    }
    
    /**
     * Analyzes and logs the tool window structure for debugging
     */
    fun analyzeToolWindowStructure(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(AndroidStudioConstants.RUNNING_DEVICES_TOOL_WINDOW) ?: return
        
        val component = toolWindow.component
        
        ConsoleLogger.logRunningDevices("=== Analyzing Tool Window Structure ===")
        ConsoleLogger.logRunningDevices("Tool window component class: ${component.javaClass.name}")
        ConsoleLogger.logRunningDevices("Component bounds: ${component.bounds}")
        
        // Log component hierarchy
        logDetailedComponentHierarchy(component, 0, AndroidStudioConstants.MAX_HIERARCHY_DEPTH)
        
        // Check for content manager
        val contentManager = toolWindow.contentManager
        ConsoleLogger.logRunningDevices("Content manager: ${contentManager.javaClass.name}")
        ConsoleLogger.logRunningDevices("Content count: ${contentManager.contentCount}")
        ConsoleLogger.logRunningDevices("Selected content: ${contentManager.selectedContent?.displayName}")
    }
    
    /**
     * Logs detailed component hierarchy
     */
    fun logDetailedComponentHierarchy(component: Component, depth: Int = 0, maxDepth: Int = AndroidStudioConstants.MAX_HIERARCHY_DEPTH) {
        if (depth > maxDepth) return
        
        val indent = "  ".repeat(depth)
        val className = component.javaClass.simpleName
        val bounds = "${component.width}x${component.height}"
        
        // Mark interesting components
        val isInteresting = isInterestingComponent(className)
        val marker = if (isInteresting) " <<<" else ""
        
        // Also log text content for labels and buttons
        val textInfo = getComponentTextInfo(component)
        
        ConsoleLogger.logRunningDevices("$indent$className [$bounds]$marker$textInfo")
        
        // Log ActionButton details
        if (className == AndroidStudioConstants.ACTION_BUTTON_CLASS_NAME) {
            logActionButtonDetails(component, "$indent  ")
        }
        
        if (component is Container) {
            for (child in component.components) {
                logDetailedComponentHierarchy(child, depth + 1, maxDepth)
            }
        }
    }
    
    private fun isInterestingComponent(className: String): Boolean {
        return className.contains("Toolbar") || 
               className.contains("Action") || 
               className.contains("Button") || 
               className.contains("streaming") ||
               className.contains("device", ignoreCase = true) || 
               className.contains("Label") || 
               className.contains("List") ||
               className.contains("Menu") || 
               className.contains("Item")
    }
    
    private fun getComponentTextInfo(component: Component): String {
        return when (component) {
            is javax.swing.JLabel -> " text='${component.text}'"
            is javax.swing.JMenuItem -> " text='${component.text}'"
            is AbstractButton -> " text='${component.text}'"
            is JList<*> -> {
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
    }
    
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
            // Ignore reflection errors
        }
    }
    
    /**
     * Finds clickable parent component
     */
    fun findClickableParent(component: Component): Component? {
        var parent = component.parent
        while (parent != null) {
            if (parent is AbstractButton || 
                parent.javaClass.simpleName.contains("Clickable") || 
                parent.mouseListeners.isNotEmpty()) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }
    
}