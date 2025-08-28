@file:Suppress("ComponentNotRegistered")

package io.github.qavlad.adbdevicemanager.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import io.github.qavlad.adbdevicemanager.utils.logging.LogLevel
import io.github.qavlad.adbdevicemanager.utils.logging.LoggingConfiguration

@Suppress("unused")
class ResetLogLevelsAction : AnAction("Reset Log Levels to Defaults", "Reset all log levels to their default values", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val result = Messages.showYesNoDialog(
            e.project,
            "Are you sure you want to reset all log levels to defaults?",
            "Reset Log Levels",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            val config = LoggingConfiguration.getInstance()
            
            // Сбрасываем все настройки
            config.setGlobalLogLevel(LogLevel.INFO)
            config.setDebugModeEnabled(false)
            config.resetRateLimits()
            
            // Восстанавливаем дефолтные уровни для категорий
            LogCategory.values().forEach { category ->
                config.setCategoryLogLevel(category, category.defaultLevel)
            }
            
            Messages.showInfoMessage(
                e.project,
                "All log levels have been reset to defaults.",
                "Log Levels Reset"
            )
        }
    }
}