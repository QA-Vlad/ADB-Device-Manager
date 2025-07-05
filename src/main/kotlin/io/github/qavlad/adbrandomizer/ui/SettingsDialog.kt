package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.table.TableCellRenderer

class SettingsDialog(private val project: Project?) : DialogWrapper(project) {
    private lateinit var table: JBTable
    private lateinit var tableModel: DevicePresetTableModel
    private var updateListener: (() -> Unit)? = null

    private var hoverState = HoverState.noHover()
    private val historyManager = HistoryManager()
    private lateinit var keyboardHandler: KeyboardHandler
    private lateinit var tableConfigurator: TableConfigurator
    private lateinit var validationRenderer: ValidationRenderer

    private var editingCellOldValue: String? = null
    private var editingCellRow: Int = -1
    private var editingCellColumn: Int = -1

    private fun onRowMoved(fromIndex: Int, toIndex: Int) {
        historyManager.onRowMoved(fromIndex, toIndex)

        if (hoverState.selectedTableRow == fromIndex) {
            hoverState = hoverState.withTableSelection(toIndex, hoverState.selectedTableColumn)
        } else if (hoverState.selectedTableRow != -1) {
            val selectedRow = hoverState.selectedTableRow
            val newSelectedRow = when {
                fromIndex < toIndex && selectedRow in (fromIndex + 1)..toIndex -> selectedRow - 1
                fromIndex > toIndex && selectedRow in toIndex until fromIndex -> selectedRow + 1
                else -> selectedRow
            }
            if (newSelectedRow != selectedRow) {
                hoverState = hoverState.withTableSelection(newSelectedRow, hoverState.selectedTableColumn)
            }
        }

        SwingUtilities.invokeLater {
            table.repaint()
        }
    }

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()

        SwingUtilities.invokeLater {
            addHoverEffectToDialogButtons()
        }

        if (project != null) {
            val activePresets = DeviceStateService.getCurrentActivePresets()
            if (activePresets.activeSizePreset == null && activePresets.activeDpiPreset == null) {
                DeviceStateService.refreshDeviceStates(project)
            }
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }

        updateListener = {
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        updateListener?.let { SettingsDialogUpdateNotifier.addListener(it) }
    }

    private fun addHoverEffectToDialogButtons() {
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
            row.add(index + 1)
            row.add(preset.label)
            row.add(preset.size)
            row.add(preset.dpi)
            row.add("Delete")
            dataVector.add(row)
        }

        tableModel = DevicePresetTableModel(dataVector, columnNames, historyManager)
        tableModel.addTableModelListener {
            validateFields()
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }

        table = object : JBTable(tableModel) {
            @Suppress("DEPRECATION")
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                val component = super.prepareRenderer(renderer, row, column)

                if (component is JComponent) {
                    val isHovered = hoverState.isTableCellHovered(row, column)
                    val isSelectedCell = hoverState.isTableCellSelected(row, column)

                    var isInvalidCell = false
                    if (column in 3..4) {
                        val value = tableModel.getValueAt(row, column)
                        val text = value as? String ?: ""
                        val isValid = if (text.isBlank()) true else when (column) {
                            3 -> ValidationUtils.isValidSizeFormat(text)
                            4 -> ValidationUtils.isValidDpi(text)
                            else -> true
                        }
                        if (!isValid) {
                            isInvalidCell = true
                            component.background = JBColor.PINK
                            component.foreground = JBColor.BLACK
                            component.isOpaque = true
                        }
                    }

                    if (!isInvalidCell) {
                        if (isSelectedCell) {
                            component.background = JBColor(Color(230, 230, 250), Color(80, 80, 100))
                            component.isOpaque = true
                        } else if (isHovered) {
                            component.background = JBColor(Gray._240, Gray._70)
                            component.isOpaque = true
                        } else {
                            component.background = UIManager.getColor("Table.background") ?: JBColor.WHITE
                            component.isOpaque = true
                        }
                    }
                }

                return component
            }

            override fun editCellAt(row: Int, column: Int): Boolean {
                if (row >= 0 && column >= 0) {
                    editingCellOldValue = tableModel.getValueAt(row, column) as? String ?: ""
                    editingCellRow = row
                    editingCellColumn = column
                }

                return super.editCellAt(row, column)
            }

