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
    
    /**
     * Проверяет, есть ли ЛЮБЫЕ процессы scrcpy для устройства (включая внешние)
     */
    fun hasAnyScrcpyProcessForDevice(serialNumber: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "=== Checking for ANY scrcpy processes for device: %s ===", serialNumber)
        
        // Сначала проверяем наши процессы
        if (isScrcpyActiveForDevice(serialNumber)) {
            PluginLogger.info(LogCategory.SCRCPY, "Found our scrcpy process for device: %s", serialNumber)
            return true
        }
        
        PluginLogger.info(LogCategory.SCRCPY, "No our scrcpy process found, checking external processes...")
        
        // Затем проверяем внешние процессы
        return try {
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val command = if (isWindows) {
                listOf("wmic", "process", "where", "name='scrcpy.exe'", "get", "CommandLine", "/format:list")
            } else {
                listOf("ps", "aux")
            }
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            
            var hasExternalScrcpy = false
            
            if (isWindows) {
                var foundAnyScrcpy = false
                output.lines().forEach { line ->
                    if (line.startsWith("CommandLine=") && line.contains("scrcpy")) {
                        foundAnyScrcpy = true
                        PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy process: %s", line)
                        if (line.contains(serialNumber)) {
                            hasExternalScrcpy = true
                            PluginLogger.info(LogCategory.SCRCPY, "Found external scrcpy process for device %s: %s", serialNumber, line)
                        }
                    }
                }
                if (foundAnyScrcpy && !hasExternalScrcpy) {
                    PluginLogger.info(LogCategory.SCRCPY, "Found scrcpy processes but none matched device %s", serialNumber)
                }
            } else {
                output.lines().forEach { line ->
                    if (line.contains("scrcpy") && line.contains(serialNumber)) {
                        hasExternalScrcpy = true
                        PluginLogger.debug(LogCategory.SCRCPY, "Found external scrcpy process for device %s", serialNumber)
                    }
                }
            }
            
            process.waitFor(3, TimeUnit.SECONDS)
            hasExternalScrcpy
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.SCRCPY, "Error checking for external scrcpy processes: %s", e.message ?: "Unknown error")
            false
        }
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
    
    /**
     * Останавливает все процессы scrcpy для указанного устройства,
     * которые были запущены не из плагина
     */
    private fun stopExternalScrcpyProcesses(serialNumber: String) {
        try {
            PluginLogger.info(LogCategory.SCRCPY, "Looking for external scrcpy processes for device: %s", serialNumber)
            
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            PluginLogger.debug(LogCategory.SCRCPY, "Operating system: %s, isWindows: %s", System.getProperty("os.name"), isWindows.toString())
            
            // Команда для поиска процессов scrcpy
            val command = if (isWindows) {
                // На Windows используем tasklist + wmic для надёжности
                listOf("cmd.exe", "/c", "tasklist | findstr scrcpy")
            } else {
                // На Unix-подобных системах используем ps
                listOf("ps", "aux")
            }
            
            PluginLogger.debug(LogCategory.SCRCPY, "Executing command: %s", command.joinToString(" "))
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            
            // Читаем ошибки, если есть
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
            if (errorOutput.isNotBlank()) {
                PluginLogger.warn(LogCategory.SCRCPY, "Command error output: %s", errorOutput)
            }
            
            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                PluginLogger.debug(LogCategory.SCRCPY, "Command output length: %d characters", output.length.toString())
                
                // Логируем первые несколько строк для отладки
                val outputLines = output.lines()
                if (outputLines.isNotEmpty()) {
                    PluginLogger.debug(LogCategory.SCRCPY, "First few lines of output:")
                    outputLines.take(5).forEach { line ->
                        PluginLogger.debug(LogCategory.SCRCPY, "  %s", line)
                    }
                }
                
                // Парсим вывод и ищем процессы scrcpy с нужным серийным номером
                val processesToKill = mutableListOf<String>()
                var totalScrcpyProcessesFound = 0
                
                if (isWindows) {
                    // Парсим вывод tasklist
                    // Формат: scrcpy.exe                    4764 Console                    6   156�440 ��
                    val lines = output.lines()
                    
                    for (line in lines) {
                        if (line.contains("scrcpy.exe")) {
                            totalScrcpyProcessesFound++
                            
                            // Парсим PID из строки tasklist
                            val parts = line.trim().split("\\s+".toRegex())
                            if (parts.size >= 2) {
                                val pid = parts[1]
                                PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy process from tasklist: PID=%s", pid)
                                
                                // Получаем командную строку для этого PID
                                val cmdLineCommand = listOf("cmd.exe", "/c", "wmic process where ProcessId=$pid get CommandLine")
                                try {
                                    val cmdLineProcess = ProcessBuilder(cmdLineCommand).start()
                                    val cmdLineOutput = cmdLineProcess.inputStream.bufferedReader().use { it.readText() }
                                    
                                    PluginLogger.debug(LogCategory.SCRCPY, "Command line for PID %s: %s", pid, cmdLineOutput.trim())
                                    
                                    // Проверяем, содержит ли командная строка наш серийный номер
                                    val containsSerial = cmdLineOutput.contains(serialNumber)
                                    PluginLogger.debug(LogCategory.SCRCPY, "Process PID=%s contains serial '%s': %s", 
                                        pid, serialNumber, containsSerial.toString())
                                    
                                    if (containsSerial) {
                                        // Проверяем, что это не наш процесс
                                        val pidInt = pid.toIntOrNull()
                                        if (pidInt != null) {
                                            val isOur = isOurProcess(pidInt)
                                            PluginLogger.debug(LogCategory.SCRCPY, "Process PID=%s is our process: %s", 
                                                pid, isOur.toString())
                                            
                                            if (!isOur) {
                                                processesToKill.add(pid)
                                                PluginLogger.info(LogCategory.SCRCPY, "Will kill external scrcpy process: PID=%s", pid)
                                            }
                                        }
                                    }
                                    
                                    cmdLineProcess.waitFor(2, TimeUnit.SECONDS)
                                } catch (e: Exception) {
                                    PluginLogger.debug(LogCategory.SCRCPY, "Error getting command line for PID %s: %s", 
                                        pid, e.message ?: "Unknown error")
                                }
                            }
                        }
                    }
                } else {
                    // Парсим вывод ps aux
                    val lines = output.lines()
                    for (line in lines) {
                        if (line.contains("scrcpy")) {
                            totalScrcpyProcessesFound++
                            PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy process: %s", line)
                            
                            if (line.contains(serialNumber)) {
                                val parts = line.trim().split("\\s+".toRegex())
                                if (parts.size > 1) {
                                    val pid = parts[1]
                                    val pidInt = pid.toIntOrNull() ?: -1
                                    val isOur = isOurProcess(pidInt)
                                    
                                    PluginLogger.debug(LogCategory.SCRCPY, "Process PID=%s is our process: %s", 
                                        pid, isOur.toString())
                                    
                                    if (!isOur) {
                                        processesToKill.add(pid)
                                        PluginLogger.info(LogCategory.SCRCPY, "Will kill external scrcpy process: PID=%s", pid)
                                    }
                                }
                            }
                        }
                    }
                }
                
                PluginLogger.info(LogCategory.SCRCPY, "Total scrcpy processes found: %s, processes to kill: %s", 
                    totalScrcpyProcessesFound.toString(), processesToKill.size.toString())
                
                // Убиваем найденные процессы
                for (pid in processesToKill) {
                    killProcess(pid, isWindows)
                }
                
                if (processesToKill.isEmpty() && totalScrcpyProcessesFound > 0) {
                    PluginLogger.info(LogCategory.SCRCPY, "Found %s scrcpy processes but none matched device %s", 
                        totalScrcpyProcessesFound.toString(), serialNumber)
                    
                    // Если не нашли процессы по серийному номеру, но есть scrcpy процессы,
                    // попробуем альтернативную стратегию
                    tryAlternativeScrcpyKillStrategy(serialNumber, isWindows)
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS)
            
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.SCRCPY, "Error stopping external scrcpy processes", e)
        }
    }
    
    /**
     * Проверяет, является ли процесс с указанным PID нашим
     */
    private fun isOurProcess(pid: Int): Boolean {
        // Проверяем, есть ли процесс с таким PID в нашем списке активных процессов
        for ((_, process) in activeScrcpyProcesses) {
            try {
                // Получаем PID процесса (доступно с Java 9)
                val processPid = process.pid()
                if (processPid == pid.toLong()) {
                    return true
                }
            } catch (_: Exception) {
                // Если не можем получить PID, пробуем другой способ
                try {
                    val processHandle = process.toHandle()
                    if (processHandle.pid() == pid.toLong()) {
                        return true
                    }
                } catch (_: Exception) {
                    // Игнорируем, если не поддерживается
                }
            }
        }
        return false
    }
    
    /**
     * Убивает процесс с указанным PID
     */
    private fun killProcess(pid: String, isWindows: Boolean) {
        try {
            val killCommand = if (isWindows) {
                listOf("taskkill", "/F", "/PID", pid)
            } else {
                listOf("kill", "-9", pid)
            }
            
            val process = ProcessBuilder(killCommand).start()
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    PluginLogger.info(LogCategory.SCRCPY, "Successfully killed process with PID: %s", pid)
                } else {
                    PluginLogger.warn(LogCategory.SCRCPY, "Failed to kill process with PID %s, exit code: %s", pid, exitCode.toString())
                }
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.SCRCPY, "Error killing process %s", e, pid)
        }
    }
    
    /**
     * Альтернативная стратегия для закрытия процессов scrcpy
     * Используется когда не можем найти процесс по серийному номеру
     */
    private fun tryAlternativeScrcpyKillStrategy(serialNumber: String, isWindows: Boolean) {
        try {
            PluginLogger.info(LogCategory.SCRCPY, "Trying alternative strategy to find scrcpy processes")
            
            // Проверяем, подключено ли устройство через ADB
            val checkCommand = if (isWindows) {
                listOf("cmd.exe", "/c", "adb", "devices", "-l")
            } else {
                listOf("adb", "devices", "-l")
            }
            
            val processBuilder = ProcessBuilder(checkCommand)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            
            PluginLogger.debug(LogCategory.SCRCPY, "ADB devices output: %s", output)
            
            // Ищем наше устройство в списке подключенных
            val lines = output.lines()
            var deviceFound = false
            
            for (line in lines) {
                if (line.contains(serialNumber) && line.contains("device")) {
                    deviceFound = true
                    PluginLogger.info(LogCategory.SCRCPY, "Device %s is connected", serialNumber)
                    break
                }
            }
            
            if (deviceFound) {
                // Если устройство подключено, закрываем ВСЕ внешние процессы scrcpy
                PluginLogger.warn(LogCategory.SCRCPY, "Will kill ALL external scrcpy processes as fallback")
                
                val killCommand = if (isWindows) {
                    // Получаем все процессы scrcpy через tasklist
                    listOf("cmd.exe", "/c", "tasklist | findstr scrcpy.exe")
                } else {
                    listOf("pgrep", "scrcpy")
                }
                
                val killProcessBuilder = ProcessBuilder(killCommand)
                val killProcess = killProcessBuilder.start()
                val killOutput = killProcess.inputStream.bufferedReader().use { it.readText() }
                
                val pidsToKill = mutableListOf<String>()
                
                if (isWindows) {
                    // Парсим вывод tasklist
                    killOutput.lines().forEach { line ->
                        if (line.contains("scrcpy.exe")) {
                            val parts = line.trim().split("\\s+".toRegex())
                            if (parts.size >= 2) {
                                val pid = parts[1]
                                if (pid.isNotBlank()) {
                                    val pidInt = pid.toIntOrNull()
                                    if (pidInt != null && !isOurProcess(pidInt)) {
                                        pidsToKill.add(pid)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    killOutput.lines().forEach { pid ->
                        if (pid.isNotBlank()) {
                            val pidInt = pid.toIntOrNull()
                            if (pidInt != null && !isOurProcess(pidInt)) {
                                pidsToKill.add(pid)
                            }
                        }
                    }
                }
                
                PluginLogger.info(LogCategory.SCRCPY, "Found %s external scrcpy processes to kill (alternative strategy)", 
                    pidsToKill.size.toString())
                
                for (pid in pidsToKill) {
                    PluginLogger.info(LogCategory.SCRCPY, "Killing scrcpy process PID=%s (alternative strategy)", pid)
                    killProcess(pid, isWindows)
                }
            }
            
            process.waitFor(3, TimeUnit.SECONDS)
            
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.SCRCPY, "Error in alternative scrcpy kill strategy", e)
        }
    }
    
    fun restartScrcpyForDevice(serialNumber: String, project: Project): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Restarting scrcpy for device: %s", serialNumber)
        
        // Останавливаем текущий процесс из плагина
        stopScrcpyForDevice(serialNumber)
        
        // Останавливаем внешние процессы scrcpy для этого устройства
        stopExternalScrcpyProcesses(serialNumber)
        
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

            // Останавливаем внешние процессы scrcpy для этого устройства перед запуском нового
            stopExternalScrcpyProcesses(serialNumber)

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
            PluginLogger.info(LogCategory.SCRCPY, "Saved scrcpy process for device %s, total active processes: %s", 
                serialNumber, activeScrcpyProcesses.size.toString())

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