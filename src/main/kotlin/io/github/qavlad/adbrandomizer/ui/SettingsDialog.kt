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
import io.github.qavlad.adbrandomizer.services.SettingsService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class SettingsDialog(project: Project?) : DialogWrapper(project) {
    private lateinit var table: JBTable
    private lateinit var tableModel: DevicePresetTableModel

    private val sizeRegex = Regex("""^\d+\s*[xхXХ]\s*\d+$""")
    private val dpiRegex = Regex("""^\d+$""")

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = Vector(listOf(" ", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        val presets = SettingsService.getPresets()
        val dataVector = Vector<Vector<Any>>()
        presets.forEach {
            val row = Vector<Any>()
            row.add("☰")
            row.add(it.label)
            row.add(it.size)
            row.add(it.dpi)
            row.add("Delete")
            dataVector.add(row)
        }

        tableModel = DevicePresetTableModel(dataVector, columnNames)
        tableModel.addTableModelListener { validateFields() }
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
            dropMode = DropMode.USE_SELECTION
            transferHandler = PresetTransferHandler()

            columnModel.getColumn(0).apply {
                minWidth = JBUI.scale(30)
                maxWidth = JBUI.scale(30)
                cellRenderer = object : DefaultTableCellRenderer() { init { horizontalAlignment = CENTER } }
            }
            columnModel.getColumn(4).apply {
                minWidth = JBUI.scale(40)
                maxWidth = JBUI.scale(40)
                cellRenderer = ButtonRenderer()
                cellEditor = ButtonEditor(table) // Передаем саму таблицу
            }
            setDefaultRenderer(Object::class.java, ValidationRenderer())
        }
    }

    private fun createButtonPanel(): JPanel {
        val panel = JPanel()
        panel.add(JButton("Add Preset", AllIcons.General.Add).apply {
            addActionListener {
                tableModel.addRow(Vector(listOf("☰", "", "", "", "Delete")))
            }
        })

        val importButton = JButton("Import Common Devices")
        importButton.addActionListener {
            val commonPresets = listOf(
                DevicePreset("Pixel 6 Pro", "1440x3120", "512"),
                DevicePreset("Pixel 5", "1080x2340", "432")
            )
            val existingLabels = tableModel.getPresets().map { it.label }.toSet()
            commonPresets.forEach {
                if (!existingLabels.contains(it.label)) {
                    tableModel.addRow(Vector(listOf("☰", it.label, it.size, it.dpi, "Delete")))
                }
            }
        }
        panel.add(importButton)

        return panel
    }

    private fun validateFields() {
        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            val size = tableModel.getValueAt(i, 2) as? String ?: ""
            val dpi = tableModel.getValueAt(i, 3) as? String ?: ""
            if (size.isNotBlank() && !sizeRegex.matches(size)) allValid = false
            if (dpi.isNotBlank() && !dpiRegex.matches(dpi)) allValid = false
        }
        isOKActionEnabled = allValid
        table.repaint()
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        SettingsService.savePresets(tableModel.getPresets())
        super.doOKAction()
    }

    inner class ValidationRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (column == 0 || column == 4) {
                background = if (isSelected) table.selectionBackground else table.background
                return component
            }

            val text = value as? String ?: ""
            val isValid = if (text.isBlank()) true else when (column) {
                2 -> sizeRegex.matches(text)
                3 -> dpiRegex.matches(text)
                else -> true
            }

            component.background = if (isSelected) table.selectionBackground else if (isValid) table.background else JBColor.PINK
            return component
        }
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