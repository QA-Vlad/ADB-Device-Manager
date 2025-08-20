// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/integration/scrcpy/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services.integration.scrcpy

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.core.Result
import io.github.qavlad.adbrandomizer.services.AdbService
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ui.ScrcpyCompatibilityDialog
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.io.File
import java.util.concurrent.TimeUnit

object ScrcpyService {

    private enum class LaunchResult {
        SUCCESS,
        UNAUTHORIZED,
        INVALID_FLAGS,
        FAILED,
        ANDROID_15_INCOMPATIBLE
    }
    
    // Хранилище последней ошибки с флагами
    private var lastInvalidFlagError: String? = null

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) 
        PluginConfig.Scrcpy.SCRCPY_NAMES["windows"]!! 
    else 
        PluginConfig.Scrcpy.SCRCPY_NAMES["default"]!!
    
    // Хранилище активных scrcpy процессов по серийному номеру устройства
    private val activeScrcpyProcesses = mutableMapOf<String, Process>()
    private var lastLaunchResult: LaunchResult = LaunchResult.SUCCESS

    // Хранилище недавно запущенных процессов (временное)
    private val recentlyStartedProcesses = mutableMapOf<Long, String>() // PID -> serialNumber
    // Устройства, для которых scrcpy был намеренно остановлен (например, при отключении Wi-Fi)
    private val intentionallyStopped = mutableSetOf<String>()

    fun findScrcpyExecutable(): String? {
        // 1. Проверяем путь из настроек плагина (если пользователь явно задал)
        val settingsPath = PluginSettings.instance.scrcpyPath
        if (settingsPath.isNotBlank()) {
            val file = File(settingsPath)
            
            // Если это папка, ищем scrcpy внутри
            if (file.isDirectory) {
                val scrcpyInDir = File(file, scrcpyName)
                if (scrcpyInDir.exists() && scrcpyInDir.canExecute()) {
                    PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy in settings directory: %s", scrcpyInDir.absolutePath)
                    return scrcpyInDir.absolutePath
                }
            }
            // Если это файл, проверяем что он исполняемый
            else if (file.isFile && file.canExecute()) {
                PluginLogger.debug(LogCategory.SCRCPY, "Using scrcpy from settings: %s", file.absolutePath)
                return file.absolutePath
            }
        }
        
        // 2. Проверяем старый сохраненный путь (для обратной совместимости)
        val savedPath = PresetStorageService.getScrcpyPath()
        if (savedPath != null && File(savedPath).canExecute()) {
            // Мигрируем в новые настройки
            PluginSettings.instance.scrcpyPath = savedPath
            PluginLogger.debug(LogCategory.SCRCPY, "Migrated scrcpy path from old storage: %s", savedPath)
            return savedPath
        }

        // 3. Ищем в системном PATH (при первом запуске или если путь не задан)
        val pathFromSystem = AdbPathResolver.findExecutableInSystemPath(scrcpyName)
        if (pathFromSystem != null) {
            PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy in system PATH: %s", pathFromSystem)
            // Сохраняем найденный путь в оба места для синхронизации
            PresetStorageService.saveScrcpyPath(pathFromSystem)
            PluginSettings.instance.scrcpyPath = pathFromSystem
            return pathFromSystem
        }

        // Если ничего не нашли - путь остаётся пустым в настройках
        PluginLogger.warn(LogCategory.SCRCPY, "scrcpy executable not found")
        return null
    }
    
    fun isScrcpyActiveForDevice(serialNumber: String): Boolean {
        val process = activeScrcpyProcesses[serialNumber]
        
        // Если процесс завершился, удаляем его из мапы
        if (process != null && !process.isAlive) {
            activeScrcpyProcesses.remove(serialNumber)
            PluginLogger.debug(LogCategory.SCRCPY, "Removed dead scrcpy process for device %s from map", serialNumber)
        }
        
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
     * Проверяет, была ли остановка scrcpy намеренной (например, при отключении Wi-Fi)
     */
    fun wasIntentionallyStopped(serialNumber: String): Boolean {
        return intentionallyStopped.contains(serialNumber)
    }
    
    /**
     * Помечает устройство как намеренно остановленное
     * Используется для предотвращения показа диалога об ошибке при отключении Wi-Fi
     */
    /**
     * Помечает ТОЛЬКО указанный serial number как намеренно остановленный, не трогая связанные
     */
    fun markSingleSerialAsIntentionallyStopped(serialNumber: String) {
        intentionallyStopped.add(serialNumber)
        // Убираем флаг через некоторое время
        Thread {
            Thread.sleep(5000)
            intentionallyStopped.remove(serialNumber)
        }.start()
    }

    /**
     * Проверяет, есть ли ЛЮБЫЕ процессы scrcpy для устройства (включая внешние)
     */
    fun hasAnyScrcpyProcessForDevice(serialNumber: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "=== Checking for ANY scrcpy processes for device: %s ===", serialNumber)
        
        // Получаем все связанные serial numbers
        val relatedSerials = findRelatedSerialNumbers(serialNumber)
        PluginLogger.info(LogCategory.SCRCPY, "Checking for scrcpy processes for related serials: %s", 
            relatedSerials.joinToString(", "))
        
        // Проверяем наши процессы для всех связанных serial numbers
        for (serial in relatedSerials) {
            if (isScrcpyActiveForDevice(serial)) {
                PluginLogger.info(LogCategory.SCRCPY, "Found our scrcpy process for serial: %s", serial)
                return true
            }
        }
        
        PluginLogger.info(LogCategory.SCRCPY, "No our scrcpy processes found, checking external processes...")
        
        // Затем проверяем внешние процессы для всех связанных serial numbers
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
                        // Проверяем все связанные serial numbers
                        for (serial in relatedSerials) {
                            if (line.contains(serial)) {
                                hasExternalScrcpy = true
                                PluginLogger.info(LogCategory.SCRCPY, "Found external scrcpy process for serial %s: %s", serial, line)
                                break
                            }
                        }
                    }
                }
                if (foundAnyScrcpy && !hasExternalScrcpy) {
                    PluginLogger.info(LogCategory.SCRCPY, "Found scrcpy processes but none matched related serials: %s", 
                        relatedSerials.joinToString(", "))
                }
            } else {
                output.lines().forEach { line ->
                    if (line.contains("scrcpy")) {
                        // Проверяем все связанные serial numbers
                        for (serial in relatedSerials) {
                            if (line.contains(serial)) {
                                hasExternalScrcpy = true
                                PluginLogger.debug(LogCategory.SCRCPY, "Found external scrcpy process for serial %s", serial)
                                break
                            }
                        }
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
    
    /**
     * Останавливает ТОЛЬКО конкретный scrcpy процесс для указанного serial number, не трогая связанные
     */
    fun stopScrcpyForSingleSerial(serialNumber: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Stopping scrcpy for single serial: %s", serialNumber)
        
        val process = activeScrcpyProcesses.remove(serialNumber)
        if (process != null && process.isAlive) {
            try {
                // Помечаем что остановка намеренная
                intentionallyStopped.add(serialNumber)
                // Убираем флаг через некоторое время (чтобы не накапливались)
                Thread {
                    Thread.sleep(5000)
                    intentionallyStopped.remove(serialNumber)
                }.start()
                
                process.destroy()
                // Даём процессу время на завершение
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                PluginLogger.info(LogCategory.SCRCPY, "Stopped scrcpy for single serial: %s", serialNumber)
                return true
            } catch (e: Exception) {
                PluginLogger.info(LogCategory.SCRCPY, "Error stopping scrcpy for single serial %s: %s", serialNumber, e.message)
                return false
            }
        }
        return false
    }
    
    /**
     * Останавливает все scrcpy процессы для устройства, включая связанные (USB и Wi-Fi)
     */
    fun stopScrcpyForDevice(serialNumber: String): Boolean {
        PluginLogger.info(LogCategory.SCRCPY, "Stopping scrcpy for device: %s", serialNumber)
        
        // Получаем все связанные serial numbers для этого устройства
        val relatedSerials = findRelatedSerialNumbers(serialNumber)
        PluginLogger.info(LogCategory.SCRCPY, "Found related serial numbers for %s: %s", 
            serialNumber, relatedSerials.joinToString(", "))
        
        var stoppedAny = false
        
        // Останавливаем процессы для всех связанных serial numbers
        relatedSerials.forEach { serial ->
            val process = activeScrcpyProcesses.remove(serial)
            if (process != null && process.isAlive) {
                try {
                    // Помечаем что остановка намеренная
                    intentionallyStopped.add(serial)
                    // Убираем флаг через некоторое время (чтобы не накапливались)
                    Thread {
                        Thread.sleep(5000)
                        intentionallyStopped.remove(serial)
                    }.start()
                    
                    process.destroy()
                    // Даём процессу время на завершение
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                    PluginLogger.info(LogCategory.SCRCPY, "Stopped scrcpy for serial: %s", serial)
                    stoppedAny = true
                } catch (e: Exception) {
                    PluginLogger.info(LogCategory.SCRCPY, "Error stopping scrcpy for serial %s: %s", serial, e.message)
                }
            }
        }
        
        return stoppedAny
    }
    
    /**
     * Проверяет, являются ли два serial numbers связанными (одно устройство)
     */
    fun areSerialNumbersRelated(serial1: String, serial2: String): Boolean {
        if (serial1 == serial2) return true
        
        // Проверяем, являются ли они USB и Wi-Fi версиями одного устройства
        val relatedToFirst = findRelatedSerialNumbers(serial1)
        return relatedToFirst.contains(serial2)
    }
    
    /**
     * Получает все активные scrcpy serial numbers для устройства и его связанных подключений
     */
    fun getActiveScrcpySerials(serialNumber: String): Set<String> {
        val activeSerials = mutableSetOf<String>()
        val relatedSerials = findRelatedSerialNumbers(serialNumber)
        
        // Проверяем какие из связанных серийников имеют активные процессы
        relatedSerials.forEach { serial ->
            if (isScrcpyActiveForDevice(serial)) {
                activeSerials.add(serial)
                PluginLogger.debug(LogCategory.SCRCPY, "Found active scrcpy for serial: %s", serial)
            }
        }
        
        PluginLogger.info(LogCategory.SCRCPY, 
            "Active scrcpy serials for device %s: %s", 
            serialNumber, activeSerials.joinToString(", "))
        
        return activeSerials
    }
    
    /**
     * Находит все связанные serial numbers для устройства (USB и Wi-Fi версии)
     */
    private fun findRelatedSerialNumbers(serialNumber: String): Set<String> {
        val relatedSerials = mutableSetOf(serialNumber)
        
        // Получаем информацию о всех подключенных устройствах
        val devicesResult = AdbService.getAllDeviceSerials()
        val allDevices = devicesResult.getOrNull() ?: emptyList()
        
        PluginLogger.debug(LogCategory.SCRCPY, "All connected devices: %s", allDevices.joinToString(", "))
        
        // Если это Wi-Fi подключение (содержит IP адрес)
        if (serialNumber.contains(":")) {
            // Ищем USB версию этого же устройства
            // Для этого нужно получить базовый serial number из Wi-Fi устройства
            val baseSerial = getBaseSerialFromWifi(serialNumber)
            if (baseSerial != null) {
                // Добавляем USB версию если она подключена
                if (allDevices.contains(baseSerial)) {
                    relatedSerials.add(baseSerial)
                    PluginLogger.debug(LogCategory.SCRCPY, "Found USB version: %s for Wi-Fi: %s", baseSerial, serialNumber)
                }
            }
            
            // Также проверяем активные процессы scrcpy
            activeScrcpyProcesses.keys.forEach { activeSerial ->
                if (!activeSerial.contains(":") && allDevices.contains(activeSerial)) {
                    // Проверяем, является ли это тем же устройством
                    val wifiSerial = getWifiSerialForUsb(activeSerial)
                    if (wifiSerial == serialNumber) {
                        relatedSerials.add(activeSerial)
                        PluginLogger.debug(LogCategory.SCRCPY, "Found related USB from active processes: %s", activeSerial)
                    }
                }
            }
        } else {
            // Если это USB подключение, ищем Wi-Fi версии
            val wifiSerial = getWifiSerialForUsb(serialNumber)
            if (wifiSerial != null && allDevices.contains(wifiSerial)) {
                relatedSerials.add(wifiSerial)
                PluginLogger.debug(LogCategory.SCRCPY, "Found Wi-Fi version: %s for USB: %s", wifiSerial, serialNumber)
            }
            
            // Также проверяем активные процессы scrcpy для Wi-Fi версий
            activeScrcpyProcesses.keys.forEach { activeSerial ->
                if (activeSerial.contains(":")) {
                    // Проверяем, является ли это Wi-Fi версией нашего устройства
                    val baseSerial = getBaseSerialFromWifi(activeSerial)
                    if (baseSerial == serialNumber) {
                        relatedSerials.add(activeSerial)
                        PluginLogger.debug(LogCategory.SCRCPY, "Found related Wi-Fi from active processes: %s", activeSerial)
                    }
                }
            }
        }
        
        return relatedSerials
    }
    
    /**
     * Получает базовый serial number для Wi-Fi устройства
     */
    private fun getBaseSerialFromWifi(wifiSerial: String): String? {
        // Используем ADB для получения информации об устройстве
        val deviceInfo = AdbService.getDeviceInfo(wifiSerial)
        return deviceInfo.getOrNull()?.displaySerialNumber
    }
    
    /**
     * Получает Wi-Fi serial для USB устройства, если оно подключено по Wi-Fi
     */
    private fun getWifiSerialForUsb(usbSerial: String): String? {
        // Получаем все Wi-Fi устройства и ищем то, у которого базовый serial совпадает
        val devicesResult = AdbService.getAllDeviceSerials()
        val allDevices = devicesResult.getOrNull() ?: emptyList()
        
        for (device in allDevices) {
            if (device.contains(":")) {
                val baseSerial = getBaseSerialFromWifi(device)
                if (baseSerial == usbSerial) {
                    return device
                }
            }
        }
        return null
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
            
            // Команда для поиска процессов scrcpy - используем более эффективный подход
            val command = if (isWindows) {
                // На Windows используем tasklist + wmic для надёжности
                listOf("cmd.exe", "/c", "tasklist | findstr scrcpy")
            } else {
                // На Unix-подобных системах используем pgrep для более быстрого поиска
                // pgrep возвращает только PID процессов, что намного быстрее чем ps aux
                listOf("pgrep", "-f", "scrcpy.*$serialNumber")
            }
            
            PluginLogger.debug(LogCategory.SCRCPY, "Executing command: %s", command.joinToString(" "))
            
            val processBuilder = ProcessBuilder(command)
            val process = processBuilder.start()
            
            // Читаем вывод с таймаутом
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                PluginLogger.warn(LogCategory.SCRCPY, "Process search timed out, destroying process")
                process.destroyForcibly()
                return
            }
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
            
            if (errorOutput.isNotBlank()) {
                PluginLogger.debug(LogCategory.SCRCPY, "Command error output: %s", errorOutput)
            }
            
            if (output.isBlank()) {
                PluginLogger.debug(LogCategory.SCRCPY, "No external scrcpy processes found for device %s", serialNumber)
                return
            }
            
            PluginLogger.debug(LogCategory.SCRCPY, "Found process output: %s", output)
            
            // Парсим вывод в зависимости от ОС
            val processesToKill = mutableListOf<String>()
            
            if (isWindows) {
                // Парсим вывод tasklist
                // Формат: scrcpy.exe                    4764 Console                    6   156�440 ��
                val lines = output.lines()
                
                for (line in lines) {
                    if (line.contains("scrcpy.exe")) {
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
                // Для Unix - pgrep уже вернул только PID процессов scrcpy с нужным серийным номером
                val lines = output.lines().filter { it.isNotBlank() }
                for (pidStr in lines) {
                    val pid = pidStr.trim().toIntOrNull()
                    if (pid != null) {
                        val isOur = isOurProcess(pid)
                        PluginLogger.debug(LogCategory.SCRCPY, "Found scrcpy process PID=%s, is our: %s", pid.toString(), isOur.toString())
                        
                        if (!isOur) {
                            processesToKill.add(pid.toString())
                            PluginLogger.info(LogCategory.SCRCPY, "Will kill external scrcpy process: PID=%s", pid.toString())
                        }
                    }
                }
            }
            
            PluginLogger.info(LogCategory.SCRCPY, "Processes to kill: %s", processesToKill.size.toString())
            
            // Убиваем найденные процессы
            for (pid in processesToKill) {
                killProcess(pid, isWindows)
            }
            
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.SCRCPY, "Error stopping external scrcpy processes", e)
        }
    }
    
    /**
     * Проверяет, является ли процесс с указанным PID нашим
     */
    private fun isOurProcess(pid: Int): Boolean {
        // Сначала проверяем временное хранилище недавно запущенных процессов
        if (recentlyStartedProcesses.containsKey(pid.toLong())) {
            PluginLogger.debug(LogCategory.SCRCPY, "Process PID=%s is in recently started list", pid)
            return true
        }
        
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
            if (isWindows) {
                // Сначала пробуем мягкое завершение без /F
                val softKillCommand = listOf("taskkill", "/PID", pid)
                val softProcess = ProcessBuilder(softKillCommand).start()
                
                if (softProcess.waitFor(3, TimeUnit.SECONDS)) {
                    val exitCode = softProcess.exitValue()
                    if (exitCode == 0) {
                        PluginLogger.info(LogCategory.SCRCPY, "Successfully terminated process with PID: %s (soft kill)", pid)
                        Thread.sleep(1000) // Даём время процессу корректно завершиться
                        return
                    }
                }
                
                // Если мягкое завершение не сработало, используем принудительное
                PluginLogger.info(LogCategory.SCRCPY, "Soft kill failed for PID %s, using force kill", pid)
                val forceKillCommand = listOf("taskkill", "/F", "/PID", pid)
                val forceProcess = ProcessBuilder(forceKillCommand).start()
                
                if (forceProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val exitCode = forceProcess.exitValue()
                    if (exitCode == 0) {
                        PluginLogger.info(LogCategory.SCRCPY, "Successfully killed process with PID: %s (force kill)", pid)
                    } else {
                        PluginLogger.warn(LogCategory.SCRCPY, "Failed to kill process with PID %s, exit code: %s", pid, exitCode.toString())
                    }
                }
            } else {
                // Для Unix сначала пробуем SIGTERM
                val softKillCommand = listOf("kill", "-15", pid)
                val softProcess = ProcessBuilder(softKillCommand).start()
                
                if (softProcess.waitFor(3, TimeUnit.SECONDS)) {
                    val exitCode = softProcess.exitValue()
                    if (exitCode == 0) {
                        PluginLogger.info(LogCategory.SCRCPY, "Successfully terminated process with PID: %s (SIGTERM)", pid)
                        Thread.sleep(1000)
                        return
                    }
                }
                
                // Если не сработало, используем SIGKILL
                PluginLogger.info(LogCategory.SCRCPY, "SIGTERM failed for PID %s, using SIGKILL", pid)
                val forceKillCommand = listOf("kill", "-9", pid)
                val forceProcess = ProcessBuilder(forceKillCommand).start()
                
                if (forceProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val exitCode = forceProcess.exitValue()
                    if (exitCode == 0) {
                        PluginLogger.info(LogCategory.SCRCPY, "Successfully killed process with PID: %s (SIGKILL)", pid)
                    } else {
                        PluginLogger.warn(LogCategory.SCRCPY, "Failed to kill process with PID %s, exit code: %s", pid, exitCode.toString())
                    }
                }
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.SCRCPY, "Error killing process %s", e, pid)
        }
    }
    
    /**
     * Проверяет и стабилизирует ADB соединение
     */
    private fun ensureAdbStable() {
        try {
            // Проверяем состояние ADB через наш сервис
            val devicesResult = AdbService.getConnectedDevices()
            if (devicesResult is Result.Error) {
                PluginLogger.warn(LogCategory.SCRCPY, "ADB connection unstable, waiting for stabilization")
                Thread.sleep(2000)
            }
        } catch (e: Exception) {
            PluginLogger.warn(LogCategory.SCRCPY, "Error checking ADB stability: %s", e.message ?: "Unknown")
        }
    }

    fun launchScrcpy(scrcpyPath: String, serialNumber: String, project: Project): Boolean {
        println("ADB_Randomizer: launchScrcpy called for device: $serialNumber")
        println("ADB_Randomizer: Stack trace:")
        Thread.currentThread().stackTrace.take(10).forEach { 
            println("  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
        }
        
        try {
            // Проверяем, не было ли устройство намеренно остановлено
            if (wasIntentionallyStopped(serialNumber)) {
                println("ADB_Randomizer: Device $serialNumber was intentionally stopped, not launching scrcpy")
                PluginLogger.info(LogCategory.SCRCPY, "Device %s was intentionally stopped, not launching scrcpy", serialNumber)
                return false
            }
            
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
            
            // Стабилизируем ADB перед запуском scrcpy
            ensureAdbStable()

            // Получаем пользовательские флаги
            val customFlags = PluginSettings.instance.scrcpyCustomFlags
            val success = launchScrcpyWithFlags(scrcpyPath, serialNumber, adbPath, customFlags, project)

            if (!success) {
                // Проверяем, была ли остановка намеренной (например при отключении Wi-Fi)
                if (wasIntentionallyStopped(serialNumber)) {
                    PluginLogger.info(LogCategory.SCRCPY, "Scrcpy was intentionally stopped for device %s, not showing error dialog", serialNumber)
                    // Убираем флаг
                    intentionallyStopped.remove(serialNumber)
                    // Не показываем диалог об ошибке
                    return false
                }
                
                // Проверяем причину неудачи
                if (lastLaunchResult == LaunchResult.UNAUTHORIZED) {
                    PluginLogger.error(LogCategory.SCRCPY, "Device %s is unauthorized", null, serialNumber)
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showError(
                            project,
                            "Device $serialNumber is not authorized. Please check USB debugging authorization."
                        )
                    }
                } else {
                    // Показываем диалог совместимости только если проблема не в авторизации
                    println("ADB_Randomizer: Showing scrcpy compatibility dialog for device: $serialNumber")
                    PluginLogger.error(LogCategory.SCRCPY, "Showing compatibility dialog for device: %s, lastLaunchResult: %s", null, serialNumber, lastLaunchResult.toString())
                    var retry = false
                    val version = checkScrcpyVersion(scrcpyPath)
                    
                    // Определяем тип проблемы на основе lastLaunchResult
                    val problemType = when (lastLaunchResult) {
                        LaunchResult.ANDROID_15_INCOMPATIBLE -> ScrcpyCompatibilityDialog.ProblemType.ANDROID_15_INCOMPATIBLE
                        LaunchResult.INVALID_FLAGS -> ScrcpyCompatibilityDialog.ProblemType.INCOMPATIBLE
                        LaunchResult.UNAUTHORIZED -> ScrcpyCompatibilityDialog.ProblemType.NOT_WORKING
                        else -> ScrcpyCompatibilityDialog.ProblemType.NOT_WORKING
                    }
                    
                    ApplicationManager.getApplication().invokeAndWait {
                        val dialog = ScrcpyCompatibilityDialog(
                            project,
                            version.ifBlank { "Unknown" },
                            serialNumber,
                            problemType
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

    private fun launchScrcpyWithFlags(scrcpyPath: String, serialNumber: String, adbPath: String, customFlags: String, project: Project): Boolean {
        // Сбрасываем результат перед началом попытки
        lastLaunchResult = LaunchResult.SUCCESS
        lastInvalidFlagError = null
        
        PluginLogger.info(LogCategory.SCRCPY, "Launching scrcpy with custom flags: %s", customFlags)
        
        // Создаём команду с базовыми параметрами
        val command = mutableListOf(scrcpyPath, "-s", serialNumber)
        
        // Добавляем пользовательские флаги, если они есть
        var allFlags = listOf<String>()
        if (customFlags.isNotBlank()) {
            // Разбиваем флаги по пробелам, учитывая возможные кавычки
            val parsedFlags = parseCommandLineFlags(customFlags)
            
            // Фильтруем явно невалидные флаги
            allFlags = filterObviouslyInvalidFlags(parsedFlags)
            
            if (allFlags.size < parsedFlags.size) {
                val removedFlags = parsedFlags.filterNot { allFlags.contains(it) }
                PluginLogger.warn(LogCategory.SCRCPY, "Removed obviously invalid flags: %s", removedFlags.joinToString(" "))
            }
            
            command.addAll(allFlags)
        }
        
        val success = launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
        
        // Если не удалось из-за невалидных флагов, пробуем убрать проблемный флаг
        if (!success && lastLaunchResult == LaunchResult.INVALID_FLAGS && lastInvalidFlagError != null) {
            PluginLogger.warn(LogCategory.SCRCPY, "Retrying without problematic flag based on error: %s", lastInvalidFlagError)
            
            // Парсим ошибку и находим проблемный флаг/аргумент
            val problematicFlag = extractProblematicFlag(lastInvalidFlagError!!)
            
            if (problematicFlag != null && allFlags.isNotEmpty()) {
                // Убираем проблемный флаг и его возможное значение
                val cleanedFlags = removeProblematicFlag(allFlags, problematicFlag)
                
                if (cleanedFlags.size < allFlags.size) {
                    // Показываем уведомление пользователю о том, какой флаг был убран
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showWarning(
                            project,
                            "Invalid scrcpy flag detected: '$problematicFlag'. Launching without it."
                        )
                    }
                    
                    // Пробуем снова с очищенными флагами
                    val retryCommand = mutableListOf(scrcpyPath, "-s", serialNumber)
                    retryCommand.addAll(cleanedFlags)
                    
                    // Сбрасываем статус перед повторной попыткой
                    lastLaunchResult = LaunchResult.SUCCESS
                    lastInvalidFlagError = null
                    
                    PluginLogger.info(LogCategory.SCRCPY, "Retrying with cleaned flags: %s", cleanedFlags.joinToString(" "))
                    
                    val retrySuccess = launchScrcpyProcess(retryCommand, adbPath, scrcpyPath, serialNumber)
                    
                    // Если повторная попытка успешна, уведомляем пользователя
                    if (retrySuccess) {
                        PluginLogger.info(LogCategory.SCRCPY, "Successfully launched scrcpy after removing invalid flag: %s", problematicFlag)
                    }
                    
                    return retrySuccess
                }
            }
            
            // Если не смогли определить проблемный флаг, показываем общую ошибку
            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showError(
                    project,
                    "scrcpy failed: $lastInvalidFlagError. Please check your flags in settings."
                )
            }
        }
        
        return success
    }
    
    private fun parseCommandLineFlags(flagsString: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var escapeNext = false
        
        for (char in flagsString) {
            when {
                escapeNext -> {
                    current.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    escapeNext = true
                }
                char == '"' || char == '\'' -> {
                    inQuotes = !inQuotes
                }
                char == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        
        return result
    }
    
    /**
     * Фильтрует явно невалидные флаги на основе базовых правил
     * Использует ту же логику группировки, что и UI
     */
    private fun filterObviouslyInvalidFlags(flags: List<String>): List<String> {
        val validFlags = mutableListOf<String>()
        val invalidGroups = mutableListOf<String>()
        
        // Разрешенные символы: латинские буквы, цифры, дефис, подчёркивание, точка, слэш, двоеточие, знак равно, x
        val validPattern = Regex("^[a-zA-Z0-9\\-_./:=x]+$")
        
        var currentFlagGroup = mutableListOf<String>()
        var groupHasInvalidToken = false
        
        for (token in flags) {
            if (token.isBlank()) continue
            
            // Если встретили новый флаг (начинается с -)
            if (token.startsWith("-")) {
                // Сохраняем предыдущую группу, если она была валидной
                if (currentFlagGroup.isNotEmpty()) {
                    if (!groupHasInvalidToken) {
                        validFlags.addAll(currentFlagGroup)
                    } else {
                        invalidGroups.add(currentFlagGroup.joinToString(" "))
                        PluginLogger.warn(LogCategory.SCRCPY, "Filtering out invalid flag group: %s", 
                            currentFlagGroup.joinToString(" "))
                    }
                }
                
                // Начинаем новую группу
                currentFlagGroup = mutableListOf(token)
                groupHasInvalidToken = false
                
                // Проверяем валидность самого флага
                if (!token.matches(validPattern)) {
                    groupHasInvalidToken = true
                } else {
                    // Флаг должен иметь хотя бы один символ после дефиса(ов)
                    val flagContent = token.removePrefix("--").removePrefix("-")
                    if (flagContent.isEmpty() || flagContent == "-") {
                        groupHasInvalidToken = true
                    } else if (flagContent.matches(Regex("^[0-9]+$"))) {
                        // Флаг не может состоять только из цифр
                        groupHasInvalidToken = true
                    }
                }
            }
            // Это значение для текущего флага
            else {
                // Если нет текущего флага, это отдельный невалидный токен
                if (currentFlagGroup.isEmpty()) {
                    invalidGroups.add(token)
                    PluginLogger.warn(LogCategory.SCRCPY, "Filtering out standalone argument: %s", token)
                } else {
                    // Добавляем к текущей группе
                    currentFlagGroup.add(token)
                    // Проверяем валидность токена
                    if (!token.matches(validPattern)) {
                        groupHasInvalidToken = true
                    }
                }
            }
        }
        
        // Обрабатываем последнюю группу
        if (currentFlagGroup.isNotEmpty()) {
            if (!groupHasInvalidToken) {
                validFlags.addAll(currentFlagGroup)
            } else {
                invalidGroups.add(currentFlagGroup.joinToString(" "))
                PluginLogger.warn(LogCategory.SCRCPY, "Filtering out invalid flag group: %s", 
                    currentFlagGroup.joinToString(" "))
            }
        }
        
        return validFlags
    }
    
    /**
     * Извлекает проблемный флаг или аргумент из сообщения об ошибке
     */
    private fun extractProblematicFlag(errorMessage: String): String? {
        // Паттерны для разных типов ошибок scrcpy
        val patterns = listOf(
            // scrcpy.exe: unknown option -- 123412
            Regex("unknown option -- ([\\w-]+)"),
            // ERROR: Unexpected additional argument: test
            Regex("Unexpected additional argument:\\s*(.+)"),
            // ERROR: Unknown option: --invalid-flag
            Regex("Unknown option:\\s*(.+)"),
            // ERROR: Unrecognized option: --bad-flag
            Regex("Unrecognized option:\\s*(.+)"),
            // ERROR: Could not parse: value
            Regex("Could not parse:\\s*(.+)"),
            // ERROR: Invalid value: something
            Regex("Invalid value:\\s*(.+)"),
            // Fallback для общего формата
            Regex("unknown option[:\\s-]+(.+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(errorMessage)
            if (match != null) {
                var flag = match.groupValues[1].trim()
                // Если извлекли просто значение без дефисов, добавляем их для консистентности
                if (!flag.startsWith("-") && flag.matches(Regex("^[\\w-]+$"))) {
                    flag = "--$flag"
                }
                return flag
            }
        }
        
        return null
    }
    
    /**
     * Удаляет проблемный флаг из списка флагов
     * Использует группировку флагов - удаляет всю группу, если она содержит проблемный элемент
     */
    private fun removeProblematicFlag(flags: List<String>, problematicFlag: String): List<String> {
        val result = mutableListOf<String>()
        var currentFlagGroup = mutableListOf<String>()
        var groupContainsProblematic = false
        
        for (token in flags) {
            // Если встретили новый флаг (начинается с -)
            if (token.startsWith("-")) {
                // Сохраняем предыдущую группу, если она не содержит проблемный элемент
                if (currentFlagGroup.isNotEmpty() && !groupContainsProblematic) {
                    result.addAll(currentFlagGroup)
                }
                
                // Начинаем новую группу
                currentFlagGroup = mutableListOf(token)
                groupContainsProblematic = (token == problematicFlag || token.startsWith("$problematicFlag="))
            }
            // Это значение для текущего флага
            else {
                if (currentFlagGroup.isNotEmpty()) {
                    currentFlagGroup.add(token)
                    // Проверяем, является ли этот токен проблемным
                    if (token == problematicFlag) {
                        groupContainsProblematic = true
                    }
                } else {
                    // Отдельный аргумент без флага - добавляем только если не проблемный
                    if (token != problematicFlag) {
                        result.add(token)
                    }
                }
            }
        }
        
        // Обрабатываем последнюю группу
        if (currentFlagGroup.isNotEmpty() && !groupContainsProblematic) {
            result.addAll(currentFlagGroup)
        }
        
        return result
    }

    private fun launchScrcpyProcess(command: List<String>, adbPath: String, scrcpyPath: String, serialNumber: String): Boolean {
        try {
            PluginLogger.info(LogCategory.SCRCPY, "Command: %s", command.joinToString(" "))
            val processBuilder = ProcessBuilder(command)
            processBuilder.environment()["ADB"] = adbPath
            processBuilder.directory(File(scrcpyPath).parentFile)
            val process = processBuilder.start()

            var unauthorizedDetected = false
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
                            // Проверяем на ошибку unauthorized
                            if (line.contains("unauthorized", ignoreCase = true) || 
                                line.contains("Device is unauthorized", ignoreCase = true)) {
                                unauthorizedDetected = true
                            }
                            // Проверяем на проблему с Android 15
                            if (line.contains("NoSuchMethodException") && 
                                line.contains("SurfaceControl.createDisplay")) {
                                lastLaunchResult = LaunchResult.ANDROID_15_INCOMPATIBLE
                                PluginLogger.info(LogCategory.SCRCPY, "Detected Android 15 compatibility issue")
                            }
                            // Проверяем на ошибки с флагами и сохраняем текст ошибки
                            if (line.contains("Unexpected additional argument", ignoreCase = true) ||
                                line.contains("Unknown option", ignoreCase = true) ||
                                line.contains("Invalid", ignoreCase = true) ||
                                line.contains("Could not parse", ignoreCase = true) ||
                                line.contains("Unrecognized option", ignoreCase = true)) {
                                lastLaunchResult = LaunchResult.INVALID_FLAGS
                                lastInvalidFlagError = line
                            }
                        }
                    }
                } catch (_: Exception) { /* Игнорируем ошибки чтения */ }
            }

            outputReader.start()
            errorReader.start()
            
            // Сразу добавляем процесс во временное хранилище
            try {
                val pid = process.pid()
                recentlyStartedProcesses[pid] = serialNumber
                PluginLogger.debug(LogCategory.SCRCPY, "Added process PID=%s to recently started for device %s", pid, serialNumber)
                
                // Удаляем из временного хранилища через 10 секунд
                Thread {
                    Thread.sleep(10000)
                    recentlyStartedProcesses.remove(pid)
                    PluginLogger.debug(LogCategory.SCRCPY, "Removed process PID=%s from recently started", pid)
                }.start()
            } catch (e: Exception) {
                PluginLogger.debug(LogCategory.SCRCPY, "Could not get PID for recently started process: %s", e.message ?: "Unknown")
            }

            Thread.sleep(PluginConfig.Scrcpy.STARTUP_WAIT_MS)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                PluginLogger.info(LogCategory.SCRCPY, "Scrcpy process finished early. Exit code: %s", exitCode)
                // Если обнаружен unauthorized, сохраняем флаг для последующей обработки
                if (unauthorizedDetected) {
                    lastLaunchResult = LaunchResult.UNAUTHORIZED
                }
                // Если уже установлен INVALID_FLAGS или ANDROID_15_INCOMPATIBLE, не перезаписываем
                if (lastLaunchResult != LaunchResult.INVALID_FLAGS && 
                    lastLaunchResult != LaunchResult.ANDROID_15_INCOMPATIBLE && 
                    exitCode != 0) {
                    // Проверяем, возможно это проблема с флагами
                    lastLaunchResult = if (exitCode == 1 || exitCode == 2) {
                        LaunchResult.INVALID_FLAGS
                    } else {
                        // Устанавливаем общую ошибку запуска
                        LaunchResult.FAILED
                    }
                }
                return exitCode == 0
            }

            Thread.sleep(PluginConfig.Scrcpy.PROCESS_CHECK_DELAY_MS)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                PluginLogger.info(LogCategory.SCRCPY, "Scrcpy process closed after startup. Exit code: %s", exitCode)
                if (unauthorizedDetected) {
                    lastLaunchResult = LaunchResult.UNAUTHORIZED
                }
                // Если уже установлен INVALID_FLAGS или ANDROID_15_INCOMPATIBLE, не перезаписываем
                if (lastLaunchResult != LaunchResult.INVALID_FLAGS && 
                    lastLaunchResult != LaunchResult.ANDROID_15_INCOMPATIBLE && 
                    exitCode != 0) {
                    lastLaunchResult = if (exitCode == 1 || exitCode == 2) {
                        LaunchResult.INVALID_FLAGS
                    } else {
                        // Устанавливаем общую ошибку запуска
                        LaunchResult.FAILED
                    }
                }
                return exitCode == 0
            }

            PluginLogger.info(LogCategory.SCRCPY, "Scrcpy started successfully for device: %s", serialNumber)
            println("ADB_Randomizer: Scrcpy started successfully for device: $serialNumber")
            
            // Сбрасываем флаг при успешном запуске
            lastLaunchResult = LaunchResult.SUCCESS
            
            // Сохраняем процесс для возможности управления им
            activeScrcpyProcesses[serialNumber] = process
            println("ADB_Randomizer: Saved scrcpy process with key: $serialNumber")
            println("ADB_Randomizer: Active scrcpy processes: ${activeScrcpyProcesses.keys.joinToString(", ")}")
            PluginLogger.info(LogCategory.SCRCPY, "Saved scrcpy process for device %s, total active processes: %s", 
                serialNumber, activeScrcpyProcesses.size.toString())

            Thread {
                try {
                    val exitCode = process.waitFor()
                    println("ADB_Randomizer: Scrcpy process for device $serialNumber exited with code: $exitCode")
                    PluginLogger.info(LogCategory.SCRCPY, "Scrcpy for device %s closed with exit code: %s", serialNumber, exitCode)
                    
                    // Удаляем процесс из активных после завершения
                    activeScrcpyProcesses.remove(serialNumber)
                    println("ADB_Randomizer: Removed scrcpy process for device $serialNumber from active map")
                    println("ADB_Randomizer: Remaining active scrcpy processes: ${activeScrcpyProcesses.keys.joinToString(", ")}")
                    
                    // Проверяем, была ли остановка намеренной
                    if (wasIntentionallyStopped(serialNumber)) {
                        println("ADB_Randomizer: Scrcpy was intentionally stopped for device $serialNumber, not triggering error handling")
                        intentionallyStopped.remove(serialNumber)
                    } else if (exitCode != 0) {
                        println("ADB_Randomizer: Scrcpy exited with error for device $serialNumber, exit code: $exitCode")
                    }
                    
                    outputReader.join(1000)
                    errorReader.join(1000)
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
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                // Извлекаем версию из первой строки вывода
                // Формат: "scrcpy 2.3.1 <https://github.com/Genymobile/scrcpy>"
                val firstLine = output.lines().firstOrNull() ?: return ""
                val versionMatch = Regex("scrcpy\\s+([\\d.]+)").find(firstLine)
                versionMatch?.groupValues?.get(1) ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            PluginLogger.info(LogCategory.SCRCPY, "Could not get scrcpy version: %s", e.message)
            ""
        }
    }
}