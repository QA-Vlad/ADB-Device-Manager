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
        // Проверяем стандартные пути для разных ОС
        val standardPaths = getStandardAdbPaths()

        for (path in standardPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                println("ADB_Randomizer: Found ADB at: $path")
                return path
            }
        }

        // Ищем в PATH
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
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val file = File(dir, executableName)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }
        return null
    }
}