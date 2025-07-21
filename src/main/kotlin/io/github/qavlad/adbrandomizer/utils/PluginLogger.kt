package io.github.qavlad.adbrandomizer.utils

import com.intellij.openapi.diagnostic.Logger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.logging.LogLevel
import io.github.qavlad.adbrandomizer.utils.logging.LoggingConfiguration

object PluginLogger {
    private val logger = Logger.getInstance("ADB_Randomizer")
    private const val PREFIX = "ADB_Randomizer"
    private val config = LoggingConfiguration.getInstance()
    
    fun trace(category: LogCategory, message: String, vararg args: Any?) {
        log(LogLevel.TRACE, category, message, null, *args)
    }
    
    fun debug(category: LogCategory, message: String, vararg args: Any?) {
        log(LogLevel.DEBUG, category, message, null, *args)
    }
    
    fun info(category: LogCategory, message: String, vararg args: Any?) {
        log(LogLevel.INFO, category, message, null, *args)
    }
    
    fun warn(category: LogCategory, message: String, vararg args: Any?) {
        log(LogLevel.WARN, category, message, null, *args)
    }
    
    fun error(category: LogCategory, message: String, throwable: Throwable? = null, vararg args: Any?) {
        log(LogLevel.ERROR, category, message, throwable, *args)
    }
    
    fun debugWithRateLimit(category: LogCategory, key: String, message: String, vararg args: Any?) {
        logWithRateLimit(LogLevel.DEBUG, category, key, message, null, *args)
    }
    
    fun infoWithRateLimit(category: LogCategory, key: String, message: String, vararg args: Any?) {
        logWithRateLimit(LogLevel.INFO, category, key, message, null, *args)
    }
    
    private fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?, vararg args: Any?) {
        if (!config.shouldLog(level, category)) return
        
        val formattedMessage = formatMessage(category, message, args)
        
        when (level) {
            LogLevel.TRACE, LogLevel.DEBUG -> if (logger.isDebugEnabled) logger.debug(formattedMessage, throwable)
            LogLevel.INFO -> logger.info(formattedMessage, throwable)
            LogLevel.WARN -> logger.warn(formattedMessage, throwable)
            LogLevel.ERROR -> logger.error(formattedMessage, throwable)
        }
    }
    
    private fun logWithRateLimit(level: LogLevel, category: LogCategory, key: String, message: String, throwable: Throwable?, vararg args: Any?) {
        if (!config.shouldLogWithRateLimit(key, level, category)) return
        
        log(level, category, message, throwable, *args)
    }
    
    private fun formatMessage(category: LogCategory, message: String, args: Array<out Any?>): String {
        val formatted = if (args.isNotEmpty()) {
            message.format(*args)
        } else {
            message
        }
        return "$PREFIX [${category.displayName}]: $formatted"
    }
    
    // Специализированные методы для частых операций
    fun deviceConnected(deviceName: String, serial: String) {
        info(LogCategory.ADB_CONNECTION, "Device connected: %s (%s)", deviceName, serial)
    }
    
    fun commandExecuted(command: String, device: String, success: Boolean) {
        if (success) {
            debug(LogCategory.ADB_CONNECTION, "Command '%s' executed successfully on %s", command, device)
        } else {
            warn(LogCategory.ADB_CONNECTION, "Command '%s' failed on %s", command, device)
        }
    }
    
    fun wifiConnectionAttempt(ipAddress: String, port: Int) {
        debug(LogCategory.ADB_CONNECTION, "Attempting Wi-Fi connection to %s:%d", ipAddress, port)
    }
    
    fun wifiConnectionSuccess(ipAddress: String, port: Int) {
        info(LogCategory.ADB_CONNECTION, "Wi-Fi connection successful: %s:%d", ipAddress, port)
    }
    
    fun wifiConnectionFailed(ipAddress: String, port: Int, error: Exception? = null) {
        error(LogCategory.ADB_CONNECTION, "Wi-Fi connection failed: %s:%d", error, ipAddress, port)
    }
    
    // Для обратной совместимости
    fun debug(message: String, vararg args: Any?) {
        debug(LogCategory.GENERAL, message, *args)
    }
    
    fun info(message: String, vararg args: Any?) {
        info(LogCategory.GENERAL, message, *args)
    }
    
    fun warn(message: String, vararg args: Any?) {
        warn(LogCategory.GENERAL, message, *args)
    }
    
    fun error(message: String, throwable: Throwable? = null, vararg args: Any?) {
        error(LogCategory.GENERAL, message, throwable, *args)
    }
} 