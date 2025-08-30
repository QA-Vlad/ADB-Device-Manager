package io.github.qavlad.adbdevicemanager.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import io.github.qavlad.adbdevicemanager.settings.PluginSettings
import io.github.qavlad.adbdevicemanager.telemetry.SentryInitializer
import io.github.qavlad.adbdevicemanager.utils.PluginLogger

/**
 * Startup activity для автоматической инициализации плагина при открытии проекта
 */
class PluginStartupActivity : ProjectActivity {
    
    override suspend fun execute(project: Project) {
        PluginLogger.info("ADB Device Manager startup activity executing")
        
        // Инициализируем Sentry при запуске (opt-out модель)
        val settings = PluginSettings.instance
        SentryInitializer.initialize(settings.enableTelemetry)
        
        // Запускаем в UI потоке, после небольшой задержки
        ApplicationManager.getApplication().invokeLater {
            initializeToolWindow(project)
        }
    }
    
    private fun initializeToolWindow(project: Project) {
        try {
            PluginLogger.info("Initializing ADB Device Manager tool window...")
            
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("ADB Device Manager")
            
            if (toolWindow != null) {
                PluginLogger.info("Found ADB Device Manager tool window, visible: ${toolWindow.isVisible}")
                
                // Запоминаем текущее состояние видимости
                val wasVisible = toolWindow.isVisible
                
                // Активируем tool window чтобы принудительно создать его содержимое
                // Это вызовет createToolWindowContent и запустит polling
                toolWindow.activate {
                    PluginLogger.info("ADB Device Manager tool window activated and initialized")
                    
                    // Если окно не было видимо изначально - скрываем его
                    if (!wasVisible) {
                        toolWindow.hide()
                    }
                }
            } else {
                PluginLogger.warn("ADB Device Manager tool window not found")
            }
        } catch (e: Exception) {
            PluginLogger.error("Failed to initialize ADB Device Manager tool window", e)
        }
    }
}