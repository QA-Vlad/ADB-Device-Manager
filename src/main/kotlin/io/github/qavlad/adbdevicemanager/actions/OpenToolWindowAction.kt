package io.github.qavlad.adbdevicemanager.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

class OpenToolWindowAction : AnAction(), DumbAware {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("ADB Device Manager")
        
        if (toolWindow != null) {
            if (toolWindow.isVisible) {
                toolWindow.hide()
            } else {
                toolWindow.show()
                toolWindow.activate(null)
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        // Action is always available when a project is open
        e.presentation.isEnabledAndVisible = e.project != null
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        // Use BGT (Background Thread) for better performance
        return ActionUpdateThread.BGT
    }
}