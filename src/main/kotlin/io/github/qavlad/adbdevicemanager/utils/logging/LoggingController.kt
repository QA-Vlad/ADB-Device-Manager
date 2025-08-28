package io.github.qavlad.adbdevicemanager.utils.logging

import com.intellij.openapi.diagnostic.Logger
import io.github.qavlad.adbdevicemanager.settings.PluginSettings
import io.github.qavlad.adbdevicemanager.utils.FileLogger

/**
 * Централизованный контроллер логирования, который обеспечивает
 * единообразный вывод логов во все необходимые места:
 * - Консоль IDE (через IntelliJ Logger API)
 * - Системная консоль (через println для отладки)
 * - Файлы логов (при включенном debug режиме)
 */
object LoggingController {
    private val logger = Logger.getInstance("ADB_Device_Manager")
    private const val PREFIX = "ADB_Device_Manager"
    private val config by lazy { LoggingConfiguration.getInstance() }
    
    /**
     * Обрабатывает лог-сообщение и направляет его во все необходимые выходы
     */
    fun processLog(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
        vararg args: Any?
    ) {
        // Проверяем, нужно ли логировать
        if (!config.shouldLog(level, category)) return
        
        // Форматируем сообщение
        val formattedMessage = formatMessage(category, message, args)
        
        // 1. Логируем в консоль IDE через IntelliJ Logger API
        logToIdeConsole(level, formattedMessage, throwable)
        
        // 2. Логируем в файл при включенном debug режиме
        if (PluginSettings.instance.debugMode) {
            logToFile(level, formattedMessage, throwable)
        }
    }
    
    /**
     * Обрабатывает лог с ограничением частоты
     */
    fun processLogWithRateLimit(
        level: LogLevel,
        category: LogCategory,
        key: String,
        message: String,
        throwable: Throwable?,
        vararg args: Any?
    ) {
        if (!config.shouldLogWithRateLimit(key, level, category)) return
        processLog(level, category, message, throwable, *args)
    }
    
    /**
     * Логирует в консоль IDE через IntelliJ Logger API
     */
    private fun logToIdeConsole(level: LogLevel, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.TRACE, LogLevel.DEBUG -> {
                if (logger.isDebugEnabled) {
                    logger.debug(message, throwable)
                }
            }
            LogLevel.INFO -> logger.info(message, throwable)
            LogLevel.WARN -> logger.warn(message, throwable)
            LogLevel.ERROR -> logger.error(message, throwable)
        }
    }
    
    /**
     * Логирует в системную консоль через println
     * Метод закомментирован для избежания дублирования с logToIdeConsole
     * Оставлен для возможности быстрого включения при отладке
     */
    /*
    private fun logToSystemConsole(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?
    ) {
        // Логируем в системную консоль для важных категорий
        val shouldPrint = when {
            level == LogLevel.ERROR -> true
            PluginSettings.instance.debugMode && level.value >= LogLevel.DEBUG.value -> true
            // Выводим INFO и WARN логи для важных категорий
            (level == LogLevel.INFO || level == LogLevel.WARN) && 
                (category == LogCategory.ADB_CONNECTION || 
                 category == LogCategory.NETWORK || 
                 category == LogCategory.SCRCPY ||
                 category == LogCategory.DEVICE_STATE) -> true
            else -> false
        }
        
        if (shouldPrint) {
            val timestamp = LocalDateTime.now().format(consoleFormatter)
            val consoleMessage = buildString {
                append("[$timestamp] ")
                append("[${level.name}] ")
                append("[${category.displayName}] ")
                append(message)
                if (throwable != null) {
                    append("\nException: ${throwable.message}")
                    append("\nStackTrace: ${throwable.stackTraceToString()}")
                }
            }
            println(consoleMessage)
        }
    }
    */
    
    /**
     * Логирует в файл
     */
    private fun logToFile(level: LogLevel, message: String, throwable: Throwable?) {
        val fileMessage = if (throwable != null) {
            "$message\nException: ${throwable.message}\nStackTrace:\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        FileLogger.log(fileMessage, level.name)
    }
    
    /**
     * Форматирует сообщение с подстановкой аргументов
     */
    private fun formatMessage(category: LogCategory, message: String, args: Array<out Any?>): String {
        val formatted = if (args.isNotEmpty()) {
            try {
                message.format(*args)
            } catch (e: Exception) {
                // В случае ошибки форматирования возвращаем оригинальное сообщение
                "$message (format error: ${e.message})"
            }
        } else {
            message
        }
        return "$PREFIX [${category.displayName}]: $formatted"
    }
    
    /**
     * Специальный метод для логирования Running Devices
     */
    fun logRunningDevices(message: String) {
        processLog(LogLevel.INFO, LogCategory.ANDROID_STUDIO, "RUNNING_DEVICES: $message", null)
    }
    
    /**
     * Специальный метод для критических ошибок, которые всегда должны логироваться
     */
//    fun logCriticalError(category: LogCategory, message: String, throwable: Throwable?) {
//        // Критические ошибки всегда логируются независимо от настроек
//        val formattedMessage = formatMessage(category, message, emptyArray())
//
//        // Всегда выводим в IDE консоль
//        logger.error(formattedMessage, throwable)
//
//        // Всегда выводим в системную консоль
//        val timestamp = LocalDateTime.now().format(consoleFormatter)
//        println("[$timestamp] [CRITICAL ERROR] $formattedMessage")
//        throwable?.let {
//            println("Exception: ${it.message}")
//            println("StackTrace: ${it.stackTraceToString()}")
//        }
//
//        // Всегда пишем в файл если возможно
//        try {
//            FileLogger.logError(formattedMessage, throwable)
//        } catch (e: Exception) {
//            println("Failed to write critical error to file: ${e.message}")
//        }
//    }
}