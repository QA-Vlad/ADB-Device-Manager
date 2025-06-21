// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/SettingsDialog.kt

package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.SettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class SettingsDialog(project: Project?) : DialogWrapper(project) {

    private val presetsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()
        loadPresets()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        val headerPanel = JPanel(GridLayout(1, 4, JBUI.scale(10), 0))
        headerPanel.add(JLabel("Label"))
        headerPanel.add(JLabel("Size (e.g., 1080x1920)"))
        headerPanel.add(JLabel("DPI (e.g., 480)"))
        headerPanel.add(Box.createRigidArea(Dimension(30, 0)))
        headerPanel.border = JBUI.Borders.emptyBottom(5)
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(presetsPanel).apply {
            preferredSize = Dimension(600, 400)
            verticalScrollBar.unitIncrement = 16
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        val addButton = JButton("Add Preset", AllIcons.General.Add)
        addButton.addActionListener {
            addPresetRow(DevicePreset("", "", ""))
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

    private fun addPresetRow(preset: DevicePreset) {
        val rowPanel = JPanel(GridLayout(1, 4, JBUI.scale(10), 0))
        rowPanel.maximumSize = Dimension(Integer.MAX_VALUE, JBUI.scale(30))

        val labelField = JBTextField(preset.label)
        val sizeField = JBTextField(preset.size)
        val dpiField = JBTextField(preset.dpi)

        // =================================================================
        // ФИНАЛЬНОЕ ИСПРАВЛЕНИЕ: Используем гарантированно существующую иконку
        // =================================================================
        val deleteButton = JButton(AllIcons.General.Remove)
        deleteButton.addActionListener {
            presetsPanel.remove(rowPanel)
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
    }

    override fun doOKAction() {
        val newPresets = mutableListOf<DevicePreset>()
        for (component in presetsPanel.components) {
            if (component is JPanel && component.componentCount >= 3) {
                val label = (component.getComponent(0) as? JBTextField)?.text ?: ""
                val size = (component.getComponent(1) as? JBTextField)?.text ?: ""
                val dpi = (component.getComponent(2) as? JBTextField)?.text ?: ""

                if (label.isNotBlank() || size.isNotBlank() || dpi.isNotBlank()) {
                    newPresets.add(DevicePreset(label, size, dpi))
                }
            }
        }
        SettingsService.savePresets(newPresets)
        super.doOKAction()
    }
}