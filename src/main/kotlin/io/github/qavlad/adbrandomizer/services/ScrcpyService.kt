// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.io.File

object ScrcpyService {

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) "scrcpy.exe" else "scrcpy"

    private fun findExecutableInSystemPath(name: String): String? {
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }
        return null
    }

    private fun findAdbExecutable(): String? {
        val adbName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"

        val standardPaths = when {
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

        for (path in standardPaths) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return path
            }
        }

        return findExecutableInSystemPath(adbName)
    }

    fun findScrcpyExecutable(): String? {
        val savedPath = SettingsService.getScrcpyPath()
        if (savedPath != null && File(savedPath).canExecute()) {
            return savedPath
        }

        val pathFromSystem = findExecutableInSystemPath(scrcpyName)
        if (pathFromSystem != null) {
            SettingsService.saveScrcpyPath(pathFromSystem)
            return pathFromSystem
        }

        return null
    }

    fun launchScrcpy(scrcpyPath: String, serialNumber: String, project: Project): Boolean {
        try {
            if (scrcpyPath.isBlank() || serialNumber.isBlank()) {
                println("ADB_Randomizer: Empty scrcpy path or serial number provided")
                return false
            }

            val scrcpyFile = File(scrcpyPath)
            if (!scrcpyFile.exists() || !scrcpyFile.canExecute()) {
                println("ADB_Randomizer: Scrcpy executable not found or not executable at: $scrcpyPath")
                return false
            }

            println("ADB_Randomizer: Starting scrcpy for device: $serialNumber")

            val adbPath = findAdbExecutable()
            if (adbPath == null) {
                println("ADB_Randomizer: Cannot find ADB executable")
                return false
            }

            println("ADB_Randomizer: Using ADB: $adbPath")

            val version = checkScrcpyVersion(scrcpyPath)
            val success = tryDifferentScrcpyMethods(scrcpyPath, serialNumber, adbPath)

            if (!success) {
                var retry = false
                ApplicationManager.getApplication().invokeAndWait {
                    val dialog = io.github.qavlad.adbrandomizer.ui.ScrcpyCompatibilityDialog(
                        project,
                        version.ifBlank { "Unknown" },
                        serialNumber
                    )
                    dialog.show()
                    if (dialog.exitCode == io.github.qavlad.adbrandomizer.ui.ScrcpyCompatibilityDialog.RETRY_EXIT_CODE) {
                        retry = true
                    }
                }

                if (retry) {
                    println("ADB_Randomizer: New scrcpy path selected. Retrying screen mirroring...")
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
            println("ADB_Randomizer: Unexpected error starting scrcpy: ${e.message}")
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
        println("ADB_Randomizer: Trying scrcpy with display-id 0...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio", "--display-id=0", "--video-codec=h264")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun tryScrcpyWithV4l2(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        println("ADB_Randomizer: Trying scrcpy with force software encoder...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio", "--video-codec=h264", "--video-encoder=OMX.google.h264.encoder")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun showScrcpyUpdateMessage() {
        println("ADB_Randomizer: =================================================")
        println("ADB_Randomizer: SCRCPY COMPATIBILITY ISSUE DETECTED")
        println("ADB_Randomizer: Your scrcpy version has known issues with Android 15")
        println("ADB_Randomizer: Showing compatibility dialog to user...")
        println("ADB_Randomizer: =================================================")
    }

    private fun tryScrcpyWithCompatibilityFlags(scrcpyPath: String, serialNumber: String, adbPath: String): Boolean {
        println("ADB_Randomizer: Trying scrcpy with compatibility flags...")
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
        println("ADB_Randomizer: Trying minimal scrcpy...")
        val command = listOf(scrcpyPath, "-s", serialNumber, "--no-audio")
        return launchScrcpyProcess(command, adbPath, scrcpyPath, serialNumber)
    }

    private fun launchScrcpyProcess(command: List<String>, adbPath: String, scrcpyPath: String, serialNumber: String): Boolean {
        try {
            println("ADB_Randomizer: Command: ${command.joinToString(" ")}")
            val processBuilder = ProcessBuilder(command)
            processBuilder.environment()["ADB"] = adbPath
            processBuilder.directory(File(scrcpyPath).parentFile)
            val process = processBuilder.start()

            val outputReader = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().take(20).forEach { line ->
                            println("ADB_Randomizer: scrcpy stdout: $line")
                        }
                    }
                } catch (_: Exception) { /* Игнорируем ошибки чтения */ }
            }

            val errorReader = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lineSequence().take(20).forEach { line ->
                            println("ADB_Randomizer: scrcpy stderr: $line")
                        }
                    }
                } catch (_: Exception) { /* Игнорируем ошибки чтения */ }
            }

            outputReader.start()
            errorReader.start()

            Thread.sleep(2000)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                println("ADB_Randomizer: Scrcpy process finished early. Exit code: $exitCode")
                return exitCode == 0
            }

            Thread.sleep(3000)

            if (!process.isAlive) {
                val exitCode = process.exitValue()
                println("ADB_Randomizer: Scrcpy process closed after startup. Exit code: $exitCode")
                return exitCode == 0
            }

            println("ADB_Randomizer: Scrcpy started successfully for device: $serialNumber")

            Thread {
                try {
                    val exitCode = process.waitFor()
                    println("ADB_Randomizer: Scrcpy for device $serialNumber closed with exit code: $exitCode")
                    outputReader.join(1000)
                    errorReader.join(1000)
                } catch (e: InterruptedException) {
                    println("ADB_Randomizer: Scrcpy monitoring interrupted for device: $serialNumber")
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    println("ADB_Randomizer: Error monitoring scrcpy process: ${e.message}")
                }
            }.start()

            return true

        } catch (e: Exception) {
            println("ADB_Randomizer: Error launching scrcpy process: ${e.message}")
            return false
        }
    }

    private fun checkScrcpyVersion(scrcpyPath: String): String {
        return try {
            val process = ProcessBuilder(scrcpyPath, "--version").start()
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            println("ADB_Randomizer: Could not get scrcpy version: ${e.message}")
            ""
        }
    }

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
                    println("ADB_Randomizer: Scrcpy path saved: $finalPath")
                } else {
                    println("ADB_Randomizer: User selected invalid scrcpy path: ${selectedFile.absolutePath}")
                }
            } else {
                println("ADB_Randomizer: User cancelled scrcpy path selection")
            }
        }
        return path
    }
}