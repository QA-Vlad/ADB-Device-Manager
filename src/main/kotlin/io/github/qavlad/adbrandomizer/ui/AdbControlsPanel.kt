// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/AdbControlsPanel.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.services.AdbService
import io.github.qavlad.adbrandomizer.services.SettingsService
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class AdbControlsPanel(private val project: Project) : JPanel() {

    init {
        // Устанавливаем вертикальное расположение кнопок
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // --- Создаем и добавляем все кнопки ---

        // 1. Кнопка "RANDOM SIZE AND DPI"
        val randomAllButton = createCenteredButton("RANDOM SIZE AND DPI")
        randomAllButton.addActionListener {
            handleRandomAction(setSize = true, setDpi = true)
        }
        add(randomAllButton)

        // 2. Кнопка "RANDOM SIZE ONLY"
        val randomSizeButton = createCenteredButton("RANDOM SIZE ONLY")
        randomSizeButton.addActionListener {
            handleRandomAction(setSize = true, setDpi = false)
        }
        add(randomSizeButton)

        // 3. Кнопка "RANDOM DPI ONLY"
        val randomDpiButton = createCenteredButton("RANDOM DPI ONLY")
        randomDpiButton.addActionListener {
            handleRandomAction(setSize = false, setDpi = true)
        }
        add(randomDpiButton)

        // 4. Кнопка "Reset size and DPI to default"
        val resetButton = createCenteredButton("Reset size and DPI to default")
        resetButton.addActionListener {
            val devices = AdbService.getConnectedDevices(project)
            if (devices.isEmpty()) {
                showNotification("No connected devices found.")
                return@addActionListener
            }
            object : Task.Backgroundable(project, "Resetting Screen and DPI") {
                override fun run(indicator: ProgressIndicator) {
                    devices.forEach { device ->
                        indicator.text = "Resetting ${device.name}..."
                        AdbService.resetScreen(device)
                    }
                    showSuccessNotification("Screen and DPI have been reset for ${devices.size} device(s).")
                }
            }.queue()
        }
        add(resetButton)

        // 5. Кнопка "SETTING" (пока без реализации, просто как заглушка)
        val settingsButton = createCenteredButton("SETTING")
        settingsButton.addActionListener {
            // TODO: Реализовать открытие диалогового окна настроек
            showNotification("Settings dialog is not implemented yet.")
        }
        add(settingsButton)
    }

    /**
     * Общий метод для обработки всех "случайных" действий.
     * @param setSize - устанавливать ли случайный размер.
     * @param setDpi - устанавливать ли случайный DPI.
     */
    private fun handleRandomAction(setSize: Boolean, setDpi: Boolean) {
        val devices = AdbService.getConnectedDevices(project)
        if (devices.isEmpty()) {
            showNotification("No connected devices found.")
            return
        }

        // Получаем значения из настроек
        val resolutions = if (setSize) SettingsService.getResolutions() else emptyList()
        val dpis = if (setDpi) SettingsService.getDpis() else emptyList()

        // Проверяем, есть ли что применять
        if (setSize && resolutions.isEmpty()) {
            showNotification("No resolutions configured in settings.")
            return
        }
        if (setDpi && dpis.isEmpty()) {
            showNotification("No DPI values configured in settings.")
            return
        }

        // Запускаем фоновую задачу
        object : Task.Backgroundable(project, "Applying Random Settings") {
            override fun run(indicator: ProgressIndicator) {
                var width: Int? = null
                var height: Int? = null
                var dpi: Int? = null
                val appliedSettings = mutableListOf<String>()

                // Выбираем случайные значения
                if (setSize) {
                    val randomResolutionParts = resolutions.random().split('x')
                    width = randomResolutionParts.getOrNull(0)?.toIntOrNull()
                    height = randomResolutionParts.getOrNull(1)?.toIntOrNull()
                    if (width == null || height == null) {
                        showErrorNotification("Invalid resolution format found in settings.")
                        return
                    }
                    appliedSettings.add("Size: ${width}x$height")
                }

                if (setDpi) {
                    dpi = dpis.random()
                    appliedSettings.add("DPI: $dpi")
                }

                // Применяем значения ко всем устройствам
                devices.forEach { device ->
                    indicator.text = "Applying settings to ${device.name}..."
                    if (setSize && width != null && height != null) {
                        AdbService.setSize(device, width, height)
                    }
                    if (setDpi && dpi != null) {
                        AdbService.setDpi(device, dpi)
                    }
                }

                showSuccessNotification("${appliedSettings.joinToString(", ")} set for ${devices.size} device(s).")
            }
        }.queue()
    }

    /** Вспомогательная функция для создания кнопок. */
    private fun createCenteredButton(text: String): JButton {
        val button = JButton(text)
        button.alignmentX = Component.CENTER_ALIGNMENT
        return button
    }

    /** Показывает информационное уведомление. */
    private fun showNotification(message: String) {
        showPopup(message, NotificationType.INFORMATION)
    }

    /** Показывает уведомление об успехе. */
    private fun showSuccessNotification(message: String) {
        showPopup(message, NotificationType.INFORMATION) // Можно использовать INFORMATION, чтобы не перегружать пользователя
    }

    /** Показывает уведомление об ошибке. */
    private fun showErrorNotification(message: String) {
        showPopup(message, NotificationType.ERROR)
    }

    /** Общая функция для показа всплывающих уведомлений. */
    private fun showPopup(message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("ADB Randomizer Notifications")
                .createNotification(message, type)
                .notify(project)
        }
    }
}