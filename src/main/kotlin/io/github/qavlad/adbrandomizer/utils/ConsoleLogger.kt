package io.github.qavlad.adbrandomizer.utils

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.ProjectManager
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Console logger that outputs to IDE notifications for debugging
 * Works even when plugin is installed from ZIP
 */
object ConsoleLogger {
    private const val NOTIFICATION_GROUP_ID = "ADB Randomizer Debug"
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    
    init {
        // Console logger initialized silently
    }
    
    fun logInfo(message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] INFO: $message"
        
        // Print to standard console
        println("ADB_RANDOMIZER: $logMessage")
        
        // Write to file if debug mode enabled
        FileLogger.log(message, "INFO")
    }
    
    fun logWarn(message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] WARN: $message"
        
        println("ADB_RANDOMIZER: $logMessage")
        FileLogger.logWarn(message)
    }
    
    fun logError(message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(formatter)
        val errorDetails = throwable?.let { "\nError: ${it.message}\nStack: ${it.stackTraceToString()}" } ?: ""
        val logMessage = "[$timestamp] ERROR: $message$errorDetails"
        
        println("ADB_RANDOMIZER: $logMessage")
        FileLogger.logError(message, throwable)
    }
    
    fun logRunningDevices(message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val logMessage = "[$timestamp] RUNNING_DEVICES: $message"
        
        // Always print Running Devices related logs
        println("ADB_RANDOMIZER: $logMessage")
        FileLogger.logRunningDevices(message)
        
        // Don't show Running Devices logs as notifications - they are too verbose
        // Only show in console and file logs
    }
    
    private fun showNotification(message: String, type: NotificationType) {
        try {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            if (project != null) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, type)
                    .notify(project)
            }
        } catch (e: Exception) {
            // Fallback to console only
            println("ADB_RANDOMIZER: Failed to show notification: ${e.message}")
        }
    }
    
    private fun isDebugMode(): Boolean {
        // Use debug mode setting from plugin settings
        return PluginSettings.instance.debugMode
    }
}