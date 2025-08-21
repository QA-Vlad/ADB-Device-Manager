package io.github.qavlad.adbrandomizer.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.github.qavlad.adbrandomizer.utils.PluginLogger

/**
 * Startup activity для автоматической инициализации плагина при открытии проекта
 */
class PluginStartupActivity : ProjectActivity {
    
    override suspend fun execute(project: Project) {
        PluginLogger.info("ADB Randomizer startup activity executing")
        
        // Запускаем в UI потоке после небольшой задержки
        ApplicationManager.getApplication().invokeLater {
            initializeToolWindow(project)
        }
    }
    
    private fun initializeToolWindow(project: Project) {
        try {
            PluginLogger.info("Initializing ADB Randomizer tool window...")
            
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("ADB Randomizer")
            
            if (toolWindow != null) {
                PluginLogger.info("Found ADB Randomizer tool window, visible: ${toolWindow.isVisible}")
                
                // Запоминаем текущее состояние видимости
                val wasVisible = toolWindow.isVisible
                
                // Активируем tool window чтобы принудительно создать его содержимое
                // Это вызовет createToolWindowContent и запустит polling
                toolWindow.activate {
                    PluginLogger.info("ADB Randomizer tool window activated and initialized")
                    
                    // Если окно не было видимо изначально - скрываем его
                    if (!wasVisible) {
                        toolWindow.hide()
                    }
                }
            } else {
                PluginLogger.warn("ADB Randomizer tool window not found")
            }
        } catch (e: Exception) {
            PluginLogger.error("Failed to initialize ADB Randomizer tool window", e)
        }
    }
}