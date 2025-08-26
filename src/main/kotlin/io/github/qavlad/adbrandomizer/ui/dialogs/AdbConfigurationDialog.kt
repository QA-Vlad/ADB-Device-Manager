package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.utils.AdbPathResolver
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AdbConfigurationDialog(
    private val project: Project?
) : DialogWrapper(project) {
    
    private val adbName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
    
    private val adbPathField = JBTextField().apply {
        // Заполняем поле текущим значением из настроек, только если путь существует
        val savedPath = PluginSettings.instance.adbPath
        println("ADB_Randomizer [Dialog]: Initial saved path: '$savedPath'")
        
        text = if (savedPath.isNotBlank()) {
            val file = File(savedPath)
            // Проверяем что файл или директория существует
            if (file.exists()) {
                println("ADB_Randomizer [Dialog]: Saved path exists, using: '$savedPath'")
                savedPath
            } else {
                println("ADB_Randomizer [Dialog]: Saved path doesn't exist, searching for ADB...")
                // Если старый путь не существует, пытаемся найти ADB автоматически
                val foundPath = AdbPathResolver.findAdbExecutable() ?: ""
                println("ADB_Randomizer [Dialog]: Found ADB at: '${foundPath.ifEmpty { "<not found>" }}'")
                foundPath
            }
        } else {
            println("ADB_Randomizer [Dialog]: No saved path, searching for ADB...")
            // Если путь не сохранен, пытаемся найти ADB автоматически  
            val foundPath = AdbPathResolver.findAdbExecutable() ?: ""
            println("ADB_Randomizer [Dialog]: Found ADB at: '${foundPath.ifEmpty { "<not found>" }}'")
            foundPath
        }
        toolTipText = """<html>
            Path to ADB executable or folder containing ADB.<br>
            <b>Examples:</b><br>
            • C:\Android\Sdk\platform-tools\adb.exe (path to executable)<br>
            • C:\Android\Sdk\platform-tools\ (path to folder)<br>
            • /usr/local/bin/adb (Linux/Mac)<br>
            • Leave empty to auto-detect from PATH
        </html>""".trimIndent()
    }
    
    private val adbPathValidationLabel = JLabel().apply {
        foreground = JBColor.GREEN
        border = JBUI.Borders.empty(2, 0, 5, 0)
        isVisible = false
    }

    companion object {
        const val RETRY_EXIT_CODE = PluginConfig.UIConstants.RETRY_EXIT_CODE
    }

    init {
        title = "ADB Configuration Required"
        setOKButtonText("Save & Close")
        init()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
    
    override fun doOKAction() {
        // Сохраняем путь к ADB в настройки
        val newPath = adbPathField.text.trim()
        if (newPath != PluginSettings.instance.adbPath) {
            PluginSettings.instance.adbPath = newPath
            PluginLogger.debug(LogCategory.GENERAL, "ADB path updated via configuration dialog: %s",
                newPath.ifEmpty { "<auto-detect>" })
        }
        super.doOKAction()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.gridx = 0
        gbc.insets = JBUI.insets(5)
        
        // Заголовок с иконкой
        val headerPanel = JPanel(BorderLayout(5, 0))
        val warningIcon = UIManager.getIcon("OptionPane.warningIcon")
        headerPanel.add(JLabel(warningIcon), BorderLayout.WEST)
        
        val headerText = JPanel()
        headerText.layout = BoxLayout(headerText, BoxLayout.Y_AXIS)
        headerText.add(JLabel("ADB not found").apply {
            font = font.deriveFont(Font.BOLD, 14f)
        })
        headerText.add(JLabel("The plugin cannot work without Android Debug Bridge").apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
        })
        headerPanel.add(headerText, BorderLayout.CENTER)
        
        gbc.gridy = 0
        panel.add(headerPanel, gbc)
        
        // Separator
        gbc.gridy = 1
        gbc.insets = JBUI.insets(10, 5)
        panel.add(JSeparator(), gbc)
        
        // Решение 1: Указать путь к ADB
        gbc.gridy = 2
        gbc.insets = JBUI.insets(5)
        panel.add(JLabel("1. Specify path to existing ADB installation").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)
        
        gbc.gridy = 3
        panel.add(JLabel("If you have ADB installed elsewhere, specify its path:").apply {
            font = JBFont.small()
        }, gbc)
        
        // Панель с полем ввода
        val pathPanel = JPanel(GridBagLayout())
        val pathGbc = GridBagConstraints()
        pathGbc.gridy = 0
        pathGbc.insets = JBUI.insets(2)
        
        // Path field
        pathGbc.gridx = 0
        pathGbc.weightx = 1.0
        pathGbc.fill = GridBagConstraints.HORIZONTAL
        pathPanel.add(adbPathField.apply {
            preferredSize = JBUI.size(300, 28)
        }, pathGbc)
        
        // Browse button
        pathGbc.gridx = 1
        pathGbc.weightx = 0.0
        pathGbc.fill = GridBagConstraints.NONE
        pathPanel.add(JButton("Browse...").apply {
            addActionListener {
                val resolvedPath = selectAdbFileOrFolder()
                if (resolvedPath != null) {
                    adbPathField.text = resolvedPath
                }
            }
            ButtonUtils.addHoverEffect(this)
        }, pathGbc)
        
        // Apply button
        pathGbc.gridx = 2
        pathPanel.add(JButton("Apply").apply {
            addActionListener {
                applyAdbPath()
            }
            ButtonUtils.addHoverEffect(this)
        }, pathGbc)
        
        gbc.gridy = 4
        panel.add(pathPanel, gbc)
        
        // Validation label
        gbc.gridy = 5
        panel.add(adbPathValidationLabel, gbc)
        setupValidationListener()
        
        // Separator
        gbc.gridy = 6
        gbc.insets = JBUI.insets(10, 5)
        panel.add(JSeparator(), gbc)
        
        // Решение 2: Скачать platform tools
        gbc.gridy = 7
        gbc.insets = JBUI.insets(5)
        panel.add(JLabel("2. Download Android SDK platform tools").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)
        
        gbc.gridy = 8
        val downloadPanel = JPanel(BorderLayout())
        downloadPanel.add(JLabel("Download the official Android SDK platform tools which includes ADB.").apply {
            font = JBFont.small()
        }, BorderLayout.CENTER)
        downloadPanel.add(JButton("Open Download Page").apply {
            addActionListener {
                BrowserUtil.browse("https://developer.android.com/studio/releases/platform-tools")
            }
            ButtonUtils.addHoverEffect(this)
        }, BorderLayout.EAST)
        panel.add(downloadPanel, gbc)
        
        // Separator
        gbc.gridy = 9
        gbc.insets = JBUI.insets(10, 5)
        panel.add(JSeparator(), gbc)
        
        // Решение 3: Установка через пакетные менеджеры
        gbc.gridy = 10
        gbc.insets = JBUI.insets(5)
        panel.add(JLabel("3. Install via package manager").apply {
            font = font.deriveFont(Font.BOLD)
        }, gbc)
        
        val commands = getInstallCommands()
        var currentRow = 11
        for ((desc, cmd) in commands) {
            gbc.gridy = currentRow
            val cmdPanel = JPanel(BorderLayout())
            cmdPanel.add(JLabel("$desc $cmd").apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            }, BorderLayout.CENTER)
            cmdPanel.add(JButton("Copy").apply {
                addActionListener { copyToClipboard(cmd) }
                ButtonUtils.addHoverEffect(this)
            }, BorderLayout.EAST)
            panel.add(cmdPanel, gbc)
            currentRow++
        }
        
        // Note at bottom
        gbc.gridy = currentRow
        gbc.insets = JBUI.insets(10, 5, 5, 5)
        panel.add(JLabel("Note: After installing ADB, restart the IDE for changes to take effect.").apply {
            font = JBFont.small()
            foreground = UIUtil.getInactiveTextColor()
        }, gbc)
        
        // Push everything to top
        gbc.gridy = currentRow + 1
        gbc.weighty = 1.0
        panel.add(Box.createVerticalGlue(), gbc)
        
        val scrollPane = JBScrollPane(panel)
        scrollPane.preferredSize = Dimension(600, 500)
        scrollPane.border = BorderFactory.createEmptyBorder()
        return scrollPane
    }

    private fun getInstallCommands(): List<Pair<String, String>> {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> listOf(
                "Homebrew:" to "brew install android-platform-tools"
            )
            osName.contains("windows") -> listOf(
                "Chocolatey:" to "choco install adb",
                "Scoop:" to "scoop install adb"
            )
            osName.contains("linux") -> listOf(
                "Ubuntu/Debian:" to "sudo apt update && sudo apt install android-tools-adb",
                "Arch Linux:" to "sudo pacman -S android-tools",
                "Fedora:" to "sudo dnf install android-tools"
            )
            else -> listOf("Manual installation:" to "See https://developer.android.com/studio/releases/platform-tools")
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = java.awt.datatransfer.StringSelection(text)
        clipboard.setContents(selection, selection)
    }

    private fun setupValidationListener() {
        adbPathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateAdbPath()
            override fun removeUpdate(e: DocumentEvent) = validateAdbPath()
            override fun changedUpdate(e: DocumentEvent) = validateAdbPath()
            
            fun validateAdbPath() {
                var path = adbPathField.text.trim()
                
                // Логируем каждую проверку
                println("ADB_Randomizer [Dialog]: Validating path: '$path'")
                
                if (path.isBlank()) {
                    // Проверяем автодетект
                    val autoPath = AdbPathResolver.findAdbExecutable()
                    if (autoPath != null) {
                        adbPathValidationLabel.text = "✓ ADB will be auto-detected from PATH"
                        adbPathValidationLabel.foreground = JBColor.GREEN
                    } else {
                        adbPathValidationLabel.text = "⚠ ADB not found in PATH"
                        adbPathValidationLabel.foreground = JBColor.YELLOW  
                    }
                    adbPathValidationLabel.isVisible = true
                    return
                }
                
                // Нормализуем путь для Windows - заменяем прямые слеши на обратные
                val isWindows = System.getProperty("os.name").startsWith("Windows")
                if (isWindows) {
                    path = path.replace('/', '\\')
                    println("ADB_Randomizer [Dialog]: Normalized Windows path: '$path'")
                }
                
                val file = File(path)
                println("ADB_Randomizer [Dialog]: File exists: ${file.exists()}, Is file: ${file.isFile}, Is directory: ${file.isDirectory}")
                
                when {
                    // Check if it's a valid ADB executable
                    file.isFile -> {
                        val expectedName = if (isWindows) "adb.exe" else "adb"
                        when {
                            !file.exists() -> {
                                adbPathValidationLabel.text = "✗ File not found"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            !file.name.equals(expectedName, ignoreCase = true) -> {
                                adbPathValidationLabel.text = "✗ File should be named '$expectedName'"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            !file.canExecute() && !isWindows -> {
                                adbPathValidationLabel.text = "✗ File is not executable"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            else -> {
                                // Проверяем работоспособность ADB
                                val validationResult = validateAdbExecutable(path)
                                when {
                                    validationResult.isValid -> {
                                        adbPathValidationLabel.text = "✓ Valid ADB executable"
                                        adbPathValidationLabel.foreground = JBColor.GREEN
                                    }
                                    validationResult.error?.contains("missing", ignoreCase = true) == true -> {
                                        adbPathValidationLabel.text = "✗ ADB is missing required DLL files"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                    validationResult.error != null -> {
                                        adbPathValidationLabel.text = "✗ ADB error: ${validationResult.error.take(50)}"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                    else -> {
                                        adbPathValidationLabel.text = "✗ Not a valid ADB executable"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                }
                            }
                        }
                        adbPathValidationLabel.isVisible = true
                    }
                    // Check if it's a valid directory containing ADB
                    file.isDirectory || path.endsWith("/") || path.endsWith("\\") -> {
                        val dir = if (file.isDirectory) file else File(path.trimEnd('/', '\\'))
                        val adbExe = if (isWindows) {
                            File(dir, "adb.exe")
                        } else {
                            File(dir, "adb")
                        }
                        
                        when {
                            !dir.exists() -> {
                                adbPathValidationLabel.text = "✗ Directory not found"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            !adbExe.exists() -> {
                                adbPathValidationLabel.text = "✗ Directory does not contain ADB"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            !adbExe.canExecute() && !isWindows -> {
                                adbPathValidationLabel.text = "✗ ADB in directory is not executable"
                                adbPathValidationLabel.foreground = JBColor.RED
                            }
                            else -> {
                                // Проверяем работоспособность ADB
                                val validationResult = validateAdbExecutable(adbExe.absolutePath)
                                when {
                                    validationResult.isValid -> {
                                        adbPathValidationLabel.text = "✓ Valid ADB directory"
                                        adbPathValidationLabel.foreground = JBColor.GREEN
                                    }
                                    validationResult.error?.contains("missing", ignoreCase = true) == true -> {
                                        adbPathValidationLabel.text = "✗ ADB is missing required DLL files"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                    validationResult.error != null -> {
                                        adbPathValidationLabel.text = "✗ ADB error: ${validationResult.error.take(50)}"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                    else -> {
                                        adbPathValidationLabel.text = "✗ ADB in directory is not valid"
                                        adbPathValidationLabel.foreground = JBColor.RED
                                    }
                                }
                            }
                        }
                        adbPathValidationLabel.isVisible = true
                    }
                    else -> {
                        adbPathValidationLabel.text = "✗ Invalid path"
                        adbPathValidationLabel.foreground = JBColor.RED
                        adbPathValidationLabel.isVisible = true
                    }
                }
            }
        })
    }
    
    private fun applyAdbPath() {
        var path = adbPathField.text.trim()
        println("ADB_Randomizer [Dialog]: Apply button clicked with path: '$path'")
        
        // Нормализуем путь для Windows
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        if (isWindows && path.isNotBlank()) {
            path = path.replace('/', '\\')
            println("ADB_Randomizer [Dialog]: Normalized path for saving: '$path'")
        }
        
        // Если путь пустой, сохраняем пустую строку (автодетект)
        if (path.isBlank()) {
            PluginSettings.instance.adbPath = ""
            PluginLogger.debug(LogCategory.GENERAL, "ADB path cleared - will use auto-detect")
            println("ADB_Randomizer [Dialog]: Saved empty path for auto-detect")
            
            val autoPath = AdbPathResolver.findAdbExecutable()
            if (autoPath != null) {
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Settings saved. ADB will be auto-detected from system PATH.",
                    "Settings Saved",
                    JOptionPane.INFORMATION_MESSAGE
                )
                SwingUtilities.invokeLater {
                    close(RETRY_EXIT_CODE)
                }
            } else {
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "ADB not found in system PATH. Please specify a path to ADB installation.",
                    "ADB Not Found",
                    JOptionPane.WARNING_MESSAGE
                )
            }
            return
        }
        
        val file = File(path)
        println("ADB_Randomizer [Dialog]: Checking file for save - exists: ${file.exists()}, isFile: ${file.isFile}, isDirectory: ${file.isDirectory}")
        
        // Валидируем путь
        val pathToSave = when {
            file.isFile && isValidAdbPath(path) -> {
                println("ADB_Randomizer [Dialog]: Path is a valid ADB file")
                // Это валидный exe файл, можно сохранить как есть или путь к директории
                path
            }
            file.isDirectory -> {
                // Это директория, проверяем есть ли внутри ADB
                val adbFile = File(path, adbName)
                println("ADB_Randomizer [Dialog]: Path is directory, checking for $adbName inside")
                println("ADB_Randomizer [Dialog]: ADB file path: ${adbFile.absolutePath}, exists: ${adbFile.exists()}")
                
                if (adbFile.exists() && isValidAdbPath(adbFile.absolutePath)) {
                    println("ADB_Randomizer [Dialog]: Directory contains valid ADB")
                    path
                } else {
                    println("ADB_Randomizer [Dialog]: Directory does not contain valid ADB")
                    // Директория не содержит валидного ADB
                    JOptionPane.showMessageDialog(
                        this.contentPane,
                        "Directory does not contain valid '$adbName' executable",
                        "Invalid Path",
                        JOptionPane.ERROR_MESSAGE
                    )
                    return
                }
            }
            else -> {
                println("ADB_Randomizer [Dialog]: Path does not exist or is invalid")
                JOptionPane.showMessageDialog(
                    this.contentPane,
                    "Path does not exist: $path",
                    "Invalid Path",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
        }
        
        println("ADB_Randomizer [Dialog]: Saving path to settings: '$pathToSave'")
        // Сохраняем путь
        PluginSettings.instance.adbPath = pathToSave
        PluginLogger.debug(LogCategory.GENERAL, "ADB path saved: %s", pathToSave)
        println("ADB_Randomizer [Dialog]: Path saved successfully")
        
        JOptionPane.showMessageDialog(
            this.contentPane,
            "ADB path saved successfully!",
            "Settings Saved",
            JOptionPane.INFORMATION_MESSAGE
        )
        
        SwingUtilities.invokeLater {
            close(RETRY_EXIT_CODE)
        }
    }

    private fun selectAdbFileOrFolder(): String? {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false).apply {
            title = "Select ADB Executable or Folder"
            description = "Select the ADB executable file or the folder containing it"
            
            // Filter to show only relevant files
            withFileFilter { virtualFile ->
                when {
                    virtualFile.isDirectory -> true
                    isWindows && virtualFile.extension?.equals("exe", ignoreCase = true) == true -> true
                    !isWindows && virtualFile.name.equals("adb", ignoreCase = true) -> true
                    else -> false
                }
            }
        }

        // Определяем начальную директорию
        val currentPath = adbPathField.text.trim() // Берем текущее значение из поля, а не из настроек
        val initialDir = if (currentPath.isNotBlank()) {
            val path = File(currentPath)
            when {
                path.exists() && path.isFile && path.parentFile != null -> path.parentFile
                path.exists() && path.isDirectory -> path
                else -> {
                    // Если путь не существует, начинаем с корня диска C:\ на Windows или домашней директории
                    if (isWindows) {
                        File("C:\\")
                    } else {
                        File(System.getProperty("user.home"))
                    }
                }
            }
        } else {
            // Если поле пустое, начинаем с корня диска C:\ на Windows или домашней директории
            if (isWindows) {
                File("C:\\")
            } else {
                File(System.getProperty("user.home"))
            }
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

        if (selectedIoFile.isFile && selectedIoFile.name.equals(adbName, ignoreCase = true)) {
            return selectedIoFile.absolutePath
        }

        if (selectedIoFile.isDirectory) {
            val potentialExe = File(selectedIoFile, adbName)
            if (potentialExe.exists() && potentialExe.isFile) {
                return potentialExe.absolutePath
            }
        }

        return selectedIoFile.absolutePath
    }

    data class AdbValidationResult(
        val isValid: Boolean,
        val error: String? = null
    )
    
    private fun validateAdbExecutable(path: String): AdbValidationResult {
        println("ADB_Randomizer [Dialog]: validateAdbExecutable called with path: '$path'")
        try {
            val file = File(path)
            println("ADB_Randomizer [Dialog]: File check - exists: ${file.exists()}, isFile: ${file.isFile}, absolutePath: ${file.absolutePath}")
            
            if (!file.exists() || !file.isFile) {
                println("ADB_Randomizer [Dialog]: File not found or not a file")
                return AdbValidationResult(false, "File not found")
            }
            
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            
            // На Windows проверяем наличие необходимых DLL в той же директории
            if (isWindows && file.parentFile != null) {
                val adbWinApi = File(file.parentFile, "AdbWinApi.dll")
                val adbWinUsbApi = File(file.parentFile, "AdbWinUsbApi.dll")
                
                println("ADB_Randomizer [Dialog]: Checking DLLs:")
                println("ADB_Randomizer [Dialog]:   AdbWinApi.dll exists: ${adbWinApi.exists()}")
                println("ADB_Randomizer [Dialog]:   AdbWinUsbApi.dll exists: ${adbWinUsbApi.exists()}")
                
                if (!adbWinApi.exists() || !adbWinUsbApi.exists()) {
                    // DLL отсутствуют, но попробуем запустить - может они в системе
                    PluginLogger.debug(LogCategory.GENERAL, "ADB DLLs not found in directory, trying to run anyway")
                }
            }
            
            // Создаем ProcessBuilder с рабочей директорией где находится ADB
            val processBuilder = ProcessBuilder(path, "version")
            // Устанавливаем рабочую директорию - это важно для поиска DLL
            if (file.parentFile != null) {
                processBuilder.directory(file.parentFile)
            }
            
            val process = processBuilder.start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return AdbValidationResult(false, "Timeout")
            }
            
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                // Часто ошибка связана с отсутствующими DLL
                if (error.contains("dll", ignoreCase = true) || 
                    error.contains("library", ignoreCase = true) ||
                    error.contains("cannot find", ignoreCase = true) ||
                    error.contains("0xc0000135", ignoreCase = true)) {  // Код ошибки Windows для отсутствующих DLL
                    return AdbValidationResult(false, "Missing DLL files")
                }
                return AdbValidationResult(false, error.ifBlank { "Exit code $exitCode" })
            }
            
            val output = process.inputStream.bufferedReader().readText()
            val isValid = output.contains("Android Debug Bridge", ignoreCase = true)
            
            return if (isValid) {
                AdbValidationResult(true)
            } else {
                AdbValidationResult(false, "Not an ADB executable")
            }
        } catch (e: Exception) {
            // Специальная обработка для IOException которая может означать отсутствие DLL
            if (e is java.io.IOException && e.message?.contains("CreateProcess error=193") == true) {
                return AdbValidationResult(false, "Not a valid Windows executable")
            }
            return AdbValidationResult(false, e.message ?: "Unknown error")
        }
    }
    
    private fun isValidAdbPath(path: String): Boolean {
        println("ADB_Randomizer [Dialog]: isValidAdbPath called with: '$path'")
        try {
            val file = File(path)
            println("ADB_Randomizer [Dialog]: isValidAdbPath - file exists: ${file.exists()}, isFile: ${file.isFile}")
            
            if (!file.exists() || !file.isFile) {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - file does not exist or is not a file: %s", path)
                println("ADB_Randomizer [Dialog]: isValidAdbPath returning false - file not found")
                return false
            }
            
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            
            // На Windows любой .exe файл считаем исполняемым
            if (!isWindows && !file.canExecute()) {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - file is not executable: %s", path)
                println("ADB_Randomizer [Dialog]: isValidAdbPath returning false - not executable")
                return false
            }
            
            if (!file.name.equals(adbName, ignoreCase = true)) {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - wrong filename: %s", file.name)
                println("ADB_Randomizer [Dialog]: isValidAdbPath returning false - wrong filename: ${file.name}, expected: $adbName")
                return false
            }
            
            println("ADB_Randomizer [Dialog]: isValidAdbPath - trying to run ADB version")
            // Проверяем что это действительно ADB через запуск с version (без --)
            val processBuilder = ProcessBuilder(path, "version")
            // Устанавливаем рабочую директорию для корректного поиска DLL
            if (file.parentFile != null) {
                processBuilder.directory(file.parentFile)
                println("ADB_Randomizer [Dialog]: isValidAdbPath - process working directory: ${file.parentFile.absolutePath}")
            }
            
            println("ADB_Randomizer [Dialog]: isValidAdbPath - starting process...")
            val process = processBuilder.start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!finished) {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - timeout waiting for version command")
                println("ADB_Randomizer [Dialog]: isValidAdbPath - timeout waiting for ADB version")
                process.destroyForcibly()
                return false
            }
            
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - exit code %d, error: %s", exitCode, error)
                println("ADB_Randomizer [Dialog]: isValidAdbPath - ADB returned exit code: $exitCode, error: $error")
                return false
            }
            
            val output = process.inputStream.bufferedReader().readText()
            println("ADB_Randomizer [Dialog]: isValidAdbPath - ADB version output: ${output.take(100)}")
            val isValid = output.contains("Android Debug Bridge", ignoreCase = true)
            
            if (!isValid) {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation failed - output doesn't contain 'Android Debug Bridge': %s", output.take(200))
                println("ADB_Randomizer [Dialog]: isValidAdbPath returning false - output doesn't contain 'Android Debug Bridge'")
            } else {
                PluginLogger.debug(LogCategory.GENERAL, "ADB validation successful for path: %s", path)
                println("ADB_Randomizer [Dialog]: isValidAdbPath returning true - ADB is valid!")
            }
            
            return isValid
        } catch (e: Exception) {
            PluginLogger.debug(LogCategory.GENERAL, "Failed to validate ADB path '%s': %s", path, e.message ?: "Unknown error")
            println("ADB_Randomizer [Dialog]: isValidAdbPath exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}