@file:Suppress("ComponentNotRegistered")

package io.github.qavlad.adbdevicemanager.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import io.github.qavlad.adbdevicemanager.utils.logging.LoggingConfiguration

@Suppress("unused")
class ShowCurrentLogLevelsAction : AnAction("Show Current Log Levels", "Display current log levels for all categories", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val config = LoggingConfiguration.getInstance()
        val sb = StringBuilder()
        
        sb.append("Global Level: ${config.getGlobalLogLevel()}\n")
        sb.append("Debug Mode: ${if (config.isDebugModeEnabled()) "Enabled" else "Disabled"}\n")
        sb.append("\nCategory Levels:\n")
        
        LogCategory.values().forEach { category ->
            val level = config.getCategoryLogLevel(category)
            val isDefault = level == category.defaultLevel
            sb.append("  ${category.displayName}: $level")
            if (isDefault) {
                sb.append(" (default)")
            }
            sb.append("\n")
        }
        
        Messages.showInfoMessage(
            e.project,
            sb.toString(),
            "Current Log Levels"
        )
    }
}