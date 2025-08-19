package io.github.qavlad.adbrandomizer.services.integration.scrcpy.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
        INCOMPATIBLE, // scrcpy несовместим (например, слишком старая версия)
        ANDROID_15_INCOMPATIBLE // специфичная проблема с Android 15
    }

    private val scrcpyName = if (System.getProperty("os.name").startsWith("Windows")) 
        PluginConfig.Scrcpy.SCRCPY_NAMES["windows"]!! 
    else 
        PluginConfig.Scrcpy.SCRCPY_NAMES["default"]!!
    
    private val scrcpyPathField = JBTextField().apply {
        toolTipText = """<html>
            Path to scrcpy executable or folder containing scrcpy.<br>
            <b>Examples:</b><br>
            • C:\scrcpy\scrcpy.exe (path to executable)<br>
            • C:\scrcpy\ (path to folder)<br>
            • /usr/local/bin/scrcpy (Linux/Mac)<br>
            • C:\downloads\scrcpy-win64-v3.1.zip (ZIP archive - will be extracted)<br>
        </html>""".trimIndent()
    }
    
    private val scrcpyPathValidationLabel = JLabel().apply {
        foreground = JBColor.GREEN
        border = JBUI.Borders.empty(2, 0, 5, 0)
        isVisible = false
    }

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

        // Решение 1: Указать путь к scrcpy с полем ввода
        val solution1Panel = createSolutionPanelWithInput()
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
            ProblemType.ANDROID_15_INCOMPATIBLE ->
                "Your scrcpy version ($currentVersion) is incompatible with Android 15.\n\n" +
                "Android 15 introduced API changes that require scrcpy version 2.4 or newer.\n" +
                "The error 'NoSuchMethodException: SurfaceControl.createDisplay' indicates your scrcpy version is too old.\n" +
                "Please update to scrcpy 2.4 or later to support Android 15 devices."
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

    private fun createSolutionPanelWithInput(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(5)
        panel.background = UIUtil.getPanelBackground()
        
        // Title
        val titleLabel = JBLabel("1. Use a different scrcpy version").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(titleLabel)
        
        // Description
        val descArea = JBTextArea().apply {
            text = "If you have another version of scrcpy installed, you can specify its path directly."
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
        }
        panel.add(descArea)
        panel.add(Box.createVerticalStrut(5))
        
        // Path input panel
        val pathPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Integer.MAX_VALUE, 35)
            
            // Инициализируем поле текущим путём
            val currentPath = PluginSettings.instance.scrcpyPath.ifBlank {
                PresetStorageService.getScrcpyPath() ?: ""
            }
            scrcpyPathField.text = currentPath
            
            add(scrcpyPathField.apply {
                preferredSize = JBUI.size(300, 30)
            })
            add(Box.createHorizontalStrut(5))
            
            val browseButton = JButton("Browse...").apply {
                addActionListener {
                    val resolvedPath = selectScrcpyFileOrFolder()
                    if (resolvedPath != null) {
                        scrcpyPathField.text = resolvedPath
                    }
                }
            }
            ButtonUtils.addHoverEffect(browseButton)
            add(browseButton)
            
            add(Box.createHorizontalStrut(5))
            
            val applyButton = JButton("Apply").apply {
                addActionListener {
                    applyScrcpyPath()
                }
            }
            ButtonUtils.addHoverEffect(applyButton)
            add(applyButton)
        }
        panel.add(pathPanel)
        
        // Validation label
        panel.add(scrcpyPathValidationLabel)
        
        // Setup validation listener
        setupValidationListener()
        
        return panel
    }
    
    private fun setupValidationListener() {
        scrcpyPathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateScrcpyPath()
            override fun removeUpdate(e: DocumentEvent) = validateScrcpyPath()
            override fun changedUpdate(e: DocumentEvent) = validateScrcpyPath()
            
            fun validateScrcpyPath() {
                var path = scrcpyPathField.text.trim()
                
                if (path.isBlank()) {
                    scrcpyPathValidationLabel.isVisible = false
                    return
                }
                
                // Удаляем кавычки, если путь обёрнут в них
                if ((path.startsWith("\"") && path.endsWith("\"")) || 
                    (path.startsWith("'") && path.endsWith("'"))) {
                    path = path.substring(1, path.length - 1).trim()
                    // Обновляем поле без кавычек
                    SwingUtilities.invokeLater {
                        scrcpyPathField.text = path
                    }
                    return // Валидация произойдёт автоматически после обновления текста
                }
                
                val file = File(path)
                val isWindows = System.getProperty("os.name").startsWith("Windows")
                
                when {
                    // Check if it's a ZIP archive
                    file.isFile && file.name.endsWith(".zip", ignoreCase = true) -> {
                        if (file.exists()) {
                            scrcpyPathValidationLabel.text = "⚠ ZIP archive detected - click here to extract"
                            scrcpyPathValidationLabel.foreground = JBColor.YELLOW
                            scrcpyPathValidationLabel.isVisible = true
                            
                            // Удаляем старые слушатели мыши
                            for (listener in scrcpyPathValidationLabel.mouseListeners) {
                                scrcpyPathValidationLabel.removeMouseListener(listener)
                            }
                            
                            // Добавляем кликабельность для извлечения архива
                            scrcpyPathValidationLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            scrcpyPathValidationLabel.addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent) {
                                    if (file.exists() && file.name.endsWith(".zip", ignoreCase = true)) {
                                        val extractedPath = handleZipArchive(file, isWindows)
                                        if (extractedPath != null) {
                                            scrcpyPathField.text = extractedPath
                                        }
                                    }
                                }
                            })
                            
                            // Также предлагаем извлечь архив автоматически
                            SwingUtilities.invokeLater {
                                val result = JOptionPane.showConfirmDialog(
                                    this@ScrcpyCompatibilityDialog.contentPane,
                                    "You've entered a path to a ZIP archive.\nWould you like to extract it now?",
                                    "Extract Archive",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE
                                )
                                
                                if (result == JOptionPane.YES_OPTION) {
                                    val extractedPath = handleZipArchive(file, isWindows)
                                    if (extractedPath != null) {
                                        scrcpyPathField.text = extractedPath
                                    }
                                }
                            }
                        } else {
                            scrcpyPathValidationLabel.text = "✗ ZIP archive not found"
                            scrcpyPathValidationLabel.foreground = JBColor.RED
                            scrcpyPathValidationLabel.isVisible = true
                            scrcpyPathValidationLabel.cursor = Cursor.getDefaultCursor()
                            
                            // Удаляем слушатели мыши
                            for (listener in scrcpyPathValidationLabel.mouseListeners) {
                                scrcpyPathValidationLabel.removeMouseListener(listener)
                            }
                        }
                    }
                    // Check if it's a valid scrcpy executable
                    file.isFile -> {
                        val expectedName = if (isWindows) "scrcpy.exe" else "scrcpy"
                        when {
                            !file.exists() -> {
                                scrcpyPathValidationLabel.text = "✗ File not found"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            !file.name.equals(expectedName, ignoreCase = true) -> {
                                scrcpyPathValidationLabel.text = "✗ File should be named '$expectedName'"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            !file.canExecute() && !isWindows -> {
                                scrcpyPathValidationLabel.text = "✗ File is not executable"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            else -> {
                                scrcpyPathValidationLabel.text = "✓ Valid scrcpy executable"
                                scrcpyPathValidationLabel.foreground = JBColor.GREEN
                            }
                        }
                        scrcpyPathValidationLabel.isVisible = true
                        scrcpyPathValidationLabel.cursor = Cursor.getDefaultCursor()
                        
                        // Удаляем слушатели мыши для не-архивов
                        for (listener in scrcpyPathValidationLabel.mouseListeners) {
                            scrcpyPathValidationLabel.removeMouseListener(listener)
                        }
                    }
                    // Check if it's a valid directory containing scrcpy
                    file.isDirectory || path.endsWith("/") || path.endsWith("\\") -> {
                        val dir = if (file.isDirectory) file else File(path.trimEnd('/', '\\'))
                        val scrcpyExe = if (isWindows) {
                            File(dir, "scrcpy.exe")
                        } else {
                            File(dir, "scrcpy")
                        }
                        
                        when {
                            !dir.exists() -> {
                                scrcpyPathValidationLabel.text = "✗ Directory not found"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            !scrcpyExe.exists() -> {
                                scrcpyPathValidationLabel.text = "✗ Directory does not contain scrcpy"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            !scrcpyExe.canExecute() && !isWindows -> {
                                scrcpyPathValidationLabel.text = "✗ scrcpy in directory is not executable"
                                scrcpyPathValidationLabel.foreground = JBColor.RED
                            }
                            else -> {
                                scrcpyPathValidationLabel.text = "✓ Valid scrcpy directory"
                                scrcpyPathValidationLabel.foreground = JBColor.GREEN
                            }
                        }
                        scrcpyPathValidationLabel.isVisible = true
                        scrcpyPathValidationLabel.cursor = Cursor.getDefaultCursor()
                        
                        // Удаляем слушатели мыши для не-архивов
                        for (listener in scrcpyPathValidationLabel.mouseListeners) {
                            scrcpyPathValidationLabel.removeMouseListener(listener)
                        }
                    }
                    else -> {
                        scrcpyPathValidationLabel.text = "✗ Invalid path"
                        scrcpyPathValidationLabel.foreground = JBColor.RED
                        scrcpyPathValidationLabel.isVisible = true
                        scrcpyPathValidationLabel.cursor = Cursor.getDefaultCursor()
                        
                        // Удаляем слушатели мыши для невалидных путей
                        for (listener in scrcpyPathValidationLabel.mouseListeners) {
                            scrcpyPathValidationLabel.removeMouseListener(listener)
                        }
                    }
                }
            }
        })
    }
    
    private fun applyScrcpyPath() {
        var path = scrcpyPathField.text.trim()
        
        if (path.isBlank()) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Please enter a path to scrcpy",
                "Empty Path",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        // Удаляем кавычки, если есть
        if ((path.startsWith("\"") && path.endsWith("\"")) || 
            (path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length - 1).trim()
        }
        
        val file = File(path)
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        
        // Проверяем, является ли это архивом
        when {
            file.isFile && file.name.endsWith(".zip", ignoreCase = true) && file.exists() -> {
                val extractedPath = handleZipArchive(file, isWindows)
                if (extractedPath != null) {
                    path = extractedPath
                    scrcpyPathField.text = path
                } else {
                    return
                }
            }
            file.isFile && (file.name.endsWith(".tar.gz", ignoreCase = true) || 
                           file.name.endsWith(".tgz", ignoreCase = true)) && file.exists() -> {
                val extractedPath = handleTarGzArchive(file, isWindows)
                if (extractedPath != null) {
                    path = extractedPath
                    scrcpyPathField.text = path
                } else {
                    return
                }
            }
        }
        
        // На macOS автоматически удаляем атрибут карантина
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            removeQuarantineAttribute(path)
        }
        
        // Валидируем путь
        var pathToSave: String? = null
        
        when {
            File(path).isFile && isValidScrcpyPath(path) -> {
                // Это валидный exe файл, сохраняем путь к директории
                val f = File(path)
                pathToSave = if (f.parentFile != null) f.parentFile.absolutePath else path
            }
            File(path).isDirectory -> {
                // Это директория, проверяем есть ли внутри scrcpy
                val scrcpyFile = File(path, scrcpyName)
                if (scrcpyFile.exists() && isValidScrcpyPath(scrcpyFile.absolutePath)) {
                    pathToSave = path
                }
            }
        }
        
        if (pathToSave != null) {
            // Сохраняем путь
            PresetStorageService.saveScrcpyPath(pathToSave)
            PluginSettings.instance.scrcpyPath = pathToSave
            
            SwingUtilities.invokeLater {
                close(RETRY_EXIT_CODE)
            }
        } else {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Could not find a valid '$scrcpyName' executable at the specified location",
                "Invalid Path",
                JOptionPane.ERROR_MESSAGE
            )
        }
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
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        // FileChooserDescriptor(chooseFiles, chooseFolders, chooseJars, chooseJarsAsFiles, chooseJarContents, chooseMultiple)
        val descriptor = FileChooserDescriptor(true, true, true, true, false, false).apply {
            title = "Select Scrcpy Executable, Folder Or Archive"
            description = "Select the scrcpy executable file, the folder containing it, or an archive (ZIP, TAR.GZ)"
            
            // Filter to show only relevant files
            withFileFilter { virtualFile ->
                when {
                    virtualFile.isDirectory -> true
                    virtualFile.extension?.equals("zip", ignoreCase = true) == true -> true
                    virtualFile.name.endsWith(".tar.gz", ignoreCase = true) -> true
                    virtualFile.name.endsWith(".tgz", ignoreCase = true) -> true
                    isWindows && virtualFile.extension?.equals("exe", ignoreCase = true) == true -> true
                    !isWindows && virtualFile.name.equals("scrcpy", ignoreCase = true) -> true
                    else -> false
                }
            }
        }

        // Определяем начальную директорию на основе текущего сохраненного пути
        val currentPath = PluginSettings.instance.scrcpyPath.ifBlank {
            PresetStorageService.getScrcpyPath()
        }
        
        val initialDir = if (!currentPath.isNullOrBlank()) {
            val path = File(currentPath)
            when {
                path.isFile && path.parentFile != null -> {
                    // Если это файл, открываем папку где он находится
                    path.parentFile
                }
                path.isDirectory -> {
                    // Если это директория, открываем её саму
                    path
                }
                else -> null
            }
        } else {
            null
        }
        
        val initialVirtualFile = initialDir?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(dir)
            } else {
                null
            }
        }

        val chosenFile: VirtualFile = FileChooser.chooseFile(descriptor, project, initialVirtualFile) ?: return null

        val selectedIoFile = File(chosenFile.path)
        
        // Handle archives
        when {
            selectedIoFile.isFile && selectedIoFile.name.endsWith(".zip", ignoreCase = true) -> {
                PluginLogger.info(LogCategory.GENERAL, "User selected ZIP archive: %s", selectedIoFile.absolutePath)
                val extractedPath = handleZipArchive(selectedIoFile, isWindows)
                if (extractedPath != null) {
                    return extractedPath
                }
                return null
            }
            selectedIoFile.isFile && (selectedIoFile.name.endsWith(".tar.gz", ignoreCase = true) || 
                                     selectedIoFile.name.endsWith(".tgz", ignoreCase = true)) -> {
                PluginLogger.info(LogCategory.GENERAL, "User selected TAR.GZ archive: %s", selectedIoFile.absolutePath)
                val extractedPath = handleTarGzArchive(selectedIoFile, isWindows)
                if (extractedPath != null) {
                    return extractedPath
                }
                return null
            }
        }

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

    private fun handleZipArchive(zipFile: File, isWindows: Boolean): String? {
        PluginLogger.info(LogCategory.GENERAL, "Processing ZIP archive: %s", zipFile.absolutePath)
        
        // Check file size (should be less than 50 MB)
        val fileSizeMB = zipFile.length() / (1024 * 1024)
        PluginLogger.info(LogCategory.GENERAL, "Archive size: %s MB", fileSizeMB.toString())
        
        if (fileSizeMB > 50) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "The selected archive is too large (${fileSizeMB} MB).\nScrcpy archives are typically less than 50 MB.\nPlease select a valid scrcpy archive.",
                "Invalid Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return null
        }
        
        // Check if archive contains scrcpy.exe (for Windows) or scrcpy
        val scrcpyFileName = if (isWindows) "scrcpy.exe" else "scrcpy"
        var containsScrcpy = false
        var scrcpyPathInArchive: String? = null
        
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    
                    // Check if this entry is scrcpy executable
                    if (entryName.endsWith(scrcpyFileName, ignoreCase = true)) {
                        containsScrcpy = true
                        scrcpyPathInArchive = entryName
                        break
                    }
                }
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Failed to read the archive: ${e.message}",
                "Error Reading Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return null
        }
        
        if (!containsScrcpy) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "The selected archive does not contain $scrcpyFileName.\nPlease select a valid scrcpy archive.",
                "Invalid Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return null
        }
        
        // Show dialog asking if user wants to extract
        val message = """
            You have selected a ZIP archive containing scrcpy.
            
            The archive needs to be extracted before scrcpy can be used.
            
            Would you like to extract it automatically to:
            ${zipFile.parentFile.absolutePath}?
        """.trimIndent()
        
        val result = JOptionPane.showConfirmDialog(
            this.contentPane,
            message,
            "Extract Archive",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            return extractArchiveAndGetPath(zipFile, scrcpyPathInArchive!!, isWindows)
        }
        
        return null
    }
    
    private fun extractArchiveAndGetPath(zipFile: File, scrcpyPathInArchive: String, isWindows: Boolean): String? {
        try {
            val targetDir = zipFile.parentFile
            
            // Check if archive has a single root directory
            var commonPrefix: String? = null
            var hasMultipleRoots = false
            
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val firstSlash = entry.name.indexOf('/')
                        val rootDir = if (firstSlash > 0) entry.name.substring(0, firstSlash) else null
                        
                        if (commonPrefix == null) {
                            commonPrefix = rootDir
                        } else if (commonPrefix != rootDir) {
                            hasMultipleRoots = true
                            break
                        }
                    }
                }
            }
            
            // Determine extraction directory
            val extractedDir = if (!hasMultipleRoots && commonPrefix != null) {
                // Archive has single root directory - extract directly to parent
                targetDir
            } else {
                // Archive has multiple roots or no common root - create subdirectory
                File(targetDir, zipFile.nameWithoutExtension).also {
                    if (!it.exists()) it.mkdirs()
                }
            }
            
            PluginLogger.info(LogCategory.GENERAL, "Extracting to: %s, single root: %s, common prefix: %s", 
                extractedDir.absolutePath, (!hasMultipleRoots).toString(), commonPrefix ?: "none")
            
            // Extract the archive
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(extractedDir, entry.name)
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        // Create parent directories if needed
                        entryFile.parentFile?.mkdirs()
                        
                        // Extract file
                        zip.getInputStream(entry).use { input ->
                            entryFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Make executable if it's scrcpy
                        if (entry.name.endsWith(if (isWindows) "scrcpy.exe" else "scrcpy", ignoreCase = true)) {
                            entryFile.setExecutable(true)
                        }
                    }
                }
            }
            
            // Find the extracted scrcpy executable
            val extractedScrcpy = File(extractedDir, scrcpyPathInArchive)
            
            if (extractedScrcpy.exists()) {
                // Возвращаем путь к директории, содержащей scrcpy
                val pathToReturn = extractedScrcpy.parentFile.absolutePath
                
                // На macOS удаляем атрибут карантина после извлечения
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    removeQuarantineAttribute(pathToReturn)
                }
                
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Archive extracted successfully!\nScrcpy path has been set to:\n$pathToReturn",
                    "Extraction Complete",
                    JOptionPane.INFORMATION_MESSAGE
                )
                
                return pathToReturn
            } else {
                // Fallback - shouldn't happen but just in case
                return extractedDir.absolutePath
            }
            
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Failed to extract archive: ${e.message}",
                "Extraction Failed",
                JOptionPane.ERROR_MESSAGE
            )
            return null
        }
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
    
    private fun handleTarGzArchive(tarGzFile: File, isWindows: Boolean): String? {
        PluginLogger.info(LogCategory.GENERAL, "Processing TAR.GZ archive: %s", tarGzFile.absolutePath)
        
        try {
            val targetDir = File(tarGzFile.parentFile, tarGzFile.nameWithoutExtension.removeSuffix(".tar"))
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            // Extract using tar command (available on all Unix systems and modern Windows)
            val extractCommand = if (isWindows) {
                // Windows 10+ has tar built-in
                listOf("tar", "-xzf", tarGzFile.absolutePath, "-C", targetDir.absolutePath)
            } else {
                // Unix/Linux/macOS
                listOf("tar", "-xzf", tarGzFile.absolutePath, "-C", targetDir.absolutePath)
            }
            
            val process = ProcessBuilder(extractCommand).start()
            val success = process.waitFor(30, TimeUnit.SECONDS)
            
            if (!success || process.exitValue() != 0) {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Failed to extract TAR.GZ archive:\n$error",
                    "Extraction Failed",
                    JOptionPane.ERROR_MESSAGE
                )
                return null
            }
            
            // Find scrcpy in extracted files
            val scrcpyFileName = if (isWindows) "scrcpy.exe" else "scrcpy"
            val scrcpyFile = findFileRecursively(targetDir, scrcpyFileName)
            
            if (scrcpyFile != null && scrcpyFile.exists()) {
                val pathToReturn = scrcpyFile.parentFile.absolutePath
                
                // На macOS удаляем атрибут карантина после извлечения
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    removeQuarantineAttribute(pathToReturn)
                }
                
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Archive extracted successfully!\nScrcpy path has been set to:\n$pathToReturn",
                    "Extraction Complete",
                    JOptionPane.INFORMATION_MESSAGE
                )
                
                return pathToReturn
            } else {
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Could not find $scrcpyFileName in the extracted archive.",
                    "Invalid Archive",
                    JOptionPane.ERROR_MESSAGE
                )
                return null
            }
            
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "Failed to extract TAR.GZ archive: ${e.message}",
                "Extraction Failed",
                JOptionPane.ERROR_MESSAGE
            )
            return null
        }
    }
    
    private fun findFileRecursively(dir: File, fileName: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isFile && file.name.equals(fileName, ignoreCase = true)) {
                return file
            } else if (file.isDirectory) {
                val found = findFileRecursively(file, fileName)
                if (found != null) return found
            }
        }
        return null
    }
    
    private fun removeQuarantineAttribute(path: String) {
        try {
            val file = File(path)
            
            // Определяем, что нужно обработать
            when {
                file.isFile -> {
                    // Удаляем атрибут у файла
                    val removeCmd = ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.absolutePath).start()
                    removeCmd.waitFor(2, TimeUnit.SECONDS)
                    PluginLogger.info(LogCategory.GENERAL, "Removed quarantine attribute from file: %s", file.absolutePath)
                }
                file.isDirectory -> {
                    // Удаляем атрибут рекурсивно у всех файлов в директории
                    val removeCmd = ProcessBuilder("xattr", "-r", "-d", "com.apple.quarantine", file.absolutePath).start()
                    removeCmd.waitFor(3, TimeUnit.SECONDS)
                    
                    // Дополнительно убеждаемся, что scrcpy исполняемый
                    val scrcpyFile = File(file, "scrcpy")
                    if (scrcpyFile.exists()) {
                        // Делаем файл исполняемым
                        val chmodCmd = ProcessBuilder("chmod", "+x", scrcpyFile.absolutePath).start()
                        chmodCmd.waitFor(1, TimeUnit.SECONDS)
                    }
                    
                    PluginLogger.info(LogCategory.GENERAL, "Removed quarantine attribute from directory: %s", file.absolutePath)
                }
            }
        } catch (e: Exception) {
            // Не показываем ошибку пользователю, просто логируем
            PluginLogger.debug(LogCategory.GENERAL, "Could not remove quarantine attribute: %s", e.message ?: "Unknown error")
        }
    }

}