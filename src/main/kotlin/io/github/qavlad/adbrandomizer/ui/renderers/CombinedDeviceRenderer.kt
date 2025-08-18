package io.github.qavlad.adbrandomizer.ui.renderers

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.models.CombinedDeviceInfo
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.ui.config.HitboxConfigManager
import io.github.qavlad.adbrandomizer.ui.config.DeviceType
import io.github.qavlad.adbrandomizer.ui.config.HitboxType
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import javax.swing.*

/**
 * Рендерер для объединённых устройств (USB + Wi-Fi)
 */
class CombinedDeviceRenderer(
    private val getHoverState: () -> HoverState
) {
    companion object {
        private const val BUTTON_HEIGHT = 22

        // Статические поля для отслеживания позиции мыши в дебаг режиме
        var debugMousePosition: Point? = null
        var debugHoveredIndex: Int = -1
    }

    private val usbIcon: Icon = IconLoader.getIcon("/icons/usb.svg", javaClass)
    // Используем ленивую инициализацию для проблемной иконки
    private val usbOffIcon: Icon by lazy { 
        loadUsbOffIcon()
    }
    private val wifiIcon: Icon = IconLoader.getIcon("/icons/wifi.svg", javaClass)
    private val mirrorIcon = IconLoader.getIcon("/icons/scrcpy.svg", javaClass)
    private val resetIcon = IconLoader.getIcon("/icons/reset.svg", javaClass)
    
    private fun loadUsbOffIcon(): Icon {
        // Временно меняем уровень логирования для UI_EVENTS на INFO для диагностики (делаем это здесь, а не в init блоке)
        try {
            val config = io.github.qavlad.adbrandomizer.utils.logging.LoggingConfiguration.getInstance()
            config.setCategoryLogLevel(LogCategory.UI_EVENTS, io.github.qavlad.adbrandomizer.utils.logging.LogLevel.INFO)
        } catch (_: Exception) {
            // Игнорируем ошибку, просто не получим дополнительные логи
        }
        
        // Логируем попытку загрузки
        PluginLogger.info(LogCategory.UI_EVENTS, "Attempting to load usb_off.svg icon")
        
        // Пробуем разные способы загрузки
        val attempts = listOf(
            { 
                PluginLogger.info(LogCategory.UI_EVENTS, "Trying standard IconLoader.getIcon")
                IconLoader.getIcon("/icons/usb_off.svg", javaClass) 
            },
            { 
                PluginLogger.info(LogCategory.UI_EVENTS, "Trying IconLoader.findIcon")
                IconLoader.findIcon("/icons/usb_off.svg", javaClass.classLoader) 
            },
            {
                PluginLogger.info(LogCategory.UI_EVENTS, "Trying IconLoader with CombinedDeviceRenderer classloader")
                IconLoader.getIcon("/icons/usb_off.svg", CombinedDeviceRenderer::class.java)
            },
            {
                // Пробуем загрузить как ресурс и проверить его наличие
                PluginLogger.info(LogCategory.UI_EVENTS, "Checking if resource exists")
                val resource = javaClass.getResource("/icons/usb_off.svg")
                if (resource != null) {
                    PluginLogger.info(LogCategory.UI_EVENTS, "Resource found at: %s", resource.toString())
                    IconLoader.getIcon("/icons/usb_off.svg", javaClass)
                } else {
                    PluginLogger.info(LogCategory.UI_EVENTS, "Resource /icons/usb_off.svg not found in classpath")
                    null
                }
            }
        )
        
        for ((index, attempt) in attempts.withIndex()) {
            try {
                val icon = attempt()
                if (icon != null) {
                    PluginLogger.info(LogCategory.UI_EVENTS, "Successfully loaded usb_off.svg icon using method %d", index + 1)
                    return icon
                }
            } catch (e: Exception) {
                PluginLogger.info(LogCategory.UI_EVENTS, "Failed attempt %d to load icon: %s", e, index + 1, e.message)
            }
        }
        
        // Если все попытки провалились, используем фоллбэк
        PluginLogger.info(LogCategory.UI_EVENTS, "All attempts to load usb_off.svg failed, using fallback icon")
        return createUsbOffFallbackIcon()
    }
    
    // Фоллбэк иконка для USB Off если загрузка SVG не удалась
    private fun createUsbOffFallbackIcon(): Icon = object : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // Используем тот же цвет, что и для текста в теме
            val baseColor = JBColor.namedColor("Label.foreground", JBColor(Gray._100, Gray._187))
            g2d.color = baseColor
            
            // Основа USB иконки
            val offsetX = x + 2
            val offsetY = y + 2
            
            // Кабель USB (вертикальная линия)
            g2d.stroke = BasicStroke(1.5f)
            g2d.drawLine(offsetX + 8, offsetY + 2, offsetX + 8, offsetY + 8)
            
            // USB коннектор (прямоугольник)
            g2d.fillRect(offsetX + 6, offsetY + 8, 4, 4)
            
            // Трезубец USB
            g2d.stroke = BasicStroke(1f)
            // Левая вилка
            g2d.drawLine(offsetX + 8, offsetY + 2, offsetX + 5, offsetY + 5)
            g2d.fillRect(offsetX + 4, offsetY + 4, 2, 2)
            // Правая вилка  
            g2d.drawLine(offsetX + 8, offsetY + 2, offsetX + 11, offsetY + 5)
            g2d.fillRect(offsetX + 10, offsetY + 4, 2, 2)
            // Центральная линия
            g2d.drawLine(offsetX + 8, offsetY, offsetX + 8, offsetY + 2)
            
            // Диагональная линия "отключено"
            g2d.color = JBColor.namedColor("Label.errorForeground", JBColor(Color(176, 0, 32), Color(255, 100, 100)))
            g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2d.drawLine(x + 3, y + 16, x + 16, y + 3)
            
            g2d.dispose()
        }
        
        override fun getIconWidth() = 20
        override fun getIconHeight() = 20
    }
    
    // Кастомная иконка редактирования - простой монохромный карандаш
    private val editIcon = object : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // Используем тот же цвет, что и для текста
            val color = JBColor.foreground()
            g2d.color = color
            
            // Основной корпус карандаша (направлен в левый нижний угол)
            g2d.stroke = BasicStroke(1.5f)
            g2d.drawLine(x + 12, y + 3, x + 5, y + 10) // Основная линия карандаша
            
            // Контур карандаша
            g2d.stroke = BasicStroke(1f)
            val bodyX = intArrayOf(x + 11, x + 13, x + 6, x + 4)
            val bodyY = intArrayOf(y + 2, y + 4, y + 11, y + 9)
            g2d.drawPolygon(bodyX, bodyY, 4)
            
            // Острие карандаша
            g2d.fillPolygon(
                intArrayOf(x + 4, x + 2, x + 3),
                intArrayOf(y + 9, y + 11, y + 12),
                3
            )
            
            // Небольшая линия-след от карандаша
            g2d.stroke = BasicStroke(0.8f)
            g2d.drawLine(x + 2, y + 13, x + 5, y + 13)
            
            g2d.dispose()
        }
        
        override fun getIconWidth() = 16
        override fun getIconHeight() = 16
    }

    fun createComponent(
        device: CombinedDeviceInfo,
        index: Int,
        isSelected: Boolean,
        list: JList<*>
    ): Component {
        // Логируем создание компонента
        PluginLogger.info(LogCategory.UI_EVENTS, "Creating component for device: %s (index: %d, USB: %s, WiFi: %s)", 
            device.displayName, index, device.hasUsbConnection, device.hasWifiConnection)
        
        val hoverState = getHoverState()
        
        // Главная панель с BorderLayout для чекбокса слева и контента справа
        val mainPanel = if (PluginSettings.instance.debugHitboxes) {
            createDebugPanel(device, index, isSelected, list)
        } else {
            JPanel(BorderLayout()).apply {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(5)
                isOpaque = true
            }
        }
        
        // Чекбокс слева (без текста, только чекбокс)
        val adbCheckbox = JCheckBox().apply {
            this.isSelected = device.isSelectedForAdb
            isOpaque = false
            toolTipText = "Include this device in ADB commands"
            // Не добавляем слушателей - они не работают в рендерере
        }
        val checkboxPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4)
            preferredSize = Dimension(35, 0)
            add(adbCheckbox, BorderLayout.NORTH) // Используем NORTH для выравнивания по верху
        }
        mainPanel.add(checkboxPanel, BorderLayout.WEST)
        
        // Панель с содержимым справа
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Первая строка - название устройства и серийный номер
        val namePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        val nameText = "${device.displayName} (${device.baseSerialNumber})"
        val nameLabel = JLabel(nameText).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (isSelected) list.selectionForeground else list.foreground
            toolTipText = "Device model and serial number"
        }
        namePanel.add(nameLabel)
        panel.add(namePanel)

        // Вторая строка - информация об Android и IP адресе
        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 18)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        val infoText = buildString {
            append("Android ${device.androidVersion} (API ${device.apiLevel})")
            // Добавляем IP адрес для всех устройств, у которых он есть
            val ipAddress = device.wifiDevice?.ipAddress ?: device.ipAddress
            ipAddress?.let {
                append(" • $it")
            }
        }
        
        val ipAddress = device.wifiDevice?.ipAddress ?: device.ipAddress
        val infoLabel = JLabel(infoText).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
            toolTipText = "Android version, API level${if (ipAddress != null) " and IP address" else ""}"
        }
        infoPanel.add(infoLabel)
        panel.add(infoPanel)
        
        // Добавляем больше отступа между информацией и параметрами
        panel.add(Box.createVerticalStrut(8))
        
        // Третья строка - дефолтные параметры экрана
        val defaultParamsPanel = createDefaultParamsPanel(device)
        panel.add(defaultParamsPanel)
        
        // Четвертая строка - текущие параметры экрана
        val currentParamsPanel = createCurrentParamsPanel(device, index, hoverState)
        panel.add(Box.createVerticalStrut(4))
        panel.add(currentParamsPanel)

        // Пятая строка - кнопки управления подключениями
        PluginLogger.info(LogCategory.UI_EVENTS, "About to create controls panel for device: %s", device.displayName)
        val controlsPanel = createControlsPanel(device, index, hoverState)
        PluginLogger.info(LogCategory.UI_EVENTS, "Controls panel created for device: %s", device.displayName)
        panel.add(Box.createVerticalStrut(4))
        panel.add(controlsPanel)
        
        mainPanel.add(panel, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createControlsPanel(device: CombinedDeviceInfo, index: Int, hoverState: HoverState): JPanel {
        // Логируем информацию об устройстве
        PluginLogger.info(LogCategory.UI_EVENTS, "Creating controls for device: %s (USB: %s, WiFi: %s, usbDevice: %s, wifiDevice: %s)", 
            device.displayName, 
            device.hasUsbConnection, 
            device.hasWifiConnection,
            device.usbDevice?.logicalSerialNumber ?: "null",
            device.wifiDevice?.logicalSerialNumber ?: "null"
        )
        
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, BUTTON_HEIGHT + 4)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val isThisDevice = hoverState.hoveredDeviceIndex == index

        // USB секция - всегда показываем кнопку зеркалирования если USB подключен
        // Добавляем отрицательный отступ, чтобы сдвинуть USB влево
        panel.add(Box.createHorizontalStrut(-5))
        
        val selectedUsbIcon = if (device.hasUsbConnection) {
            PluginLogger.info(LogCategory.UI_EVENTS, "Device %s has USB connection, using usbIcon", device.displayName)
            usbIcon
        } else {
            // Логируем использование usbOffIcon
            PluginLogger.info(LogCategory.UI_EVENTS, "Device %s has NO USB connection, using usbOffIcon (size: %dx%d)", 
                device.displayName, usbOffIcon.iconWidth, usbOffIcon.iconHeight)
            usbOffIcon
        }
        
        val usbPanel = createConnectionSection(
            selectedUsbIcon,
            "USB",
            device.hasUsbConnection,
            showMirror = true, // Показываем всегда, но будет серой если нет USB
            showConnect = false,
            showDisconnect = false,
            isMirrorHovered = isThisDevice && hoverState.hoveredButtonType == "USB_MIRROR",
            isConnectHovered = false,
            isDisconnectHovered = false,
            isMirrorEnabled = device.hasUsbConnection // Передаём флаг активности
        )
        panel.add(usbPanel)

        // Разделитель
        panel.add(Box.createHorizontalStrut(15))
        panel.add(createSeparator())
        panel.add(Box.createHorizontalStrut(15))

        // Wi-Fi секция
        if (device.hasWifiConnection) {
            val wifiPanel = createConnectionSection(
                wifiIcon,
                "Wi-Fi",
                true,
                showMirror = true,
                showConnect = false,
                showDisconnect = true,
                isMirrorHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_MIRROR",
                isConnectHovered = false,
                isDisconnectHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_DISCONNECT",
                isMirrorEnabled = true // Wi-Fi зеркалирование всегда активно если есть подключение
            )
            panel.add(wifiPanel)
        } else {
            // Если есть USB подключение, но нет Wi-Fi - показываем кнопку Connect
            val showConnectButton = device.hasUsbConnection
            val wifiPanel = createConnectionSection(
                wifiIcon,
                "Wi-Fi",
                false,
                showMirror = false,
                showConnect = showConnectButton,
                showDisconnect = false,
                isMirrorHovered = false,
                isConnectHovered = isThisDevice && hoverState.hoveredButtonType == "WIFI_CONNECT",
                isDisconnectHovered = false,
                isMirrorEnabled = false
            )
            panel.add(wifiPanel)
        }
        
        return panel
    }

    private fun createConnectionSection(
        icon: Icon,
        text: String,
        isActive: Boolean,
        showMirror: Boolean,
        showConnect: Boolean,
        showDisconnect: Boolean,
        isMirrorHovered: Boolean,
        isConnectHovered: Boolean,
        isDisconnectHovered: Boolean,
        isMirrorEnabled: Boolean = true // По умолчанию активна
    ): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        // Иконка подключения
        val iconLabel = JLabel(icon).apply {
            // Для отладки - проверяем что за иконка
            if (icon === usbOffIcon) {
                PluginLogger.info(LogCategory.UI_EVENTS, "Creating JLabel with usbOffIcon, isActive=%s", isActive)
            }
            // ВАЖНО: Не отключаем JLabel для неактивных иконок, иначе они могут не отображаться
            // isEnabled = isActive // Закомментировано - иконка должна быть всегда видима
            
            // Делаем иконку полупрозрачной если неактивна
            if (!isActive) {
                foreground = JBColor.GRAY
            }
            
            toolTipText = when {
                icon == usbIcon && isActive -> "Connected via USB"
                (icon === usbOffIcon || (icon == usbIcon && !isActive)) -> "USB not connected"
                icon == wifiIcon && isActive -> "Connected via Wi-Fi"
                icon == wifiIcon && !isActive -> "Wi-Fi not connected"
                else -> null
            }
        }
        panel.add(iconLabel)

        panel.add(Box.createHorizontalStrut(4))

        // Текст (IP для Wi-Fi или "USB")
        val textLabel = JLabel(text).apply {
            font = JBFont.small()
            foreground = if (isActive) JBColor.foreground() else JBColor.GRAY
        }
        panel.add(textLabel)

        // Кнопка Mirror
        if (showMirror) {
            panel.add(Box.createHorizontalStrut(4))
            val connectionType = if (icon == usbIcon || icon === usbOffIcon) "USB" else "Wi-Fi"
            val tooltip = if (isMirrorEnabled) {
                "Mirror screen via $connectionType"
            } else {
                "Cannot mirror: $connectionType not connected"
            }
            val mirrorButton = createIconButton(
                mirrorIcon, 
                tooltip, 
                isMirrorHovered && isMirrorEnabled, // Hover только если активна
                isEnabled = isMirrorEnabled
            )
            panel.add(mirrorButton)
        }

        // Кнопка Connect Wi-Fi
        if (showConnect) {
            panel.add(Box.createHorizontalStrut(4))
            val connectButton = createConnectButton(isConnectHovered)
            panel.add(connectButton)
        }

        // Кнопка Disconnect (только для Wi-Fi)
        if (showDisconnect) {
            panel.add(Box.createHorizontalStrut(4))
            val disconnectButton = createDisconnectButton(isDisconnectHovered)
            panel.add(disconnectButton)
        }

        return panel
    }

    private fun createIconButton(
        icon: Icon, 
        tooltip: String, 
        isHovered: Boolean,
        isEnabled: Boolean = true
    ): JButton {
        // Используем disabled версию иконки если кнопка неактивна
        val buttonIcon = if (isEnabled) {
            icon
        } else {
            // Создаём серую версию иконки для неактивного состояния
            IconLoader.getDisabledIcon(icon)
        }
        
        return JButton(buttonIcon).apply {
            toolTipText = tooltip
            preferredSize = Dimension(BUTTON_HEIGHT, BUTTON_HEIGHT)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            this.isEnabled = isEnabled
            isContentAreaFilled = isHovered && isEnabled
            isBorderPainted = isHovered && isEnabled
            cursor = if (isEnabled) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            
            if (isHovered && isEnabled) {
                background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
        }
    }
    
    private fun createConnectButton(isHovered: Boolean): JButton {
        return JButton("Connect").apply {
            toolTipText = "Connect to this device via Wi-Fi"
            font = JBFont.small()
            preferredSize = Dimension(65, BUTTON_HEIGHT) // Увеличиваем ширину с 55 до 65
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            isContentAreaFilled = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Зелёная рамка с эффектом при наведении
            border = if (isHovered) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(100, 200, 100), Color(120, 220, 120)), 2),
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)
                )
            } else {
                BorderFactory.createLineBorder(JBColor(Color(50, 150, 50), Color(60, 180, 60)), 1)
            }
            
            if (isHovered) {
                background = JBColor(Color(230, 255, 230), Color(30, 80, 30))
            }
        }
    }
    
    private fun createDisconnectButton(isHovered: Boolean): JButton {
        return JButton("Disconnect").apply {
            toolTipText = "Disconnect Wi-Fi connection"
            font = JBFont.small()
            preferredSize = Dimension(70, BUTTON_HEIGHT)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            isContentAreaFilled = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Красная рамка с эффектом при наведении
            border = if (isHovered) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(250, 100, 100), Color(220, 80, 80)), 2),
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)
                )
            } else {
                BorderFactory.createLineBorder(JBColor(Color(200, 50, 50), Color(180, 40, 40)), 1)
            }
            
            if (isHovered) {
                background = JBColor(Color(255, 230, 230), Color(80, 30, 30))
            }
        }
    }

    private fun createSeparator(): JLabel {
        return JLabel("|").apply {
            foreground = JBColor.GRAY
        }
    }
    
    private fun createDefaultParamsPanel(device: CombinedDeviceInfo): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 25)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        // Default Size label и значение
        panel.add(JLabel("Default Size:").apply {
            font = JBFont.small()
            foreground = JBColor.foreground()
            toolTipText = "Factory default screen resolution"
        })
        
        // Отступ между лейблом "Default Size:" и полем ввода (компенсирует разницу в длине с "Current Size:")
        panel.add(Box.createHorizontalStrut(6))
        
        val defaultSizeText = device.defaultResolution?.let { "${it.first}x${it.second}" } ?: "N/A"
        val sizeDefField = JTextField(defaultSizeText).apply {
            font = JBFont.small()
            preferredSize = Dimension(80, 20)
            maximumSize = Dimension(80, 20)
            isEditable = false
            border = BorderFactory.createLineBorder(JBColor.GRAY, 1)
            background = JBColor.background()
            foreground = JBColor.GRAY
            toolTipText = "Factory default screen resolution: $defaultSizeText"
        }
        panel.add(sizeDefField)
        
        // Отступ между полем ввода размера и иконкой сброса
        panel.add(Box.createHorizontalStrut(6))
        
        // Иконка сброса для Size
        val resetSizeButton = JButton(resetIcon).apply {
            toolTipText = "Reset to default size"
            preferredSize = Dimension(16, 16)
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        panel.add(resetSizeButton)
        
        // Отступ между секцией Size (иконка сброса) и секцией DPI
        panel.add(Box.createHorizontalStrut(10))
        
        // Default DPI label и значение
        panel.add(JLabel("Default DPI:").apply {
            font = JBFont.small()
            foreground = JBColor.foreground()
            toolTipText = "Factory default dots per inch"
        })
        
        // Отступ между лейблом "Default DPI:" и полем ввода
        panel.add(Box.createHorizontalStrut(6))
        
        val defaultDpiText = device.defaultDpi?.toString() ?: "N/A"
        val dpiDefField = JTextField(defaultDpiText).apply {
            font = JBFont.small()
            preferredSize = Dimension(50, 20)
            maximumSize = Dimension(50, 20)
            isEditable = false
            border = BorderFactory.createLineBorder(JBColor.GRAY, 1)
            background = JBColor.background()
            foreground = JBColor.GRAY
            toolTipText = "Factory default DPI: $defaultDpiText"
        }
        panel.add(dpiDefField)
        
        // Отступ между полем ввода DPI и иконкой сброса
        panel.add(Box.createHorizontalStrut(5))
        
        // Иконка сброса для DPI
        val resetDpiButton = JButton(resetIcon).apply {
            toolTipText = "Reset to default DPI"
            preferredSize = Dimension(16, 16)
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        panel.add(resetDpiButton)
        
        panel.add(Box.createHorizontalGlue())
        
        return panel
    }
    
    private fun createCurrentParamsPanel(device: CombinedDeviceInfo, index: Int, hoverState: HoverState): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 25)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        val isThisDevice = hoverState.hoveredDeviceIndex == index
        val isEditSizeHovered = isThisDevice && hoverState.hoveredButtonType == "EDIT_SIZE"
        val isEditDpiHovered = isThisDevice && hoverState.hoveredButtonType == "EDIT_DPI"
        
        // Current Size label и поле ввода с иконкой редактирования
        panel.add(JLabel("Current Size:").apply {
            font = JBFont.small()
            foreground = JBColor.foreground()
            toolTipText = "Current screen resolution (click edit icon to change)"
        })
        
        // Отступ между лейблом "Current Size:" и полем ввода  
        panel.add(Box.createHorizontalStrut(5))
        
        val currentSizeText = device.currentResolution?.let { "${it.first}x${it.second}" } ?: "N/A"
        val sizeField = JTextField(currentSizeText).apply {
            font = JBFont.small()
            preferredSize = Dimension(80, 20)
            maximumSize = Dimension(80, 20)
            isEditable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to edit size"
            // Подсветка рамки при наведении
            border = if (isEditSizeHovered) {
                BorderFactory.createLineBorder(JBColor(Color(100, 180, 255), Color(120, 200, 255)), 2)
            } else {
                BorderFactory.createLineBorder(JBColor(Color.WHITE, Color.WHITE), 1)
            }
            background = if (device.hasModifiedResolution) 
                JBColor(Color(255, 250, 240), Color(60, 50, 40))
            else JBColor.background()
            foreground = if (device.hasModifiedResolution) 
                JBColor(Color(255, 140, 0), Color(255, 160, 0))
            else JBColor.foreground()
        }
        panel.add(sizeField)
        
        // Отступ между полем ввода размера и иконкой редактирования
        panel.add(Box.createHorizontalStrut(5))
        
        // Иконка редактирования для Size
        val editSizeButton = JButton(editIcon).apply {
            toolTipText = "Click to edit screen resolution"
            preferredSize = Dimension(16, 16)
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        panel.add(editSizeButton)
        
        // Отступ между секцией Size (иконка редактирования) и секцией DPI - такой же, как в Default
        panel.add(Box.createHorizontalStrut(10))
        
        // Current DPI label и поле ввода с иконкой редактирования
        panel.add(JLabel("Current DPI:").apply {
            font = JBFont.small()
            foreground = JBColor.foreground()
            toolTipText = "Current dots per inch (click edit icon to change)"
        })
        
        // Отступ между лейблом "Current DPI:" и полем ввода (компенсирует разницу в длине с "Default DPI:")
        panel.add(Box.createHorizontalStrut(6))
        
        val currentDpiText = device.currentDpi?.toString() ?: "N/A"
        val dpiField = JTextField(currentDpiText).apply {
            font = JBFont.small()
            preferredSize = Dimension(50, 20)
            maximumSize = Dimension(50, 20)
            isEditable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click to edit DPI"
            // Подсветка рамки при наведении
            border = if (isEditDpiHovered) {
                BorderFactory.createLineBorder(JBColor(Color(100, 180, 255), Color(120, 200, 255)), 2)
            } else {
                BorderFactory.createLineBorder(JBColor(Color.WHITE, Color.WHITE), 1)
            }
            background = if (device.hasModifiedDpi) 
                JBColor(Color(255, 250, 240), Color(60, 50, 40))
            else JBColor.background()
            foreground = if (device.hasModifiedDpi) 
                JBColor(Color(255, 140, 0), Color(255, 160, 0))
            else JBColor.foreground()
        }
        panel.add(dpiField)
        
        // Отступ между полем ввода DPI и иконкой редактирования
        panel.add(Box.createHorizontalStrut(5))
        
        // Иконка редактирования для DPI
        val editDpiButton = JButton(editIcon).apply {
            toolTipText = "Click to edit DPI"
            preferredSize = Dimension(16, 16)
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        panel.add(editDpiButton)
        
        panel.add(Box.createHorizontalGlue())
        
        return panel
    }

    /**
     * Вычисляет области кнопок для обработки событий мыши
     * Важно: JList рендереры - это не настоящие компоненты, а просто "штампы" для рисования.
     * Поэтому мы должны вручную вычислять позиции кнопок для обработки кликов.
     */
    fun calculateButtonRects(
        device: CombinedDeviceInfo,
        cellBounds: Rectangle
    ): ButtonRects {
        val rects = ButtonRects()
        
        // Логируем только раз в 5 секунд для каждого устройства
        PluginLogger.debugWithRateLimit(
            LogCategory.UI_EVENTS, 
            "hitbox_${device.displayName}",
            "HITBOX DEBUG for device: %s, Cell bounds: x=%d, y=%d, w=%d, h=%d",
            device.displayName, cellBounds.x, cellBounds.y, cellBounds.width, cellBounds.height
        )
        
        // Загружаем позиции из JSON конфигурации
        try {
            // Используем позиции из JSON если они доступны
            rects.checkboxRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED, 
                HitboxType.CHECKBOX, 
                cellBounds
            )
            
            rects.resetSizeRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED,
                HitboxType.RESET_SIZE,
                cellBounds
            )
            
            rects.resetDpiRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED,
                HitboxType.RESET_DPI,
                cellBounds
            )
            
            rects.editSizeRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED,
                HitboxType.EDIT_SIZE,
                cellBounds
            )
            
            rects.editDpiRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED,
                HitboxType.EDIT_DPI,
                cellBounds
            )
            
            // Кнопка USB зеркалирования показывается всегда, но активна только при USB подключении
            rects.usbMirrorRect = HitboxConfigManager.getHitboxRect(
                DeviceType.CONNECTED,
                HitboxType.USB_MIRROR,
                cellBounds
            )
            
            if (device.hasWifiConnection) {
                rects.wifiMirrorRect = HitboxConfigManager.getHitboxRect(
                    DeviceType.CONNECTED,
                    HitboxType.WIFI_MIRROR,
                    cellBounds
                )
                
                rects.wifiDisconnectRect = HitboxConfigManager.getHitboxRect(
                    DeviceType.CONNECTED,
                    HitboxType.WIFI_DISCONNECT,
                    cellBounds
                )
            } else if (device.hasUsbConnection) {
                rects.wifiConnectRect = HitboxConfigManager.getHitboxRect(
                    DeviceType.CONNECTED,
                    HitboxType.WIFI_CONNECT,
                    cellBounds
                )
            }
            
            // Если какой-то хитбокс null, используем fallback
            if (rects.checkboxRect == null) {
                rects.checkboxRect = calculateFallbackCheckbox(cellBounds)
            }
            if (rects.resetSizeRect == null || rects.resetDpiRect == null || 
                rects.editSizeRect == null || rects.editDpiRect == null) {
                calculateFallbackPositions(rects, cellBounds)
            }
            
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load hitbox config, using fallback", e)
            calculateFallbackPositions(rects, cellBounds)
        }
        
        return rects
    }
    
    /**
     * Вычисляет fallback позиции если JSON недоступен
     */
    private fun calculateFallbackPositions(rects: ButtonRects, cellBounds: Rectangle) {
        // Используем старую логику как fallback
        if (rects.checkboxRect == null) {
            rects.checkboxRect = Rectangle(cellBounds.x + 8, cellBounds.y + 10, 20, 20)
        }
        
        // Позиции для параметров экрана
        val paramsYDefault = cellBounds.y + 50
        val paramsYCurrent = cellBounds.y + 75
        
        if (rects.resetSizeRect == null) {
            rects.resetSizeRect = Rectangle(cellBounds.x + 180, paramsYDefault + 2, 16, 16)
        }
        if (rects.resetDpiRect == null) {
            rects.resetDpiRect = Rectangle(cellBounds.x + 292, paramsYDefault + 2, 16, 16)
        }
        if (rects.editSizeRect == null) {
            rects.editSizeRect = Rectangle(cellBounds.x + 94, paramsYCurrent, 101, 20)
        }
        if (rects.editDpiRect == null) {
            rects.editDpiRect = Rectangle(cellBounds.x + 241, paramsYCurrent, 71, 20)
        }
        
        // Кнопки подключения
        val yOffset = cellBounds.y + cellBounds.height - 32
        if (rects.usbMirrorRect == null) {
            rects.usbMirrorRect = Rectangle(cellBounds.x + 75, yOffset, 22, 22)
        }
        if (rects.wifiMirrorRect == null) {
            rects.wifiMirrorRect = Rectangle(cellBounds.x + 235, yOffset, 22, 22)
        }
        if (rects.wifiConnectRect == null) {
            rects.wifiConnectRect = Rectangle(cellBounds.x + 190, yOffset, 65, 22)
        }
        if (rects.wifiDisconnectRect == null) {
            rects.wifiDisconnectRect = Rectangle(cellBounds.x + 262, yOffset, 70, 22)
        }
    }
    
    /**
     * Вычисляет fallback позицию для чекбокса
     */
    private fun calculateFallbackCheckbox(cellBounds: Rectangle): Rectangle {
        return Rectangle(cellBounds.x + 8, cellBounds.y + 10, 20, 20)
    }

    data class ButtonRects(
        var usbMirrorRect: Rectangle? = null,
        var wifiConnectRect: Rectangle? = null,
        var wifiMirrorRect: Rectangle? = null,
        var wifiDisconnectRect: Rectangle? = null,
        var resetSizeRect: Rectangle? = null,
        var resetDpiRect: Rectangle? = null,
        var editSizeRect: Rectangle? = null,
        var editDpiRect: Rectangle? = null,
        var checkboxRect: Rectangle? = null
    )
    
    /**
     * Создает панель с визуальной отладкой хитбоксов
     */
    private fun createDebugPanel(
        device: CombinedDeviceInfo,
        index: Int,
        isSelected: Boolean,
        list: JList<*>
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                background = if (isSelected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(5)
                isOpaque = true
            }
            
            override fun paint(g: Graphics) {
                super.paint(g)
                
                // Рисуем хитбоксы поверх содержимого
                if (PluginSettings.instance.debugHitboxes) {
                    val g2d = g.create() as Graphics2D
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    
                    // Вычисляем хитбоксы относительно этой панели
                    val cellBounds = Rectangle(0, 0, width, height)
                    val rects = calculateButtonRects(device, cellBounds)
                    
                    // Используем статические поля для позиции мыши
                    val mousePosition = if (debugHoveredIndex == index) debugMousePosition else null
                    
                    // Функция для определения прозрачности на основе наведения
                    fun getAlpha(rect: Rectangle?): Float {
                        return if (rect != null && mousePosition != null && rect.contains(mousePosition)) {
                            0.7f // Менее прозрачный при наведении
                        } else {
                            0.3f // Обычная прозрачность
                        }
                    }
                    
                    // Рисуем чекбокс - синий
                    rects.checkboxRect?.let {
                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                        g2d.color = JBColor.BLUE
                        g2d.fillRect(it.x, it.y, it.width, it.height)
                        g2d.color = Color.BLUE.darker()
                        g2d.drawRect(it.x, it.y, it.width, it.height)
                    }
                    
                    // Рисуем кнопки сброса - красные
                    listOf(rects.resetSizeRect, rects.resetDpiRect).forEach { rect ->
                        rect?.let {
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                            g2d.color = JBColor.RED
                            g2d.fillRect(it.x, it.y, it.width, it.height)
                            g2d.color = Color.RED.darker()
                            g2d.drawRect(it.x, it.y, it.width, it.height)
                        }
                    }
                    
                    // Рисуем кнопки редактирования - зеленые
                    listOf(rects.editSizeRect, rects.editDpiRect).forEach { rect ->
                        rect?.let {
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                            g2d.color = JBColor.GREEN
                            g2d.fillRect(it.x, it.y, it.width, it.height)
                            g2d.color = Color.GREEN.darker()
                            g2d.drawRect(it.x, it.y, it.width, it.height)
                        }
                    }
                    
                    // Рисуем кнопки зеркалирования - желтые
                    listOf(rects.usbMirrorRect, rects.wifiMirrorRect).forEach { rect ->
                        rect?.let {
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                            g2d.color = JBColor.YELLOW
                            g2d.fillRect(it.x, it.y, it.width, it.height)
                            g2d.color = Color.YELLOW.darker()
                            g2d.drawRect(it.x, it.y, it.width, it.height)
                        }
                    }
                    
                    // Рисуем кнопки подключения/отключения - оранжевые
                    listOf(rects.wifiConnectRect, rects.wifiDisconnectRect).forEach { rect ->
                        rect?.let {
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                            g2d.color = JBColor.ORANGE
                            g2d.fillRect(it.x, it.y, it.width, it.height)
                            g2d.color = Color.ORANGE.darker()
                            g2d.drawRect(it.x, it.y, it.width, it.height)
                        }
                    }
                    
                    // Рисуем tooltip хитбоксы - фиолетовые
                    val tooltipColor = JBColor(Color(128, 0, 128), Color(128, 0, 128)) // Purple
                    
                    // Default Size tooltip
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.DEFAULT_SIZE_TOOLTIP, cellBounds)?.let {
                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                        g2d.color = tooltipColor
                        g2d.fillRect(it.x, it.y, it.width, it.height)
                        g2d.color = tooltipColor.darker()
                        g2d.drawRect(it.x, it.y, it.width, it.height)
                    }
                    
                    // Default DPI tooltip
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.DEFAULT_DPI_TOOLTIP, cellBounds)?.let {
                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                        g2d.color = tooltipColor
                        g2d.fillRect(it.x, it.y, it.width, it.height)
                        g2d.color = tooltipColor.darker()
                        g2d.drawRect(it.x, it.y, it.width, it.height)
                    }
                    
                    // USB Icon tooltip
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.USB_ICON_TOOLTIP, cellBounds)?.let {
                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                        g2d.color = tooltipColor
                        g2d.fillRect(it.x, it.y, it.width, it.height)
                        g2d.color = tooltipColor.darker()
                        g2d.drawRect(it.x, it.y, it.width, it.height)
                    }
                    
                    // Wi-Fi Icon tooltip
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.WIFI_ICON_TOOLTIP, cellBounds)?.let {
                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getAlpha(it))
                        g2d.color = tooltipColor
                        g2d.fillRect(it.x, it.y, it.width, it.height)
                        g2d.color = tooltipColor.darker()
                        g2d.drawRect(it.x, it.y, it.width, it.height)
                    }
                    
                    // Добавляем подписи к хитбоксам
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
                    g2d.color = JBColor.BLACK
                    g2d.font = Font("Arial", Font.PLAIN, 9)
                    
                    // Подписываем каждый хитбокс
                    rects.checkboxRect?.let {
                        g2d.drawString("CB", it.x + 2, it.y + it.height - 2)
                    }
                    rects.resetSizeRect?.let {
                        g2d.drawString("RS", it.x, it.y - 2)
                    }
                    rects.resetDpiRect?.let {
                        g2d.drawString("RD", it.x, it.y - 2)
                    }
                    rects.editSizeRect?.let {
                        g2d.drawString("ES", it.x + 2, it.y - 2)
                    }
                    rects.editDpiRect?.let {
                        g2d.drawString("ED", it.x + 2, it.y - 2)
                    }
                    rects.usbMirrorRect?.let {
                        g2d.drawString("UM", it.x, it.y - 2)
                    }
                    rects.wifiMirrorRect?.let {
                        g2d.drawString("WM", it.x, it.y - 2)
                    }
                    rects.wifiConnectRect?.let {
                        g2d.drawString("WC", it.x + 2, it.y - 2)
                    }
                    rects.wifiDisconnectRect?.let {
                        g2d.drawString("WD", it.x + 2, it.y - 2)
                    }
                    
                    // Подписи для tooltip хитбоксов
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.DEFAULT_SIZE_TOOLTIP, cellBounds)?.let {
                        g2d.drawString("DS", it.x + 2, it.y + 12)
                    }
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.DEFAULT_DPI_TOOLTIP, cellBounds)?.let {
                        g2d.drawString("DD", it.x + 2, it.y + 12)
                    }
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.USB_ICON_TOOLTIP, cellBounds)?.let {
                        g2d.drawString("UI", it.x + 2, it.y + 12)
                    }
                    HitboxConfigManager.getHitboxRect(DeviceType.CONNECTED, HitboxType.WIFI_ICON_TOOLTIP, cellBounds)?.let {
                        g2d.drawString("WI", it.x + 2, it.y + 12)
                    }
                    
                    g2d.dispose()
                }
            }
        }
    }
}