// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import org.jetbrains.android.sdk.AndroidSdkUtils

object ScrcpyService {

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) "scrcpy.exe" else "scrcpy"

    /**
     * Ищет исполняемый файл scrcpy.
     * Сначала в сохраненных настройках, потом в системном PATH.
     * @return Путь к файлу или null, если не найден.
     */
    fun findScrcpyExecutable(): String? {
        // 1. Проверяем сохраненный путь
        val savedPath = SettingsService.getScrcpyPath()
        if (savedPath != null && File(savedPath).canExecute()) {
            return savedPath
        }

        // 2. Ищем в системном PATH
        val pathDirs = System.getenv("PATH").split(File.pathSeparator)
        for (dir in pathDirs) {
            val file = File(dir, scrcpyName)
            if (file.exists() && file.canExecute()) {
                SettingsService.saveScrcpyPath(file.absolutePath) // Нашли - сохраняем
                return file.absolutePath
            }
        }
        return null
    }

    fun launchScrcpy(scrcpyPath: String, serialNumber: String, project: Project): Boolean {
        return try {
            // Валидация входных параметров
            if (scrcpyPath.isBlank()) {
                println("ADB_Randomizer: Empty scrcpy path provided")
                return false
            }

            if (serialNumber.isBlank()) {
                println("ADB_Randomizer: Empty serial number provided")
                return false
            }

            val scrcpyFile = File(scrcpyPath)
            if (!scrcpyFile.exists()) {
                println("ADB_Randomizer: Scrcpy executable not found at: $scrcpyPath")
                return false
            }

            if (!scrcpyFile.canExecute()) {
                println("ADB_Randomizer: Scrcpy file is not executable: $scrcpyPath")
                return false
            }

            println("ADB_Randomizer: Starting scrcpy for device: $serialNumber")
            
            @Suppress("DEPRECATION")
            val adbPath = AndroidSdkUtils.getAdb(project)?.absolutePath
            if (adbPath == null) {
                println("ADB_Randomizer: Cannot get ADB path from IDE")
                return false
            }

            println("ADB_Randomizer: Using ADB: $adbPath")

            // Команда без рестарта ADB
            val processBuilder = ProcessBuilder(
                scrcpyPath,
                "-s", serialNumber
            )

            // КРИТИЧЕСКИ ВАЖНО: Устанавливаем переменную среды ADB для scrcpy
            processBuilder.environment()["ADB"] = adbPath

            // Настраиваем рабочую директорию
            processBuilder.directory(scrcpyFile.parentFile)

            // Перенаправляем потоки для лучшего логирования
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // Проверяем, что процесс успешно запустился
            Thread.sleep(1000) // Даем время процессу запуститься

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                println("ADB_Randomizer: Scrcpy failed to start. Exit code: $exitCode")
                println("ADB_Randomizer: Scrcpy output: $output")
                return false
            }

            println("ADB_Randomizer: Scrcpy started successfully for device: $serialNumber")

            // Запускаем мониторинг процесса в отдельном потоке
            Thread {
                monitorScrcpyProcess(process, serialNumber)
            }.start()

            true

        } catch (e: SecurityException) {
            println("ADB_Randomizer: Security error starting scrcpy: ${e.message}")
            false
        } catch (e: java.io.IOException) {
            println("ADB_Randomizer: IO error starting scrcpy: ${e.message}")
            false
        } catch (e: Exception) {
            println("ADB_Randomizer: Unexpected error starting scrcpy: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // Мониторинг процесса scrcpy для логирования
    private fun monitorScrcpyProcess(process: Process, @Suppress("UNUSED_PARAMETER") serialNumber: String) {
        try {
            val exitCode = process.waitFor()
            when (exitCode) {
                0 -> println("ADB_Randomizer: Scrcpy for device $serialNumber closed normally")
                1 -> println("ADB_Randomizer: Scrcpy for device $serialNumber closed with generic error")
                2 -> println("ADB_Randomizer: Scrcpy for device $serialNumber: Device disconnected")
                else -> println("ADB_Randomizer: Scrcpy for device $serialNumber closed with exit code: $exitCode")
            }
        } catch (_: InterruptedException) {
            println("ADB_Randomizer: Scrcpy monitoring interrupted for device: $serialNumber")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            println("ADB_Randomizer: Error monitoring scrcpy process: ${e.message}")
        }
    }

    /**
     * Показывает диалог выбора файла или папки, чтобы пользователь указал путь к scrcpy.
     * @return Путь к файлу или null, если пользователь отменил выбор или файл не найден.
     */
    fun promptForScrcpyPath(project: Project?): String? {
        var path: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
                .withTitle("Select Scrcpy Executable or Its Containing Folder")
                .withDescription("Please locate the '$scrcpyName' file or the folder it's in.")

            val files = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null).choose(project)

            if (files.isNotEmpty()) {
                val selectedFile = File(files[0].path)
                var scrcpyExe: File? = null

                if (selectedFile.isDirectory) {
                    val potentialExe = File(selectedFile, scrcpyName)
                    if (potentialExe.exists() && potentialExe.canExecute()) {
                        scrcpyExe = potentialExe
                    } else {
                        // Дополнительная проверка в поддиректориях (для Windows с scrcpy в папке)
                        val subdirs = selectedFile.listFiles { file -> file.isDirectory }
                        for (subdir in subdirs ?: emptyArray()) {
                            val subPotentialExe = File(subdir, scrcpyName)
                            if (subPotentialExe.exists() && subPotentialExe.canExecute()) {
                                scrcpyExe = subPotentialExe
                                break
                            }
                        }
                    }
                } else if (selectedFile.isFile && selectedFile.name.equals(scrcpyName, ignoreCase = true)) {
                    scrcpyExe = selectedFile
                }

                if (scrcpyExe != null) {
                    val finalPath = scrcpyExe.absolutePath
                    SettingsService.saveScrcpyPath(finalPath)
                    path = finalPath
                    println("ADB_Randomizer: Scrcpy path saved: $finalPath")
                } else {
                    val errorMessage = "Could not find the executable file '$scrcpyName' in the selected location. " +
                            "Please select the correct file or folder containing scrcpy."
                    Messages.showErrorDialog(
                        project,
                        errorMessage,
                        "Executable Not Found"
                    )
                    println("ADB_Randomizer: User selected invalid scrcpy path: ${selectedFile.absolutePath}")
                }
            } else {
                println("ADB_Randomizer: User cancelled scrcpy path selection")
            }
        }
        return path
    }
}