            override fun removeEditor() {
                if (editingCellOldValue != null) {
                    editingCellOldValue = null
                    editingCellRow = -1
                    editingCellColumn = -1
                }
                super.removeEditor()
            }

            override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
                if (rowIndex >= 0 && columnIndex >= 0 && columnIndex in 2..4) {
                    val oldRow = selectionModel.leadSelectionIndex
                    val oldColumn = columnModel.selectionModel.leadSelectionIndex

                    if (oldRow != rowIndex || oldColumn != columnIndex) {
                        editingCellOldValue = tableModel.getValueAt(rowIndex, columnIndex) as? String ?: ""
                        editingCellRow = rowIndex
                        editingCellColumn = columnIndex
                    }
                }

                super.changeSelection(rowIndex, columnIndex, toggle, extend)
            }
        }

        // Инициализируем компоненты после создания таблицы
        validationRenderer = ValidationRenderer(
            hoverState = { hoverState },
            getPresetAtRow = ::getPresetAtRow,
            findDuplicates = { (table.model as DevicePresetTableModel).findDuplicates() }
        )

        keyboardHandler = KeyboardHandler(
            table = table,
            tableModel = tableModel,
            hoverState = { hoverState },
            historyManager = historyManager,
            validateFields = ::validateFields,
            setEditingCellData = { oldValue, row, column ->
                editingCellOldValue = oldValue
                editingCellRow = row
                editingCellColumn = column
            },
            onDuplicate = ::duplicatePreset
        )

        tableConfigurator = TableConfigurator(
            table = table,
            hoverState = { hoverState },
            setHoverState = { newState -> hoverState = newState },
            onRowMoved = ::onRowMoved,
            onCellClicked = ::handleCellClick,
            onTableExited = ::handleTableExit,
            validationRenderer = validationRenderer,
            showContextMenu = ::showContextMenu
        )

        tableConfigurator.configure()
        table.addKeyListener(keyboardHandler.createTableKeyListener())
        keyboardHandler.addGlobalKeyListener()
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

    private fun handleCellClick(row: Int, column: Int, clickCount: Int) {
        if (row >= 0 && column >= 0) {
            val oldSelectedRow = hoverState.selectedTableRow
            val oldSelectedColumn = hoverState.selectedTableColumn

            // Всегда выделяем строку. Колонку выделяем, только если она редактируемая.
            val newSelectedColumn = if (column in 2..4) column else -1
            hoverState = hoverState.withTableSelection(row, newSelectedColumn)

            // Перерисовываем старую выделенную ячейку, если она была
            if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                val oldRect = table.getCellRect(oldSelectedRow, oldSelectedColumn, false)
                table.repaint(oldRect)
            }

            // Перерисовываем новую ячейку/строку
            val newRect = table.getCellRect(row, column, false)
            table.repaint(newRect)

            table.requestFocus()

            // Двойной клик для редактирования работает только на разрешенных колонках
            if (clickCount == 2 && column in 2..4) {
                table.editCellAt(row, column)
                table.editorComponent?.requestFocus()
            }
        } else {
            // Эта часть для сброса выделения при клике мимо таблицы, она работает верно
            val oldSelectedRow = hoverState.selectedTableRow
            val oldSelectedColumn = hoverState.selectedTableColumn

            hoverState = hoverState.clearTableSelection()

            if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                val oldRect = table.getCellRect(oldSelectedRow, oldSelectedColumn, false)
                table.repaint(oldRect)
            }
        }
    }

    private fun handleTableExit() {
        val oldHoverState = hoverState
        hoverState = hoverState.clearTableHover()

        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
            table.repaint(oldRect)
        }
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel()

        val addButton = JButton("Add Preset", AllIcons.General.Add).apply {
            addActionListener {
                val newRowIndex = tableModel.rowCount
                tableModel.addRow(Vector(listOf("☰", newRowIndex + 1, "", "", "", "Delete")))

                SwingUtilities.invokeLater {
                    hoverState = hoverState.withTableSelection(newRowIndex, 2)
                    table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true))
                    table.editCellAt(newRowIndex, 2)
                    table.editorComponent?.requestFocus()
                    table.repaint()
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

    private fun getPresetAtRow(row: Int): DevicePreset {
        return DevicePreset(
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
    }

    private fun validateFields() {
        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            val size = tableModel.getValueAt(i, 3) as? String ?: ""
            val dpi = tableModel.getValueAt(i, 4) as? String ?: ""
            if (size.isNotBlank() && !ValidationUtils.isValidSizeFormat(size)) allValid = false
            if (dpi.isNotBlank() && !ValidationUtils.isValidDpi(dpi)) allValid = false
        }
        isOKActionEnabled = allValid
        table.repaint()
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()

        val rowsToRemove = mutableListOf<Int>()

        for (i in 0 until tableModel.rowCount) {
            val label = (tableModel.getValueAt(i, 2) as? String ?: "").trim()
            val size = (tableModel.getValueAt(i, 3) as? String ?: "").trim()
            val dpi = (tableModel.getValueAt(i, 4) as? String ?: "").trim()

            if (label.isEmpty() && size.isEmpty() && dpi.isEmpty()) {
                rowsToRemove.add(i)
            }
        }

        rowsToRemove.reversed().forEach { rowIndex ->
            tableModel.removeRow(rowIndex)
        }

        SettingsService.savePresets(tableModel.getPresets())

        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        keyboardHandler.removeGlobalKeyListener()

        super.doOKAction()
    }

    override fun doCancelAction() {
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        keyboardHandler.removeGlobalKeyListener()

        super.doCancelAction()
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return

        val preset = getPresetAtRow(row)
        val popupMenu = JPopupMenu()

        val shortcut = if (SystemInfo.isMac) "Cmd+D" else "Ctrl+D"
        val duplicateItem = JMenuItem("Duplicate ($shortcut)")
        duplicateItem.addActionListener {
            duplicatePreset(row)
        }
        popupMenu.add(duplicateItem)
        popupMenu.addSeparator()

        if (preset.dpi.isNotBlank()) {
            val applyDpiItem = JMenuItem("Apply DPI only (${preset.dpi})")
            applyDpiItem.addActionListener {
                applyPresetFromRow(row, setSize = false, setDpi = true)
            }
            popupMenu.add(applyDpiItem)
        }

        if (preset.size.isNotBlank()) {
            val applySizeItem = JMenuItem("Apply Size only (${preset.size})")
            applySizeItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = false)
            }
            popupMenu.add(applySizeItem)
        }

        if (preset.dpi.isNotBlank() && preset.size.isNotBlank()) {
            val applyBothItem = JMenuItem("Apply Size and DPI")
            applyBothItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = true)
            }
            popupMenu.add(applyBothItem)
        }

        if (popupMenu.componentCount > 2) { // Проверяем, есть ли что-то кроме дубликата и разделителя
            popupMenu.show(e.component, e.x, e.y)
        } else if (popupMenu.componentCount > 0 && popupMenu.getComponent(0) == duplicateItem) {
            popupMenu.show(e.component, e.x, e.y)
        }
    }

    private fun applyPresetFromRow(row: Int, setSize: Boolean, setDpi: Boolean) {
        if (project != null) {
            val currentPreset = getPresetAtRow(row)

            PresetApplicationService.applyPreset(project, currentPreset, setSize, setDpi)

            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
    }

    private fun duplicatePreset(row: Int) {
        if (row < 0 || row >= tableModel.rowCount) return

        val originalPreset = getPresetAtRow(row)
        val newPreset = originalPreset.copy(label = "${originalPreset.label} (copy)")

        // Конвертируем DevicePreset обратно в Vector для вставки
        val newRowVector = Vector<Any>()
        newRowVector.add("☰")
        newRowVector.add(0) // Номер будет обновлен автоматически
        newRowVector.add(newPreset.label)
        newRowVector.add(newPreset.size)
        newRowVector.add(newPreset.dpi)
        newRowVector.add("Delete")

        val insertIndex = row + 1
        (table.model as DevicePresetTableModel).insertRow(insertIndex, newRowVector)

        // Выделяем новую строку и начинаем редактирование
        SwingUtilities.invokeLater {
            table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
            hoverState = hoverState.withTableSelection(insertIndex, 2)
            table.editCellAt(insertIndex, 2)
            table.editorComponent?.requestFocusInWindow()
            table.repaint()
        }
    }
}