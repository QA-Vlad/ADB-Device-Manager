// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/utils/AdbPathResolver.kt
package io.github.qavlad.adbrandomizer.utils

import io.github.qavlad.adbrandomizer.settings.PluginSettings
import java.io.File

object AdbPathResolver {

    private val adbName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"

    /**
     * Находит путь к ADB executable в системе
     * @return String? - путь к ADB или null, если не найден
     */
    fun findAdbExecutable(): String? {
        // Сначала проверяем пользовательский путь из настроек
        val customPath = PluginSettings.instance.adbPath
        
        if (customPath.isNotBlank()) {
            
            val customFile = File(customPath)
            
            // Если указана директория, добавляем имя исполняемого файла
            val adbFile = if (customFile.isDirectory || !customPath.endsWith(adbName, ignoreCase = true)) {
                File(customFile, adbName)
            } else {
                customFile
            }
            
            if (adbFile.exists() && adbFile.isFile) {
                val isWindows = System.getProperty("os.name").startsWith("Windows")
                val canExecute = if (isWindows && adbFile.name.endsWith(".exe", ignoreCase = true)) {
                    true
                } else {
                    adbFile.canExecute()
                }
                
                if (canExecute) {
                    return adbFile.absolutePath
                }
            }
        }
        
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        
        // Проверяем стандартные пути для разных ОС
        val standardPaths = getStandardAdbPaths()

        for (path in standardPaths) {
            val file = File(path)
            val exists = file.exists()
            val canExecute = if (exists) {
                // На Windows для .exe файлов проверяем только существование
                if (isWindows && path.endsWith(".exe", ignoreCase = true)) {
                    true
                } else {
                    file.canExecute()
                }
            } else {
                false
            }
            
            if (exists && canExecute) {
                return path
            }
        }

        // Ищем в PATH
        val pathFromSystem = findExecutableInSystemPath(adbName)
        if (pathFromSystem != null) {
            return pathFromSystem
        }

        return null
    }

    /**
     * Возвращает список стандартных путей для поиска ADB в зависимости от ОС
     */
    private fun getStandardAdbPaths(): List<String> {
        return when {
            System.getProperty("os.name").contains("Mac") -> listOf(
                System.getProperty("user.home") + "/Library/Android/sdk/platform-tools/adb",
                "/usr/local/bin/adb",
                "/opt/homebrew/bin/adb"
            )
            System.getProperty("os.name").startsWith("Windows") -> listOf(
                System.getenv("LOCALAPPDATA") + "\\Android\\Sdk\\platform-tools\\adb.exe",
                System.getenv("USERPROFILE") + "\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
            )
            else -> listOf( // Linux
                System.getProperty("user.home") + "/Android/Sdk/platform-tools/adb",
                "/usr/bin/adb"
            )
        }
    }

    /**
     * Ищет исполняемый файл в системном PATH
     * @param executableName имя исполняемого файла для поиска
     * @return String? путь к файлу или null, если не найден
     */
    fun findExecutableInSystemPath(executableName: String): String? {
        val pathEnv = System.getenv("PATH")
        
        val pathDirs = pathEnv?.split(File.pathSeparator) ?: emptyList()
        
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        
        for (dir in pathDirs) {
            val file = File(dir, executableName)
            val exists = file.exists()
            
            val canExecute = if (exists) {
                // На Windows для .exe файлов проверяем только существование
                if (isWindows && executableName.endsWith(".exe", ignoreCase = true)) {
                    true
                } else {
                    file.canExecute()
                }
            } else {
                false
            }
            
            if (exists && canExecute) {
                return file.absolutePath
            }
        }
        
        return null
    }
}