// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/utils/AdbPathResolver.kt
package io.github.qavlad.adbrandomizer.utils

import java.io.File

object AdbPathResolver {

    private val adbName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"

    /**
     * Находит путь к ADB executable в системе
     * @return String? - путь к ADB или null, если не найден
     */
    fun findAdbExecutable(): String? {
        println("ADB_Randomizer: Starting ADB search...")
        println("ADB_Randomizer: Looking for: $adbName")
        
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        
        // Проверяем стандартные пути для разных ОС
        val standardPaths = getStandardAdbPaths()
        println("ADB_Randomizer: Checking standard paths: ${standardPaths.joinToString()}")

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
            
            println("ADB_Randomizer: Checking standard path: $path - exists: $exists, canExecute: $canExecute")
            if (exists && canExecute) {
                println("ADB_Randomizer: Found ADB at: $path")
                return path
            }
        }

        // Ищем в PATH
        println("ADB_Randomizer: Searching in system PATH...")
        val pathFromSystem = findExecutableInSystemPath(adbName)
        if (pathFromSystem != null) {
            println("ADB_Randomizer: Found ADB in PATH at: $pathFromSystem")
            return pathFromSystem
        }

        println("ADB_Randomizer: ADB not found in standard locations or PATH")
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
        println("ADB_Randomizer: PATH environment variable: ${pathEnv?.take(200)}...") // Показываем первые 200 символов PATH
        
        val pathDirs = pathEnv?.split(File.pathSeparator) ?: emptyList()
        println("ADB_Randomizer: Found ${pathDirs.size} directories in PATH")
        
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
            
            // Логируем только для директорий где есть adb или интересных путей
            if (exists || dir.contains("adb", ignoreCase = true) || dir.contains("android", ignoreCase = true)) {
                println("ADB_Randomizer: Checking PATH dir: $dir")
                println("ADB_Randomizer:   File: ${file.absolutePath}")
                println("ADB_Randomizer:   Exists: $exists, CanExecute: $canExecute")
                if (exists && isWindows) {
                    println("ADB_Randomizer:   (Windows detected - treating .exe as executable)")
                }
            }
            
            if (exists && canExecute) {
                println("ADB_Randomizer: SUCCESS! Found executable in PATH")
                return file.absolutePath
            }
        }
        
        println("ADB_Randomizer: Executable '$executableName' not found in any PATH directory")
        return null
    }
}