package io.github.qavlad.adbdevicemanager.utils

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation

/**
 * Helper class for creating AnActionEvent instances using modern API
 */
object ActionEventHelper {
    /**
     * Creates an AnActionEvent using the modern factory method with ActionUiKind.
     * Available since IntelliJ Platform 2024.3 (build 243).
     */
    fun createActionEvent(
        place: String,
        presentation: Presentation?,
        dataContext: DataContext
    ): AnActionEvent {
        return AnActionEvent.createEvent(
            dataContext,
            presentation,
            place,
            ActionUiKind.NONE,
            null
        )
    }
}