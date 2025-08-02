package io.github.qavlad.adbrandomizer.services.integration.scrcpy.ui

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
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.*

class ScrcpyCompatibilityDialog(
    private val project: Project?,
    private val currentVersion: String,
    // Device name parameter is kept for future use
    @Suppress("UNUSED_PARAMETER")
    private val deviceName: String = "",
    private val problemType: ProblemType = ProblemType.NOT_WORKING // по умолчанию универсальная проблема
) : DialogWrapper(project) {

    enum class ProblemType {
        NOT_FOUND, // scrcpy не найден
        NOT_WORKING, // scrcpy найден, но не запускается/ошибка
        INCOMPATIBLE // scrcpy несовместим (например, слишком старая версия)
    }

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) 
        PluginConfig.Scrcpy.SCRCPY_NAMES["windows"]!! 
    else 
        PluginConfig.Scrcpy.SCRCPY_NAMES["default"]!!

    companion object {
        const val RETRY_EXIT_CODE = PluginConfig.UIConstants.RETRY_EXIT_CODE
    }

    init {
        title = "Scrcpy Problem Solver"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(635, 681)
        mainPanel.minimumSize = Dimension(635, 681)

        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val warningIcon = UIManager.getIcon("OptionPane.warningIcon")
        headerPanel.add(JLabel(warningIcon))

        val titleLabel = JBLabel("Scrcpy problem detected").apply {
            font = font.deriveFont(Font.BOLD, 16f)
        }
        headerPanel.add(titleLabel)
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.empty(15, 20)

        // Описание проблемы
        val problemText = JBTextArea().apply {
            text = getProblemDescription()
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

        val solutionsLabel = JBLabel("Solutions:").apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        contentPanel.add(solutionsLabel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 1: Указать путь к scrcpy
        val solution1Panel = createSolutionPanel(
            "1. Use a different scrcpy version",
            "If you have another version of scrcpy installed, you can specify its path directly.",
            "Select scrcpy Path"
        ) {
            promptForScrcpyPathWithRetry()
        }
        contentPanel.add(solution1Panel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 2: Скачать или обновить scrcpy
        val solution2Title: String
        val solution2Desc: String
        val solution2Button: String
        val solution2Action: () -> Unit
        if (problemType == ProblemType.NOT_FOUND) {
            solution2Title = "2. Download and install scrcpy"
            solution2Desc = "Download the latest scrcpy release and follow the installation instructions."
            solution2Button = "Open GitHub Releases"
            solution2Action = { BrowserUtil.browse("https://github.com/Genymobile/scrcpy/releases") }
        } else {
            solution2Title = "2. Download latest version from GitHub"
            solution2Desc = "Download the latest scrcpy release that supports your device."
            solution2Button = "Open GitHub Releases"
            solution2Action = { BrowserUtil.browse("https://github.com/Genymobile/scrcpy/releases") }
        }
        val solution2Panel = createSolutionPanel(solution2Title, solution2Desc, solution2Button, solution2Action)
        contentPanel.add(solution2Panel)
        contentPanel.add(Box.createVerticalStrut(15))

        // Решение 3: Команды для установки/обновления scrcpy
        val commands = getCommandList()
        val solution3Panel = JPanel()
        solution3Panel.layout = BoxLayout(solution3Panel, BoxLayout.Y_AXIS)
        solution3Panel.isOpaque = false
        val solution3Title = when (problemType) {
            ProblemType.NOT_FOUND -> "3. Install scrcpy via terminal/command line"
            else -> "3. Update scrcpy via terminal/command line"
        }
        val solution3Label = JBLabel(solution3Title).apply { font = font.deriveFont(Font.BOLD) }
        solution3Panel.add(solution3Label)
        solution3Panel.add(Box.createVerticalStrut(5))
        for ((desc, cmd) in commands) {
            val cmdPanel = JPanel(BorderLayout())
            cmdPanel.isOpaque = false
            val cmdArea = JBTextArea(cmd).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                font = UIUtil.getLabelFont()
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.empty(2)
                rows = 2
            }
            val copyBtn = JButton("Copy").apply {
                addActionListener { copyToClipboard(cmd) }
            }
            ButtonUtils.addHoverEffect(copyBtn)
            cmdPanel.add(JLabel(desc), BorderLayout.NORTH)
            cmdPanel.add(cmdArea, BorderLayout.CENTER)
            cmdPanel.add(copyBtn, BorderLayout.EAST)
            solution3Panel.add(cmdPanel)
            solution3Panel.add(Box.createVerticalStrut(5))
        }
        contentPanel.add(solution3Panel)
        contentPanel.add(Box.createVerticalStrut(20))

        val noteText = JBTextArea().apply {
            text = "Note: After installing or updating scrcpy, you may need to restart the IDE for the changes to take effect."
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

    private fun getProblemDescription(): String {
        return when (problemType) {
            ProblemType.NOT_FOUND ->
                "scrcpy executable was not found on your system.\n\n" +
                "Screen mirroring cannot be started because scrcpy is missing.\n" +
                "You need to install scrcpy or specify its path."
            ProblemType.NOT_WORKING ->
                "scrcpy could not be started or failed to run.\n\n" +
                "This may be caused by an outdated or broken scrcpy installation, or by missing dependencies.\n" +
                "Try updating scrcpy or selecting a different version."
            ProblemType.INCOMPATIBLE ->
                "Your current scrcpy version ($currentVersion) is incompatible with your device or Android version.\n\n" +
                "Please update scrcpy to the latest version or select a compatible version."
        }
    }

    private fun getCommandList(): List<Pair<String, String>> {
        val osName = System.getProperty("os.name").lowercase()
        return when (problemType) {
            ProblemType.NOT_FOUND -> when {
                osName.contains("mac") -> listOf(
                    "Homebrew:" to "brew install scrcpy"
                )
                osName.contains("windows") -> listOf(
                    "Chocolatey:" to "choco install scrcpy",
                    "Scoop:" to "scoop install scrcpy",
                    "Winget:" to "winget install scrcpy"
                )
                osName.contains("linux") -> listOf(
                    "Ubuntu/Debian:" to "sudo apt update && sudo apt install scrcpy",
                    "Arch Linux:" to "sudo pacman -S scrcpy",
                    "Fedora:" to "sudo dnf install scrcpy",
                    "Snap:" to "sudo snap install scrcpy"
                )
                else -> listOf("Manual installation:" to "See https://github.com/Genymobile/scrcpy for instructions.")
            }
            else -> when {
                osName.contains("mac") -> listOf(
                    "Homebrew:" to "brew upgrade scrcpy"
                )
                osName.contains("windows") -> listOf(
                    "Chocolatey:" to "choco upgrade scrcpy",
                    "Scoop:" to "scoop update scrcpy",
                    "Winget:" to "winget upgrade scrcpy"
                )
                osName.contains("linux") -> listOf(
                    "Ubuntu/Debian:" to "sudo apt update && sudo apt upgrade scrcpy",
                    "Arch Linux:" to "sudo pacman -Syu scrcpy",
                    "Fedora:" to "sudo dnf upgrade scrcpy",
                    "Snap:" to "sudo snap refresh scrcpy"
                )
                else -> listOf("Manual update:" to "See https://github.com/Genymobile/scrcpy for instructions.")
            }
        }
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
        ButtonUtils.addHoverEffect(button)

        panel.add(textPanel, BorderLayout.CENTER)
        panel.add(button, BorderLayout.EAST)

        return panel
    }

    private fun selectScrcpyFileOrFolder(): String? {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
            .withTitle("Select Scrcpy Executable or Its Containing Folder")
            .withDescription("Please locate the '$scrcpyName' file or the folder where it resides.")

        val chosenFile: VirtualFile? = FileChooser.chooseFile(descriptor, project, null)

        if (chosenFile == null) return null

        val selectedIoFile = File(chosenFile.path)

        if (selectedIoFile.isFile && selectedIoFile.name.equals(scrcpyName, ignoreCase = true)) {
            return selectedIoFile.absolutePath
        }

        if (selectedIoFile.isDirectory) {
            val potentialExe = File(selectedIoFile, scrcpyName)
            if (potentialExe.exists() && potentialExe.isFile) {
                return potentialExe.absolutePath
            }
        }

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
        } catch (_: Exception) {
            return false
        }
    }

    private fun promptForScrcpyPathWithRetry() {
        while (true) {
            val resolvedScrcpyPath = selectScrcpyFileOrFolder()

            if (resolvedScrcpyPath == null) {
                return
            }

            if (isValidScrcpyPath(resolvedScrcpyPath)) {
                PresetStorageService.saveScrcpyPath(resolvedScrcpyPath)
                SwingUtilities.invokeLater {
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
                    return
                }
            }
        }
    }
}