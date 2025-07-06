package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.util.SystemInfo
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
        return when {
            SystemInfo.isWindows -> listOf("adb", "kill-server")
            SystemInfo.isMac -> listOf("adb", "kill-server")
            SystemInfo.isLinux -> listOf("adb", "kill-server")
            else -> {
                PluginLogger.warn("Unknown operating system, using default adb command")
                listOf("adb", "kill-server")
            }
        }
    }
}
