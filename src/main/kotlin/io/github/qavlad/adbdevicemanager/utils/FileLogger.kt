package io.github.qavlad.adbdevicemanager.utils

import com.intellij.openapi.application.PathManager
import io.github.qavlad.adbdevicemanager.settings.PluginSettings
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * File-based logger for debugging purposes
 * Logs are written to user's plugin directory
 */
object FileLogger {
    private const val LOG_DIR_NAME = "AdbDeviceManagerLogs"
    private const val LOG_FILE_PREFIX = "adb_device_manager_"
    private const val LOG_FILE_EXTENSION = ".log"
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024 // 10 MB
    private const val MAX_LOG_FILES = 5
    
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val lock = ReentrantLock()
    
    private var currentLogFile: File? = null
    private var logWriter: PrintWriter? = null
    
    init {
        if (PluginSettings.instance.debugMode) {
            initializeLogger()
        }
    }
    
    /**
     * Gets the log directory path
     */
    fun getLogDirectory(): Path {
        val pluginDir = PathManager.getPluginsPath()
        return Paths.get(pluginDir, LOG_DIR_NAME)
    }
    
    /**
     * Initializes the logger and creates necessary directories
     */
    private fun initializeLogger() {
        try {
            val logDir = getLogDirectory()
            Files.createDirectories(logDir)
            
            // Clean up old log files
            cleanupOldLogs(logDir)
            
            // Create new log file
            val timestamp = LocalDateTime.now().format(fileFormatter)
            val logFileName = "$LOG_FILE_PREFIX$timestamp$LOG_FILE_EXTENSION"
            currentLogFile = logDir.resolve(logFileName).toFile()
            
            // Open writer in append mode
            logWriter = PrintWriter(FileWriter(currentLogFile!!, true), true)
            
            log("FileLogger initialized. Log directory: $logDir")
        } catch (e: Exception) {
            println("ADB_DEVICE_MANAGER: Failed to initialize file logger: ${e.message}")
        }
    }
    
    /**
     * Cleans up old log files, keeping only the most recent ones
     */
    private fun cleanupOldLogs(logDir: Path) {
        try {
            val logFiles = logDir.toFile().listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_EXTENSION)
            }?.sortedByDescending { it.lastModified() } ?: return
            
            // Delete old files if we have too many
            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.drop(MAX_LOG_FILES).forEach { file ->
                    try {
                        file.delete()
                    } catch (_: Exception) {
                        // Ignore deletion errors
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }
    
    /**
     * Logs a message to the file if debug mode is enabled
     */
    fun log(message: String, level: String = "INFO") {
        if (!PluginSettings.instance.debugMode) return
        
        lock.withLock {
            try {
                // Initialize if needed
                if (logWriter == null) {
                    initializeLogger()
                }
                
                // Check file size and rotate if needed
                currentLogFile?.let { file ->
                    if (file.length() > MAX_LOG_SIZE) {
                        rotateLog()
                    }
                }
                
                // Write log entry
                val timestamp = LocalDateTime.now().format(formatter)
                val logEntry = "[$timestamp] [$level] $message"
                
                logWriter?.println(logEntry)
                logWriter?.flush()
            } catch (e: Exception) {
                println("ADB_DEVICE_MANAGER: Failed to write to log file: ${e.message}")
            }
        }
    }
    
    /**
     * Logs an error with exception details
     */
    fun logError(message: String, throwable: Throwable? = null) {
        val errorMessage = if (throwable != null) {
            "$message\nException: ${throwable.message}\nStackTrace:\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(errorMessage, "ERROR")
    }

    /**
     * Logs Running Devices specific messages
     */
    fun logRunningDevices(message: String) {
        log("RUNNING_DEVICES: $message", "INFO")
    }
    
    /**
     * Rotates the log file when it gets too large
     */
    private fun rotateLog() {
        try {
            // Close current writer
            logWriter?.close()
            
            // Create new log file with timestamp
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
            val logFileName = "$LOG_FILE_PREFIX$timestamp$LOG_FILE_EXTENSION"
            val logDir = getLogDirectory()
            currentLogFile = logDir.resolve(logFileName).toFile()
            
            // Open new writer
            logWriter = PrintWriter(FileWriter(currentLogFile!!, true), true)
            
            // Clean up old logs
            cleanupOldLogs(logDir)
            
            log("Log rotated. New file: ${currentLogFile?.name}")
        } catch (e: Exception) {
            println("ADB_DEVICE_MANAGER: Failed to rotate log: ${e.message}")
        }
    }
    
    /**
     * Closes the logger and releases resources
     */
    fun close() {
        lock.withLock {
            try {
                logWriter?.close()
                logWriter = null
                currentLogFile = null
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }
    
    /**
     * Reinitializes the logger (called when debug mode is toggled)
     */
    fun reinitialize() {
        close()
        if (PluginSettings.instance.debugMode) {
            initializeLogger()
        }
    }
}