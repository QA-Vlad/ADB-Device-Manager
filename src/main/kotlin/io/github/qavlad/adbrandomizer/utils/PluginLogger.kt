package io.github.qavlad.adbrandomizer.utils

import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.logging.LogLevel
import io.github.qavlad.adbrandomizer.utils.logging.LoggingController

object PluginLogger {
    
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
    
    fun warn(category: LogCategory, message: String, throwable: Throwable?, vararg args: Any?) {
        log(LogLevel.WARN, category, message, throwable, *args)
    }
    
    fun error(category: LogCategory, message: String, throwable: Throwable? = null, vararg args: Any?) {
        log(LogLevel.ERROR, category, message, throwable, *args)
    }
    
    fun debugWithRateLimit(category: LogCategory, key: String, message: String, vararg args: Any?) {
        logWithRateLimit(LogLevel.DEBUG, category, key, message, *args)
    }
    
    // Оставляем для будущего использования, может понадобиться для INFO логов с ограничением частоты
    @Suppress("unused")
    fun infoWithRateLimit(category: LogCategory, key: String, message: String, vararg args: Any?) {
        logWithRateLimit(LogLevel.INFO, category, key, message, *args)
    }
    
    private fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?, vararg args: Any?) {
        LoggingController.processLog(level, category, message, throwable, *args)
    }
    
    private fun logWithRateLimit(level: LogLevel, category: LogCategory, key: String, message: String, vararg args: Any?) {
        // Для rate limited логов не поддерживаем throwable, так как это обычно для частых информационных сообщений
        LoggingController.processLogWithRateLimit(level, category, key, message, null, *args)
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
    
    fun wifiConnectionFailed(ipAddress: String, port: Int, exception: Exception? = null) {
        warn(LogCategory.ADB_CONNECTION, "Wi-Fi connection failed: %s:%d", exception, ipAddress, port)
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