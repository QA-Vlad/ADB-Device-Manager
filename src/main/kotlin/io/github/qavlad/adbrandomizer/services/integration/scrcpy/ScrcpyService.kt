// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/integration/scrcpy/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services.integration.scrcpy

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ui.ScrcpyCompatibilityDialog
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.io.File
import java.util.concurrent.TimeUnit

object ScrcpyService {

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) 
        PluginConfig.Scrcpy.SCRCPY_NAMES["windows"]!! 
    else 
        PluginConfig.Scrcpy.SCRCPY_NAMES["default"]!!
    
    // Хранилище активных scrcpy процессов по серийному номеру устройства
    private val activeScrcpyProcesses = mutableMapOf<String, Process>()

    fun findScrcpyExecutable(): String? {
        val savedPath = PresetStorageService.getScrcpyPath()
        if (savedPath != null && File(savedPath).canExecute()) {
            return savedPath
        }

        val pathFromSystem = AdbPathResolver.findExecutableInSystemPath(scrcpyName)
        if (pathFromSystem != null) {
            PresetStorageService.saveScrcpyPath(pathFromSystem)
            return pathFromSystem
        }

        return null
    }
    
    fun isScrcpyActiveForDevice(serialNumber: String): Boolean {
        val process = activeScrcpyProcesses[serialNumber]
        val isActive = process != null && process.isAlive
        
        // Логируем все активные процессы для отладки
        PluginLogger.debug(LogCategory.SCRCPY, "Active scrcpy processes: %s", 
            activeScrcpyProcesses.keys.joinToString(", "))
        
        PluginLogger.debug(LogCategory.SCRCPY, "Checking scrcpy status for device %s: process=%s, isAlive=%s, result=%s", 
            serialNumber, 
            process != null, 
            process?.isAlive ?: false,
            isActive
        )
        return isActive
    }
    
    fun stopScrcpyForDevice(serialNumber: String): Boolean {
        val process = activeScrcpyProcesses.remove(serialNumber)
        if (process != null && process.isAlive) {
            try {
                process.destroy()
                // Даём процессу время на завершение
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                PluginLogger.info(LogCategory.SCRCPY, "Stopped scrcpy for device: %s", serialNumber)
                return true
            } catch (e: Exception) {
                PluginLogger.info(LogCategory.SCRCPY, "Error stopping scrcpy for device %s: %s", serialNumber, e.message)
            }
        }
        return false
    }
    
    fun restartScrcpyForDevice(serialNumber: String, project: Project): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Restarting scrcpy for device: %s", serialNumber)
        
        // Останавливаем текущий процесс
        stopScrcpyForDevice(serialNumber)
        
        // Небольшая задержка перед перезапуском
        Thread.sleep(1000)
        
        // Находим путь к scrcpy
        val scrcpyPath = findScrcpyExecutable()
        if (scrcpyPath == null) {
            PluginLogger.info(LogCategory.SCRCPY, "Cannot restart scrcpy - executable not found")
            return false
        }
        
        // Запускаем новый процесс
        return launchScrcpy(scrcpyPath, serialNumber, project)
    }

    fun launchScrcpy(scrcpyPath: String, serialNumber: String, @Suppress("UNUSED_PARAMETER") project: Project): Boolean {
        try {
            if (scrcpyPath.isBlank() || serialNumber.isBlank()) {
                PluginLogger.info(LogCategory.SCRCPY, "Empty scrcpy path or serial number provided")
                return false
            }

            val scrcpyFile = File(scrcpyPath)
            if (!scrcpyFile.exists() || !scrcpyFile.canExecute()) {
                PluginLogger.info(LogCategory.SCRCPY, "Scrcpy executable not found or not executable at: %s", scrcpyPath)
                return false
            }

            PluginLogger.info(LogCategory.SCRCPY, "Starting scrcpy for device: %s", serialNumber)

            val adbPath = AdbPathResolver.findAdbExecutable()
            if (adbPath == null) {
                PluginLogger.info(LogCategory.SCRCPY, "Cannot find ADB executable")
                return false
            }

            PluginLogger.info(LogCategory.SCRCPY, "Using ADB: %s", adbPath)

            val version = checkScrcpyVersion(scrcpyPath)
            val success = tryDifferentScrcpyMethods(scrcpyPath, serialNumber, adbPath)

            if (!success) {
                var retry = false
                ApplicationManager.getApplication().invokeAndWait {
                    val dialog = ScrcpyCompatibilityDialog(
                        project,
                        version.ifBlank { "Unknown" },
                        serialNumber
                    )
                    dialog.show()
                    if (dialog.exitCode == PluginConfig.UIConstants.RETRY_EXIT_CODE) {
                        retry = true
                    }
                }

                if (retry) {
                    PluginLogger.info(LogCategory.SCRCPY, "New scrcpy path selected. Retrying screen mirroring...")
                    val newPath = findScrcpyExecutable()
                    if (newPath != null) {
                        return launchScrcpy(newPath, serialNumber, project)
                    }
                }
            }

            return success

        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            PluginLogger.info(LogCategory.SCRCPY, "Unexpected error starting scrcpy: %s", e.message)
            e.printStackTrace()
            return false
        }
    }

    private fun tryDifferentScrcpyMethods(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        if (tryScrcpyWithDisplayId(scrcpyPath, serialNumber, adbPath)) return true
        if (tryScrcpyWithV4l2(scrcpyPath, serialNumber, adbPath)) return true
        if (tryScrcpyWithCompatibilityFlags(scrcpyPath, serialNumber, adbPath)) return true
        if (tryMinimalScrcpy(scrcpyPath, serialNumber, adbPath)) return true

        showScrcpyUpdateMessage()
        return false
    }

    private fun tryScrcpyWithDisplayId(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Trying scrcpy with display-id 0...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio", "--display-id=0", "--video-codec=h264")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun tryScrcpyWithV4l2(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Trying scrcpy with force software encoder...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio", "--video-codec=h264", "--video-encoder=OMX.google.h264.encoder")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun showScrcpyUpdateMessage() {
        PluginLogger.info(LogCategory.SCRCPY, "=================================================")
        PluginLogger.info(LogCategory.SCRCPY, "SCRCPY COMPATIBILITY ISSUE DETECTED")
        PluginLogger.info(LogCategory.SCRCPY, "Your scrcpy version has known issues with Android 15")
        PluginLogger.info(LogCategory.SCRCPY, "Showing compatibility dialog to user...")
        PluginLogger.info(LogCategory.SCRCPY, "=================================================")
    }

    private fun tryScrcpyWithCompatibilityFlags(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Trying scrcpy with compatibility flags...")
        val command = mutableListOf(scrcpyPath, "-s", serialNumber)
        command.addAll(listOf(
            "--no-audio",
            "--no-cleanup",
            "--video-codec=h264",
            "--max-size=1920",
            "--video-bit-rate=8M",
            "--disable-screensaver",
            "--stay-awake"
        ))
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun tryMinimalScrcpy(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Trying minimal scrcpy...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun launchScrcpyProcess(command: List<String>, adbPath: String, scrcpyPath: String, serialNumber: String): Boolean {
        try {
            PluginLogger.info(LogCategory.SCRCPY, "Command: %s", command.joinToString(" "))
            val processBuilder = ProcessBuilder(command)
            processBuilder.environment()["ADB"] = adbPath
            processBuilder.directory(File(scrcpyPath).parentFile)
            val process = processBuilder.start()

            val outputReader = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().take(PluginConfig.Scrcpy.MAX_LOG_LINES).forEach { line ->
                            PluginLogger.info(LogCategory.SCRCPY, "scrcpy stdout: %s", line)
                        }
                    }
                } catch (_: Exception) { /* Игнорируем ошибки чтения */ }
            }

            val errorReader = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lineSequence().take(PluginConfig.Scrcpy.MAX_LOG_LINES).forEach { line ->
                            PluginLogger.info(LogCategory.SCRCPY, "scrcpy stderr: %s", line)
                        }
                    }
                } catch (_: Exception) { /* Игнорируем ошибки чтения */ }
            }

            outputReader.start()
            errorReader.start()

            Thread.sleep(PluginConfig.Scrcpy.STARTUP_WAIT_MS)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                PluginLogger.info(LogCategory.SCRCPY, "Scrcpy process finished early. Exit code: %s", exitCode)
                return exitCode == 0
            }

            Thread.sleep(PluginConfig.Scrcpy.PROCESS_CHECK_DELAY_MS)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                PluginLogger.info(LogCategory.SCRCPY, "Scrcpy process closed after startup. Exit code: %s", exitCode)
                return exitCode == 0
            }

            PluginLogger.info(LogCategory.SCRCPY, "Scrcpy started successfully for device: %s", serialNumber)
            
            // Сохраняем процесс для возможности управления им
            activeScrcpyProcesses[serialNumber] = process
            PluginLogger.info(LogCategory.SCRCPY, "Saved scrcpy process for device %s, total active processes: %d", 
                serialNumber, activeScrcpyProcesses.size)

            Thread {
                try {
                    val exitCode = process.waitFor()
                    PluginLogger.info(LogCategory.SCRCPY, "Scrcpy for device %s closed with exit code: %s", serialNumber, exitCode)
                    outputReader.join(1000)
                    errorReader.join(1000)
                    // Удаляем процесс из активных после завершения
                    activeScrcpyProcesses.remove(serialNumber)
                } catch (_: InterruptedException) {
                    PluginLogger.info(LogCategory.SCRCPY, "Scrcpy monitoring interrupted for device: %s", serialNumber)
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    PluginLogger.info(LogCategory.SCRCPY, "Error monitoring scrcpy process: %s", e.message)
                }
            }.start()

            return true

        } catch (e: Exception) {
            PluginLogger.info(LogCategory.SCRCPY, "Error launching scrcpy process: %s", e.message)
            return false
        }
    }

    private fun checkScrcpyVersion(scrcpyPath: String): String {
        return try {
            val process = ProcessBuilder(scrcpyPath, "--version").start()
            val finished = process.waitFor(PluginConfig.Scrcpy.VERSION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (finished) {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            PluginLogger.info(LogCategory.SCRCPY, "Could not get scrcpy version: %s", e.message)
            ""
        }
    }
}