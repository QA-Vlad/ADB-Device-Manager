package io.github.qavlad.adbrandomizer.utils

import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.logging.LogLevel
import io.github.qavlad.adbrandomizer.utils.logging.LoggingController

/**
 * Console logger that outputs to IDE notifications for debugging
 * Works even when plugin is installed from ZIP
 * 
 * Теперь использует централизованный LoggingController
 */
object ConsoleLogger {
    
    fun logError(message: String, throwable: Throwable? = null) {
        // Используем централизованный контроллер для обработки ошибок
        LoggingController.processLog(
            LogLevel.ERROR,
            LogCategory.ANDROID_STUDIO,
            message,
            throwable
        )
    }
    
    fun logRunningDevices(message: String) {
        // Используем специальный метод контроллера для Running Devices
        LoggingController.logRunningDevices(message)
    }

}