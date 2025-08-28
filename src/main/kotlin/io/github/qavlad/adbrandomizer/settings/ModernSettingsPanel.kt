package io.github.qavlad.adbrandomizer.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.qavlad.adbrandomizer.services.PluginResetService
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ScrcpyService
import io.github.qavlad.adbrandomizer.utils.*
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import java.awt.event.*
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

open class ModernSettingsPanel : JBPanel<ModernSettingsPanel>() {
    private val settings = PluginSettings.instance

    private val contentPanel = JPanel(CardLayout())
    private val headerLabel = JLabel()
    private val headerDescription = JLabel()
    private val categoryButtons = mutableMapOf<SettingsCategory, JButton>()
    
    // Категории настроек
    private enum class SettingsCategory(val displayName: String, val icon: Icon, val description: String) {
        CONNECTION("ADB", AllIcons.Nodes.DataTables, "ADB and device connection settings"),
        DISPLAY("Mirroring", AllIcons.General.FitContent, "Screen mirroring and display settings"),
        NETWORK("Wi-Fi", AllIcons.General.Web, "WiFi and network configuration"),
        DEVELOPER("Developer", AllIcons.Nodes.Tag, "Advanced developer settings"),
        ABOUT("About", AllIcons.Actions.Help, "Plugin information and support")
    }
    
    // UI компоненты для настроек
    private val adbPathField = createModernTextField().apply {
        toolTipText = "Path to ADB executable or its parent directory. Leave empty for auto-detection."
    }
    private val adbPathButton = createModernButton("Browse", AllIcons.General.OpenDisk).apply {
        toolTipText = "Browse for ADB executable or folder"
    }
    private val adbPathStatus = createStatusLabel()
    private val adbPortField = createModernTextField().apply {
        toolTipText = "TCP/IP port for wireless device connections (default: 5555)"
    }
    private val maxDpiField = createModernTextField().apply {
        toolTipText = "Maximum DPI value that can be applied to prevent device crashes (default: 640)"
    }
    private var dangerousDpiConfirmed = false // Track if user confirmed dangerous DPI value
    
    private val restartScrcpySwitch = createModernSwitch("Auto-restart scrcpy").apply {
        toolTipText = "Automatically restart scrcpy when device resolution changes"
    }
    private val restartRunningDevicesSwitch = createModernSwitch("Auto-restart Running Devices").apply {
        toolTipText = "Automatically restart Android Studio's Running Devices panel"
    }
    private val restartActiveAppSwitch = createModernSwitch("Auto-restart active app").apply {
        toolTipText = "Restart the currently running app after resolution change"
    }
    private val scrcpyPathField = createModernTextField().apply {
        toolTipText = "Path to scrcpy executable or installation directory"
    }
    private val scrcpyPathButton = createModernButton("Browse", AllIcons.General.OpenDisk).apply {
        toolTipText = "Browse for scrcpy executable or installation folder"
    }
    private val scrcpyPathStatus = createStatusLabel()
    private val scrcpyFlagsField = createModernTextField().apply {
        toolTipText = "Additional command-line arguments for scrcpy (e.g., --show-touches --stay-awake)"
    }
    
    private val wifiModeGroup = ButtonGroup()
    private val noWifiOption = createModernRadio("Manual network management").apply {
        toolTipText = "WiFi networks are managed manually, no automatic switching"
    }
    private val deviceToHostOption = createModernRadio("Device follows PC network").apply {
        toolTipText = "Device automatically switches to PC's WiFi network (requires root)"
    }
    private val hostToDeviceOption = createModernRadio("PC follows device network").apply {
        toolTipText = "PC automatically switches to device's WiFi network (requires admin privileges)"
    }
    
    private val debugModeSwitch = createModernSwitch("Enable debug logging").apply {
        toolTipText = "Enable detailed logging for troubleshooting issues"
    }
    private val hitboxesSwitch = createModernSwitch("Show UI hitbox overlays").apply {
        toolTipText = "Display visual boundaries of UI elements for debugging"
    }
    private val simulateAdbNotFoundSwitch = createModernSwitch("Simulate ADB not found").apply {
        toolTipText = "Make plugin behave as if ADB is not installed (for testing)"
    }
    private val simulateScrcpyNotFoundSwitch = createModernSwitch("Simulate Scrcpy not found").apply {
        toolTipText = "Make plugin behave as if Scrcpy is not installed (for testing)"
    }
    private val openLogsButton = createModernButton("Open Logs", AllIcons.Actions.Show).apply {
        toolTipText = "Open the plugin's log directory"
    }
    private val resetButton = createDangerButton().apply {
        toolTipText = "Reset ALL plugin settings, presets, and cached data to defaults"
    }
    
