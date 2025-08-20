package io.github.qavlad.adbrandomizer.services

import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
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
            val command = getKillServerCommand()
            PluginLogger.info("Executing ADB kill-server command: ${command.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            val success = exitCode == 0
            if (success) {
                PluginLogger.info("ADB server killed successfully")
            } else {
                PluginLogger.error("Failed to kill ADB server, exit code: $exitCode")
            }
            
            success
        } catch (e: IOException) {
            PluginLogger.error("Error killing ADB server", e)
            false
        } catch (e: InterruptedException) {
            PluginLogger.error("ADB kill-server command was interrupted", e)
            Thread.currentThread().interrupt()
            false
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
