package io.github.qavlad.adbrandomizer.ui.components

import com.android.ddmlib.IDevice
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.AdbService
import io.github.qavlad.adbrandomizer.services.DeviceInfo
import io.github.qavlad.adbrandomizer.services.DeviceOrderService
import io.github.qavlad.adbrandomizer.services.WifiDeviceHistoryService
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.ui.config.DeviceType
import io.github.qavlad.adbrandomizer.ui.config.HitboxConfigManager
import io.github.qavlad.adbrandomizer.ui.config.HitboxType
import io.github.qavlad.adbrandomizer.ui.models.CombinedDeviceInfo
import io.github.qavlad.adbrandomizer.ui.renderers.DeviceListRenderer
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*

sealed class DeviceListItem {
    data class SectionHeader(val title: String) : DeviceListItem()
    data class Device(val info: DeviceInfo, val isConnected: Boolean) : DeviceListItem()
    data class CombinedDevice(val info: CombinedDeviceInfo) : DeviceListItem()
    data class WifiHistoryDevice(val entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry) : DeviceListItem()
    data class GroupedWifiHistoryDevice(
        val entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry,
        val otherIPs: List<String>
    ) : DeviceListItem()
}

class DeviceListPanel(
    private val getHoverState: () -> HoverState,
    private val setHoverState: (HoverState) -> Unit,
    private val getAllDevices: () -> List<DeviceInfo>,
    private val onMirrorClick: (DeviceInfo) -> Unit,
    private val onWifiClick: (IDevice) -> Unit,
    private val onWifiDisconnect: (String) -> Unit, // Новый callback для disconnect Wi-Fi
    private val compactActionPanel: CompactActionPanel,
    private val onForceUpdate: () -> Unit,  // Новый callback для форсированного обновления
    private val onResetSize: (CombinedDeviceInfo) -> Unit = {},
    private val onResetDpi: (CombinedDeviceInfo) -> Unit = {},
    private val onApplyChanges: (CombinedDeviceInfo, String?, String?) -> Unit = { _, _, _ -> },
    private val onAdbCheckboxChanged: (CombinedDeviceInfo, Boolean) -> Unit = { _, _ -> },
    private val onWifiConnectByIp: (String, Int) -> Unit = { _, _ -> }, // Callback для подключения по IP
    private val onParallelWifiConnect: ((List<Pair<String, Int>>) -> Unit)? = null // Callback для параллельного подключения
) : JPanel(BorderLayout()) {

    companion object {
        private const val CONFIRM_DELETE_KEY = "adbrandomizer.skipWifiHistoryDeleteConfirm"
        private val DELETE_ICON: Icon = AllIcons.Actions.GC
        private const val DELETE_BUTTON_WIDTH = 35
        private const val DELETE_BUTTON_HEIGHT = 25
    }

    private val deviceListModel = DefaultListModel<DeviceListItem>()
    private var currentMousePosition: Point? = null
    private val deviceList = object : JBList<DeviceListItem>(deviceListModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index == -1 || index >= deviceListModel.size()) return null
            
            val item = deviceListModel.getElementAt(index)
            val bounds = getCellBounds(index, index) ?: return null
            
            return when (item) {
                is DeviceListItem.SectionHeader -> {
                    // Если это заголовок Connected devices с ADB
                    if (item.title == "Connected devices") {
                        // Проверяем, находится ли курсор над областью ADB
                        val cellRelativePoint = Point(event.point.x - bounds.x, event.point.y - bounds.y)
                        // Хитбокс для ADB лейбла (должен совпадать с тем что в paint)
                        val adbHitbox = Rectangle(4, 4, 32, 22)
                        
                        if (adbHitbox.contains(cellRelativePoint)) {
                            "ADB commands will be executed only for devices selected with checkboxes below"
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                is DeviceListItem.CombinedDevice -> {
                    getTooltipForCombinedDevice(item.info, event.point, bounds)
                }
                is DeviceListItem.WifiHistoryDevice -> {
                    // Преобразуем координаты мыши относительно ячейки  
                    val cellRelativePoint = Point(event.point.x - bounds.x, event.point.y - bounds.y)
                    val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                    val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                    val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                    
                    when {
                        connectButtonRect.contains(cellRelativePoint) -> "Connect to this device via Wi-Fi"
                        deleteButtonRect.contains(cellRelativePoint) -> "Remove this device from Wi-Fi connection history"
                        else -> null
                    }
                }
                is DeviceListItem.GroupedWifiHistoryDevice -> {
                    // Преобразуем координаты мыши относительно ячейки  
                    val cellRelativePoint = Point(event.point.x - bounds.x, event.point.y - bounds.y)
                    val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                    val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                    val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                    
                    // Проверяем, находится ли курсор над индикатором +N
                    val indicatorRect = getGroupIndicatorRect(cellBoundsAtOrigin)
                    
                    when {
                        connectButtonRect.contains(cellRelativePoint) -> "Connect to this device via Wi-Fi"
                        deleteButtonRect.contains(cellRelativePoint) -> "Remove all IP addresses for this device from history"
                        indicatorRect.contains(cellRelativePoint) -> {
                            // Показываем все IP адреса в tooltip
                            "<html>Other IP addresses for this device:<br>" +
                            item.otherIPs.joinToString("<br>") { "• $it" } +
                            "</html>"
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
    private val properties = PropertiesComponent.getInstance()

    private val combinedDeviceRenderer = io.github.qavlad.adbrandomizer.ui.renderers.CombinedDeviceRenderer(
        getHoverState = getHoverState
    )

    init {
        setupUI()
        setupDeviceListInteractions()
        setupDragAndDrop()
        // Включаем подсказки для JList
        ToolTipManager.sharedInstance().registerComponent(deviceList)
    }

    private fun setupUI() {
        // Создаём заголовок без ADB (ADB будет под Connected devices)
        val titlePanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
        }
        
        // Центральная панель с заголовком Devices
        val titleLabel = JLabel("Devices").apply {
            border = JBUI.Borders.empty()
            font = font.deriveFont(font.style or Font.BOLD)
        }
        
        titlePanel.add(titleLabel, BorderLayout.WEST)
        
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(1, 1, 1, 1, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                JBUI.Borders.empty(4, 8)
            )
            add(titlePanel, BorderLayout.WEST)
            add(compactActionPanel, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            preferredSize = Dimension(preferredSize.width, JBUI.scale(36))
        }
        deviceList.cellRenderer = object : ListCellRenderer<DeviceListItem> {
            private val defaultRenderer = DeviceListRenderer(
                getHoverState = getHoverState,
                getAllDevices = getAllDevices,
                onMirrorClick = onMirrorClick,
                onWifiClick = onWifiClick
            )
            override fun getListCellRendererComponent(
                list: JList<out DeviceListItem>, value: DeviceListItem, index: Int, selected: Boolean, focused: Boolean
            ): Component {
                return when (value) {
                    is DeviceListItem.SectionHeader -> {
                        // Если это заголовок Connected devices, создаем специальную компоновку
                        if (value.title == "Connected devices") {
                            val mainPanel = JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
                                border = BorderFactory.createCompoundBorder(
                                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                                    JBUI.Borders.empty(4)
                                )
                            }
                            
                            // Одна строка с "ADB" и "Connected devices" с визуальным разделением
                            val titleHeaderPanel = object : JPanel(BorderLayout()) {
                                init {
                                    isOpaque = false
                                    maximumSize = Dimension(Int.MAX_VALUE, 30)
                                    alignmentX = LEFT_ALIGNMENT
                                }
                                
                                override fun paint(g: Graphics) {
                                    super.paint(g)
                                    
                                    // Рисуем хитбокс для ADB в режиме дебага
                                    if (PluginSettings.instance.debugHitboxes) {
                                        val g2d = g.create() as Graphics2D
                                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                        
                                        // Хитбокс для ADB лейбла (примерные координаты)
                                        val adbHitbox = Rectangle(4, 4, 32, 22)
                                        
                                        // Рисуем хитбокс фиолетовым цветом для tooltip зон
                                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                                        g2d.color = JBColor(Color(128, 0, 128), Color(128, 0, 128))
                                        g2d.fillRect(adbHitbox.x, adbHitbox.y, adbHitbox.width, adbHitbox.height)
                                        g2d.color = JBColor(Color(128, 0, 128).darker(), Color(128, 0, 128).darker())
                                        g2d.drawRect(adbHitbox.x, adbHitbox.y, adbHitbox.width, adbHitbox.height)
                                        
                                        // Подпись
                                        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
                                        g2d.color = JBColor.BLACK
                                        g2d.font = Font("Arial", Font.PLAIN, 9)
                                        g2d.drawString("ADB", adbHitbox.x + 2, adbHitbox.y + 12)
                                        
                                        g2d.dispose()
                                    }
                                }
                            }
                            
                            // Левая часть с ADB и Connected devices
                            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                                isOpaque = false
                            }
                            
                            // Сначала ADB
                            val adbLabel = JLabel("ADB").apply {
                                font = JBFont.medium().deriveFont(Font.BOLD)
                                foreground = JBColor.foreground() // Делаем текст ярче
                                border = JBUI.Borders.empty(4, 4, 4, 0)
                                preferredSize = Dimension(32, preferredSize.height)
                                toolTipText = "ADB commands will be executed only for devices selected with checkboxes below"
                            }
                            leftPanel.add(adbLabel)
                            
                            // Разделитель
                            val separatorLabel = JLabel(" | ").apply {
                                foreground = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                            }
                            leftPanel.add(separatorLabel)
                            
                            // Потом Connected devices
                            val connectedLabel = JLabel(value.title).apply {
                                font = font.deriveFont(font.style or Font.BOLD)
                                border = JBUI.Borders.empty(4, 0, 4, 4)
                            }
                            leftPanel.add(connectedLabel)
                            
                            titleHeaderPanel.add(leftPanel, BorderLayout.WEST)
                            mainPanel.add(titleHeaderPanel)
                            
                            mainPanel
                        } else {
                            // Для Previously connected devices - добавляем отступ сверху
                            val panel = JPanel(BorderLayout()).apply {
                                background = JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
                                val isPrevoiuslyConnected = value.title == "Previously connected devices"
                                border = if (isPrevoiuslyConnected) {
                                    BorderFactory.createCompoundBorder(
                                        JBUI.Borders.emptyTop(8), // Отступ сверху
                                        BorderFactory.createCompoundBorder(
                                            BorderFactory.createMatteBorder(1, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                                            JBUI.Borders.empty(8, 8, 4, 8)
                                        )
                                    )
                                } else {
                                    BorderFactory.createCompoundBorder(
                                        BorderFactory.createMatteBorder(0, 0, 1, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                                        JBUI.Borders.empty(8, 8, 4, 8)
                                    )
                                }
                            }
                            val sectionLabel = JLabel(value.title).apply {
                                font = font.deriveFont(font.style or Font.BOLD)
                            }
                            panel.add(sectionLabel, BorderLayout.WEST)
                            panel
                        }
                    }
                    is DeviceListItem.Device -> {
                        @Suppress("UNCHECKED_CAST")
                        defaultRenderer.getListCellRendererComponent(
                            list as JList<DeviceInfo>, value.info, index, selected, focused
                        )
                    }
                    is DeviceListItem.CombinedDevice -> {
                        @Suppress("UNCHECKED_CAST")
                        combinedDeviceRenderer.createComponent(
                            value.info, index, selected, list as JList<DeviceListItem>
                        )
                    }
                    is DeviceListItem.WifiHistoryDevice -> {
                        // Создаем панель с BorderLayout для фиксированной позиции кнопок
                        val panel = if (PluginSettings.instance.debugHitboxes) {
                            createDebugWifiHistoryPanel(list)
                        } else {
                            JPanel(BorderLayout()).apply {
                                border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
                                background = list.background
                                isOpaque = true
                            }
                        }
                        // Создаём панель с информацией без иконки Wi-Fi
                        val infoPanel = JPanel(BorderLayout()).apply {
                            isOpaque = false
                            
                            val textPanel = JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                isOpaque = false
                            }
                            
                            // Первая строка - название и серийник
                            val firstLine = JLabel("${value.entry.displayName} (${value.entry.realSerialNumber ?: value.entry.logicalSerialNumber})").apply {
                                font = font.deriveFont(font.style or Font.BOLD)
                            }
                            textPanel.add(firstLine)
                            
                            // Вторая строка - Android версия и IP
                            val secondLine = JLabel("Android ${value.entry.androidVersion} (API ${value.entry.apiLevel}) • ${value.entry.ipAddress}:${value.entry.port}").apply {
                                font = JBFont.small()
                                foreground = JBColor.GRAY
                            }
                            textPanel.add(secondLine)
                            
                            add(textPanel, BorderLayout.CENTER)
                        }
                        
                        // Проверяем, находится ли курсор над кнопками
                        val isDeleteHovered = getHoverState().hoveredDeviceIndex == index && 
                                      getHoverState().hoveredButtonType == "DELETE"
                        val isConnectHovered = getHoverState().hoveredDeviceIndex == index && 
                                      getHoverState().hoveredButtonType == "WIFI_HISTORY_CONNECT"
                        
                        // Кнопка Connect
                        val connectButton = JButton("Connect").apply {
                            isFocusable = false
                            font = JBFont.small()
                            preferredSize = Dimension(65, 22)
                            isContentAreaFilled = true
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            toolTipText = "Connect to this device via Wi-Fi"
                            
                            // Зелёная рамка с эффектом при наведении
                            border = if (isConnectHovered) {
                                BorderFactory.createCompoundBorder(
                                    BorderFactory.createLineBorder(JBColor(Color(100, 200, 100), Color(120, 220, 120)), 2),
                                    BorderFactory.createEmptyBorder(0, 0, 0, 0)
                                )
                            } else {
                                BorderFactory.createLineBorder(JBColor(Color(50, 150, 50), Color(60, 180, 60)), 1)
                            }
                            
                            if (isConnectHovered) {
                                background = JBColor(Color(230, 255, 230), Color(30, 80, 30))
                            }
                        }
                        
                        val deleteButton = JButton(DELETE_ICON).apply {
                            isFocusable = false
                            isContentAreaFilled = isDeleteHovered
                            isBorderPainted = isDeleteHovered
                            isRolloverEnabled = true
                            toolTipText = "Remove this device from Wi-Fi connection history"
                            preferredSize = Dimension(DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)
                            if (isDeleteHovered) {
                                background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                            }
                        }
                        
                        // Создаём панель для кнопок справа с GridBagLayout для точного позиционирования
                        val buttonsPanel = JPanel(GridBagLayout()).apply {
                            isOpaque = false
                            val gbc = GridBagConstraints().apply {
                                gridy = 0
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.CENTER
                            }
                            
                            // Connect button
                            gbc.gridx = 0
                            gbc.insets = JBUI.insetsRight(8) // 8px gap справа
                            add(connectButton, gbc)
                            
                            // Delete button
                            gbc.gridx = 1
                            gbc.insets = JBUI.emptyInsets()
                            add(deleteButton, gbc)
                        }
                        
                        // Добавляем элементы в BorderLayout
                        panel.add(infoPanel, BorderLayout.CENTER)
                        panel.add(buttonsPanel, BorderLayout.EAST)
                        
                        panel
                    }
                    is DeviceListItem.GroupedWifiHistoryDevice -> {
                        // Создаем панель с BorderLayout для фиксированной позиции кнопок
                        val panel = if (PluginSettings.instance.debugHitboxes) {
                            createDebugGroupedWifiHistoryPanel(list)
                        } else {
                            JPanel(BorderLayout()).apply {
                                border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
                                background = list.background
                                isOpaque = true
                            }
                        }
                        
                        // Создаём панель с информацией без иконки Wi-Fi
                        val infoPanel = JPanel(BorderLayout()).apply {
                            isOpaque = false
                            
                            val textPanel = JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                isOpaque = false
                            }
                            
                            // Первая строка - название и серийник (как у обычного WifiHistoryDevice)
                            val firstLine = JLabel("${value.entry.displayName} (${value.entry.realSerialNumber ?: value.entry.logicalSerialNumber})").apply {
                                font = font.deriveFont(font.style or Font.BOLD)
                                alignmentX = LEFT_ALIGNMENT
                            }
                            textPanel.add(firstLine)
                            
                            // Вторая строка - Android версия, IP и индикатор группировки в одной панели
                            val secondLinePanel = JPanel().apply {
                                layout = BoxLayout(this, BoxLayout.X_AXIS)
                                isOpaque = false
                                alignmentX = LEFT_ALIGNMENT
                                
                                add(JLabel("Android ${value.entry.androidVersion} (API ${value.entry.apiLevel}) • ${value.entry.ipAddress}:${value.entry.port}").apply {
                                    font = JBFont.small()
                                    foreground = JBColor.GRAY
                                })
                                
                                add(Box.createHorizontalStrut(5))
                                
                                // Добавляем индикатор количества других IP
                                add(JLabel("+${value.otherIPs.size}").apply {
                                    font = JBFont.small()
                                    foreground = JBColor.GRAY
                                    border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createLineBorder(JBColor.GRAY, 1, true),
                                        BorderFactory.createEmptyBorder(1, 4, 1, 4)
                                    )
                                    toolTipText = "<html>${value.otherIPs.joinToString("<br>")}</html>"
                                })
                                
                                add(Box.createHorizontalGlue())
                            }
                            secondLinePanel.alignmentX = LEFT_ALIGNMENT
                            textPanel.add(secondLinePanel)
                            
                            add(textPanel, BorderLayout.CENTER)
                        }
                        
                        // Создаём панель для кнопок справа с GridBagLayout для точного позиционирования
                        val buttonsPanel = JPanel(GridBagLayout()).apply {
                            isOpaque = false
                            val gbc = GridBagConstraints().apply {
                                gridy = 0
                                insets = JBUI.emptyInsets()
                                anchor = GridBagConstraints.CENTER
                            }
                            
                            // Проверяем, находится ли курсор над кнопками
                            val isDeleteHovered = getHoverState().hoveredDeviceIndex == index && 
                                          getHoverState().hoveredButtonType == "DELETE"
                            val isConnectHovered = getHoverState().hoveredDeviceIndex == index && 
                                          getHoverState().hoveredButtonType == "WIFI_HISTORY_CONNECT"
                            
                            // Connect button (со стилем как у обычного WifiHistoryDevice)
                            val connectButton = JButton("Connect").apply {
                                isFocusable = false
                                font = JBFont.small()
                                preferredSize = Dimension(65, 22)
                                isContentAreaFilled = true
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                toolTipText = "Connect to this device via Wi-Fi"
                                
                                // Зелёная рамка с эффектом при наведении
                                border = if (isConnectHovered) {
                                    BorderFactory.createCompoundBorder(
                                        BorderFactory.createLineBorder(JBColor(Color(100, 200, 100), Color(120, 220, 120)), 2),
                                        BorderFactory.createEmptyBorder(0, 0, 0, 0)
                                    )
                                } else {
                                    BorderFactory.createLineBorder(JBColor(Color(50, 150, 50), Color(60, 180, 60)), 1)
                                }
                                
                                if (isConnectHovered) {
                                    background = JBColor(Color(230, 255, 230), Color(30, 80, 30))
                                }
                            }
                            gbc.gridx = 0
                            gbc.insets = JBUI.insetsRight(8) // 8px gap справа
                            add(connectButton, gbc)
                            
                            // Delete button
                            val deleteButton = JButton(DELETE_ICON).apply {
                                isFocusable = false
                                isContentAreaFilled = isDeleteHovered
                                isBorderPainted = isDeleteHovered
                                isRolloverEnabled = true
                                toolTipText = "Remove all IP addresses for this device"
                                preferredSize = Dimension(DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)
                                if (isDeleteHovered) {
                                    background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                                }
                            }
                            gbc.gridx = 1
                            gbc.insets = JBUI.emptyInsets()
                            add(deleteButton, gbc)
                        }
                        
                        // Добавляем элементы в BorderLayout
                        panel.add(infoPanel, BorderLayout.CENTER)
                        panel.add(buttonsPanel, BorderLayout.EAST)
                        
                        panel
                    }
                }
            }
        }
        // Полностью отключаем выделение элементов
        deviceList.selectionModel = object : DefaultListSelectionModel() {
            override fun setSelectionInterval(index0: Int, index1: Int) {
                // Не делаем ничего - блокируем выделение
            }
            override fun addSelectionInterval(index0: Int, index1: Int) {
                // Не делаем ничего - блокируем выделение
            }
        }
        deviceList.emptyText.text = "Scanning for devices..."
        deviceList.emptyText.appendLine("Make sure ADB is running and devices are connected")
        val scrollPane = JBScrollPane(deviceList)
        scrollPane.border = BorderFactory.createEmptyBorder()
        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupDeviceListInteractions() {
        deviceList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                handleMouseMovement(e)
            }
        })
        deviceList.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                // Используем mouseReleased вместо mouseClicked, чтобы клик срабатывал
                // даже если мышь немного сместилась между нажатием и отпусканием
                if (SwingUtilities.isLeftMouseButton(e)) {
                    handleMouseClick(e)
                }
            }
            override fun mouseExited(event: MouseEvent?) {
                currentMousePosition = null
                resetHoverState()
                if (PluginSettings.instance.debugHitboxes) {
                    io.github.qavlad.adbrandomizer.ui.renderers.CombinedDeviceRenderer.debugMousePosition = null
                    io.github.qavlad.adbrandomizer.ui.renderers.CombinedDeviceRenderer.debugHoveredIndex = -1
                    deviceList.repaint()
                }
            }
        })
    }

    private fun handleMouseMovement(e: MouseEvent) {
        // Сохраняем позицию мыши для дебаг режима
        currentMousePosition = e.point
        if (PluginSettings.instance.debugHitboxes) {
            val index = deviceList.locationToIndex(e.point)
            if (index != -1 && index < deviceListModel.size()) {
                val bounds = deviceList.getCellBounds(index, index)
                if (bounds != null) {
                    // Сохраняем позицию относительно ячейки в статические поля рендерера
                    io.github.qavlad.adbrandomizer.ui.renderers.CombinedDeviceRenderer.debugMousePosition = 
                        Point(e.point.x - bounds.x, e.point.y - bounds.y)
                    io.github.qavlad.adbrandomizer.ui.renderers.CombinedDeviceRenderer.debugHoveredIndex = index
                }
            }
            deviceList.repaint()
        }
        
        val index = deviceList.locationToIndex(e.point)
        if (index == -1 || index >= deviceListModel.size()) return
        when (val item = deviceListModel.getElementAt(index)) {
            is DeviceListItem.Device -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deviceInfo = item.info
                val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                    bounds,
                    DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
                )
                val newButtonType = when {
                    buttonLayout.mirrorButtonRect.contains(e.point) -> HoverState.BUTTON_TYPE_MIRROR
                    buttonLayout.wifiButtonRect?.contains(e.point) == true -> HoverState.BUTTON_TYPE_WIFI
                    else -> null
                }
                updateCursorAndHoverState(index, newButtonType)
            }
            is DeviceListItem.CombinedDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                val buttonRects = combinedDeviceRenderer.calculateButtonRects(item.info, bounds)
                val newButtonType = when {
                    buttonRects.checkboxRect?.contains(e.point) == true -> "CHECKBOX"
                    buttonRects.resetSizeRect?.contains(e.point) == true -> "RESET_SIZE"
                    buttonRects.resetDpiRect?.contains(e.point) == true -> "RESET_DPI"
                    buttonRects.editSizeRect?.contains(e.point) == true -> "EDIT_SIZE"
                    buttonRects.editDpiRect?.contains(e.point) == true -> "EDIT_DPI"
                    buttonRects.usbMirrorRect?.contains(e.point) == true && item.info.hasUsbConnection -> "USB_MIRROR"
                    buttonRects.wifiConnectRect?.contains(e.point) == true -> "WIFI_CONNECT"
                    buttonRects.wifiMirrorRect?.contains(e.point) == true -> "WIFI_MIRROR"
                    buttonRects.wifiDisconnectRect?.contains(e.point) == true -> "WIFI_DISCONNECT"
                    else -> null
                }
                updateCursorAndHoverState(index, newButtonType)
            }
            is DeviceListItem.WifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                // Проверяем, что мышь действительно внутри границ ячейки
                if (!bounds.contains(e.point)) {
                    // Если мышь не в ячейке, сбрасываем состояние
                    deviceList.cursor = Cursor.getDefaultCursor()
                    if (getHoverState().hoveredDeviceIndex == index) {
                        setHoverState(HoverState.noHover())
                        deviceList.repaint()
                    }
                    return
                }
                
                // Преобразуем координаты мыши относительно ячейки
                val cellRelativePoint = Point(e.point.x - bounds.x, e.point.y - bounds.y)
                // Для хитбоксов используем координаты относительно начала координат (0,0)
                val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                
                val newButtonType = when {
                    connectButtonRect.contains(cellRelativePoint) -> "WIFI_HISTORY_CONNECT"
                    deleteButtonRect.contains(cellRelativePoint) -> "DELETE"
                    else -> null
                }
                
                if (newButtonType != null) {
                    deviceList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val newHoverState = HoverState(hoveredDeviceIndex = index, hoveredButtonType = newButtonType)
                    if (getHoverState() != newHoverState) {
                        setHoverState(newHoverState)
                        deviceList.repaint()
                    }
                } else {
                    deviceList.cursor = Cursor.getDefaultCursor()
                    if (getHoverState().hoveredDeviceIndex == index) {
                        setHoverState(HoverState.noHover())
                        deviceList.repaint()
                    }
                }
            }
            is DeviceListItem.GroupedWifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                // Проверяем, что мышь действительно внутри границ ячейки
                if (!bounds.contains(e.point)) {
                    // Если мышь не в ячейке, сбрасываем состояние
                    deviceList.cursor = Cursor.getDefaultCursor()
                    if (getHoverState().hoveredDeviceIndex == index) {
                        setHoverState(HoverState.noHover())
                        deviceList.repaint()
                    }
                    return
                }
                
                // Преобразуем координаты мыши относительно ячейки
                val cellRelativePoint = Point(e.point.x - bounds.x, e.point.y - bounds.y)
                // Для хитбоксов используем координаты относительно начала координат (0,0)
                val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                val indicatorRect = getGroupIndicatorRect(cellBoundsAtOrigin)
                
                val newButtonType = when {
                    connectButtonRect.contains(cellRelativePoint) -> "WIFI_HISTORY_CONNECT"
                    deleteButtonRect.contains(cellRelativePoint) -> "DELETE"
                    indicatorRect.contains(cellRelativePoint) -> "GROUP_INDICATOR"
                    else -> null
                }
                
                if (newButtonType != null) {
                    deviceList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val newHoverState = HoverState(hoveredDeviceIndex = index, hoveredButtonType = newButtonType)
                    if (getHoverState() != newHoverState) {
                        setHoverState(newHoverState)
                        deviceList.repaint()
                    }
                } else {
                    deviceList.cursor = Cursor.getDefaultCursor()
                    if (getHoverState().hoveredDeviceIndex == index) {
                        setHoverState(HoverState.noHover())
                        deviceList.repaint()
                    }
                }
            }
            else -> {
                deviceList.cursor = Cursor.getDefaultCursor()
                setHoverState(HoverState.noHover())
            }
        }
    }

    private fun updateCursorAndHoverState(index: Int, newButtonType: String?) {
        deviceList.cursor = if (newButtonType != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        val currentHoverState = getHoverState()
        val newHoverState = if (index != -1 && newButtonType != null) {
            HoverState(hoveredDeviceIndex = index, hoveredButtonType = newButtonType)
        } else {
            HoverState.noHover()
        }
        if (currentHoverState != newHoverState) {
            setHoverState(newHoverState)
            deviceList.repaint()
        }
    }

    private fun handleMouseClick(e: MouseEvent) {
        val index = deviceList.locationToIndex(e.point)
        if (index == -1 || index >= deviceListModel.size()) return
        val item = deviceListModel.getElementAt(index)
        
        PluginLogger.debug(
            LogCategory.UI_EVENTS,
            "Mouse click at point: %s, index: %d, item type: %s",
            e.point, index, item.javaClass.simpleName
        )
        
        when (item) {
            is DeviceListItem.Device -> {
                val bounds = deviceList.getCellBounds(index, index)
                val deviceInfo = item.info
                val buttonLayout = DeviceConnectionUtils.calculateButtonLayout(
                    bounds,
                    DeviceConnectionUtils.isWifiConnection(deviceInfo.logicalSerialNumber)
                )
                when {
                    buttonLayout.mirrorButtonRect.contains(e.point) -> onMirrorClick(deviceInfo)
                    buttonLayout.wifiButtonRect?.contains(e.point) == true -> deviceInfo.device?.let { onWifiClick(it) }
                }
            }
            is DeviceListItem.CombinedDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                val buttonRects = combinedDeviceRenderer.calculateButtonRects(item.info, bounds)
                
                PluginLogger.debug(LogCategory.UI_EVENTS, 
                    "Click at: %s, bounds: %s", 
                    e.point, bounds)
                
                when {
                    buttonRects.checkboxRect?.contains(e.point) == true -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Click HIT: Checkbox for %s", item.info.displayName)
                        // Переключаем состояние чекбокса
                        item.info.isSelectedForAdb = !item.info.isSelectedForAdb
                        onAdbCheckboxChanged(item.info, item.info.isSelectedForAdb)
                        deviceList.repaint()
                    }
                    buttonRects.resetSizeRect?.contains(e.point) == true -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Click HIT: Reset Size for %s", item.info.displayName)
                        onResetSize(item.info)
                    }
                    buttonRects.resetDpiRect?.contains(e.point) == true -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Click HIT: Reset DPI for %s", item.info.displayName)
                        onResetDpi(item.info)
                    }
                    buttonRects.editSizeRect?.contains(e.point) == true -> {
                        // Показываем диалог редактирования размера
                        val currentSizeText = when {
                            item.info.isLoadingCurrentParams -> "Loading..."
                            item.info.currentResolution != null -> "${item.info.currentResolution.first}x${item.info.currentResolution.second}"
                            else -> "N/A"
                        }
                        val newSize = JOptionPane.showInputDialog(
                            deviceList,
                            "Enter new size (e.g., 1080x1920):",
                            "Edit Size",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            null,
                            currentSizeText
                        ) as? String
                        
                        if (newSize != null && newSize.isNotBlank() && newSize != currentSizeText) {
                            onApplyChanges(item.info, newSize, null)
                        }
                    }
                    buttonRects.editDpiRect?.contains(e.point) == true -> {
                        // Показываем диалог редактирования DPI
                        val currentDpiText = when {
                            item.info.isLoadingCurrentParams -> "Loading..."
                            item.info.currentDpi != null -> item.info.currentDpi.toString()
                            else -> "N/A"
                        }
                        val newDpi = JOptionPane.showInputDialog(
                            deviceList,
                            "Enter new DPI (e.g., 320):",
                            "Edit DPI",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            null,
                            currentDpiText
                        ) as? String
                        
                        if (newDpi != null && newDpi.isNotBlank() && newDpi != currentDpiText) {
                            onApplyChanges(item.info, null, newDpi)
                        }
                    }
                    buttonRects.usbMirrorRect?.contains(e.point) == true -> {
                        // Кликаем только если есть USB подключение
                        if (item.info.hasUsbConnection) {
                            item.info.usbDevice?.let { onMirrorClick(it) }
                        }
                    }
                    buttonRects.wifiConnectRect?.contains(e.point) == true -> {
                        item.info.usbDevice?.device?.let { onWifiClick(it) }
                    }
                    buttonRects.wifiMirrorRect?.contains(e.point) == true -> {
                        item.info.wifiDevice?.let { onMirrorClick(it) }
                    }
                    buttonRects.wifiDisconnectRect?.contains(e.point) == true -> {
                        val ip = item.info.wifiDevice?.ipAddress ?: item.info.ipAddress
                        ip?.let { onWifiDisconnect(it) }
                    }
                }
            }
            is DeviceListItem.WifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                // Преобразуем координаты мыши относительно ячейки
                val cellRelativePoint = Point(e.point.x - bounds.x, e.point.y - bounds.y)
                // Для хитбоксов используем координаты относительно начала координат (0,0)
                val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                
                when {
                    connectButtonRect.contains(cellRelativePoint) -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Connect button clicked!")
                        // Подключаемся к устройству по Wi-Fi
                        handleConnectHistoryDevice(item.entry)
                    }
                    deleteButtonRect.contains(cellRelativePoint) -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Delete button clicked!")
                        handleDeleteHistoryDevice(item.entry)
                    }
                }
            }
            is DeviceListItem.GroupedWifiHistoryDevice -> {
                val bounds = deviceList.getCellBounds(index, index)
                // Преобразуем координаты мыши относительно ячейки
                val cellRelativePoint = Point(e.point.x - bounds.x, e.point.y - bounds.y)
                // Для хитбоксов используем координаты относительно начала координат (0,0)
                val cellBoundsAtOrigin = Rectangle(0, 0, bounds.width, bounds.height)
                val deleteButtonRect = getDeleteButtonRect(cellBoundsAtOrigin)
                val connectButtonRect = getConnectButtonRect(cellBoundsAtOrigin)
                
                when {
                    connectButtonRect.contains(cellRelativePoint) -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Connect button clicked for grouped device!")
                        // Подключаемся ко всем IP адресам параллельно
                        handleConnectGroupedHistoryDevice(item)
                    }
                    deleteButtonRect.contains(cellRelativePoint) -> {
                        PluginLogger.debug(LogCategory.UI_EVENTS, "Delete button clicked for grouped device!")
                        // Удаляем все IP адреса для этого устройства
                        handleDeleteGroupedHistoryDevice(item)
                    }
                }
            }
            else -> return
        }
        deviceList.clearSelection()
    }

    private fun resetHoverState() {
        val currentHoverState = getHoverState()
        if (currentHoverState.hoveredDeviceIndex != -1 && currentHoverState.hoveredButtonType != null) {
            setHoverState(HoverState.noHover())
            deviceList.cursor = Cursor.getDefaultCursor()
            deviceList.repaint()
        }
    }

    /**
     * Обновляет список объединёнными устройствами (USB + Wi-Fi в одном элементе)
     */
    fun updateCombinedDeviceList(devices: List<CombinedDeviceInfo>) {
        SwingUtilities.invokeLater {
            deviceListModel.clear()
            
            // Обновляем текст для пустого списка
            if (devices.isEmpty() && deviceListModel.isEmpty) {
                deviceList.emptyText.clear()
                deviceList.emptyText.text = "No devices found"
                deviceList.emptyText.appendLine("Connect a device via USB or enable Wi-Fi debugging")
            }
            
            // 1. Подключённые устройства
            if (devices.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Connected devices"))
                
                // Сортируем устройства согласно сохраненному порядку
                val savedOrder = DeviceOrderService.loadDeviceOrder()
                val sortedDevices = if (savedOrder.isNotEmpty()) {
                    devices.sortedBy { device ->
                        val index = savedOrder.indexOf(device.baseSerialNumber)
                        if (index == -1) Int.MAX_VALUE else index
                    }
                } else {
                    devices
                }
                
                sortedDevices.forEach { device ->
                    deviceListModel.addElement(DeviceListItem.CombinedDevice(device))
                }
            }
            
            // 2. История Wi-Fi устройств (которые сейчас не подключены)
            val history = WifiDeviceHistoryService.getHistory()
            
            // Получаем серийные номера подключенных устройств
            val connectedSerials = devices.flatMap { device ->
                val serials = mutableListOf<String>()
                // Добавляем базовый серийник
                serials.add(device.baseSerialNumber)
                // Добавляем серийники от USB и Wi-Fi устройств
                device.usbDevice?.let {
                    serials.add(it.logicalSerialNumber)
                    it.displaySerialNumber?.let { serial -> serials.add(serial) }
                }
                device.wifiDevice?.let {
                    serials.add(it.logicalSerialNumber)
                    it.displaySerialNumber?.let { serial -> serials.add(serial) }
                }
                serials
            }.toSet()
            
            // Фильтруем историю - исключаем устройства, которые сейчас подключены
            val notConnectedHistory = history.filter { historyEntry ->
                val realSerial = historyEntry.realSerialNumber ?: historyEntry.logicalSerialNumber
                realSerial !in connectedSerials
            }
            
            // Группируем по реальному серийному номеру
            val groupedBySerial = notConnectedHistory.groupBy { 
                it.realSerialNumber ?: it.logicalSerialNumber 
            }
            
            if (groupedBySerial.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Previously connected devices"))
                
                // Для каждой группы устройств с одинаковым серийником
                groupedBySerial.forEach { (_, entries) ->
                    // Берем последнюю запись (с самым свежим IP)
                    val latestEntry = entries.last()
                    
                    // Если есть еще записи с другими IP, создаем модифицированную запись
                    if (entries.size > 1) {
                        val otherIPs = entries.dropLast(1).map { "${it.ipAddress}:${it.port}" }
                        deviceListModel.addElement(DeviceListItem.GroupedWifiHistoryDevice(latestEntry, otherIPs))
                    } else {
                        deviceListModel.addElement(DeviceListItem.WifiHistoryDevice(latestEntry))
                    }
                }
            }
            
            deviceList.revalidate()
            deviceList.repaint()
        }
    }
    
    fun updateDeviceList(devices: List<DeviceInfo>) {
        SwingUtilities.invokeLater {
            deviceListModel.clear()
            // 1. Подключённые устройства
            if (devices.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Connected devices"))
                
                // Сортируем устройства согласно сохраненному порядку
                val savedOrder = DeviceOrderService.loadDeviceOrder()
                val sortedDevices = if (savedOrder.isNotEmpty()) {
                    devices.sortedBy { device ->
                        val index = savedOrder.indexOf(device.logicalSerialNumber)
                        if (index == -1) Int.MAX_VALUE else index
                    }
                } else {
                    devices
                }
                
                sortedDevices.forEach {
                    deviceListModel.addElement(DeviceListItem.Device(it, true))
                }
            }
            // 2. Ранее подключённые по Wi-Fi
            val history = WifiDeviceHistoryService.getHistory()
            
            // Получаем серийные номера подключенных устройств
            val connectedSerials = devices.flatMap { device ->
                listOfNotNull(
                    device.logicalSerialNumber,
                    device.displaySerialNumber
                )
            }.toSet()
            
            // Фильтруем историю - исключаем устройства, которые сейчас подключены
            val notConnectedHistory = history.filter { historyEntry ->
                val realSerial = historyEntry.realSerialNumber ?: historyEntry.logicalSerialNumber
                realSerial !in connectedSerials
            }
            
            // Группируем по реальному серийному номеру
            val groupedBySerial = notConnectedHistory.groupBy { 
                it.realSerialNumber ?: it.logicalSerialNumber 
            }
            
            if (groupedBySerial.isNotEmpty()) {
                deviceListModel.addElement(DeviceListItem.SectionHeader("Previously connected devices"))
                
                // Для каждой группы устройств с одинаковым серийником
                groupedBySerial.forEach { (_, entries) ->
                    // Берем последнюю запись (с самым свежим IP)
                    val latestEntry = entries.last()
                    
                    // Если есть еще записи с другими IP, создаем модифицированную запись
                    if (entries.size > 1) {
                        val otherIPs = entries.dropLast(1).map { "${it.ipAddress}:${it.port}" }
                        deviceListModel.addElement(DeviceListItem.GroupedWifiHistoryDevice(latestEntry, otherIPs))
                    } else {
                        deviceListModel.addElement(DeviceListItem.WifiHistoryDevice(latestEntry))
                    }
                }
            }
        }
    }

    fun getDeviceListModel(): DefaultListModel<DeviceListItem> = deviceListModel
    
    /**
     * Настраивает drag and drop для списка устройств
     */
    private fun setupDragAndDrop() {
        deviceList.dragEnabled = true
        deviceList.dropMode = DropMode.INSERT
        deviceList.transferHandler = DeviceTransferHandler()
    }
    
    /**
     * TransferHandler для перетаскивания устройств
     */
    private inner class DeviceTransferHandler : TransferHandler() {
        private var draggedIndex = -1
        
        override fun getSourceActions(c: JComponent): Int = MOVE
        
        override fun createTransferable(c: JComponent): Transferable? {
            if (c !is JList<*>) return null
            
            // Получаем индекс элемента под курсором
            val point = deviceList.mousePosition ?: return null
            val index = deviceList.locationToIndex(point)
            if (index < 0 || index >= deviceListModel.size()) return null
            
            val item = deviceListModel.getElementAt(index)
            // Разрешаем перетаскивать только CombinedDevice
            if (item !is DeviceListItem.CombinedDevice) return null
            
            draggedIndex = index
            return StringSelection(item.info.baseSerialNumber)
        }
        
        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return false
            }
            
            val dropLocation = support.dropLocation as? JList.DropLocation ?: return false
            val dropIndex = dropLocation.index
            
            // Запрещаем вставку в самое начало (индекс 0 - перед заголовком "Connected devices")
            if (dropIndex <= 0) {
                return false
            }
            
            // Находим границы секции Connected devices
            var connectedDevicesStart = -1
            var connectedDevicesEnd = -1
            
            for (i in 0 until deviceListModel.size()) {
                when (val item = deviceListModel.getElementAt(i)) {
                    is DeviceListItem.SectionHeader -> {
                        if (item.title == "Connected devices") {
                            connectedDevicesStart = i + 1 // Начало секции - после заголовка
                        } else if (item.title == "Previously connected devices") {
                            connectedDevicesEnd = i // Конец секции - перед следующим заголовком
                            break
                        }
                    }
                    else -> {}
                }
            }
            
            // Если секция Connected devices найдена
            if (connectedDevicesStart != -1) {
                // Если нет заголовка Previously connected, конец секции - конец списка Connected устройств
                if (connectedDevicesEnd == -1) {
                    // Ищем последнее подключенное устройство
                    for (i in deviceListModel.size() - 1 downTo connectedDevicesStart) {
                        val item = deviceListModel.getElementAt(i)
                        if (item is DeviceListItem.CombinedDevice) {
                            connectedDevicesEnd = i + 1
                            break
                        }
                    }
                    // Если не нашли устройств, но есть заголовок, разрешаем вставку сразу после него
                    if (connectedDevicesEnd == -1) {
                        connectedDevicesEnd = connectedDevicesStart
                    }
                }
                
                // Разрешаем вставку только в пределах секции Connected devices
                return dropIndex in connectedDevicesStart..connectedDevicesEnd
            }
            
            return false
        }
        
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            
            val dropLocation = support.dropLocation as JList.DropLocation
            val dropIndex = dropLocation.index
            
            // Корректируем индекс, учитывая заголовок секции
            var adjustedDropIndex = dropIndex
            
            // Если вставляем в начало списка устройств (после заголовка)
            if (dropIndex == 1) {
                adjustedDropIndex = 1
            }
            
            // Если перетаскиваемый элемент находится выше точки вставки, корректируем индекс
            if (draggedIndex in 0 until dropIndex) {
                adjustedDropIndex--
            }
            
            // Перемещаем элемент в модели
            if (draggedIndex >= 0 && draggedIndex != adjustedDropIndex) {
                val item = deviceListModel.remove(draggedIndex)
                
                // Вставляем на новое место
                deviceListModel.add(adjustedDropIndex, item)
                
                // Сохраняем новый порядок
                saveCurrentDeviceOrder()
                
                // Обновляем отображение
                deviceList.repaint()
            }
            
            return true
        }
        
        override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
            draggedIndex = -1
        }
    }
    
    /**
     * Сохраняет текущий порядок устройств
     */
    private fun saveCurrentDeviceOrder() {
        val deviceSerials = mutableListOf<String>()
        
        for (i in 0 until deviceListModel.size()) {
            val item = deviceListModel.getElementAt(i)
            if (item is DeviceListItem.CombinedDevice) {
                deviceSerials.add(item.info.baseSerialNumber)
            }
        }
        
        DeviceOrderService.saveDeviceOrder(deviceSerials)
    }

    /**
     * Получает подсказку для комбинированного устройства в зависимости от позиции мыши
     */
    private fun getTooltipForCombinedDevice(device: CombinedDeviceInfo, point: Point, bounds: Rectangle): String? {
        val buttonRects = combinedDeviceRenderer.calculateButtonRects(device, bounds)
        
        // Получаем координаты полей из конфигурации
        val defaultSizeFieldRect = HitboxConfigManager.getHitboxRect(
            DeviceType.CONNECTED, 
            HitboxType.DEFAULT_SIZE_TOOLTIP, 
            bounds
        )
        val defaultDpiFieldRect = HitboxConfigManager.getHitboxRect(
            DeviceType.CONNECTED, 
            HitboxType.DEFAULT_DPI_TOOLTIP, 
            bounds
        )
        
        // Получаем координаты иконок из конфигурации
        val usbIconRect = HitboxConfigManager.getHitboxRect(
            DeviceType.CONNECTED,
            HitboxType.USB_ICON_TOOLTIP,
            bounds
        )
        val wifiIconRect = HitboxConfigManager.getHitboxRect(
            DeviceType.CONNECTED,
            HitboxType.WIFI_ICON_TOOLTIP,
            bounds
        )
        
        return when {
            // Чекбокс для выбора устройства
            buttonRects.checkboxRect?.contains(point) == true -> {
                if (device.isSelectedForAdb) {
                    "Device is selected for ADB commands. Click to deselect"
                } else {
                    "Select this device for ADB commands"
                }
            }
            
            // Кнопки управления параметрами
            buttonRects.resetSizeRect?.contains(point) == true -> "Reset to default size"
            buttonRects.resetDpiRect?.contains(point) == true -> "Reset to default DPI"
            buttonRects.editSizeRect?.contains(point) == true -> "Click to edit screen resolution"
            buttonRects.editDpiRect?.contains(point) == true -> "Click to edit DPI"
            
            // Поля с дефолтными значениями
            defaultSizeFieldRect?.contains(point) == true -> {
                val defaultSize = device.defaultResolution?.let { "${it.first}x${it.second}" } ?: "N/A"
                "Device default resolution: $defaultSize"
            }
            defaultDpiFieldRect?.contains(point) == true -> {
                val defaultDpi = device.defaultDpi?.toString() ?: "N/A"
                "Device default DPI: $defaultDpi"
            }
            
            // Кнопки подключения
            buttonRects.usbMirrorRect?.contains(point) == true -> {
                if (device.hasUsbConnection) "Mirror screen via USB" else "Cannot mirror: USB not connected"
            }
            buttonRects.wifiConnectRect?.contains(point) == true -> "Connect to this device via Wi-Fi"
            buttonRects.wifiMirrorRect?.contains(point) == true -> "Mirror screen via Wi-Fi"
            buttonRects.wifiDisconnectRect?.contains(point) == true -> "Disconnect Wi-Fi connection"
            
            // Иконки статуса
            usbIconRect?.contains(point) == true -> {
                if (device.hasUsbConnection) "Connected via USB" else "USB not connected"
            }
            wifiIconRect?.contains(point) == true -> {
                when {
                    device.hasWifiConnection -> "Connected via Wi-Fi at ${device.wifiDevice?.ipAddress ?: device.ipAddress}"
                    device.hasUsbConnection -> "Wi-Fi not connected. Click 'Connect' button to connect via Wi-Fi"
                    else -> "Wi-Fi not connected"
                }
            }
            
            else -> null
        }
    }
    
    /**
     * Вычисляет прямоугольник кнопки удаления
     */
    private fun getDeleteButtonRect(bounds: Rectangle): Rectangle {
        // Пытаемся загрузить позицию из JSON конфигурации
        return try {
            HitboxConfigManager.getHitboxRect(
                DeviceType.PREVIOUSLY_CONNECTED,
                HitboxType.DELETE,
                bounds
            ) ?: calculateFallbackDeleteRect(bounds)
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load delete button rect from config", e)
            calculateFallbackDeleteRect(bounds)
        }
    }
    
    /**
     * Вычисляет прямоугольник индикатора группировки +N
     */
    private fun getGroupIndicatorRect(bounds: Rectangle): Rectangle {
        // Пытаемся загрузить позицию из JSON конфигурации
        return try {
            HitboxConfigManager.getHitboxRect(
                DeviceType.PREVIOUSLY_CONNECTED,
                HitboxType.GROUP_INDICATOR,
                bounds
            ) ?: calculateFallbackGroupIndicatorRect(bounds)
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load group indicator rect from config", e)
            calculateFallbackGroupIndicatorRect(bounds)
        }
    }
    
    private fun calculateFallbackGroupIndicatorRect(bounds: Rectangle): Rectangle {
        // Резервные значения если конфигурация не загружена
        val x = bounds.x + 280  // После IP:порт во второй строке
        val y = bounds.y + 20   // Вторая строка  
        val width = 35          // Ширина плашки с отступами
        val height = 18         // Высота плашки
        return Rectangle(x, y, width, height)
    }
    
    private fun getConnectButtonRect(bounds: Rectangle): Rectangle {
        // Пытаемся загрузить позицию из JSON конфигурации
        return try {
            HitboxConfigManager.getHitboxRect(
                DeviceType.PREVIOUSLY_CONNECTED,
                HitboxType.CONNECT,
                bounds
            ) ?: calculateFallbackConnectRect(bounds)
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load connect button rect from config", e)
            calculateFallbackConnectRect(bounds)
        }
    }
    
    /**
     * Вычисляет fallback позицию для кнопки удаления
     */
    private fun calculateFallbackDeleteRect(bounds: Rectangle): Rectangle {
        // Delete button теперь справа с фиксированной позицией благодаря BorderLayout.EAST
        // bounds - это размеры ячейки относительно (0,0)
        // Учитываем padding панели (2px сверху/снизу, 5px слева/справа из BorderFactory.createEmptyBorder)
        val deleteButtonX = bounds.width - DELETE_BUTTON_WIDTH - 5 // 5px - правый padding панели
        val deleteButtonY = (bounds.height - DELETE_BUTTON_HEIGHT) / 2
        val rect = Rectangle(deleteButtonX, deleteButtonY, DELETE_BUTTON_WIDTH, DELETE_BUTTON_HEIGHT)
        
        PluginLogger.debugWithRateLimit(
            LogCategory.UI_EVENTS,
            "prev_connected_delete_rect",
            "Previously connected - Delete button rect: %s for bounds %s",
            rect, bounds
        )
        return rect
    }
    
    /**
     * Вычисляет fallback позицию для кнопки подключения
     */
    private fun calculateFallbackConnectRect(bounds: Rectangle): Rectangle {
        // Connect button теперь справа с фиксированной позицией благодаря BorderLayout.EAST
        // bounds - это размеры ячейки относительно (0,0)
        // Connect идёт первой в GridBagLayout панели кнопок
        val connectButtonX = bounds.width - 65 - DELETE_BUTTON_WIDTH - 8 - 5 // 65 (width) + 35 (delete) + 8 (gap между кнопками) + 5 (правый padding)
        val connectButtonY = (bounds.height - 22) / 2  
        val rect = Rectangle(connectButtonX, connectButtonY, 65, 22)
        
        PluginLogger.debugWithRateLimit(
            LogCategory.UI_EVENTS,
            "prev_connected_connect_rect",
            "Previously connected - Connect button rect: %s for bounds %s",
            rect, bounds
        )
        return rect
    }

    private fun handleConnectHistoryDevice(entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry) {
        // Используем тот же метод подключения, что и в Connected devices
        onWifiConnectByIp(entry.ipAddress, entry.port)
    }
    
    private fun handleDeleteHistoryDevice(entry: WifiDeviceHistoryService.WifiDeviceHistoryEntry) {
        val skipConfirm = properties.getBoolean(CONFIRM_DELETE_KEY, false)
        var doDelete = true
        if (!skipConfirm) {
            val checkbox = JCheckBox("Don't ask again")
            val result = JOptionPane.showConfirmDialog(
                this,
                arrayOf("Delete device from history?", checkbox),
                "Confirm deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (checkbox.isSelected) {
                properties.setValue(CONFIRM_DELETE_KEY, true)
            }
            doDelete = result == JOptionPane.YES_OPTION
        }
        if (doDelete) {
            val newHistory = WifiDeviceHistoryService.getHistory().filterNot {
                it.ipAddress == entry.ipAddress && it.port == entry.port
            }
            WifiDeviceHistoryService.saveHistory(newHistory)
            // Обновляем список
            updateDeviceList(getAllDevices())
            // Форсируем обновление списка устройств
            onForceUpdate()
        }
    }
    
    private fun handleDeleteGroupedHistoryDevice(item: DeviceListItem.GroupedWifiHistoryDevice) {
        val skipConfirm = properties.getBoolean(CONFIRM_DELETE_KEY, false)
        var doDelete = true
        if (!skipConfirm) {
            val checkbox = JCheckBox("Don't ask again")
            val message = "Delete all ${item.otherIPs.size + 1} IP addresses for this device from history?"
            val result = JOptionPane.showConfirmDialog(
                this,
                arrayOf(message, checkbox),
                "Confirm deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (checkbox.isSelected) {
                properties.setValue(CONFIRM_DELETE_KEY, true)
            }
            doDelete = result == JOptionPane.YES_OPTION
        }
        if (doDelete) {
            val currentHistory = WifiDeviceHistoryService.getHistory()
            val serialToRemove = item.entry.realSerialNumber ?: item.entry.logicalSerialNumber
            
            // Удаляем все записи с этим серийным номером
            val newHistory = currentHistory.filterNot { historyEntry ->
                val historySerial = historyEntry.realSerialNumber ?: historyEntry.logicalSerialNumber
                historySerial == serialToRemove
            }
            
            WifiDeviceHistoryService.saveHistory(newHistory)
            // Обновляем список
            updateDeviceList(getAllDevices())
            // Форсируем обновление списка устройств
            onForceUpdate()
        }
    }
    
    /**
     * Подключается ко всем IP адресам группированного устройства параллельно
     */
    private fun handleConnectGroupedHistoryDevice(item: DeviceListItem.GroupedWifiHistoryDevice) {
        // Собираем все IP адреса устройства
        val allIPs = mutableListOf<Pair<String, Int>>()
        
        // Добавляем основной IP
        allIPs.add(item.entry.ipAddress to item.entry.port)
        
        // Добавляем остальные IP из группы otherIPs
        item.otherIPs.forEach { ipWithPort ->
            // Парсим IP:порт
            val parts = ipWithPort.split(":")
            if (parts.size == 2) {
                val ip = parts[0]
                val port = parts[1].toIntOrNull() ?: 5555
                allIPs.add(ip to port)
            }
        }
        
        val deviceName = item.entry.displayName
        val deviceSerial = item.entry.realSerialNumber ?: item.entry.logicalSerialNumber
        
        PluginLogger.debug("Attempting parallel connection to ${allIPs.size} IP addresses for device $deviceName ($deviceSerial)")
        PluginLogger.debug("IP addresses to try: ${allIPs.joinToString(", ") { "${it.first}:${it.second}" }}")
        
        // Проверяем, подключено ли устройство по USB сейчас
        val currentDevices = getAllDevices()
        val usbDevice = currentDevices.find { device ->
            !DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber) &&
            (device.displaySerialNumber == deviceSerial || device.logicalSerialNumber == deviceSerial)
        }
        
        if (usbDevice != null && usbDevice.device != null) {
            // Устройство подключено по USB - сначала включаем TCP/IP
            PluginLogger.debug("Device is connected via USB, enabling TCP/IP mode first")
            Thread {
                try {
                    val tcpResult = AdbService.enableTcpIp(usbDevice.device, 5555)
                    if (tcpResult.isSuccess()) {
                        PluginLogger.debug("TCP/IP mode enabled successfully, waiting 2 seconds before connecting...")
                        Thread.sleep(2000) // Даем время устройству переключиться
                        
                        // Теперь пробуем подключиться
                        if (onParallelWifiConnect != null) {
                            onParallelWifiConnect.invoke(allIPs)
                        } else {
                            allIPs.forEach { (ip, port) ->
                                PluginLogger.debug("Initiating connection to $ip:$port for device $deviceName")
                                onWifiConnectByIp(ip, port)
                            }
                        }
                    } else {
                        PluginLogger.debug("Failed to enable TCP/IP mode: ${tcpResult.getErrorMessage()}")
                        // Все равно пробуем подключиться - может уже включено
                        if (onParallelWifiConnect != null) {
                            onParallelWifiConnect.invoke(allIPs)
                        } else {
                            allIPs.forEach { (ip, port) ->
                                onWifiConnectByIp(ip, port)
                            }
                        }
                    }
                } catch (e: Exception) {
                    PluginLogger.debug("Error enabling TCP/IP mode: ${e.message}")
                }
            }.start()
        } else {
            // Устройство не подключено по USB
            PluginLogger.info("Device $deviceName not connected via USB, attempting direct Wi-Fi connection")
            
            // Пробуем подключиться напрямую - возможно TCP/IP уже включен
            // Используем специальный callback для параллельного подключения
            if (onParallelWifiConnect != null) {
                onParallelWifiConnect.invoke(allIPs)
            } else {
                // Иначе используем обычный callback для каждого IP
                allIPs.forEach { (ip, port) ->
                    PluginLogger.debug("Attempting connection to $ip:$port for device $deviceName")
                    onWifiConnectByIp(ip, port)
                }
            }
            
            // Показываем подсказку пользователю через 3 секунды если подключение не удалось
            Thread {
                Thread.sleep(3000)
                SwingUtilities.invokeLater {
                    // Проверяем, подключилось ли устройство
                    val currentDevices = getAllDevices()
                    val wifiConnected = currentDevices.any { device ->
                        DeviceConnectionUtils.isWifiConnection(device.logicalSerialNumber) &&
                        allIPs.any { (ip, port) -> device.logicalSerialNumber.contains("$ip:$port") }
                    }
                    
                    if (!wifiConnected) {
                        // Просто логируем, без диалога
                        PluginLogger.info("""
                            Failed to connect to device $deviceName via Wi-Fi.
                            Device needs to be connected via USB first to enable TCP/IP mode.
                        """.trimIndent())
                    }
                }
            }.start()
        }
    }
    
    /**
     * Создает панель с визуальной отладкой для Previously connected devices
     */
    private fun createDebugWifiHistoryPanel(
        list: JList<out DeviceListItem>
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
                background = list.background
                isOpaque = true
            }
            
            override fun paint(g: Graphics) {
                super.paint(g)
                
                // Рисуем хитбоксы поверх содержимого
                if (PluginSettings.instance.debugHitboxes) {
                    val g2d = g.create() as Graphics2D
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    
                    // Используем те же методы, что и для обработки кликов
                    val cellBoundsAtOrigin = Rectangle(0, 0, width, height)
                    val connectRect = getConnectButtonRect(cellBoundsAtOrigin)
                    val deleteRect = getDeleteButtonRect(cellBoundsAtOrigin)
                    
                    // Настройка прозрачности
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                    
                    // Рисуем кнопку Connect - оранжевая
                    g2d.color = JBColor.ORANGE
                    g2d.fillRect(connectRect.x, connectRect.y, connectRect.width, connectRect.height)
                    g2d.color = Color.ORANGE.darker()
                    g2d.drawRect(connectRect.x, connectRect.y, connectRect.width, connectRect.height)
                    
                    // Рисуем кнопку Delete - красная
                    g2d.color = JBColor.RED
                    g2d.fillRect(deleteRect.x, deleteRect.y, deleteRect.width, deleteRect.height)
                    g2d.color = Color.RED.darker()
                    g2d.drawRect(deleteRect.x, deleteRect.y, deleteRect.width, deleteRect.height)
                    
                    // Добавляем подписи
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
                    g2d.color = JBColor.BLACK
                    g2d.font = Font("Arial", Font.PLAIN, 9)
                    
                    g2d.drawString("CN", connectRect.x + 2, connectRect.y - 2)
                    g2d.drawString("DEL", deleteRect.x + 2, deleteRect.y - 2)
                    
                    g2d.dispose()
                }
            }
        }
    }
    
    /**
     * Создает панель с визуальной отладкой для группированных Previously connected devices
     */
    private fun createDebugGroupedWifiHistoryPanel(
        list: JList<out DeviceListItem>
    ): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
                background = list.background
                isOpaque = true
            }
            
            override fun paint(g: Graphics) {
                super.paint(g)
                
                // Рисуем хитбоксы поверх содержимого
                if (PluginSettings.instance.debugHitboxes) {
                    val g2d = g.create() as Graphics2D
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    
                    // Используем те же методы, что и для обработки кликов
                    val cellBoundsAtOrigin = Rectangle(0, 0, width, height)
                    val connectRect = getConnectButtonRect(cellBoundsAtOrigin)
                    val deleteRect = getDeleteButtonRect(cellBoundsAtOrigin)
                    val indicatorRect = getGroupIndicatorRect(cellBoundsAtOrigin)
                    
                    // Настройка прозрачности
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                    
                    // Рисуем кнопку Connect - оранжевая
                    g2d.color = JBColor.ORANGE
                    g2d.fillRect(connectRect.x, connectRect.y, connectRect.width, connectRect.height)
                    g2d.color = Color.ORANGE.darker()
                    g2d.drawRect(connectRect.x, connectRect.y, connectRect.width, connectRect.height)
                    
                    // Рисуем кнопку Delete - красная
                    g2d.color = JBColor.RED
                    g2d.fillRect(deleteRect.x, deleteRect.y, deleteRect.width, deleteRect.height)
                    g2d.color = Color.RED.darker()
                    g2d.drawRect(deleteRect.x, deleteRect.y, deleteRect.width, deleteRect.height)
                    
                    // Рисуем индикатор +N - синий
                    g2d.color = JBColor.BLUE
                    g2d.fillRect(indicatorRect.x, indicatorRect.y, indicatorRect.width, indicatorRect.height)
                    g2d.color = Color.BLUE.darker()
                    g2d.drawRect(indicatorRect.x, indicatorRect.y, indicatorRect.width, indicatorRect.height)
                    
                    // Подписи
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
                    g2d.color = JBColor.BLACK
                    g2d.font = Font("Arial", Font.PLAIN, 9)
                    g2d.drawString("C", connectRect.x + 25, connectRect.y + 14)
                    g2d.drawString("D", deleteRect.x + 9, deleteRect.y + 14)
                    g2d.drawString("+N", indicatorRect.x + 10, indicatorRect.y + 12)
                    
                    g2d.dispose()
                }
            }
        }
    }
}