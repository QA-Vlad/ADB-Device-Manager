// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/ScrcpyService.kt

package io.github.qavlad.adbrandomizer.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.ui.dialogs.ScrcpyCompatibilityDialog
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import java.io.File

object ScrcpyService {

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) "scrcpy.exe" else "scrcpy"

    fun findScrcpyExecutable(): String? {
        val savedPath = SettingsService.getScrcpyPath()
        if (savedPath != null && File(savedPath).canExecute()) {
            return savedPath
        }

        val pathFromSystem = AdbPathResolver.findExecutableInSystemPath(scrcpyName)
        if (pathFromSystem != null) {
            SettingsService.saveScrcpyPath(pathFromSystem)
            return pathFromSystem
        }

        return null
    }

    fun launchScrcpy(scrcpyPath: String, serialNumber: String, @Suppress("UNUSED_PARAMETER") project: Project): Boolean {
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

            val adbPath = AdbPathResolver.findAdbExecutable()
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
                    val dialog = ScrcpyCompatibilityDialog(
                        project,
                        version.ifBlank { "Unknown" },
                        serialNumber
                    )
                    dialog.show()
                    if (dialog.exitCode == ScrcpyCompatibilityDialog.RETRY_EXIT_CODE) {
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
                } catch (_: InterruptedException) {
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
}