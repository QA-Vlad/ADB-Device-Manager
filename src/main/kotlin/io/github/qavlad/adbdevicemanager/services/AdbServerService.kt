package io.github.qavlad.adbdevicemanager.services

import io.github.qavlad.adbdevicemanager.utils.AdbPathResolver
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import java.io.IOException

/**
 * Сервис для управления ADB сервером
 */
object AdbServerService {
    
    /**
     * Останавливает ADB сервер
     */
    fun killAdbServer(): Boolean {
        return try {
            // Уведомляем о начале рестарта ADB (если ещё не установлено)
            if (!AdbStateManager.isAdbRestarting()) {
                AdbStateManager.setAdbRestarting(true)
            }
            
            // Сначала отключаем все устройства
            disconnectAll()
            
            // Затем убиваем сервер
            val command = getKillServerCommand()
            PluginLogger.info("Executing ADB kill-server command: ${command.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            val success = exitCode == 0
            if (success) {
                PluginLogger.info("ADB server killed successfully")
                // Не снимаем флаг здесь - его снимет вызывающий код после полного рестарта
            } else {
                PluginLogger.error("Failed to kill ADB server, exit code: $exitCode")
                // При ошибке снимаем флаг
                AdbStateManager.setAdbRestarting(false)
            }
            
            success
        } catch (e: IOException) {
            PluginLogger.error("Error killing ADB server", e)
            AdbStateManager.setAdbRestarting(false)
            false
        } catch (e: InterruptedException) {
            PluginLogger.error("ADB kill-server command was interrupted", e)
            Thread.currentThread().interrupt()
            AdbStateManager.setAdbRestarting(false)
            false
        }
    }
    
    /**
     * Отключает все подключенные устройства
     */
    private fun disconnectAll(): Boolean {
        return try {
            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                PluginLogger.error("ADB executable not found in system")
                return false
            }
            
            val command = listOf(adbPath, "disconnect")
            PluginLogger.info("Executing ADB disconnect command: ${command.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            val success = exitCode == 0
            if (success) {
                PluginLogger.info("Disconnected all devices")
            } else {
                PluginLogger.warn("Failed to disconnect devices, exit code: $exitCode")
            }
            
            success
        } catch (e: Exception) {
            PluginLogger.warn("Error disconnecting devices: ${e.message}")
            // Не критичная ошибка, продолжаем
            true
        }
    }
    
    /**
     * Возвращает команду для остановки ADB сервера в зависимости от ОС
     */
    private fun getKillServerCommand(): List<String> {
        val adbPath = AdbPathResolver.findAdbExecutable()
        if (adbPath == null) {
            PluginLogger.error("ADB executable not found in system")
            throw IOException("ADB executable not found. Please ensure ADB is installed and accessible.")
        }
        
        return listOf(adbPath, "kill-server")
    }
}
