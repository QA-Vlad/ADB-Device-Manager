package io.github.qavlad.adbrandomizer.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.AndroidStudioDetector
import io.github.qavlad.adbrandomizer.utils.FileLogger
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.services.PluginResetService
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PluginSettingsConfigurable : Configurable {
    private var settingsPanel: PluginSettingsPanel? = null
    
    override fun createComponent(): JComponent {
        settingsPanel = PluginSettingsPanel()
        return settingsPanel!!
    }
    
    override fun isModified(): Boolean {
        return settingsPanel?.isModified() ?: false
    }
    
    override fun apply() {
        settingsPanel?.apply()
    }
    
    override fun reset() {
        settingsPanel?.reset()
    }
    
    override fun getDisplayName(): String = "ADB Screen Randomizer"
    
    override fun disposeUIResources() {
        settingsPanel = null
    }
}

class PluginSettingsPanel : JBPanel<PluginSettingsPanel>(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {
    private val settings = PluginSettings.instance
    
    private val restartScrcpyCheckBox = JBCheckBox("Automatically restart scrcpy when screen resolution changes").apply {
        toolTipText = "When enabled, scrcpy will automatically restart when screen resolution is changed via presets or reset"
    }
    
    private val restartRunningDevicesCheckBox = JBCheckBox("Automatically restart Running Devices mirroring when screen resolution changes").apply {
        toolTipText = "When enabled, Android Studio's Running Devices will automatically restart when screen resolution is changed via presets or reset"
    }
    
    private val restartActiveAppCheckBox = JBCheckBox("Automatically restart active app when screen resolution changes").apply {
        toolTipText = "When enabled, the currently active app will be restarted after resolution change (excluding system apps and launchers)"
    }
    
    private val autoSwitchWifiCheckBox = JBCheckBox("Automatically switch device to PC's Wi-Fi network when connecting (requires root)").apply {
        toolTipText = "When enabled, the device will automatically switch to the same Wi-Fi network as your PC before establishing Wi-Fi connection. REQUIRES ROOT ACCESS on the device. Non-root devices will be shown Wi-Fi settings for manual switching."
    }
    
    private val debugModeCheckBox = JBCheckBox("Enable debug mode (writes logs to file)").apply {
        toolTipText = "When enabled, all plugin logs will be written to files in the plugin directory"
    }
    
    private val scrcpyPathField = JBTextField().apply {
        toolTipText = """<html>
            Path to scrcpy executable or folder containing scrcpy.<br>
            <b>Examples:</b><br>
            • C:\scrcpy\scrcpy.exe (path to executable)<br>
            • C:\scrcpy\ (path to folder)<br>
            • /usr/local/bin/scrcpy (Linux/Mac)<br>
            • C:\downloads\scrcpy-win64-v3.1.zip (ZIP archive - will be extracted)<br>
            Leave empty to use scrcpy from system PATH.
        </html>""".trimIndent()
    }
    
    private val scrcpyPathButton = JButton("Browse...").apply {
        toolTipText = "Select scrcpy executable or folder"
    }
    
    private val scrcpyPathValidationLabel = JLabel().apply {
        foreground = JBColor.GREEN
        border = JBUI.Borders.empty(2, 0, 5, 0)
        isVisible = false
    }
    
    private val scrcpyFlagsField = JBTextField().apply {
        toolTipText = """<html>
            Command line flags for scrcpy.<br>
            <b>Example:</b> --show-touches --stay-awake --always-on-top<br>
            <b>Note:</b> Use only valid scrcpy flags starting with -- or -<br>
            Invalid flags will be ignored and defaults will be used.
        </html>""".trimIndent()
        
        // Добавляем обработку фокуса для лучшего UX
        putClientProperty("JTextField.clearButton.visible", true) // Показываем кнопку очистки если поддерживается
    }
    
    private val openLogsButton = JButton("Open Logs Folder").apply {
        toolTipText = "Opens the folder containing debug log files"
        isEnabled = false
    }
    
    private val resetAllButton = JButton("Reset All Plugin Data").apply {
        toolTipText = "Resets all plugin settings, presets, and cached data to default values"
        foreground = JBColor.RED
    }
    
    init {
        createUI()
        setupListeners()
        setupFocusHandling()
        reset()
    }
    
    private fun handleZipArchive(zipFile: File, isWindows: Boolean) {
        PluginLogger.info(LogCategory.GENERAL, "Processing ZIP archive: %s", zipFile.absolutePath)
        
        // Check file size (should be less than 50 MB)
        val fileSizeMB = zipFile.length() / (1024 * 1024)
        PluginLogger.info(LogCategory.GENERAL, "Archive size: %s MB", fileSizeMB.toString())
        
        if (fileSizeMB > 50) {
            JOptionPane.showMessageDialog(
                this,
                "The selected archive is too large (${fileSizeMB} MB).\nScrcpy archives are typically less than 50 MB.\nPlease select a valid scrcpy archive.",
                "Invalid Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return
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
                this,
                "Failed to read the archive: ${e.message}",
                "Error Reading Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }
        
        if (!containsScrcpy) {
            JOptionPane.showMessageDialog(
                this,
                "The selected archive does not contain $scrcpyFileName.\nPlease select a valid scrcpy archive.",
                "Invalid Archive",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }
        
        // Show dialog asking if user wants to extract
        val message = """
            You have selected a ZIP archive containing scrcpy.
            
            The archive needs to be extracted before scrcpy can be used.
            
            Would you like to extract it automatically to:
            ${zipFile.parentFile.absolutePath}?
        """.trimIndent()
        
        val result = JOptionPane.showConfirmDialog(
            this,
            message,
            "Extract Archive",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            extractArchiveAndSetPath(zipFile, scrcpyPathInArchive!!, isWindows)
        }
    }
    
    private fun extractArchiveAndSetPath(zipFile: File, scrcpyPathInArchive: String, isWindows: Boolean) {
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
            
            // Determine the correct path to set
            val pathToSet = if (extractedScrcpy.exists()) {
                // Get the directory containing scrcpy.exe
                extractedScrcpy.parentFile.absolutePath
            } else {
                // Fallback - shouldn't happen but just in case
                extractedDir.absolutePath
            }
            
            PluginLogger.info(LogCategory.GENERAL, "Setting scrcpy path to: %s", pathToSet)
            
            // Update the path field
            scrcpyPathField.text = pathToSet
            
            JOptionPane.showMessageDialog(
                this,
                "Archive extracted successfully!\nScrcpy path has been set to:\n$pathToSet",
                "Extraction Complete",
                JOptionPane.INFORMATION_MESSAGE
            )
            
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to extract archive: ${e.message}",
                "Extraction Failed",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    private fun createUI() {
        // Screen mirroring settings
        val mirroringPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        mirroringPanel.add(restartScrcpyCheckBox)
        
        // Show Running Devices option only in Android Studio
        if (AndroidStudioDetector.isAndroidStudio()) {
            mirroringPanel.add(restartRunningDevicesCheckBox)
        }
        
        mirroringPanel.add(restartActiveAppCheckBox)
        
        // scrcpy path settings
        val scrcpyPathLabel = JLabel("scrcpy executable path:").apply {
            border = JBUI.Borders.empty(10, 0, 5, 0)
        }
        mirroringPanel.add(scrcpyPathLabel)
        
        // Panel for path field and browse button
        val scrcpyPathPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(scrcpyPathField.apply {
                preferredSize = JBUI.size(400, 30)
            })
            add(Box.createHorizontalStrut(5))
            add(scrcpyPathButton)
        }
        mirroringPanel.add(scrcpyPathPanel)
        
        // Validation label for scrcpy path
        mirroringPanel.add(scrcpyPathValidationLabel)
        
        // scrcpy flags settings
        val scrcpyFlagsLabel = JLabel("scrcpy command line flags:").apply {
            border = JBUI.Borders.empty(10, 0, 5, 0)
        }
        mirroringPanel.add(scrcpyFlagsLabel)
        
        mirroringPanel.add(scrcpyFlagsField)
        
        // Validation label for flags
        val flagsValidationLabel = JLabel().apply {
            foreground = JBColor.RED
            border = JBUI.Borders.empty(2, 0, 5, 0)
            isVisible = false
        }
        mirroringPanel.add(flagsValidationLabel)
        
        // Add validation listener
        scrcpyFlagsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateFlags()
            override fun removeUpdate(e: DocumentEvent) = validateFlags()
            override fun changedUpdate(e: DocumentEvent) = validateFlags()
            
            fun validateFlags() {
                val flags = scrcpyFlagsField.text.trim()
                if (flags.isNotBlank()) {
                    val tokens = flags.split("\\s+".toRegex())
                    val invalidGroups = mutableListOf<String>()
                    
                    // Разрешенные символы: латинские буквы, цифры, дефис, подчёркивание, точка, слэш, двоеточие, знак равно
                    val validPattern = Regex("^[a-zA-Z0-9\\-_./:=x]+$")
                    
                    var currentFlagGroup = mutableListOf<String>()
                    var groupHasInvalidToken = false
                    
                    for (token in tokens) {
                        if (token.isBlank()) continue
                        
                        // Если встретили новый флаг (начинается с -)
                        if (token.startsWith("-")) {
                            // Сохраняем предыдущую группу, если она была невалидной
                            if (currentFlagGroup.isNotEmpty() && groupHasInvalidToken) {
                                invalidGroups.add(currentFlagGroup.joinToString(" "))
                            }
                            
                            // Начинаем новую группу
                            currentFlagGroup = mutableListOf(token)
                            groupHasInvalidToken = false
                            
                            // Проверяем валидность самого флага
                            if (!token.matches(validPattern)) {
                                groupHasInvalidToken = true
                            } else {
                                // Флаг должен иметь хотя бы один символ после дефиса(ов)
                                val flagContent = token.removePrefix("--").removePrefix("-")
                                if (flagContent.isEmpty() || flagContent == "-") {
                                    groupHasInvalidToken = true
                                } else if (flagContent.matches(Regex("^[0-9]+$"))) {
                                    // Флаг не может состоять только из цифр
                                    groupHasInvalidToken = true
                                }
                            }
                        }
                        // Это значение для текущего флага
                        else {
                            // Если нет текущего флага, это отдельный невалидный токен
                            if (currentFlagGroup.isEmpty()) {
                                invalidGroups.add(token)
                            } else {
                                // Добавляем к текущей группе
                                currentFlagGroup.add(token)
                                // Проверяем валидность токена
                                if (!token.matches(validPattern)) {
                                    groupHasInvalidToken = true
                                }
                            }
                        }
                    }
                    
                    // Обрабатываем последнюю группу
                    if (currentFlagGroup.isNotEmpty() && groupHasInvalidToken) {
                        invalidGroups.add(currentFlagGroup.joinToString(" "))
                    }
                    
                    if (invalidGroups.isNotEmpty()) {
                        flagsValidationLabel.text = "⚠ Invalid arguments: ${invalidGroups.joinToString(", ")}"
                        flagsValidationLabel.isVisible = true
                    } else {
                        flagsValidationLabel.isVisible = false
                    }
                } else {
                    flagsValidationLabel.isVisible = false
                }
            }
        })
        
        // Documentation link
        val docsLink = JLabel("<html><a href=''>View scrcpy documentation</a></html>").apply {
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Opens scrcpy documentation on GitHub"
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    try {
                        Desktop.getDesktop().browse(URI("https://github.com/Genymobile/scrcpy/blob/master/README.md#features"))
                    } catch (ex: Exception) {
                        JOptionPane.showMessageDialog(
                            this@PluginSettingsPanel,
                            "Failed to open documentation: ${ex.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            })
        }
        
        // Создаём панель для ссылки, чтобы она не растягивалась на всю ширину
        val docsLinkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.emptyTop(5)
            isOpaque = false
            add(docsLink)
        }
        mirroringPanel.add(docsLinkPanel)
        
        add(mirroringPanel)
        
        // Network settings
        val networkPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        networkPanel.add(autoSwitchWifiCheckBox)
        
        add(networkPanel)
        
        // Debug settings
        val debugPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        debugPanel.add(debugModeCheckBox)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = JBUI.Borders.emptyLeft(20)
            add(openLogsButton)
        }
        debugPanel.add(buttonPanel)
        
        add(debugPanel)
        
        // Reset section
        val resetPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val resetSeparator = JSeparator()
        resetPanel.add(resetSeparator)
        
        val resetLabel = JLabel("Danger Zone").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(10, 0, 5, 0)
        }
        resetPanel.add(resetLabel)
        
        val resetButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(resetAllButton)
        }
        resetPanel.add(resetButtonPanel)
        
        add(resetPanel)
    }
    
    private fun setupFocusHandling() {
        // Добавляем обработчик клика на основную панель для снятия фокуса с текстовых полей
        val mouseListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Если клик не на текстовом поле, снимаем с него фокус
                val source = e.source
                if (source != scrcpyFlagsField && source != scrcpyPathField) {
                    // Переводим фокус на панель
                    this@PluginSettingsPanel.requestFocusInWindow()
                }
            }
        }
        
        // Добавляем слушатель на все панели
        this.addMouseListener(mouseListener)
        
        // Рекурсивно добавляем слушатель на все дочерние компоненты кроме текстовых полей и кнопок
        fun addListenerRecursively(component: java.awt.Component) {
            if (component is JPanel) {
                component.addMouseListener(mouseListener)
                for (child in component.components) {
                    if (child !is JTextField && child !is JButton && child !is JCheckBox) {
                        addListenerRecursively(child)
                    }
                }
            }
        }
        
        addListenerRecursively(this)
        
        // Делаем панель фокусируемой
        this.isFocusable = true
    }
    
    private fun setupListeners() {
        // Enable/disable open logs button based on debug mode checkbox
        debugModeCheckBox.addChangeListener {
            openLogsButton.isEnabled = debugModeCheckBox.isSelected
        }
        
        // Browse button for scrcpy path
        scrcpyPathButton.addActionListener {
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            // FileChooserDescriptor(chooseFiles, chooseFolders, chooseJars, chooseJarsAsFiles, chooseJarContents, chooseMultiple)
            val descriptor = FileChooserDescriptor(true, true, true, true, false, false).apply {
                title = "Select Scrcpy Executable, Folder Or Archive"  
                description = "Select the scrcpy executable file, the folder containing it, or a ZIP archive"
                
                // Filter to show only relevant files
                withFileFilter { virtualFile ->
                    when {
                        virtualFile.isDirectory -> true
                        virtualFile.extension?.equals("zip", ignoreCase = true) == true -> true
                        isWindows && virtualFile.extension?.equals("exe", ignoreCase = true) == true -> true
                        !isWindows && virtualFile.name.equals("scrcpy", ignoreCase = true) -> true
                        else -> false
                    }
                }
            }
            
            // Определяем начальную директорию для диалога
            val initialDir = if (scrcpyPathField.text.isNotBlank()) {
                val currentPath = File(scrcpyPathField.text)
                when {
                    currentPath.isFile && currentPath.parentFile != null -> {
                        // Если это файл, открываем папку где он находится
                        currentPath.parentFile
                    }
                    currentPath.isDirectory -> {
                        // Если это директория, открываем её саму
                        currentPath
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
            
            val chosenFile = FileChooser.chooseFile(descriptor, null, initialVirtualFile)
            PluginLogger.info(LogCategory.GENERAL, "User selected file: %s", chosenFile?.path ?: "null")
            
            if (chosenFile != null) {
                val path = chosenFile.path
                val file = File(path)
                
                PluginLogger.info(LogCategory.GENERAL, "File details - exists: %s, isFile: %s, isDirectory: %s, name: %s", 
                    file.exists().toString(), file.isFile.toString(), file.isDirectory.toString(), file.name)
                
                // Handle ZIP archives
                if (file.isFile && file.name.endsWith(".zip", ignoreCase = true)) {
                    PluginLogger.info(LogCategory.GENERAL, "Detected ZIP archive, processing...")
                    handleZipArchive(file, isWindows)
                    return@addActionListener
                }
                
                // Validate the selected path
                when {
                    file.isDirectory -> {
                        // If it's a directory, check if it contains scrcpy
                        val scrcpyExe = if (isWindows) {
                            File(file, "scrcpy.exe")
                        } else {
                            File(file, "scrcpy")
                        }
                        
                        if (scrcpyExe.exists() && scrcpyExe.canExecute()) {
                            scrcpyPathField.text = path
                        } else {
                            JOptionPane.showMessageDialog(
                                this,
                                "The selected folder does not contain a valid scrcpy executable",
                                "Invalid Path",
                                JOptionPane.WARNING_MESSAGE
                            )
                        }
                    }
                    file.isFile -> {
                        // If it's a file, check if it's an executable
                        if (file.canExecute() || (isWindows && file.name.endsWith(".exe", ignoreCase = true))) {
                            scrcpyPathField.text = path
                        } else {
                            JOptionPane.showMessageDialog(
                                this,
                                "The selected file is not an executable",
                                "Invalid File",
                                JOptionPane.WARNING_MESSAGE
                            )
                        }
                    }
                    else -> {
                        JOptionPane.showMessageDialog(
                            this,
                            "The selected path does not exist",
                            "Invalid Path",
                            JOptionPane.WARNING_MESSAGE
                        )
                    }
                }
            }
        }
        
        // Add validation listener for scrcpy path
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
                                        handleZipArchive(file, isWindows)
                                    }
                                }
                            })
                            
                            // Также предлагаем извлечь архив автоматически
                            SwingUtilities.invokeLater {
                                val result = JOptionPane.showConfirmDialog(
                                    this@PluginSettingsPanel,
                                    "You've entered a path to a ZIP archive.\nWould you like to extract it now?",
                                    "Extract Archive",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE
                                )
                                
                                if (result == JOptionPane.YES_OPTION) {
                                    handleZipArchive(file, isWindows)
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
        
        
        // Open logs folder when button clicked
        openLogsButton.addActionListener {
            try {
                val logsDir = FileLogger.getLogDirectory().toFile()
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                }
                Desktop.getDesktop().open(logsDir)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to open logs folder: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        
        // Reset all plugin data when button clicked
        resetAllButton.addActionListener {
            val result = JOptionPane.showConfirmDialog(
                this,
                """This will reset ALL plugin data including:

• All plugin settings
• All custom presets  
• WiFi device history
• Log files
• All cached data

⚠️ This action cannot be undone!

Are you sure you want to continue?""",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            
            if (result == JOptionPane.YES_OPTION) {
                try {
                    PluginResetService.resetAllPluginData()
                    
                    // Reset UI to reflect changes
                    reset()
                    
                    JOptionPane.showMessageDialog(
                        this,
                        "Plugin data has been reset to defaults.\nPlease restart the IDE for all changes to take effect.",
                        "Reset Complete",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                } catch (e: Exception) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to reset plugin data: ${e.message}",
                        "Reset Failed",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
    
    fun isModified(): Boolean {
        var modified = restartScrcpyCheckBox.isSelected != settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            modified = modified || restartRunningDevicesCheckBox.isSelected != settings.restartRunningDevicesOnResolutionChange
        }
        modified = modified || restartActiveAppCheckBox.isSelected != settings.restartActiveAppOnResolutionChange
        modified = modified || autoSwitchWifiCheckBox.isSelected != settings.autoSwitchToHostWifi
        modified = modified || debugModeCheckBox.isSelected != settings.debugMode
        modified = modified || scrcpyPathField.text != settings.scrcpyPath
        modified = modified || scrcpyFlagsField.text != settings.scrcpyCustomFlags
        return modified
    }
    
    fun apply() {
        settings.restartScrcpyOnResolutionChange = restartScrcpyCheckBox.isSelected
        if (AndroidStudioDetector.isAndroidStudio()) {
            settings.restartRunningDevicesOnResolutionChange = restartRunningDevicesCheckBox.isSelected
        }
        settings.restartActiveAppOnResolutionChange = restartActiveAppCheckBox.isSelected
        settings.autoSwitchToHostWifi = autoSwitchWifiCheckBox.isSelected
        settings.scrcpyPath = scrcpyPathField.text
        settings.scrcpyCustomFlags = scrcpyFlagsField.text
        
        val debugModeChanged = settings.debugMode != debugModeCheckBox.isSelected
        settings.debugMode = debugModeCheckBox.isSelected
        
        // Reinitialize FileLogger if debug mode changed
        if (debugModeChanged) {
            FileLogger.reinitialize()
        }
    }
    
    fun reset() {
        restartScrcpyCheckBox.isSelected = settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            restartRunningDevicesCheckBox.isSelected = settings.restartRunningDevicesOnResolutionChange
        }
        restartActiveAppCheckBox.isSelected = settings.restartActiveAppOnResolutionChange
        autoSwitchWifiCheckBox.isSelected = settings.autoSwitchToHostWifi
        debugModeCheckBox.isSelected = settings.debugMode
        
        // Синхронизируем путь scrcpy из старого хранилища если нужно
        if (settings.scrcpyPath.isBlank()) {
            val oldPath = PresetStorageService.getScrcpyPath()
            if (oldPath != null && File(oldPath).exists()) {
                settings.scrcpyPath = oldPath
            }
        }
        
        scrcpyPathField.text = settings.scrcpyPath
        scrcpyFlagsField.text = settings.scrcpyCustomFlags
        openLogsButton.isEnabled = settings.debugMode
    }
}