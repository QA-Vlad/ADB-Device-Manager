package io.github.qavlad.adbdevicemanager.utils

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation

/**
 * Helper class for creating AnActionEvent instances
 * Replaces deprecated AnActionEvent.createFromDataContext()
 */
object ActionEventHelper {
    /**
     * Creates an AnActionEvent using the modern API
     * This replaces the deprecated AnActionEvent.createFromDataContext()
     */
    fun createActionEvent(
        place: String,
        presentation: Presentation?,
        dataContext: DataContext
    ): AnActionEvent {
        // Use the constructor that's available in 223+
        // This constructor is not deprecated and works in all versions
        return AnActionEvent(
            null, // inputEvent - can be null
            dataContext,
            place,
            presentation ?: Presentation(),
            com.intellij.openapi.actionSystem.ActionManager.getInstance(),
            0 // modifiers
        )
    }
}