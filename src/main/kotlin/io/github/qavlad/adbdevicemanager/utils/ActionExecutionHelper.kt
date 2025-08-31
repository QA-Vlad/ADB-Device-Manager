package io.github.qavlad.adbdevicemanager.utils

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager

/**
 * Helper class for executing actions using official API methods
 */
object ActionExecutionHelper {
    
    /**
     * Safely performs an action using ActionUtil.performActionDumbAwareWithCallbacks
     * This is the recommended way to execute actions programmatically
     * Note: This method is deprecated but there's no alternative in Platform 243+
     */
    @Suppress("DEPRECATION")
    fun performAction(action: AnAction, event: AnActionEvent): Boolean {
        return try {
            // Use ActionUtil.performActionDumbAwareWithCallbacks which is the official way to execute actions
            // It handles update, enabled checks, and execution properly without violating @ApiStatus.OverrideOnly
            // This method is deprecated but there's no replacement API yet
            ApplicationManager.getApplication().invokeLater {
                try {
                    ActionUtil.performActionDumbAwareWithCallbacks(action, event)
                } catch (e: Exception) {
                    PluginLogger.error("Failed to perform action", e)
                }
            }
            true
        } catch (e: Exception) {
            PluginLogger.error("Failed to perform action", e)
            false
        }
    }
    
    /**
     * Safely updates an action and checks if it's enabled
     * Returns true if the action is enabled after update
     * Note: performDumbAwareUpdate is deprecated but there's no alternative in Platform 243+
     */
    @Suppress("DEPRECATION")
    fun updateAndCheckEnabled(action: AnAction, event: AnActionEvent): Boolean {
        return try {
            // Use ActionUtil.performDumbAwareUpdate for platform 243+ compatibility
            // This method is deprecated but there's no replacement API yet
            ActionUtil.performDumbAwareUpdate(action, event, false)
            event.presentation.isEnabled
        } catch (e: Exception) {
            PluginLogger.error("Failed to update action", e)
            false
        }
    }
    
    /**
     * Safely gets children of an ActionGroup using proper API
     * Note: performDumbAwareUpdate is deprecated but there's no alternative in Platform 243+
     */
    @Suppress("DEPRECATION")
    fun getActionGroupChildren(actionGroup: ActionGroup, event: AnActionEvent): Array<AnAction> {
        return try {
            // First update the action group using ActionUtil
            // This method is deprecated but there's no replacement API yet
            ActionUtil.performDumbAwareUpdate(actionGroup, event, false)
            
            // For all ActionGroup types, use DefaultActionGroup if possible
            when (actionGroup) {
                is DefaultActionGroup -> {
                    // For DefaultActionGroup, we can directly get children
                    val childActions = actionGroup.getChildActionsOrStubs()
                    Array(childActions.size) { i -> childActions[i] }
                }
                else -> {
                    // For other ActionGroup types, return empty array as getChildren() is removed
                    // The proper way would be to cast to DefaultActionGroup if possible
                    // or refactor the code to not need children
                    PluginLogger.warn("Cannot get children for non-DefaultActionGroup: ${actionGroup.javaClass.name}")
                    emptyArray()
                }
            }
        } catch (e: Exception) {
            PluginLogger.error("Failed to get action group children", e)
            emptyArray()
        }
    }
}