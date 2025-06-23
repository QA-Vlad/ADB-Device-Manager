// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/AdbControlsPanel.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.services.AdbService
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.util.Locale.getDefault
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class AdbControlsPanel(private val project: Project) : JPanel() {

    private var lastUsedPreset: DevicePreset? = null
    private var currentPresetIndex: Int = -1

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(createCenteredButton("RANDOM SIZE AND DPI") {
            handleRandomAction(setSize = true, setDpi = true)
        })
        add(createCenteredButton("RANDOM SIZE ONLY") {
            handleRandomAction(setSize = true, setDpi = false)
        })
        add(createCenteredButton("RANDOM DPI ONLY") {
            handleRandomAction(setSize = false, setDpi = true)
        })
        add(createCenteredButton("NEXT PRESET") {
            handleNextPreset()
        })
        add(createCenteredButton("PREVIOUS PRESET") {
            handlePreviousPreset()
        })
        add(createCenteredButton("Reset size and DPI to default") {
            handleResetAction(resetSize = true, resetDpi = true)
        })
        add(createCenteredButton("RESET SIZE ONLY") {
            handleResetAction(resetSize = true, resetDpi = false)
        })
        add(createCenteredButton("RESET DPI ONLY") {
            handleResetAction(resetSize = false, resetDpi = true)
        })
        add(createCenteredButton("SETTING") {
            SettingsDialog(project).show()
        })
    }

    private fun handleNextPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            showNotification("No presets found in settings.")
            return
        }

        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        applyPresetByIndex(currentPresetIndex)
    }

    private fun handlePreviousPreset() {
        val presets = SettingsService.getPresets()
        if (presets.isEmpty()) {
            showNotification("No presets found in settings.")
            return
        }

        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        applyPresetByIndex(currentPresetIndex)
    }

    private fun applyPresetByIndex(index: Int) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }

        val presets = SettingsService.getPresets()
        if (index < 0 || index >= presets.size) {
            showNotification("Invalid preset index.")
            return
        }

        val preset = presets[index]
        applyPreset(preset, index + 1, setSize = true, setDpi = true)
    }

    private fun applyPreset(preset: DevicePreset, presetNumber: Int, setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)

        object : Task.Backgroundable(project, "Applying preset") {
            override fun run(indicator: ProgressIndicator) {
                lastUsedPreset = preset
                val appliedSettings = mutableListOf<String>()

                var width: Int? = null
                var height: Int? = null
                if (setSize && preset.size.isNotBlank()) {
                    val parts = preset.size.split('x', 'X', 'х', 'Х').map { it.trim() }
                    width = parts.getOrNull(0)?.toIntOrNull()
                    height = parts.getOrNull(1)?.toIntOrNull()
                    if (width == null || height == null) {
                        showErrorNotification("Invalid size format in preset '${preset.label}': ${preset.size}")
                        return
                    }
                    appliedSettings.add("Size: ${preset.size}")
                }

                var dpi: Int? = null
                if (setDpi && preset.dpi.isNotBlank()) {
                    dpi = preset.dpi.trim().toIntOrNull()
                    if (dpi == null) {
                        showErrorNotification("Invalid DPI format in preset '${preset.label}': ${preset.dpi}")
                        return
                    }
                    appliedSettings.add("DPI: ${preset.dpi}")
                }

                // Применяем только если есть что применять
                if (appliedSettings.isEmpty()) {
                    showNotification("No settings to apply for preset '${preset.label}'")
                    return
                }

                devices.forEach { device ->
                    indicator.text = "Applying '${preset.label}' to ${device.name}..."
                    if (setSize && width != null && height != null) {
                        AdbService.setSize(device, width, height)
                    }
                    if (setDpi && dpi != null) {
                        AdbService.setDpi(device, dpi)
                    }
                }

                val message = "<html>Preset №${presetNumber}: ${preset.label};<br>${appliedSettings.joinToString(", ")}</html>"
                showSuccessNotification(message)
            }
        }.queue()
    }

    private fun handleResetAction(resetSize: Boolean, resetDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }

        val actionDescription = when {
            resetSize && resetDpi -> "Resetting screen and DPI"
            resetSize -> "Resetting screen size"
            resetDpi -> "Resetting DPI"
            else -> "No action"
        }

        object : Task.Backgroundable(project, actionDescription) {
            override fun run(indicator: ProgressIndicator) {
                devices.forEach { device ->
                    indicator.text = "Resetting ${device.name}..."
                    if (resetSize) {
                        AdbService.resetSize(device)
                    }
                    if (resetDpi) {
                        AdbService.resetDpi(device)
                    }
                }

                val resetItems = mutableListOf<String>()
                if (resetSize) resetItems.add("screen size")
                if (resetDpi) resetItems.add("DPI")

                showSuccessNotification("${
                    resetItems.joinToString(" and ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
                } reset for ${devices.size} device(s).")
            }
        }.queue()
    }

    private fun handleRandomAction(setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }

        var availablePresets = SettingsService.getPresets().filter {
            (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank())
        }

        if (availablePresets.size > 1 && lastUsedPreset != null) {
            availablePresets = availablePresets.filter { it != lastUsedPreset }
        }

        if (availablePresets.isEmpty()) {
            val allSuitablePresets = SettingsService.getPresets().filter {
                (!setSize || it.size.isNotBlank()) && (!setDpi || it.dpi.isNotBlank())
            }
            if (allSuitablePresets.isNotEmpty()) {
                availablePresets = allSuitablePresets
            } else {
                showNotification("No suitable presets found in settings.")
                return
            }
        }

        val randomPreset = availablePresets.random()

        // Обновляем currentPresetIndex при случайном выборе
        val allPresets = SettingsService.getPresets()
        currentPresetIndex = allPresets.indexOf(randomPreset)
        val presetNumber = allPresets.indexOfFirst { it.label == randomPreset.label } + 1

        applyPreset(randomPreset, presetNumber, setSize, setDpi)
    }

    private fun createCenteredButton(text: String, action: () -> Unit): JButton {
        val button = JButton(text)
        button.alignmentX = CENTER_ALIGNMENT

        // Используем общую функцию
        ButtonUtils.addHoverEffect(button)

        button.addActionListener { action() }
        return button
    }

    private fun showNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showSuccessNotification(message: String) = showPopup(message, NotificationType.INFORMATION)
    private fun showErrorNotification(message: String) = showPopup(message, NotificationType.ERROR)

    private fun showPopup(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("ADB Randomizer Notifications")
                .createNotification(message, type).notify(project)
        }
    }
}