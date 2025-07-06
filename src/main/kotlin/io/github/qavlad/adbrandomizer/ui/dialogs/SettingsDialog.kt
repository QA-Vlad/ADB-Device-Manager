package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Диалог настроек ADB Randomizer.
 * Использует SettingsDialogController для управления логикой.
 */
class SettingsDialog(project: Project?) : DialogWrapper(project) {
    
    private val controller = SettingsDialogController(project, this)

    init {
        title = "ADB Randomizer Settings"
        setOKButtonText("Save")
        init()
        
        // Инициализируем контроллер после создания диалога
        SwingUtilities.invokeLater {
            controller.addHoverEffectToDialogButtons(contentPane)
            controller.initialize()
        }
    }

    override fun createCenterPanel(): JComponent {
        // Создаем модель и таблицу через контроллер
        val tableModel = controller.createTableModel()
        val table = controller.createTable(tableModel)
        
        // Инициализируем обработчики
        controller.initializeHandlers()

        // Создаем UI
        val scrollPane = JBScrollPane(table).apply { 
            preferredSize = Dimension(650, 400) 
        }
        
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

    private fun createButtonPanel(): JPanel {
        val panel = JPanel()

        val addButton = JButton("Add Preset", AllIcons.General.Add).apply {
            addActionListener {
                controller.addNewPreset()
            }
        }
        ButtonUtils.addHoverEffect(addButton)
        panel.add(addButton)

        val importButton = JButton("Import Common Devices").apply {
            addActionListener {
                controller.importCommonDevices()
            }
        }
        ButtonUtils.addHoverEffect(importButton)
        panel.add(importButton)

        return panel
    }

    override fun doOKAction() {
        controller.saveSettings()
        controller.dispose()
        super.doOKAction()
    }

    override fun doCancelAction() {
        controller.dispose()
        super.doCancelAction()
    }
}
