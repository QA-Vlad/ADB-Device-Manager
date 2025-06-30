// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/ScrcpyCompatibilityDialog.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.*

class ScrcpyCompatibilityDialog(
    private val project: Project?,
    private val currentVersion: String,
    private val deviceName: String
) : DialogWrapper(project) {

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) "scrcpy.exe" else "scrcpy"

    companion object {
        // <<< ИЗМЕНЕНИЕ 2: Новый код выхода для сигнала о повторной попытке
        const val RETRY_EXIT_CODE = 101
    }

    init {
        title = "Scrcpy Compatibility Issue"
        setOKButtonText("Close")
        init()
    }

    // <<< ИЗМЕНЕНИЕ 1: Оставляем только одну кнопку "Close"
    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }

    // ... (createCenterPanel и другие методы остаются без изменений) ...
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(700, 500)

        // Заголовок с иконкой предупреждения
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val warningIcon = UIManager.getIcon("OptionPane.warningIcon")
        headerPanel.add(JLabel(warningIcon))

        val titleLabel = JBLabel("Screen Mirroring Failed").apply {
            font = font.deriveFont(Font.BOLD, 16f)
        }
        headerPanel.add(titleLabel)

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Основной контент
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Описание проблемы
        val problemText = JBTextArea().apply {
            text = """
                Unable to start screen mirroring for device: $deviceName
                
                Problem: Your current scrcpy version ($currentVersion) has compatibility issues with Android 15 devices.
                The error is caused by changes in Android's SurfaceControl API that require a newer version of scrcpy.
                
                This is a known issue that has been resolved in scrcpy version 2.4 and later.
            """.trimIndent()

            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            rows = 6
        }
        contentPanel.add(problemText)

        contentPanel.add(Box.createVerticalStrut(25))

        // Варианты решения
        val solutionsLabel = JBLabel("Solutions:").apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        contentPanel.add(solutionsLabel)

        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 1: Указать путь к новой версии
        val solution1Panel = createSolutionPanel(
            "1. Use a different scrcpy version",
            "If you have a newer version of scrcpy installed elsewhere, you can specify its path directly.",
            "Select scrcpy Path"
        ) {
            // НЕ закрываем диалог сразу
            promptForScrcpyPathWithRetry()
        }
        contentPanel.add(solution1Panel)

        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 2: Скачать с GitHub
        val solution2Panel = createSolutionPanel(
            "2. Download latest version from GitHub",
            "Download the latest scrcpy release that supports Android 15.",
            "Open GitHub Releases"
        ) {
            BrowserUtil.browse("https://github.com/Genymobile/scrcpy/releases")
        }
        contentPanel.add(solution2Panel)

        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 3: Обновить через терминал
        val updateCommand = getUpdateCommand()
        val solution3Panel = createSolutionPanel(
            "3. Update via terminal/command line",
            "Run the following command to update scrcpy:\n$updateCommand",
            "Copy Command"
        ) {
            // Копируем команду в буфер обмена
            copyToClipboard(updateCommand)
            // Показываем уведомление
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Command copied to clipboard!\nPaste it into your terminal and press Enter.",
                "Command Copied",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        contentPanel.add(solution3Panel)

        contentPanel.add(Box.createVerticalStrut(20))

        // Дополнительная информация
        val noteText = JBTextArea().apply {
            text = "Note: After updating scrcpy, you may need to restart the IDE for the changes to take effect."
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getFont(UIUtil.FontSize.SMALL, null)
            foreground = UIUtil.getInactiveTextColor()
            rows = 2
        }
        contentPanel.add(noteText)

        mainPanel.add(contentPanel, BorderLayout.CENTER)

        return mainPanel
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = java.awt.datatransfer.StringSelection(text)
        clipboard.setContents(selection, selection)
    }

    private fun createSolutionPanel(
        title: String,
        description: String,
        buttonText: String,
        buttonAction: () -> Unit
    ): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)
        panel.background = UIUtil.getPanelBackground()

        val textPanel = JPanel()
        textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
        textPanel.isOpaque = false

        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
        }
        textPanel.add(titleLabel)

        val descArea = JBTextArea().apply {
            text = description
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
        }
        textPanel.add(descArea)

        val button = JButton(buttonText).apply {
            addActionListener { buttonAction() }
        }
        ButtonUtils.addHoverEffect(button) // Предполагается, что ButtonUtils существует

        panel.add(textPanel, BorderLayout.CENTER)
        panel.add(button, BorderLayout.EAST)

        return panel
    }

    private fun getUpdateCommand(): String {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> "brew upgrade scrcpy"
            osName.contains("windows") -> {
                """
                # If you have Chocolatey:
                choco upgrade scrcpy
                
                # If you have Scoop:
                scoop update scrcpy
                
                # If you have Winget:
                winget upgrade scrcpy
                """.trimIndent()
            }
            osName.contains("linux") -> {
                """
                # For Ubuntu/Debian:
                sudo apt update && sudo apt upgrade scrcpy
                
                # For Arch Linux:
                sudo pacman -Syu scrcpy
                
                # For Fedora:
                sudo dnf upgrade scrcpy
                
                # Or use Snap:
                sudo snap refresh scrcpy
                """.trimIndent()
            }
            else -> "# Please check your package manager documentation for updating scrcpy"
        }
    }

    // <<< ИЗМЕНЕНИЕ 3: Логика выбора папки или файла
    private fun selectScrcpyFileOrFolder(): String? {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
            .withTitle("Select Scrcpy Executable or Its Containing Folder")
            .withDescription("Please locate the '$scrcpyName' file or the folder where it resides.")

        val chosenFile: VirtualFile? = FileChooser.chooseFile(descriptor, project, null)

        if (chosenFile == null) return null

        val selectedIoFile = File(chosenFile.path)

        // Проверяем, если пользователь выбрал сам исполняемый файл
        if (selectedIoFile.isFile && selectedIoFile.name.equals(scrcpyName, ignoreCase = true)) {
            return selectedIoFile.absolutePath
        }

        // Проверяем, если пользователь выбрал папку, содержащую исполняемый файл
        if (selectedIoFile.isDirectory) {
            val potentialExe = File(selectedIoFile, scrcpyName)
            if (potentialExe.exists() && potentialExe.isFile) {
                return potentialExe.absolutePath
            }
        }

        // Если ничего не подошло, возвращаем исходный путь, чтобы проверка isValideScrcpyPath показала ошибку
        return selectedIoFile.absolutePath
    }

    private fun isValidScrcpyPath(path: String): Boolean {
        try {
            val file = File(path)
            if (!file.exists() || !file.isFile || !file.canExecute() || !file.name.equals(scrcpyName, ignoreCase = true)) {
                return false
            }
            val process = ProcessBuilder(path, "--version").start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            return finished && process.exitValue() == 0
        } catch (e: Exception) {
            return false
        }
    }

    private fun promptForScrcpyPathWithRetry() {
        while (true) {
            val resolvedScrcpyPath = selectScrcpyFileOrFolder()

            if (resolvedScrcpyPath == null) {
                return // Пользователь отменил выбор
            }

            if (isValidScrcpyPath(resolvedScrcpyPath)) {
                SettingsService.saveScrcpyPath(resolvedScrcpyPath)
                SwingUtilities.invokeLater {
                    // <<< ИЗМЕНЕНИЕ 2: Закрываем диалог с кастомным кодом
                    close(RETRY_EXIT_CODE)
                }
                return
            } else {
                val message = """
                    Could not find a valid '$scrcpyName' executable at the selected location.

                    Please make sure you select either:
                    • The actual '$scrcpyName' executable file.
                    • The folder that contains the '$scrcpyName' file.

                    Would you like to try again?
                """.trimIndent()

                val result = JOptionPane.showConfirmDialog(
                    this.contentPane, message, "Invalid Scrcpy Path",
                    JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE
                )

                if (result != JOptionPane.YES_OPTION) {
                    return // Пользователь не хочет продолжать
                }
            }
        }
    }
}