    init {
        layout = BorderLayout()
        background = getBackgroundColor()
        preferredSize = JBUI.size(700, 450)
        maximumSize = JBUI.size(800, 500)
        
        setupUI()
        setupListeners()
        resetSettings()
        
        // Enable focus traversal for the panel
        isFocusable = true
        
        // Add mouse listener to clear focus when clicking on empty space
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                requestFocusInWindow()
            }
        })
    }
    
    private fun setupUI() {
        // Создаём верхнюю панель с навигацией
        val topPanel = JPanel(BorderLayout()).apply {
            background = getBackgroundColor()
            
            // Навигационная панель с кнопками
            add(createNavigationPanel(), BorderLayout.NORTH)
            
            // Заголовок текущей категории
            val headerPanel = createHeaderPanel()
            add(headerPanel, BorderLayout.CENTER)
        }
        
        // Основная панель контента
        val mainPanel = JPanel(BorderLayout()).apply {
            background = getBackgroundColor()
            
            // Контент с отступами
            val contentWrapper = JPanel(BorderLayout()).apply {
                background = getBackgroundColor()
                border = JBUI.Borders.empty(8)
                add(contentPanel, BorderLayout.CENTER)
            }
            add(contentWrapper, BorderLayout.CENTER)
        }
        
        // Создаём карточки для каждой категории
        SettingsCategory.values().forEach { category ->
            contentPanel.add(createCategoryPanel(category), category.name)
        }
        
        // Добавляем панели на основную панель
        add(topPanel, BorderLayout.NORTH)
        add(mainPanel, BorderLayout.CENTER)
        
        // Выбираем первую категорию
        selectCategory(SettingsCategory.CONNECTION)
    }
    
    private fun createNavigationPanel(): JPanel {
        return JPanel().apply {
            layout = FlowLayout(FlowLayout.CENTER, 0, 0)
            background = JBColor(Color(248, 249, 251), Color(40, 42, 44))
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 10)
            )
            
            // Создаём кнопки для каждой категории
            SettingsCategory.values().forEach { category ->
                val button = createNavigationButton(category)
                categoryButtons[category] = button
                add(button)
                
                // Добавляем небольшой отступ между кнопками
                if (category != SettingsCategory.values().last()) {
                    add(Box.createHorizontalStrut(8))
                }
            }
        }
    }
    
    @Suppress("UseJBColor", "UseGrayConstants")
    private fun createNavigationButton(category: SettingsCategory): JButton {
        return JButton(category.displayName, category.icon).apply {
            isOpaque = true
            isFocusPainted = false
            font = UIUtil.getLabelFont().deriveFont(13f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = category.description
            
            // Начальный стиль
            border = JBUI.Borders.compound(
                RoundedBorder(16, JBColor.border()),
                JBUI.Borders.empty(5, 12)
            )
            background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Gray._230
            foreground = if (UIUtil.isUnderDarcula()) Gray._187 else Color.BLACK
            
            addActionListener {
                selectCategory(category)
            }
            
            // Hover эффект
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!isSelected) {
                        background = JBColor(Gray._210, Color(55, 57, 59))  // Более тёмный при наведении
                    }
                }
                
                override fun mouseExited(e: MouseEvent) {
                    if (!isSelected) {
                        background = JBColor(Gray._230, Color(60, 63, 65))  // Возврат к начальному цвету
                    }
                }
                
                private val isSelected: Boolean
                    get() = categoryButtons.entries.find { it.value == this@apply }?.key == currentCategory
            })
        }
    }
    
    private var currentCategory: SettingsCategory? = null
    
    @Suppress("UseJBColor", "UseGrayConstants")
    private fun selectCategory(category: SettingsCategory) {
        currentCategory = category
        
        // Обновляем стили кнопок
        categoryButtons.forEach { (cat, button) ->
            if (cat == category) {
                // Выбранная кнопка
                button.background = if (UIUtil.isUnderDarcula()) Color(80, 150, 255) else Color(66, 133, 244)
                button.foreground = if (UIUtil.isUnderDarcula()) Color.WHITE else Color.BLACK  // Чёрный текст для светлой темы
                button.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
                button.border = JBUI.Borders.compound(
                    RoundedBorder(16, if (UIUtil.isUnderDarcula()) Color(80, 150, 255) else Color(66, 133, 244)),
                    JBUI.Borders.empty(5, 12)
                )
            } else {
                // Невыбранные кнопки
                button.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Gray._230
                button.foreground = if (UIUtil.isUnderDarcula()) Gray._187 else Color.BLACK
                button.font = UIUtil.getLabelFont().deriveFont(13f)
                button.border = JBUI.Borders.compound(
                    RoundedBorder(16, JBColor.border()),
                    JBUI.Borders.empty(5, 12)
                )
            }
        }
        
        // Показываем соответствующую категорию
        showCategory(category)
    }
    
    
    private fun createHeaderPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = getBackgroundColor()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(10, 15)
            )
            
            headerLabel.apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 16f)
                foreground = JBColor.foreground()
                alignmentX = LEFT_ALIGNMENT
            }
            add(headerLabel)
            
            add(Box.createVerticalStrut(3))
            
            headerDescription.apply {
                font = UIUtil.getLabelFont().deriveFont(13f)
                foreground = JBColor.GRAY
                alignmentX = LEFT_ALIGNMENT
            }
            add(headerDescription)
        }
    }
    
    private fun createCategoryPanel(category: SettingsCategory): JPanel {
        return when (category) {
            SettingsCategory.CONNECTION -> createConnectionPanel()
            SettingsCategory.DISPLAY -> createDisplayPanel()
            SettingsCategory.NETWORK -> createNetworkPanel()
            SettingsCategory.DEVELOPER -> createDeveloperPanel()
            SettingsCategory.ABOUT -> createAboutPanel()
        }
    }
    
    private fun createConnectionPanel(): JPanel {
        return createScrollablePanel {
            addCard("Android Debug Bridge", "Configure ADB executable and connection settings") {
                addLabeledField("ADB Executable Path", 
                    "Path to adb.exe or directory containing it",
                    adbPathField, adbPathButton, adbPathStatus
                )
                
                addSpace(8)
                
                addLabeledField("Device TCP/IP Port", 
                    "Port for wireless device connections (default: 5555)",
                    adbPortField
                )
                
                addSpace(8)
                
                addLabeledField("Maximum DPI Limit",
                    "Maximum DPI value allowed to prevent device crashes (default: 640)",
                    maxDpiField
                )
                
                addSpace(8)
                addInfoBox("💡 Leave fields empty to use default values (auto-detect ADB, port 5555, max DPI 640)")
            }
        }
    }
    
    private fun createDisplayPanel(): JPanel {
        return createScrollablePanel {
            addCard("Auto-Restart Behavior", "Configure when to restart display connections") {
                addSwitch(restartScrcpySwitch, 
                    "Automatically restarts scrcpy when device resolution changes")
                
                if (AndroidStudioDetector.isAndroidStudio()) {
                    addSwitch(restartRunningDevicesSwitch,
                        "Automatically restarts Running Devices panel on resolution change")
                }
                
                addSwitch(restartActiveAppSwitch,
                    "Automatically restarts the active app after resolution change")
            }
            
            addCard("Scrcpy Configuration", "Screen mirroring tool settings") {
                addLabeledField("Scrcpy Path",
                    "Path to scrcpy executable or installation directory",
                    scrcpyPathField, scrcpyPathButton, scrcpyPathStatus
                )
                
                addSpace(8)
                
                addLabeledField("Command Flags",
                    "Additional command-line arguments for scrcpy",
                    scrcpyFlagsField
                )
                
                addSpace(8)
                addInfoBox("💡 Leave scrcpy path empty to auto-detect from system PATH")
                
                addSpace(8)
                addLinkButton("View scrcpy documentation →") {
                    Desktop.getDesktop().browse(URI("https://github.com/Genymobile/scrcpy"))
                }
            }
        }
    }
    
    private fun createNetworkPanel(): JPanel {
        return createScrollablePanel {
            addCard("WiFi Network Synchronization", "Configure automatic network switching") {
                wifiModeGroup.add(noWifiOption)
                wifiModeGroup.add(deviceToHostOption)
                wifiModeGroup.add(hostToDeviceOption)
                
                addRadioOption(noWifiOption,
                    "Networks are managed manually, no automatic switching")
                
                addRadioOption(deviceToHostOption,
                    "Device automatically switches to PC's WiFi network (requires root)")
                
                addRadioOption(hostToDeviceOption,
                    "PC automatically switches to device's WiFi network (requires admin)")
                
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    hostToDeviceOption.isEnabled = false
                    addSpace(10)
                    addWarningBox("⚠️ PC-to-device switching is not supported on macOS")
                }
            }
        }
    }
    
    private fun createDeveloperPanel(): JPanel {
        return createScrollablePanel {
            addCard("Debug Options", "Developer and diagnostic settings") {
                addSwitch(hitboxesSwitch,
                    "Shows visual overlays for UI element boundaries")
                
                addSwitch(debugModeSwitch,
                    "Enables detailed logging for troubleshooting")
                
                addSpace(10)
                
                addButton(openLogsButton) {
                    try {
                        val logsDir = FileLogger.getLogDirectory().toFile()
                        if (!logsDir.exists()) logsDir.mkdirs()
                        Desktop.getDesktop().open(logsDir)
                    } catch (e: Exception) {
                        showError("Failed to open logs: ${e.message}")
                    }
                }
            }
            
            addCard("Testing Options", "Simulate errors for testing") {
                addSwitch(simulateAdbNotFoundSwitch,
                    "Simulate ADB not found (for testing ADB configuration dialog)")
                
                addSwitch(simulateScrcpyNotFoundSwitch, 
                    "Simulate Scrcpy not found (for testing Scrcpy compatibility dialog)")
                
                addSpace(8)
                
                addInfoBox("⚠️ These options make the plugin behave as if ADB/Scrcpy are not installed")
            }
            
            addCard("Danger Zone", "Actions that cannot be undone") {
                addWarningBox("⚠️ This will reset ALL plugin settings, presets, and cached data")
                
                addSpace(8)
                
                addButton(resetButton) {
                    handleResetAll()
                }
            }
        }
    }
    
    private fun createAboutPanel(): JPanel {
        return createScrollablePanel {
            addCenteredContent {
                add(JLabel(AllIcons.Nodes.Plugin).apply {
                    alignmentX = CENTER_ALIGNMENT
                })
                
                add(Box.createVerticalStrut(10))
                
                add(JLabel("ADB Screen Randomizer").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 36f)
                    alignmentX = CENTER_ALIGNMENT
                })
                
                add(Box.createVerticalStrut(8))
                
                add(JLabel("Version 1.0.0").apply {
                    font = UIUtil.getLabelFont().deriveFont(24f)
                    foreground = JBColor.GRAY
                    alignmentX = CENTER_ALIGNMENT
                })
                
                add(Box.createVerticalStrut(30))
                
                // Create panel for text with gradient effect
                add(createGradientTextPanel())
                
                add(Box.createVerticalStrut(15))
                
                val linksPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }
                
                linksPanel.add(createLinkLabel("🔗 GitHub Repository").apply {
                    font = UIUtil.getLabelFont().deriveFont(15f)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            Desktop.getDesktop().browse(URI("https://github.com/qa-vlad/adb-screen-randomizer"))
                        }
                    })
                })
                
                linksPanel.add(Box.createVerticalStrut(12))
                
                linksPanel.add(createLinkLabel("🐛 Report an Issue").apply {
                    font = UIUtil.getLabelFont().deriveFont(15f)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            Desktop.getDesktop().browse(URI("https://github.com/qa-vlad/adb-screen-randomizer/issues"))
                        }
                    })
                })
                
                linksPanel.add(Box.createVerticalStrut(12))
                
                linksPanel.add(createLinkLabel("📧 Contact Developer").apply {
                    font = UIUtil.getLabelFont().deriveFont(15f)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            Desktop.getDesktop().browse(URI("https://qa-vlad.github.io/MY_CV/en/"))
                        }
                    })
                })
                
                add(linksPanel)
                
                add(Box.createVerticalStrut(30))
                
                add(JLabel("Created with ❤️ by Vladlen Kuznetsov").apply {
                    font = UIUtil.getLabelFont().deriveFont(20f)
                    foreground = JBColor.GRAY
                    alignmentX = CENTER_ALIGNMENT
                })
            }
        }
    }
    
    // UI компоненты-хелперы
    private fun createModernTextField(): JBTextField {
        return JBTextField().apply {
            val defaultBorder = JBUI.Borders.compound(
                RoundedBorder(8, JBColor.border()),
                JBUI.Borders.empty(5, 10)
            )
            border = defaultBorder
            font = UIUtil.getLabelFont().deriveFont(13f)
            isFocusable = true
            putClientProperty("JTextField.variant", "search")
            
            // Add hover and focus effects
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (!hasFocus()) {
                        border = JBUI.Borders.compound(
                            RoundedBorder(8, JBColor(Color(100, 150, 250), Color(80, 120, 200))),
                            JBUI.Borders.empty(5, 10)
                        )
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (!hasFocus()) {
                        border = defaultBorder
                    }
                }
            })
            
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    border = JBUI.Borders.compound(
                        RoundedBorder(8, JBColor(Color(66, 133, 244), Color(80, 150, 255))),
                        JBUI.Borders.empty(5, 10)
                    )
                }
                override fun focusLost(e: FocusEvent) {
                    border = defaultBorder
                }
            })
        }
    }
    
    private fun createModernButton(text: String, icon: Icon? = null): JButton {
        return JButton(text, icon).apply {
            isOpaque = false
            isFocusPainted = false
            border = JBUI.Borders.compound(
                RoundedBorder(6, JBColor.border()),
                JBUI.Borders.empty(6, 12)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = JBColor(Gray._230, Gray._60)
                    border = JBUI.Borders.compound(
                        RoundedBorder(6, JBColor(Color(100, 150, 250), Color(80, 120, 200))),
                        JBUI.Borders.empty(6, 12)
                    )
                }
                override fun mouseExited(e: MouseEvent) {
                    background = UIUtil.getPanelBackground()
                    border = JBUI.Borders.compound(
                        RoundedBorder(6, JBColor.border()),
                        JBUI.Borders.empty(6, 12)
                    )
                }
            })
        }
    }
    
    @Suppress("UseJBColor", "UseGrayConstants")
    private fun createDangerButton(): JButton {
        return JButton("Reset All Settings", AllIcons.General.Reset).apply {
            isOpaque = true
            isFocusPainted = false
            foreground = if (UIUtil.isUnderDarcula()) Color.WHITE else Color.BLACK  // Чёрный текст для светлой темы
            background = if (UIUtil.isUnderDarcula()) Color(176, 42, 55) else Color(220, 53, 69)
            border = if (UIUtil.isUnderDarcula()) {
                JBUI.Borders.compound(
                    RoundedBorder(6, Color(176, 42, 55), 2),  // Красная обводка для тёмной темы
                    JBUI.Borders.empty(6, 12)
                )
            } else {
                JBUI.Borders.compound(
                    RoundedBorder(6, Gray._180, 1),  // Серая обводка для светлой темы
                    JBUI.Borders.empty(6, 12)
                )
            }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    // Более яркий/тёмный красный при наведении
                    background = if (UIUtil.isUnderDarcula()) Color(220, 70, 85) else Color(255, 100, 110)  // Светлее для светлой темы
                    border = if (UIUtil.isUnderDarcula()) {
                        JBUI.Borders.compound(
                            RoundedBorder(6, Color(240, 90, 105), 2),  // Красная обводка для тёмной темы
                            JBUI.Borders.empty(6, 12)
                        )
                    } else {
                        JBUI.Borders.compound(
                            RoundedBorder(6, Gray._150, 1),  // Более тёмная серая обводка при hover
                            JBUI.Borders.empty(6, 12)
                        )
                    }
                    foreground = if (UIUtil.isUnderDarcula()) Color.WHITE else Color.BLACK  // Чёрный текст для светлой темы при hover
                }
                override fun mouseExited(e: MouseEvent) {
                    background = if (UIUtil.isUnderDarcula()) Color(176, 42, 55) else Color(220, 53, 69)
                    border = if (UIUtil.isUnderDarcula()) {
                        JBUI.Borders.compound(
                            RoundedBorder(6, Color(176, 42, 55), 2),  // Красная обводка для тёмной темы
                            JBUI.Borders.empty(6, 12)
                        )
                    } else {
                        JBUI.Borders.compound(
                            RoundedBorder(6, Gray._180, 1),  // Серая обводка для светлой темы
                            JBUI.Borders.empty(6, 12)
                        )
                    }
                    foreground = if (UIUtil.isUnderDarcula()) Color.WHITE else Color.BLACK
                }
            })
        }
    }
    
    private fun createModernSwitch(text: String): JBCheckBox {
        return JBCheckBox(text).apply {
            isOpaque = false
            font = UIUtil.getLabelFont().deriveFont(13f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Add hover effect
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    foreground = JBColor(Color(0, 100, 200), Color(100, 180, 255))
                }
                override fun mouseExited(e: MouseEvent) {
                    foreground = JBColor.foreground()
                }
            })
        }
    }
    
    private fun createModernRadio(text: String): JRadioButton {
        return JRadioButton(text).apply {
            isOpaque = false
            font = UIUtil.getLabelFont().deriveFont(13f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Add hover effect
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    foreground = JBColor(Color(0, 100, 200), Color(100, 180, 255))
                }
                override fun mouseExited(e: MouseEvent) {
                    foreground = JBColor.foreground()
                }
            })
        }
    }
    
    private fun createStatusLabel(): JLabel {
        return JLabel().apply {
            font = UIUtil.getLabelFont().deriveFont(11f)
            isVisible = false
        }
    }
    
    private fun createScrollablePanel(content: PanelBuilder.() -> Unit): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = getBackgroundColor()
        
        // Add mouse listener to clear focus when clicking on panel
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                panel.requestFocusInWindow()
            }
        })
        
        val builder = PanelBuilder(panel)
        builder.content()
        
        // Добавляем пружину внизу
        panel.add(Box.createVerticalGlue())
        
        val scrollPane = JBScrollPane(panel).apply {
            border = null
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.background = getBackgroundColor()
        }
        
        return JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            // Also add listener to wrapper panel
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    requestFocusInWindow()
                }
            })
        }
    }
    
    // Builder для панелей
    private class PanelBuilder(private val panel: JPanel) {
        fun addCard(title: String, description: String, content: CardBuilder.() -> Unit) {
            val card = createCard().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                
                // Заголовок карточки
                add(JLabel(title).apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
                    alignmentX = LEFT_ALIGNMENT
                })
                
                add(JLabel(description).apply {
                    font = UIUtil.getLabelFont().deriveFont(10f)
                    foreground = JBColor.GRAY
                    alignmentX = LEFT_ALIGNMENT
                })
                
                add(Box.createVerticalStrut(3))
                
                val separator = JSeparator().apply {
                    alignmentX = LEFT_ALIGNMENT
                    maximumSize = Dimension(Integer.MAX_VALUE, 1)
                }
                add(separator)
                
                add(Box.createVerticalStrut(3))
            }
            
            val cardBuilder = CardBuilder(card)
            cardBuilder.content()
            
            panel.add(card)
            panel.add(Box.createVerticalStrut(5))
        }
        
        fun addCenteredContent(content: JPanel.() -> Unit) {
            val centeredPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = CENTER_ALIGNMENT
            }
            
            centeredPanel.content()
            
            val wrapper = JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(centeredPanel)
            }
            
            panel.add(wrapper)
        }
        
        private fun createCard(): JPanel {
            return JPanel().apply {
                background = JBColor(Color.WHITE, Color(60, 63, 65))
                border = JBUI.Borders.compound(
                    RoundedBorder(8, JBColor.border()),
                    JBUI.Borders.empty(10)
                )
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Integer.MAX_VALUE, preferredSize.height)
            }
        }
    }
    
    private class CardBuilder(private val card: JPanel) {
        fun addLabeledField(label: String, description: String? = null, vararg components: JComponent) {
            card.add(JLabel(label).apply {
                font = UIUtil.getLabelFont().deriveFont(12f)
                alignmentX = LEFT_ALIGNMENT
            })
            
            if (description != null) {
                card.add(Box.createVerticalStrut(1))
                card.add(JLabel(description).apply {
                    font = UIUtil.getLabelFont().deriveFont(11f)
                    foreground = JBColor.GRAY
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            
            card.add(Box.createVerticalStrut(3))
            
            val fieldPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Integer.MAX_VALUE, 35)
                
                components.forEach { comp ->
                    add(comp)
                    if (comp != components.last()) {
                        add(Box.createHorizontalStrut(8))
                    }
                }
            }
            card.add(fieldPanel)
        }
        
        fun addSwitch(switch: JBCheckBox, description: String) {
            card.add(switch.apply {
                alignmentX = LEFT_ALIGNMENT
            })
            
            card.add(Box.createVerticalStrut(1))
            
            card.add(JLabel(description).apply {
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = JBColor.GRAY
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 25, 3, 0)
            })
        }
        
        fun addRadioOption(radio: JRadioButton, description: String) {
            card.add(radio.apply {
                alignmentX = LEFT_ALIGNMENT
            })
            
            card.add(Box.createVerticalStrut(1))
            
            card.add(JLabel(description).apply {
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = JBColor.GRAY
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 25, 3, 0)
            })
        }
        
        fun addButton(button: JButton, action: () -> Unit = {}) {
            button.addActionListener { action() }
            button.alignmentX = LEFT_ALIGNMENT
            card.add(button)
        }
        
        fun addSpace(height: Int) {
            card.add(Box.createVerticalStrut(height))
        }
        
        fun addInfoBox(text: String) {
            card.add(createInfoPanel(text))
        }
        
        fun addWarningBox(text: String) {
            card.add(createWarningPanel(text))
        }
        
        fun addLinkButton(text: String, action: () -> Unit) {
            val link = JLabel("<html><a href='#'>$text</a></html>").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = JBColor.BLUE
                alignmentX = LEFT_ALIGNMENT
            }
            link.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = action()
            })
            card.add(link)
        }
        
        private fun createInfoPanel(text: String): JPanel {
            return JPanel(BorderLayout()).apply {
                background = JBColor(Color(230, 240, 255), Color(45, 55, 70))
                border = JBUI.Borders.compound(
                    RoundedBorder(6, JBColor(Color(70, 130, 255), Color(100, 150, 255)), 3),
                    JBUI.Borders.empty(8, 10)
                )
                alignmentX = LEFT_ALIGNMENT
                preferredSize = Dimension(Integer.MAX_VALUE, 36)
                minimumSize = Dimension(100, 36)
                maximumSize = Dimension(Integer.MAX_VALUE, 50)
                
                add(JLabel(text).apply {
                    font = UIUtil.getLabelFont().deriveFont(12f)
                    foreground = JBColor(Color(0, 70, 140), Color(120, 180, 240))
                }, BorderLayout.CENTER)
            }
        }
        
        private fun createWarningPanel(text: String): JPanel {
            return JPanel(BorderLayout()).apply {
                background = JBColor(Color(255, 245, 240), Color(60, 50, 40))
                border = JBUI.Borders.compound(
                    RoundedBorder(6, JBColor(Color(255, 200, 150), Color(150, 100, 50)), 2),
                    JBUI.Borders.empty(8, 10)
                )
                alignmentX = LEFT_ALIGNMENT
                preferredSize = Dimension(Integer.MAX_VALUE, 36)
                minimumSize = Dimension(100, 36)
                maximumSize = Dimension(Integer.MAX_VALUE, 50)
                
                add(JLabel(text).apply {
                    font = UIUtil.getLabelFont().deriveFont(12f)
                    foreground = JBColor(Color(200, 80, 0), Color(255, 150, 50))
                }, BorderLayout.CENTER)
            }
        }
    }
    
    // Custom border для закруглённых углов
    private class RoundedBorder(private val radius: Int, private val color: Color, private val thickness: Int = 1) : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = BasicStroke(thickness.toFloat())
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
        }
        
        override fun getBorderInsets(c: Component): Insets = JBUI.insets(1)
    }
    
    
    // Вспомогательные методы
    private fun showCategory(category: SettingsCategory) {
        headerLabel.text = category.displayName
        headerDescription.text = category.description
        (contentPanel.layout as CardLayout).show(contentPanel, category.name)
    }

    private fun createLinkLabel(text: String): JLabel {
        return JLabel("<html><a href='#'>$text</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = JBColor(Color(0, 120, 215), Color(100, 180, 255))
            font = UIUtil.getLabelFont().deriveFont(13f)
            alignmentX = CENTER_ALIGNMENT
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    foreground = JBColor(Color(0, 100, 200), Color(150, 200, 255))
                }
                override fun mouseExited(e: MouseEvent) {
                    foreground = JBColor(Color(0, 120, 215), Color(100, 180, 255))
                }
            })
        }
    }
    
    private fun getBackgroundColor(): Color = UIUtil.getPanelBackground()
    
    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
    }
    
    private fun createGradientTextPanel(): JPanel {
        return JPanel().apply {
            layout = FlowLayout(FlowLayout.CENTER, 0, 0)
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            
            add(JLabel("A powerful ").apply {
                font = UIUtil.getLabelFont().deriveFont(21f)
                foreground = JBColor.GRAY
            })
            
            // IntelliJ with purple-magenta gradient colors
            add(JLabel("IntelliJ").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 21f)
                foreground = JBColor(Color(156, 39, 176), Color(186, 85, 211))
            })
            
            add(JLabel(" plugin for Android ").apply {
                font = UIUtil.getLabelFont().deriveFont(21f)
                foreground = JBColor.GRAY
            })
            
            // QA with gradient-like green-yellow colors
            add(JLabel("QA").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 21f)
                foreground = JBColor(Color(52, 168, 83), Color(100, 200, 120))
            })
            
            add(JLabel(" testing").apply {
                font = UIUtil.getLabelFont().deriveFont(21f)
                foreground = JBColor.GRAY
            })
        }
    }
    
    // Методы настроек
    private fun setupListeners() {
        // ADB path browse
        adbPathButton.addActionListener {
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val descriptor = FileChooserDescriptor(true, true, false, false, false, false).apply {
                title = "Select ADB Executable or Folder"
                withFileFilter { virtualFile ->
                    virtualFile.isDirectory || 
                    (isWindows && virtualFile.name.equals("adb.exe", ignoreCase = true)) ||
                    (!isWindows && virtualFile.name.equals("adb", ignoreCase = true))
                }
            }
            
            // Определяем начальную директорию
            val currentPath = adbPathField.text.trim()
            val initialDir = if (currentPath.isNotBlank()) {
                val path = File(currentPath)
                when {
                    path.exists() && path.isFile && path.parentFile != null -> path.parentFile
                    path.exists() && path.isDirectory -> path
                    else -> {
                        // Если путь не существует, начинаем с корня диска C:\ на Windows или домашней директории
                        if (isWindows) File("C:\\") else File(System.getProperty("user.home"))
                    }
                }
            } else {
                // Если поле пустое, начинаем с корня диска C:\ на Windows или домашней директории
                if (isWindows) File("C:\\") else File(System.getProperty("user.home"))
            }
            
            val initialVirtualFile = initialDir?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(dir)
                } else {
                    null
                }
            }
            
            FileChooser.chooseFile(descriptor, null, initialVirtualFile)?.let {
                adbPathField.text = it.path
            }
        }
        
        // Scrcpy path browse
        scrcpyPathButton.addActionListener {
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val descriptor = FileChooserDescriptor(true, true, true, true, false, false).apply {
                title = "Select Scrcpy Executable, Folder or Archive"
            }
            
            // Определяем начальную директорию
            val currentPath = scrcpyPathField.text.trim()
            val initialDir = if (currentPath.isNotBlank()) {
                val path = File(currentPath)
                when {
                    path.exists() && path.isFile && path.parentFile != null -> path.parentFile
                    path.exists() && path.isDirectory -> path
                    else -> {
                        // Если путь не существует, начинаем с корня диска C:\ на Windows или домашней директории
                        if (isWindows) File("C:\\") else File(System.getProperty("user.home"))
                    }
                }
            } else {
                // Если поле пустое, начинаем с корня диска C:\ на Windows или домашней директории
                if (isWindows) File("C:\\") else File(System.getProperty("user.home"))
            }
            
            val initialVirtualFile = initialDir?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(dir)
                } else {
                    null
                }
            }
            
            FileChooser.chooseFile(descriptor, null, initialVirtualFile)?.let {
                scrcpyPathField.text = it.path
            }
        }
        
        // Debug mode toggle
        debugModeSwitch.addChangeListener {
            val isEnabled = debugModeSwitch.isSelected
            openLogsButton.isEnabled = isEnabled
        }
        
        // Open logs
        openLogsButton.addActionListener {
            try {
                val logsDir = FileLogger.getLogDirectory().toFile()
                if (!logsDir.exists()) logsDir.mkdirs()
                Desktop.getDesktop().open(logsDir)
            } catch (e: Exception) {
                showError("Failed to open logs: ${e.message}")
            }
        }
        
        // ADB path validation
        adbPathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateAdbPath()
            override fun removeUpdate(e: DocumentEvent) = validateAdbPath()
            override fun changedUpdate(e: DocumentEvent) = validateAdbPath()
            
            private fun validateAdbPath() {
                val path = adbPathField.text.trim()
                
                if (path.isBlank()) {
                    val autoPath = AdbPathResolver.findAdbExecutable()
                    if (autoPath != null) {
                        adbPathStatus.text = "✓ Auto-detected"
                        adbPathStatus.foreground = JBColor.GREEN
                    } else {
                        adbPathStatus.text = "⚠ Not found in PATH"
                        adbPathStatus.foreground = JBColor.YELLOW
                    }
                } else {
                    val file = File(path)
                    when {
                        !file.exists() -> {
                            adbPathStatus.text = "✗ Path not found"
                            adbPathStatus.foreground = JBColor.RED
                        }
                        file.isDirectory && !File(file, "adb.exe").exists() && !File(file, "adb").exists() -> {
                            adbPathStatus.text = "✗ ADB not found in directory"
                            adbPathStatus.foreground = JBColor.RED
                        }
                        else -> {
                            adbPathStatus.text = "✓ Valid"
                            adbPathStatus.foreground = JBColor.GREEN
                        }
                    }
                }
                adbPathStatus.isVisible = true
            }
        })
        
        // Auto-restore default values on focus lost
        adbPathField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                SwingUtilities.invokeLater {
                    if (adbPathField.text.trim().isBlank()) {
                        val autoPath = AdbPathResolver.findAdbExecutable()
                        if (autoPath != null) {
                            val parent = File(autoPath).parent
                            if (parent != null) {
                                adbPathField.text = parent
                            }
                        }
                    }
                }
            }
        })
        
        adbPortField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                SwingUtilities.invokeLater {
                    if (adbPortField.text.trim().isBlank()) {
                        adbPortField.text = "5555"
                    }
                }
            }
        })
        
        // Reset confirmation flag when text changes
        maxDpiField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                dangerousDpiConfirmed = false
            }
            override fun removeUpdate(e: DocumentEvent) {
                dangerousDpiConfirmed = false
            }
            override fun changedUpdate(e: DocumentEvent) {
                dangerousDpiConfirmed = false
            }
        })
        
        maxDpiField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                SwingUtilities.invokeLater {
                    val text = maxDpiField.text.trim()
                    if (text.isBlank()) {
                        maxDpiField.text = "640"
                        dangerousDpiConfirmed = false
                    } else {
                        val value = text.toIntOrNull()
                        if (value != null && value > 999 && !dangerousDpiConfirmed) {
                            val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                                maxDpiField,
                                "Setting maximum DPI limit above 999 can cause devices to crash or reboot.\n\n" +
                                "Values above 999 are extremely dangerous and may require factory reset to recover.\n" +
                                "Are you absolutely sure you want to set the limit to $value?",
                                "Dangerous DPI Limit Warning",
                                com.intellij.openapi.ui.Messages.getWarningIcon()
                            )
                            if (result == com.intellij.openapi.ui.Messages.YES) {
                                dangerousDpiConfirmed = true
                            } else {
                                maxDpiField.text = "640"
                                dangerousDpiConfirmed = false
                            }
                        } else if (value != null && value <= 999) {
                            dangerousDpiConfirmed = false
                        }
                    }
                }
            }
        })
        
        // Scrcpy path validation
        scrcpyPathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = validateScrcpyPath()
            override fun removeUpdate(e: DocumentEvent) = validateScrcpyPath()
            override fun changedUpdate(e: DocumentEvent) = validateScrcpyPath()
            
            private fun validateScrcpyPath() {
                val path = scrcpyPathField.text.trim()
                val isWindows = System.getProperty("os.name").startsWith("Windows")
                val scrcpyName = if (isWindows) "scrcpy.exe" else "scrcpy"
                
                if (path.isBlank()) {
                    // Try to auto-detect scrcpy
                    val autoPath = ScrcpyService.findScrcpyExecutable()
                    if (autoPath != null) {
                        scrcpyPathStatus.text = "✓ Auto-detected"
                        scrcpyPathStatus.foreground = JBColor.GREEN
                    } else {
                        scrcpyPathStatus.text = "⚠ Not found in PATH"
                        scrcpyPathStatus.foreground = JBColor.YELLOW
                    }
                } else {
                    val file = File(path)
                    when {
                        !file.exists() -> {
                            scrcpyPathStatus.text = "✗ Path not found"
                            scrcpyPathStatus.foreground = JBColor.RED
                        }
                        file.isDirectory -> {
                            val scrcpyInDir = File(file, scrcpyName)
                            if (scrcpyInDir.exists() && scrcpyInDir.canExecute()) {
                                scrcpyPathStatus.text = "✓ Valid"
                                scrcpyPathStatus.foreground = JBColor.GREEN
                            } else {
                                scrcpyPathStatus.text = "✗ Scrcpy not found in directory"
                                scrcpyPathStatus.foreground = JBColor.RED
                            }
                        }
                        file.isFile -> {
                            if (file.name.equals(scrcpyName, ignoreCase = true) && file.canExecute()) {
                                scrcpyPathStatus.text = "✓ Valid"
                                scrcpyPathStatus.foreground = JBColor.GREEN
                            } else if (!file.canExecute()) {
                                scrcpyPathStatus.text = "✗ File is not executable"
                                scrcpyPathStatus.foreground = JBColor.RED
                            } else {
                                scrcpyPathStatus.text = "✗ Not a valid scrcpy executable"
                                scrcpyPathStatus.foreground = JBColor.RED
                            }
                        }
                        else -> {
                            scrcpyPathStatus.text = "✗ Invalid path"
                            scrcpyPathStatus.foreground = JBColor.RED
                        }
                    }
                }
                scrcpyPathStatus.isVisible = true
            }
        })
        
        // Auto-restore scrcpy path on focus lost
        scrcpyPathField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                SwingUtilities.invokeLater {
                    if (scrcpyPathField.text.trim().isBlank()) {
                        val autoPath = ScrcpyService.findScrcpyExecutable()
                        if (autoPath != null) {
                            val autoFile = File(autoPath)
                            // If it's an executable, use its parent directory
                            if (autoFile.isFile) {
                                scrcpyPathField.text = autoFile.parent ?: ""
                            } else {
                                scrcpyPathField.text = autoPath
                            }
                        }
                    }
                }
            }
        })
    }
    
    private fun handleResetAll() {
        val result = JOptionPane.showConfirmDialog(
            this,
            """This will reset ALL plugin data including:
            • All settings
            • Custom presets
            • Cached data
            
            This action cannot be undone!
            
            Continue?""".trimIndent(),
            "Confirm Reset",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                PluginResetService.resetAllPluginData()
                resetSettings()
                JOptionPane.showMessageDialog(
                    this,
                    "Plugin data has been reset. Please restart the IDE.",
                    "Reset Complete",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
                showError("Failed to reset: ${e.message}")
            }
        }
    }
    
    fun checkModified(): Boolean {
        var modified = restartScrcpySwitch.isSelected != settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            modified = modified || restartRunningDevicesSwitch.isSelected != settings.restartRunningDevicesOnResolutionChange
        }
        modified = modified || restartActiveAppSwitch.isSelected != settings.restartActiveAppOnResolutionChange
        
        val currentWifiMode = when {
            noWifiOption.isSelected -> 0
            deviceToHostOption.isSelected -> 1
            hostToDeviceOption.isSelected -> 2
            else -> 0
        }
        val savedWifiMode = when {
            !settings.autoSwitchToHostWifi && !settings.autoSwitchPCWifi -> 0
            settings.autoSwitchToHostWifi && !settings.autoSwitchPCWifi -> 1
            !settings.autoSwitchToHostWifi && settings.autoSwitchPCWifi -> 2
            else -> 0
        }
        modified = modified || currentWifiMode != savedWifiMode
        
        modified = modified || debugModeSwitch.isSelected != settings.debugMode
        modified = modified || hitboxesSwitch.isSelected != settings.debugHitboxes
        modified = modified || simulateAdbNotFoundSwitch.isSelected != settings.debugSimulateAdbNotFound
        modified = modified || simulateScrcpyNotFoundSwitch.isSelected != settings.debugSimulateScrcpyNotFound
        modified = modified || scrcpyPathField.text != settings.scrcpyPath
        modified = modified || scrcpyFlagsField.text != settings.scrcpyCustomFlags
        modified = modified || adbPathField.text != settings.adbPath
        
        val currentPort = adbPortField.text.trim().toIntOrNull() ?: 5555
        modified = modified || currentPort != settings.adbPort
        
        val currentMaxDpi = maxDpiField.text.trim().toIntOrNull() ?: 640
        modified = modified || currentMaxDpi != settings.maxDpiLimit
        
        return modified
    }
    
    fun applySettings() {
        settings.restartScrcpyOnResolutionChange = restartScrcpySwitch.isSelected
        if (AndroidStudioDetector.isAndroidStudio()) {
            settings.restartRunningDevicesOnResolutionChange = restartRunningDevicesSwitch.isSelected
        }
        settings.restartActiveAppOnResolutionChange = restartActiveAppSwitch.isSelected
        
        when {
            noWifiOption.isSelected -> {
                settings.autoSwitchToHostWifi = false
                settings.autoSwitchPCWifi = false
            }
            deviceToHostOption.isSelected -> {
                settings.autoSwitchToHostWifi = true
                settings.autoSwitchPCWifi = false
            }
            hostToDeviceOption.isSelected -> {
                settings.autoSwitchToHostWifi = false
                settings.autoSwitchPCWifi = true
            }
        }
        
        settings.scrcpyPath = scrcpyPathField.text
        settings.scrcpyCustomFlags = scrcpyFlagsField.text
        settings.adbPath = adbPathField.text
        settings.adbPort = adbPortField.text.trim().toIntOrNull() ?: 5555
        
        // Check max DPI limit before applying
        val maxDpiValue = maxDpiField.text.trim().toIntOrNull() ?: 640
        if (maxDpiValue > 999 && !dangerousDpiConfirmed) {
            val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                maxDpiField,
                "Setting maximum DPI limit above 999 can cause devices to crash or reboot.\n\n" +
                "Values above 999 are extremely dangerous and may require factory reset to recover.\n" +
                "Are you absolutely sure you want to set the limit to $maxDpiValue?",
                "Dangerous DPI Limit Warning",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            )
            if (result == com.intellij.openapi.ui.Messages.YES) {
                dangerousDpiConfirmed = true
                settings.maxDpiLimit = maxDpiValue
            } else {
                maxDpiField.text = "640"
                settings.maxDpiLimit = 640
                dangerousDpiConfirmed = false
            }
        } else {
            settings.maxDpiLimit = maxDpiValue
        }
        
        val debugModeChanged = settings.debugMode != debugModeSwitch.isSelected
        settings.debugMode = debugModeSwitch.isSelected
        settings.debugHitboxes = hitboxesSwitch.isSelected
        settings.debugSimulateAdbNotFound = simulateAdbNotFoundSwitch.isSelected
        settings.debugSimulateScrcpyNotFound = simulateScrcpyNotFoundSwitch.isSelected
        
        if (debugModeChanged) {
            FileLogger.reinitialize()
        }
        
        PluginLogger.info(LogCategory.GENERAL, "Settings applied")
    }
    
    fun resetSettings() {
        restartScrcpySwitch.isSelected = settings.restartScrcpyOnResolutionChange
        if (AndroidStudioDetector.isAndroidStudio()) {
            restartRunningDevicesSwitch.isSelected = settings.restartRunningDevicesOnResolutionChange
        }
        restartActiveAppSwitch.isSelected = settings.restartActiveAppOnResolutionChange
        
        when {
            !settings.autoSwitchToHostWifi && !settings.autoSwitchPCWifi -> noWifiOption.isSelected = true
            settings.autoSwitchToHostWifi && !settings.autoSwitchPCWifi -> deviceToHostOption.isSelected = true
            !settings.autoSwitchToHostWifi && settings.autoSwitchPCWifi -> hostToDeviceOption.isSelected = true
            else -> noWifiOption.isSelected = true
        }
        
        debugModeSwitch.isSelected = settings.debugMode
        hitboxesSwitch.isSelected = settings.debugHitboxes
        simulateAdbNotFoundSwitch.isSelected = settings.debugSimulateAdbNotFound
        simulateScrcpyNotFoundSwitch.isSelected = settings.debugSimulateScrcpyNotFound
        
        if (settings.scrcpyPath.isBlank()) {
            val oldPath = PresetStorageService.getScrcpyPath()
            if (oldPath != null && File(oldPath).exists()) {
                settings.scrcpyPath = oldPath
            }
        }
        
        scrcpyPathField.text = settings.scrcpyPath
        scrcpyFlagsField.text = settings.scrcpyCustomFlags
        
        // Всегда пытаемся найти актуальный путь к ADB
        val savedPath = settings.adbPath
        if (savedPath.isNotBlank()) {
            // Проверяем, существует ли сохранённый путь
            val savedFile = File(savedPath)
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val adbName = if (isWindows) "adb.exe" else "adb"
            
            val pathIsValid = when {
                savedFile.isDirectory -> File(savedFile, adbName).exists()
                savedFile.isFile -> savedFile.exists() && savedFile.name.equals(adbName, ignoreCase = true)
                else -> false
            }
            
            if (pathIsValid) {
                // Сохранённый путь валиден, используем его
                adbPathField.text = savedPath
            } else {
                // Сохранённый путь невалиден, ищем ADB автоматически
                val autoPath = AdbPathResolver.findAdbExecutable()
                if (autoPath != null) {
                    // Используем родительскую директорию найденного ADB
                    val newPath = File(autoPath).parent ?: autoPath
                    adbPathField.text = newPath
                    // Автоматически обновляем сохранённый путь на найденный
                    settings.adbPath = newPath
                } else {
                    // Если ADB не найден, очищаем поле
                    adbPathField.text = ""
                    settings.adbPath = ""
                }
            }
        } else {
            // Путь не был сохранён, пытаемся найти автоматически
            val autoPath = AdbPathResolver.findAdbExecutable()
            if (autoPath != null) {
                val newPath = File(autoPath).parent ?: autoPath
                adbPathField.text = newPath
                // Сохраняем автоматически найденный путь
                settings.adbPath = newPath
            } else {
                adbPathField.text = ""
            }
        }
        adbPortField.text = settings.adbPort.toString()
        maxDpiField.text = settings.maxDpiLimit.toString()
        dangerousDpiConfirmed = false // Reset confirmation flag when loading settings
        
        openLogsButton.isEnabled = settings.debugMode
    }
}