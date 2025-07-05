// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/SettingsDialog.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.DeviceStateService
import io.github.qavlad.adbrandomizer.services.PresetApplicationService
import io.github.qavlad.adbrandomizer.services.SettingsDialogUpdateNotifier
import io.github.qavlad.adbrandomizer.services.SettingsService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.border.Border
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class SettingsDialog(private val project: Project?) : DialogWrapper(project) {
    private lateinit var table: JBTable
    private lateinit var tableModel: DevicePresetTableModel
    private var updateListener: (() -> Unit)? = null

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()

        // Добавляем hover эффекты для кнопок диалога
        SwingUtilities.invokeLater {
            addHoverEffectToDialogButtons()
        }
        
        // Обновляем состояние устройств при открытии диалога только если нет активных пресетов
        if (project != null) {
            val activePresets = DeviceStateService.getCurrentActivePresets()
            if (activePresets.activeSizePreset == null && activePresets.activeDpiPreset == null) {
                DeviceStateService.refreshDeviceStates(project)
            }
            // Небольшая задержка для загрузки состояния устройств
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        
        // Подписываемся на уведомления об обновлениях
        updateListener = {
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        updateListener?.let { SettingsDialogUpdateNotifier.addListener(it) }
    }

    private fun addHoverEffectToDialogButtons() {
        // Находим и обрабатываем кнопки OK и Cancel
        fun processButtons(container: java.awt.Container) {
            for (component in container.components) {
                when (component) {
                    is JButton -> {
                        if (component.text == "Save" || component.text == "Cancel") {
                            ButtonUtils.addHoverEffect(component)
                        }
                    }
                    is java.awt.Container -> processButtons(component)
                }
            }
        }

        processButtons(contentPane)
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        val presets = SettingsService.getPresets()
        val dataVector = Vector<Vector<Any>>()
        presets.forEachIndexed { index, preset ->
            val row = Vector<Any>()
            row.add("☰")
            row.add(index + 1) // Номер строки
            row.add(preset.label)
            row.add(preset.size)
            row.add(preset.dpi)
            row.add("Delete")
            dataVector.add(row)
        }

        tableModel = DevicePresetTableModel(dataVector, columnNames)
        tableModel.addTableModelListener { 
            validateFields()
            // Обновляем индикаторы при изменении данных
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }

        table = JBTable(tableModel)

        setupTable()
        validateFields()

        val scrollPane = JBScrollPane(table).apply { preferredSize = Dimension(650, 400) }
        val buttonPanel = createButtonPanel()

        val tablePanel = JPanel(BorderLayout()).apply {
            add(table.tableHeader, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(tablePanel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }
    }

    private fun setupTable() {
        table.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = JBUI.scale(35)
            dragEnabled = true
            dropMode = DropMode.INSERT_ROWS
            transferHandler = PresetTransferHandler()
            
            // Убираем синее выделение строк
            selectionModel.clearSelection()
            setRowSelectionAllowed(false)
            setCellSelectionEnabled(false)

            // Настраиваем быстрое появление tooltip
            val toolTipManager = ToolTipManager.sharedInstance()
            toolTipManager.initialDelay = 100
            toolTipManager.dismissDelay = 5000
            toolTipManager.reshowDelay = 50

            // Отключаем hover эффекты, которые могут влиять на цвет
            putClientProperty("JTable.stripedBackground", false)
            putClientProperty("Table.isFileList", false)

            // Колонка 0: Drag handle
            columnModel.getColumn(0).apply {
                minWidth = JBUI.scale(30)
                maxWidth = JBUI.scale(30)
                cellRenderer = object : DefaultTableCellRenderer() {
                    init {
                        horizontalAlignment = CENTER
                        isOpaque = true
                    }

                    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                        toolTipText = "Drag and drop"
                        return component
                    }
                }
            }

            // Колонка 1: Номер строки
            columnModel.getColumn(1).apply {
                minWidth = JBUI.scale(40)
                maxWidth = JBUI.scale(40)
                cellRenderer = object : DefaultTableCellRenderer() {
                    init {
                        horizontalAlignment = CENTER
                        isOpaque = true
                    }
                }
            }

            // Колонка 5: Delete button (было 4, теперь 5)
            columnModel.getColumn(5).apply {
                minWidth = JBUI.scale(40)
                maxWidth = JBUI.scale(40)
                cellRenderer = ButtonRenderer()
                cellEditor = ButtonEditor(table)
            }
            setDefaultRenderer(Object::class.java, ValidationRenderer())
            
            // Добавляем контекстное меню
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }
                
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }
            })
        }
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel()

        val addButton = JButton("Add Preset", AllIcons.General.Add).apply {
            addActionListener {
                // Добавляем новую строку
                val newRowIndex = tableModel.rowCount
                tableModel.addRow(Vector(listOf("☰", newRowIndex + 1, "", "", "", "Delete")))

                // Выделяем новую строку, прокручиваем к ней и начинаем редактирование колонки Label
                SwingUtilities.invokeLater {
                    table.setRowSelectionInterval(newRowIndex, newRowIndex)

                    // Прокручиваем таблицу к новой строке
                    table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true)) // Теперь Label в колонке 2

                    // Начинаем редактирование колонки Label (индекс 2)
                    table.editCellAt(newRowIndex, 2)
                    table.editorComponent?.requestFocus()
                }
            }
        }
        ButtonUtils.addHoverEffect(addButton)
        panel.add(addButton)

        val importButton = JButton("Import Common Devices").apply {
            addActionListener {
                val commonPresets = listOf(
                    DevicePreset("Pixel 6 Pro", "1440x3120", "512"),
                    DevicePreset("Pixel 5", "1080x2340", "432")
                )
                val existingLabels = tableModel.getPresets().map { it.label }.toSet()
                commonPresets.forEach {
                    if (!existingLabels.contains(it.label)) {
                        val newRowIndex = tableModel.rowCount
                        tableModel.addRow(Vector(listOf("☰", newRowIndex + 1, it.label, it.size, it.dpi, "Delete")))
                    }
                }
            }
        }
        ButtonUtils.addHoverEffect(importButton)
        panel.add(importButton)

        return panel
    }
    
    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return
        
        val preset = getPresetAtRow(row)
        
        val popupMenu = JPopupMenu()
        
        // Проверяем, есть ли DPI в пресете
        if (preset.dpi.isNotBlank()) {
            val applyDpiItem = JMenuItem("Apply DPI only (${preset.dpi})")
            applyDpiItem.addActionListener {
                applyPresetFromRow(row, setSize = false, setDpi = true)
            }
            popupMenu.add(applyDpiItem)
        }
        
        // Проверяем, есть ли Size в пресете
        if (preset.size.isNotBlank()) {
            val applySizeItem = JMenuItem("Apply Size only (${preset.size})")
            applySizeItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = false)
            }
            popupMenu.add(applySizeItem)
        }
        
        // Добавляем "Apply Size and DPI" только если есть и DPI и Size
        if (preset.dpi.isNotBlank() && preset.size.isNotBlank()) {
            val applyBothItem = JMenuItem("Apply Size and DPI")
            applyBothItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = true)
            }
            popupMenu.add(applyBothItem)
        }
        
        if (popupMenu.componentCount > 0) {
            popupMenu.show(e.component, e.x, e.y)
        }
    }
    
    private fun getPresetAtRow(row: Int): DevicePreset {
        return DevicePreset(
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
    }
    
    private fun applyPresetFromRow(row: Int, setSize: Boolean, setDpi: Boolean) {
        if (project != null) {
            // Берем текущее состояние пресета из таблицы (может быть отредактированным)
            val currentPreset = getPresetAtRow(row)
            
            PresetApplicationService.applyPreset(project, currentPreset, setSize, setDpi)
            
            // Обновляем таблицу после применения пресета
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
    }
    


    private fun validateFields() {
        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            val size = tableModel.getValueAt(i, 3) as? String ?: "" // Теперь Size в колонке 3
            val dpi = tableModel.getValueAt(i, 4) as? String ?: ""  // Теперь DPI в колонке 4
            if (size.isNotBlank() && !ValidationUtils.isValidSizeFormat(size)) allValid = false
            if (dpi.isNotBlank() && !ValidationUtils.isValidDpi(dpi)) allValid = false
        }
        isOKActionEnabled = allValid
        table.repaint()
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()

        // Удаляем пустые строки перед сохранением
        val rowsToRemove = mutableListOf<Int>()

        // Проходим по всем строкам и находим пустые
        for (i in 0 until tableModel.rowCount) {
            val label = (tableModel.getValueAt(i, 2) as? String ?: "").trim()
            val size = (tableModel.getValueAt(i, 3) as? String ?: "").trim()
            val dpi = (tableModel.getValueAt(i, 4) as? String ?: "").trim()

            // Если все три поля пустые, помечаем строку для удаления
            if (label.isEmpty() && size.isEmpty() && dpi.isEmpty()) {
                rowsToRemove.add(i)
            }
        }

        // Удаляем строки в обратном порядке (с конца), чтобы не сбить индексы
        rowsToRemove.reversed().forEach { rowIndex ->
            tableModel.removeRow(rowIndex)
        }

        // Сохраняем только непустые пресеты
        SettingsService.savePresets(tableModel.getPresets())
        
        // Отписываемся от уведомлений
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        
        super.doOKAction()
    }
    
    override fun doCancelAction() {
        // Отписываемся от уведомлений
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        super.doCancelAction()
    }

    enum class IndicatorType {
        NONE, GREEN, YELLOW, GRAY
    }

    inner class ValidationRenderer : DefaultTableCellRenderer() {
        
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            isOpaque = true
            
            // Убираем любое выделение
            background = table.background
            foreground = table.foreground

            when (column) {
                0, 1, 5 -> {
                    // Для колонок с иконками, номерами и кнопками - стандартное поведение
                    border = null
                }
                2 -> {
                    // Для колонки Label - с индикатором если активен весь пресет
                    val preset = getPresetAtRow(row)
                    val activePresets = DeviceStateService.getCurrentActivePresets()
                    
                    val sizeIndicator = getIndicatorType(preset, activePresets, isSize = true)
                    val dpiIndicator = getIndicatorType(preset, activePresets, isSize = false)
                    
                    val text = value as? String ?: ""
                    
                    // Добавляем галочку только если активны ОБА параметра (Size И DPI)
                    if (sizeIndicator != IndicatorType.NONE && dpiIndicator != IndicatorType.NONE && text.isNotBlank()) {
                        // Для серого индикатора не добавляем никаких символов
                        if (sizeIndicator == IndicatorType.GRAY || dpiIndicator == IndicatorType.GRAY) {
                            println("ADB_DEBUG: Рендерим СЕРУЮ рамку для ${preset.label} (sizeIndicator=$sizeIndicator, dpiIndicator=$dpiIndicator)")
                            this.text = text
                            foreground = table.foreground
                            border = GrayParameterBorder()
                        } else {
                            val indicator = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                "✓ " // Желтая галка для измененных
                            } else {
                                "✓ " // Зеленая галка для точно совпадающих
                            }
                            this.text = indicator + text
                            foreground = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                JBColor.ORANGE
                            } else {
                                JBColor.GREEN.darker()
                            }
                            
                            // Добавляем обводку для Label если активен полный пресет
                            border = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                                YellowParameterBorder()
                            } else {
                                ActiveParameterBorder()
                            }
                        }
                    } else {
                        this.text = text
                        foreground = table.foreground
                        border = null
                    }
                }
                3, 4 -> {
                    // Для колонок Size и DPI - с валидацией и индикаторами активности
                    val text = value as? String ?: ""
                    val isValid = if (text.isBlank()) true else when (column) {
                        3 -> ValidationUtils.isValidSizeFormat(text) // Size теперь в колонке 3
                        4 -> ValidationUtils.isValidDpi(text)         // DPI теперь в колонке 4
                        else -> true
                    }

                    val preset = getPresetAtRow(row)
                    val activePresets = DeviceStateService.getCurrentActivePresets()
                    
                    val isSize = column == 3
                    val indicatorType = getIndicatorType(preset, activePresets, isSize = isSize)

                    if (!isValid) {
                        background = JBColor.PINK
                        foreground = JBColor.BLACK
                        border = null
                        this.text = text
                    } else {
                        // Добавляем обводку для активных параметров
                        border = when (indicatorType) {
                            IndicatorType.GRAY -> GrayParameterBorder()
                            IndicatorType.YELLOW -> YellowParameterBorder()
                            IndicatorType.GREEN -> ActiveParameterBorder()
                            else -> null
                        }
                        
                        // Добавляем индикатор к тексту для активных параметров
                        if (indicatorType != IndicatorType.NONE && text.isNotBlank()) {
                            if (indicatorType == IndicatorType.GRAY) {
                                // Для серого индикатора - просто текст без символов
                                println("ADB_DEBUG: Рендерим серую рамку в колонке ${if (isSize) "SIZE" else "DPI"} для пресета с текстом '$text'")
                                this.text = text
                                foreground = table.foreground
                            } else {
                                // Для остальных - галочка
                                val indicator = "✓ "
                                this.text = indicator + text
                                foreground = when (indicatorType) {
                                    IndicatorType.YELLOW -> JBColor.ORANGE
                                    else -> JBColor.GREEN.darker()
                                }
                            }
                        } else {
                            this.text = text
                            foreground = table.foreground
                        }
                    }
                }
            }

            return this
        }
        
        private fun getIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo, isSize: Boolean): IndicatorType {
            return if (isSize) {
                getSizeIndicatorType(preset, activePresets)
            } else {
                getDpiIndicatorType(preset, activePresets)
            }
        }
        
        private fun getSizeIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
            return getPresetIndicatorType(
                presetValue = preset.size,
                preset = preset,
                resetPreset = activePresets.resetSizePreset,
                activeValue = activePresets.activeSizePreset?.size,
                originalPreset = activePresets.originalSizePreset,
                originalValue = activePresets.originalSizePreset?.size
            )
        }
        
        private fun getDpiIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
            return getPresetIndicatorType(
                presetValue = preset.dpi,
                preset = preset,
                resetPreset = activePresets.resetDpiPreset,
                activeValue = activePresets.activeDpiPreset?.dpi,
                originalPreset = activePresets.originalDpiPreset,
                originalValue = activePresets.originalDpiPreset?.dpi
            )
        }
        
        private fun getPresetIndicatorType(
            presetValue: String,
            preset: DevicePreset,
            resetPreset: DevicePreset?,
            activeValue: String?,
            originalPreset: DevicePreset?,
            originalValue: String?
        ): IndicatorType {
            if (presetValue.isBlank()) return IndicatorType.NONE
            
            // Проверяем, был ли этот пресет сброшен (приоритет)
            if (resetPreset?.label == preset.label) {
                return IndicatorType.GRAY
            }
            
            return getParameterIndicatorType(
                preset = preset,
                presetValue = presetValue,
                activeValue = activeValue,
                originalPreset = originalPreset,
                originalValue = originalValue
            )
        }
        
        private fun getParameterIndicatorType(
            preset: DevicePreset,
            presetValue: String,
            activeValue: String?,
            originalPreset: DevicePreset?,
            originalValue: String?
        ): IndicatorType {
            // Проверяем точное совпадение с текущим состоянием устройств
            val isCurrentlyActive = activeValue == presetValue
            
            if (isCurrentlyActive) {
                // Проверяем, был ли этот параметр из этого пресета применен изначально
                val wasFromThisPreset = originalPreset?.label == preset.label
                
                if (wasFromThisPreset) {
                    // Проверяем, изменился ли параметр в этом пресете с момента применения
                    val isModified = presetValue != originalValue
                    return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
                } else {
                    // Параметр совпадает, но был применен из другого пресета - желтый
                    return IndicatorType.YELLOW
                }
            }
            
            // Проверяем, не изменился ли активный пресет (для желтой галки без активного совпадения)
            val isFromOriginalPreset = originalPreset?.label == preset.label
            
            if (isFromOriginalPreset) {
                val isModified = presetValue != originalValue
                return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
            }
            
            return IndicatorType.NONE
        }
    }
    
    // Кастомная обводка для активных параметров
    inner class ActiveParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.GREEN
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
    }
    
    // Кастомная обводка для измененных активных параметров
    inner class YellowParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.ORANGE
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
    }
    
    // Кастомная обводка для сброшенных параметров
    inner class GrayParameterBorder : Border {
        override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
            g?.let { graphics ->
                val g2d = graphics as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.color = JBColor.GRAY
                g2d.drawRect(x, y, width - 1, height - 1)
            }
        }

        override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
        override fun isBorderOpaque(): Boolean = false
    }
}

private class ButtonRenderer : JButton(AllIcons.Actions.Cancel), TableCellRenderer {
    init {
        isOpaque = true
        preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        background = if (isSelected) table.selectionBackground else UIManager.getColor("Button.background")
        return this
    }
}

private class ButtonEditor(private val table: JTable) : AbstractTableCellEditor(), TableCellEditor {
    private val button = JButton(AllIcons.Actions.Cancel)

    init {
        button.preferredSize = Dimension(JBUI.scale(40), JBUI.scale(35))
        button.minimumSize = button.preferredSize
        button.maximumSize = button.preferredSize

        button.addActionListener {
            // Когда кнопка нажата, мы получаем строку, которая сейчас редактируется.
            // Это всегда будет правильная строка.
            val modelRow = table.convertRowIndexToModel(table.editingRow)

            // Останавливаем редактирование, чтобы избежать ошибок
            fireEditingStopped()

            if (modelRow != -1) {
                (table.model as DefaultTableModel).removeRow(modelRow)
            }
        }
    }

    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        button.background = table.selectionBackground
        return button
    }

    override fun getCellEditorValue(): Any = "Delete"
}