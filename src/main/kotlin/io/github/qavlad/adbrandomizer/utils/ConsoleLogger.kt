package io.github.qavlad.adbrandomizer.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Console logger that outputs to IDE notifications for debugging
 * Works even when plugin is installed from ZIP
 */
object ConsoleLogger {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

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

}