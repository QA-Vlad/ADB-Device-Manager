// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

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

    /**
     * Запускает scrcpy для указанного устройства.
     * @param scrcpyPath Полный путь к scrcpy.
     * @param serialNumber Устройство для зеркалирования.
     */
    fun launchScrcpy(scrcpyPath: String, serialNumber: String) {
        try {
            ProcessBuilder(scrcpyPath, "-s", serialNumber).start()
        } catch (e: Exception) {
            e.printStackTrace()
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
                    }
                } else if (selectedFile.isFile && selectedFile.name.equals(scrcpyName, ignoreCase = true)) {
                    scrcpyExe = selectedFile
                }

                if (scrcpyExe != null) {
                    val finalPath = scrcpyExe.absolutePath
                    SettingsService.saveScrcpyPath(finalPath)
                    path = finalPath
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Could not find the executable file '$scrcpyName' in the selected location. Please select the correct file or folder.",
                        "Executable Not Found"
                    )
                }
            }
        }
        return path
    }
}