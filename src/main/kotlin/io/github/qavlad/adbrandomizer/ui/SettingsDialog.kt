// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/SettingsDialog.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor // ИСПРАВЛЕНО
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.SettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SettingsDialog(project: Project?) : DialogWrapper(project) {

    private val presetsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val sizeRegex = Regex("""^\d+\s*[xхXХ]\s*\d+$""")
    private val dpiRegex = Regex("""^\d*$""")

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()
        loadPresets()
        validateFields()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        val headerPanel = JPanel(GridLayout(1, 4, JBUI.scale(10), 0))
        headerPanel.add(JLabel("Label"))
        headerPanel.add(JLabel("Size (e.g., 1080x1920)"))
        headerPanel.add(JLabel("DPI (e.g., 480)"))
        headerPanel.add(Box.createRigidArea(Dimension(JBUI.scale(30), 0)))
        headerPanel.border = JBUI.Borders.emptyBottom(5)
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(presetsPanel).apply {
            preferredSize = Dimension(600, 400)
            verticalScrollBar.unitIncrement = 16
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        val addButton = JButton("Add Preset", AllIcons.General.Add)
        addButton.addActionListener {
            val newRow = addPresetRow(DevicePreset("", "", ""))
            val newLabelField = newRow.getComponent(0) as JBTextField

            SwingUtilities.invokeLater {
                val rect = newRow.bounds
                presetsPanel.scrollRectToVisible(rect)
                newLabelField.requestFocusInWindow()
            }
        }
        mainPanel.add(addButton, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun loadPresets() {
        presetsPanel.removeAll()
        SettingsService.getPresets().forEach { addPresetRow(it) }
        presetsPanel.revalidate()
        presetsPanel.repaint()
    }

    private fun addPresetRow(preset: DevicePreset): JPanel {
        val rowPanel = JPanel(GridLayout(1, 4, JBUI.scale(10), 0))
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(30))

        val labelField = JBTextField(preset.label)
        val sizeField = JBTextField(preset.size)
        val dpiField = JBTextField(preset.dpi)

        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = validateFields()
            override fun removeUpdate(e: DocumentEvent?) = validateFields()
            override fun changedUpdate(e: DocumentEvent?) = validateFields()
        }
        sizeField.document.addDocumentListener(listener)
        dpiField.document.addDocumentListener(listener)

        val deleteButton = JButton(AllIcons.Actions.Cancel)
        deleteButton.addActionListener {
            presetsPanel.remove(rowPanel)
            validateFields()
            presetsPanel.revalidate()
            presetsPanel.repaint()
        }

        rowPanel.add(labelField)
        rowPanel.add(sizeField)
        rowPanel.add(dpiField)
        rowPanel.add(deleteButton)

        presetsPanel.add(rowPanel)
        presetsPanel.revalidate()
        presetsPanel.repaint()

        return rowPanel
    }

    private fun validateFields() {
        var allFieldsValid = true
        for (component in presetsPanel.components) {
            if (component is JPanel && component.componentCount >= 3) {
                val sizeField = component.getComponent(1) as JBTextField
                val dpiField = component.getComponent(2) as JBTextField

                val isSizeValid = sizeField.text.isBlank() || sizeRegex.matches(sizeField.text)
                // ИСПРАВЛЕНО: используем JBColor
                sizeField.background = if (isSizeValid) UIManager.getColor("TextField.background") else JBColor.PINK
                if (!isSizeValid) allFieldsValid = false

                val isDpiValid = dpiField.text.isBlank() || dpiRegex.matches(dpiField.text)
                // ИСПРАВЛЕНО: используем JBColor
                dpiField.background = if (isDpiValid) UIManager.getColor("TextField.background") else JBColor.PINK
                if (!isDpiValid) allFieldsValid = false
            }
        }
        isOKActionEnabled = allFieldsValid
    }

    override fun doValidate(): ValidationInfo? {
        for (component in presetsPanel.components) {
            if (component is JPanel && component.componentCount >= 3) {
                val sizeField = component.getComponent(1) as JBTextField
                val dpiField = component.getComponent(2) as JBTextField
                if (!sizeField.text.isBlank() && !sizeRegex.matches(sizeField.text)) {
                    return ValidationInfo("Invalid format in 'Size' field. Use '1080x1920' format.", sizeField)
                }
                if (!dpiField.text.isBlank() && !dpiRegex.matches(dpiField.text)) {
                    return ValidationInfo("Invalid format in 'DPI' field. Use numbers only.", dpiField)
                }
            }
        }
        return null
    }

    override fun doOKAction() {
        val newPresets = mutableListOf<DevicePreset>()
        for (component in presetsPanel.components) {
            if (component is JPanel && component.componentCount >= 3) {
                val label = (component.getComponent(0) as JBTextField).text
                val size = (component.getComponent(1) as JBTextField).text.replace(" ", "").replace(Regex("[хХ]"), "x")
                val dpi = (component.getComponent(2) as JBTextField).text.replace(" ", "")

                if (label.isNotBlank() || size.isNotBlank() || dpi.isNotBlank()) {
                    newPresets.add(DevicePreset(label, size, dpi))
                }
            }
        }
        SettingsService.savePresets(newPresets)
        super.doOKAction()
    }
}