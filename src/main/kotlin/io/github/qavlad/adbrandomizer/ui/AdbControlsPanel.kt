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
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class AdbControlsPanel(private val project: Project) : JPanel() {

    private var lastUsedPreset: DevicePreset? = null

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
        add(createCenteredButton("Reset size and DPI to default") {
            handleResetAction()
        })
        add(createCenteredButton("SETTING") {
            SettingsDialog(project).show()
        })
    }

    private fun handleResetAction() {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }
        object : Task.Backgroundable(project, "Resetting screen and DPI") {
            override fun run(indicator: ProgressIndicator) {
                devices.forEach { device ->
                    indicator.text = "Resetting ${device.name}..."
                    AdbService.resetScreen(device)
                }
                showSuccessNotification("Screen and DPI have been reset for ${devices.size} device(s).")
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

        object : Task.Backgroundable(project, "Applying random settings") {
            override fun run(indicator: ProgressIndicator) {
                val randomPreset = availablePresets.random()
                lastUsedPreset = randomPreset
                val appliedSettings = mutableListOf<String>()

                var width: Int? = null
                var height: Int? = null
                if (setSize) {
                    val parts = randomPreset.size.split('x', 'X', 'х', 'Х').map { it.trim() }
                    width = parts.getOrNull(0)?.toIntOrNull()
                    height = parts.getOrNull(1)?.toIntOrNull()
                    if (width == null || height == null) {
                        showErrorNotification("Invalid size format in preset '${randomPreset.label}': ${randomPreset.size}")
                        return
                    }
                    appliedSettings.add("Size: ${randomPreset.size}")
                }

                var dpi: Int? = null
                if (setDpi) {
                    dpi = randomPreset.dpi.trim().toIntOrNull()
                    if (dpi == null) {
                        showErrorNotification("Invalid DPI format in preset '${randomPreset.label}': ${randomPreset.dpi}")
                        return
                    }
                    appliedSettings.add("DPI: ${randomPreset.dpi}")
                }

                devices.forEach { device ->
                    indicator.text = "Applying '${randomPreset.label}' to ${device.name}..."
                    width?.let { w -> height?.let { h -> AdbService.setSize(device, w, h) } }
                    dpi?.let { d -> AdbService.setDpi(device, d) }
                }
                showSuccessNotification("${appliedSettings.joinToString(", ")} from '${randomPreset.label}' set for ${devices.size} device(s).")
            }
        }.queue()
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