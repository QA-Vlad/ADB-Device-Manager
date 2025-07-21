package io.github.qavlad.adbrandomizer.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.logging.LogLevel
import io.github.qavlad.adbrandomizer.utils.logging.LoggingConfiguration
import javax.swing.Icon

class SetCategoryLogLevelAction : AnAction("Set Category Log Level", "Configure log level for specific category", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val categories = LogCategory.values().toList()
        
        val categoryStep = object : BaseListPopupStep<LogCategory>("Select Category", categories) {
            override fun getTextFor(value: LogCategory): String {
                val config = LoggingConfiguration.getInstance()
                val currentLevel = config.getCategoryLogLevel(value)
                return "${value.displayName} (current: $currentLevel)"
            }
            
            override fun onChosen(selectedValue: LogCategory, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    return showLevelSelection(selectedValue, e)
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        
        val popup = JBPopupFactory.getInstance().createListPopup(categoryStep)
        e.project?.let { project ->
            popup.showCenteredInCurrentWindow(project)
        } ?: popup.showInFocusCenter()
    }
    
    private fun showLevelSelection(category: LogCategory, event: AnActionEvent): PopupStep<*> {
        val levels = LogLevel.values().toList()
        val config = LoggingConfiguration.getInstance()
        val currentLevel = config.getCategoryLogLevel(category)
        
        return object : BaseListPopupStep<LogLevel>("Select Log Level for ${category.displayName}", levels) {
            override fun getTextFor(value: LogLevel): String {
                return if (value == currentLevel) {
                    "$value (current)"
                } else if (value == category.defaultLevel) {
                    "$value (default)"
                } else {
                    value.toString()
                }
            }
            
            override fun onChosen(selectedValue: LogLevel, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    config.setCategoryLogLevel(category, selectedValue)
                    
                    val message = "Log level for ${category.displayName} set to $selectedValue"
                    Messages.showInfoMessage(
                        event.project,
                        message,
                        "Log Level Changed"
                    )
                }
                return PopupStep.FINAL_CHOICE
            }
        }
    }
}