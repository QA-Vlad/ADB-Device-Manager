package io.github.qavlad.adbrandomizer.utils

import com.intellij.openapi.diagnostic.Logger

object PluginLogger {
    private val logger = Logger.getInstance("ADB_Randomizer")
    private const val PREFIX = "ADB_Randomizer"
    
    fun debug(message: String, vararg args: Any?) {
        if (logger.isDebugEnabled) {
            logger.debug(formatMessage(message, args))
        }
    }
    
    fun info(message: String, vararg args: Any?) {
        logger.info(formatMessage(message, args))
    }
    
    fun warn(message: String, vararg args: Any?) {
        logger.warn(formatMessage(message, args))
    }
    
    fun error(message: String, throwable: Throwable? = null, vararg args: Any?) {
        logger.error(formatMessage(message, args), throwable)
    }
    
    private fun formatMessage(message: String, args: Array<out Any?>): String {
        val formatted = if (args.isNotEmpty()) {
            message.format(*args)
        } else {
            message
        }
        return "$PREFIX: $formatted"
    }
    
    // Специализированные методы для частых операций
    fun deviceConnected(deviceName: String, serial: String) {
        info("Device connected: %s (%s)", deviceName, serial)
    }
    
    fun commandExecuted(command: String, device: String, success: Boolean) {
        if (success) {
            debug("Command '%s' executed successfully on %s", command, device)
        } else {
            warn("Command '%s' failed on %s", command, device)
        }
    }
    
    fun wifiConnectionAttempt(ipAddress: String, port: Int) {
        debug("Attempting Wi-Fi connection to %s:%d", ipAddress, port)
    }
    
    fun wifiConnectionSuccess(ipAddress: String, port: Int) {
        info("Wi-Fi connection successful: %s:%d", ipAddress, port)
    }
    
    fun wifiConnectionFailed(ipAddress: String, port: Int, error: Exception? = null) {
        error("Wi-Fi connection failed: %s:%d", error, ipAddress, port)
    }
} 