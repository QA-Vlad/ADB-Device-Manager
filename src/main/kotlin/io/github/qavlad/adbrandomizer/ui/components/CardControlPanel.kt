package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetListService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class CardControlPanel(
    private val onRandomAction: (setSize: Boolean, setDpi: Boolean) -> Unit,
    private val onNextPreset: () -> Unit,
    private val onPreviousPreset: () -> Unit,
    private val onResetAction: (resetSize: Boolean, resetDpi: Boolean) -> Unit,
    private val onOpenPresetSettings: () -> Unit
) : JPanel() {

    private var lastUsedPreset: DevicePreset? = null
    private var lastUsedPresetListName: String? = null
    private lateinit var activePresetLabel: JLabel
    private lateinit var activePresetListLabel: JLabel
    private lateinit var activePresetPanel: JPanel

    init {
        setupUI()
    }

    private fun setupUI() {
        layout = BorderLayout()
        background = UIManager.getColor("Panel.background")
        border = JBUI.Borders.empty(8, 12)
        preferredSize = Dimension(0, 190) // Увеличенная высота панели для размещения всех элементов с учётом hover рамок
        
        // Основная панель с горизонтальной компоновкой
        val mainPanel = JPanel().apply {
            layout = GridBagLayout()
            isOpaque = false
        }
        
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        
        // Левая секция - Randomize
        gbc.gridx = 0
        gbc.weightx = 0.22
        gbc.insets = JBUI.insetsRight(10)
        mainPanel.add(createRandomizeSection(), gbc)
        
        // Центральная секция - Presets Manager и Active preset
        gbc.gridx = 1
        gbc.weightx = 0.56
        gbc.insets = JBUI.insets(0, 10)
        mainPanel.add(createCenterSection(), gbc)
        
        // Правая секция - Reset
        gbc.gridx = 2
        gbc.weightx = 0.22
        gbc.insets = JBUI.insetsLeft(10)
        mainPanel.add(createResetSection(), gbc)
        
        add(mainPanel, BorderLayout.CENTER)
    }
    
    private fun createRandomizeSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        
        return panel.apply {
            // Иконка
            val iconPanel = JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER, 0, 0)
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
                
                // Эмодзи игральной кости
                add(JLabel("🎲").apply {
                    font = Font("Segoe UI Emoji", Font.PLAIN, 28)
                })
            }
            add(iconPanel)
            
            // Заголовок
            val titlePanel = JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER, 0, 0)
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 20)
                
                add(JLabel("Random").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(Color(70, 130, 180), Color(100, 150, 200))
                })
            }
            add(titlePanel)
            
            add(Box.createVerticalStrut(6))
            
            // Кнопки
            val allButton = createStyledButton("All", true) {
                updateLastUsedPreset(null, null)
                onRandomAction(true, true)
            }
            allButton.toolTipText = "Randomize screen Size and DPI"
            allButton.alignmentX = CENTER_ALIGNMENT
            add(allButton)
            
            add(Box.createVerticalStrut(4))
            
            val sizeButton = createStyledButton("Size", false) {
                updateLastUsedPreset(null, null)
                onRandomAction(true, false)
            }
            sizeButton.toolTipText = "Randomize screen Size only"
            sizeButton.alignmentX = CENTER_ALIGNMENT
            add(sizeButton)
            
            add(Box.createVerticalStrut(4))
            
            val dpiButton = createStyledButton("DPI", false) {
                updateLastUsedPreset(null, null)
                onRandomAction(false, true)
            }
            dpiButton.toolTipText = "Randomize DPI only"
            dpiButton.alignmentX = CENTER_ALIGNMENT
            add(dpiButton)
            
            add(Box.createVerticalGlue())
        }
    }
    
    private fun createCenterSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.maximumSize = Dimension(450, Short.MAX_VALUE.toInt())
        panel.minimumSize = Dimension(450, 0)
        panel.preferredSize = Dimension(450, 0)
        
        return panel.apply {
            // Блок PRESETS MANAGER
            add(createPresetsManagerBlock())
            
            add(Box.createVerticalStrut(8))
            
            // Блок Active preset
            activePresetPanel = createActivePresetBlock()
            add(activePresetPanel)
            
            add(Box.createVerticalGlue())
        }
    }
    
    private fun createPresetsManagerBlock(): JPanel {
        return JPanel().apply {
            layout = BorderLayout()
            maximumSize = Dimension(450, 80)
            minimumSize = Dimension(450, 80)
            preferredSize = Dimension(450, 80)
            background = JBColor(Color(245, 248, 250), Color(55, 58, 60))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(200, 210, 220), Color(80, 85, 90)), 2),
                JBUI.Borders.empty(15, 20)
            )
            
            // Контент панель
            val contentPanel = JPanel().apply {
                layout = GridBagLayout()
                isOpaque = false
            }
            
            val gbc = GridBagConstraints()
            gbc.anchor = GridBagConstraints.CENTER
            gbc.fill = GridBagConstraints.NONE
            
            // Создаем новую большую иконку
            val iconLabel = JLabel().apply {
                icon = createLargeIcon()
                preferredSize = Dimension(32, 32)
            }
            gbc.gridx = 0
            gbc.insets = JBUI.insetsRight(15)
            contentPanel.add(iconLabel, gbc)
            
            // Текст
            gbc.gridx = 1
            gbc.insets = JBUI.emptyInsets()
            val textPanel = JPanel()
            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false
            textPanel.apply {
                add(JLabel("PRESETS").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f)
                    foreground = JBColor(Gray._50, Gray._200)
                    alignmentX = CENTER_ALIGNMENT
                })
                add(JLabel("MANAGER").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f)
                    foreground = JBColor(Gray._50, Gray._200)
                    alignmentX = CENTER_ALIGNMENT
                })
            }
            contentPanel.add(textPanel, gbc)
            
            add(contentPanel, BorderLayout.CENTER)
            
            // Добавляем интерактивность
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Open presets manager"
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpenPresetSettings()
                }
                
                override fun mouseEntered(e: MouseEvent) {
                    background = JBColor(Color(235, 240, 245), Color(65, 68, 70))
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(100, 150, 200), Color(100, 120, 140)), 3),
                        JBUI.Borders.empty(14, 19)
                    )
                }
                
                override fun mouseExited(e: MouseEvent) {
                    background = JBColor(Color(245, 248, 250), Color(55, 58, 60))
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(200, 210, 220), Color(80, 85, 90)), 2),
                        JBUI.Borders.empty(15, 20)
                    )
                }
            })
        }
    }
    
    private fun createActivePresetBlock(): JPanel {
        return JPanel().apply {
            layout = BorderLayout()
            maximumSize = Dimension(450, 85)
            minimumSize = Dimension(450, 85)
            preferredSize = Dimension(450, 85)
            background = JBColor(Color(245, 248, 250), Color(55, 58, 60))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8, 12)
            )
            
            // Левая стрелка
            val leftArrow = createArrowButton(AllIcons.Actions.Back) {
                onPreviousPreset()
                updatePresetIndicator()
            }
            leftArrow.toolTipText = "Previous preset"
            add(leftArrow, BorderLayout.WEST)
            
            // Центральная часть с информацией о пресете (вертикальная компоновка)
            val centerPanel = JPanel()
            centerPanel.layout = GridBagLayout()
            centerPanel.isOpaque = false
            
            val gbc = GridBagConstraints()
            gbc.gridx = 0
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.anchor = GridBagConstraints.CENTER
            
            // Заголовок "Active preset:"
            gbc.gridy = 0
            gbc.insets = JBUI.insetsBottom(2)
            centerPanel.add(JLabel("Active preset:").apply {
                font = UIUtil.getLabelFont().deriveFont(12f)
                foreground = UIUtil.getLabelForeground()
                horizontalAlignment = SwingConstants.CENTER
            }, gbc)
            
            // Название пресета
            gbc.gridy = 1
            gbc.insets = JBUI.insetsBottom(1)
            activePresetLabel = JLabel("Not selected").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
                foreground = JBColor(Color(50, 100, 150), Color(100, 150, 200))
                horizontalAlignment = SwingConstants.CENTER
            }
            centerPanel.add(activePresetLabel, gbc)
            
            // Имя листа
            gbc.gridy = 2
            gbc.insets = JBUI.emptyInsets()
            activePresetListLabel = JLabel("").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, 11f)
                foreground = JBColor.GRAY
                horizontalAlignment = SwingConstants.CENTER
                isVisible = false
            }
            centerPanel.add(activePresetListLabel, gbc)
            
            add(centerPanel, BorderLayout.CENTER)
            
            // Правая стрелка
            val rightArrow = createArrowButton(AllIcons.Actions.Forward) {
                onNextPreset()
                updatePresetIndicator()
            }
            rightArrow.toolTipText = "Next preset"
            add(rightArrow, BorderLayout.EAST)
        }
    }
    
    private fun createResetSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        
        return panel.apply {
            // Иконка
            val iconPanel = JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER, 0, 0)
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
                
                // Символ сброса
                add(JLabel("↺").apply {
                    font = Font("Dialog", Font.BOLD, 28)
                    foreground = JBColor(Color(150, 100, 50), Color(200, 150, 100))
                })
            }
            add(iconPanel)
            
            // Заголовок
            val titlePanel = JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER, 0, 0)
                isOpaque = false
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 20)
                
                add(JLabel("Reset").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(Color(150, 100, 50), Color(200, 150, 100))
                })
            }
            add(titlePanel)
            
            add(Box.createVerticalStrut(6))
            
            // Кнопки
            val allButton = createStyledButton("All", true) {
                onResetAction(true, true)
            }
            allButton.toolTipText = "Reset screen Size and DPI to Default"
            allButton.alignmentX = CENTER_ALIGNMENT
            add(allButton)
            
            add(Box.createVerticalStrut(4))
            
            val sizeButton = createStyledButton("Size", false) {
                onResetAction(true, false)
            }
            sizeButton.toolTipText = "Reset screen Size to Default"
            sizeButton.alignmentX = CENTER_ALIGNMENT
            add(sizeButton)
            
            add(Box.createVerticalStrut(4))
            
            val dpiButton = createStyledButton("DPI", false) {
                onResetAction(false, true)
            }
            dpiButton.toolTipText = "Reset DPI to Default"
            dpiButton.alignmentX = CENTER_ALIGNMENT
            add(dpiButton)
            
            add(Box.createVerticalGlue())
        }
    }
    
    private fun createStyledButton(text: String, isPrimary: Boolean, action: () -> Unit): JButton {
        return JButton(text).apply {
            maximumSize = Dimension(160, 36)
            preferredSize = Dimension(160, 36)
            minimumSize = Dimension(160, 36)
            font = UIUtil.getLabelFont().deriveFont(if (isPrimary) Font.BOLD else Font.PLAIN, 12f)
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.CENTER
            
            // Убираем все кастомные стили для primary кнопок
            // Используем стандартный стиль IntelliJ для всех кнопок
            
            addActionListener { action() }
            
            // Добавляем небольшую подсветку при наведении для всех кнопок
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(Color(100, 150, 200), Color(150, 180, 210)), 2),
                        JBUI.Borders.empty(2, 4)
                    )
                }
                
                override fun mouseExited(e: MouseEvent) {
                    border = UIManager.getBorder("Button.border")
                }
            })
        }
    }
    
    private fun createArrowButton(icon: Icon, action: () -> Unit): JButton {
        return JButton(icon).apply {
            preferredSize = Dimension(28, 28)
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            addActionListener { action() }
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isContentAreaFilled = true
                    background = UIManager.getColor("Button.hoverBackground")
                    border = BorderFactory.createLineBorder(JBColor(Color(100, 150, 200), Color(150, 180, 210)), 1)
                }
                
                override fun mouseExited(e: MouseEvent) {
                    isContentAreaFilled = false
                    border = JBUI.Borders.empty()
                }
            })
        }
    }
    
    private fun createLargeIcon(): Icon {
        return object : Icon {
            override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                g?.let { graphics ->
                    val g2d = graphics as Graphics2D
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    
                    val color = if (UIUtil.isUnderDarcula()) Gray._200 else Gray._60
                    g2d.color = color
                    
                    // Рисуем папку
                    g2d.fillRect(x + 2, y + 8, 28, 20)
                    g2d.fillRect(x + 2, y + 4, 10, 4)
                    
                    // Рисуем линии документов внутри
                    g2d.color = if (UIUtil.isUnderDarcula()) Gray._80 else Gray._240
                    g2d.fillRect(x + 6, y + 12, 20, 1)
                    g2d.fillRect(x + 6, y + 16, 20, 1)
                    g2d.fillRect(x + 6, y + 20, 16, 1)
                    g2d.fillRect(x + 6, y + 24, 12, 1)
                }
            }
            
            override fun getIconWidth(): Int = 32
            override fun getIconHeight(): Int = 32
        }
    }
    
    fun updateLastUsedPreset(preset: DevicePreset?) {
        updateLastUsedPreset(preset, getCurrentListName())
    }
    
    fun updateLastUsedPreset(preset: DevicePreset?, listName: String?) {
        lastUsedPreset = preset
        lastUsedPresetListName = listName
        updatePresetIndicator()
    }
    
    private fun getCurrentListName(): String? {
        // Пытаемся получить имя текущего активного листа
        return try {
            val activeList = PresetListService.getActivePresetList()
            activeList?.name
        } catch (_: Exception) {
            null
        }
    }
    
    private fun updatePresetIndicator() {
        SwingUtilities.invokeLater {
            if (lastUsedPreset != null) {
                val presetName = lastUsedPreset!!.label
                val displayName = if (presetName.length > 25) {
                    presetName.take(22) + "..."
                } else {
                    presetName
                }
                
                activePresetLabel.text = displayName
                activePresetLabel.foreground = JBColor(Color(50, 100, 150), Color(100, 150, 200))
                
                // Показываем имя листа
                if (lastUsedPresetListName != null) {
                    activePresetListLabel.text = "(${lastUsedPresetListName})"
                    activePresetListLabel.isVisible = true
                } else {
                    activePresetListLabel.isVisible = false
                }
                
                // Используем тот же фон, что и у PRESETS MANAGER
                activePresetPanel.background = JBColor(Color(245, 248, 250), Color(55, 58, 60))
                
                // Добавляем tooltip с информацией о пресете
                val size = lastUsedPreset!!.size
                val dpi = lastUsedPreset!!.dpi
                activePresetPanel.toolTipText = "Resolution: $size | DPI: $dpi"
            } else {
                activePresetLabel.text = "Not selected"
                activePresetLabel.foreground = JBColor.GRAY
                activePresetListLabel.isVisible = false
                
                activePresetPanel.background = JBColor(Color(245, 248, 250), Color(55, 58, 60))
                activePresetPanel.toolTipText = null
            }
        }
    }
}