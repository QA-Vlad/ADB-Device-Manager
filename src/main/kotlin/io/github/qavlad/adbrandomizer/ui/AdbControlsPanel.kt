// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/AdbControlsPanel.kt

package io.github.qavlad.adbrandomizer.ui

// Добавь этот импорт для работы с потоками
import com.intellij.openapi.application.ApplicationManager
// ... остальные импорты ...
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.services.AdbService
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class AdbControlsPanel(private val project: Project) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(createCenteredButton("RANDOM SIZE AND DPI"))
        add(createCenteredButton("RANDOM SIZE ONLY"))
        add(createCenteredButton("RANDOM DPI ONLY"))

        val resetButton = createCenteredButton("Reset size and DPI to default")

        resetButton.addActionListener {
            // --- НАЧАЛО ИЗМЕНЕНИЙ ---
            // Этот код будет выполнен в UI-потоке (EDT), когда пользователь нажмет кнопку

            // 1. Получаем список устройств ПЕРЕД запуском фоновой задачи.
            // Мы вызываем наш сервис из EDT, как того требует API.
            val devices = AdbService.getConnectedDevices(project)

            if (devices.isEmpty()) {
                showNotification("No connected devices found.")
                // Если устройств нет, просто выходим, не запуская фоновую задачу.
                return@addActionListener
            }

            // 2. Теперь, когда у нас есть список устройств, запускаем фоновую задачу
            // для выполнения самих ADB-команд.
            object : Task.Backgroundable(project, "Resetting Screen and DPI") {
                override fun run(indicator: ProgressIndicator) {
                    // Список устройств у нас уже есть, мы передали его из UI-потока.
                    // Просто итерируемся по нему.
                    devices.forEach { device ->
                        indicator.text = "Resetting ${device.name}..."
                        AdbService.resetScreen(device)
                    }

                    // Показываем уведомление в UI-потоке, когда все готово
                    ApplicationManager.getApplication().invokeLater {
                        showNotification("Screen and DPI have been reset for ${devices.size} device(s).")
                    }
                }
            }.queue()
            // --- КОНЕЦ ИЗМЕНЕНИЙ ---
        }

        add(resetButton)

        add(createCenteredButton("Reset size"))
        add(createCenteredButton("Reset DPI"))
        add(createCenteredButton("SETTING"))
    }

    private fun createCenteredButton(text: String): JButton {
        val button = JButton(text)
        button.alignmentX = Component.CENTER_ALIGNMENT
        return button
    }

    private fun showNotification(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ADB Randomizer Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}