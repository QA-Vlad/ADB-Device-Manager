package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.ui.components.TableWithAddButtonPanel
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import com.intellij.icons.AllIcons
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.Desktop

/**
 * Диалог управления пресетами ADB Randomizer.
 * Использует PresetsDialogController для управления логикой.
 */
class PresetsDialog(project: Project?) : DialogWrapper(project) {
    
    private val controller = PresetsDialogController(project, this)

    init {
        PluginLogger.debug(LogCategory.UI_EVENTS, "PresetsDialog constructor called")
        title = "ADB Randomizer Presets"
        setOKButtonText("Save")
        init()
        
        // Инициализируем контроллер после создания диалога
        SwingUtilities.invokeLater {
            controller.addHoverEffectToDialogButtons(contentPane)
            controller.initialize()
            
            // Устанавливаем фокус на таблицу после полной инициализации
            SwingUtilities.invokeLater {
                controller.setFocusToTable()
            }
            
            // Добавляем ховер эффект к кнопке Open presets folder
            addHoverEffectToLeftButton()
        }
    }

    override fun createCenterPanel(): JComponent {
        PluginLogger.debug(LogCategory.UI_EVENTS, "createCenterPanel called")
        
        // Создаем панель управления списками
        val listManagerPanel = controller.createListManagerPanel()
        
        // Создаем модель и таблицу через контроллер
        val tableModel = controller.createTableModel()
        val table = controller.createTable(tableModel)
        
        // Инициализируем обработчики
        controller.initializeHandlers()

        // Создаем UI
        val scrollPane = JBScrollPane(table).apply { 
            preferredSize = Dimension(PluginConfig.UI.PRESETS_DIALOG_WIDTH, PluginConfig.UI.PRESETS_DIALOG_HEIGHT - 90) 
        }

        // Используем новую панель с кнопкой добавления
        val tableWithButtonPanel = TableWithAddButtonPanel(
            table = table,
            scrollPane = scrollPane,
            onAddPreset = { controller.addNewPreset() }
        )
        
        // Обновляем видимость кнопки в зависимости от режима
        controller.setTablePanelReference(tableWithButtonPanel)

        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(listManagerPanel, BorderLayout.NORTH)
            add(tableWithButtonPanel, BorderLayout.CENTER)
        }
        
        // Добавляем обработчик клика для выхода из режима редактирования ко всем компонентам
        controller.addClickListenerRecursively(mainPanel, table)
        
        // Добавляем глобальный обработчик кликов через AWTEventListener
        SwingUtilities.invokeLater {
            setupGlobalClickListener(table)
        }
        
        return mainPanel
    }


    
    /**
     * Настраивает глобальный обработчик кликов через AWTEventListener
     */
    private fun setupGlobalClickListener(@Suppress("UNUSED_PARAMETER") table: JTable) {
        controller.setupGlobalClickListener()
    }

    override fun doOKAction() {
        controller.saveSettings()
        controller.dispose()
        super.doOKAction()
    }

    override fun doCancelAction() {
        controller.restoreOriginalState()
        controller.dispose()
        super.doCancelAction()
    }
    
    override fun createLeftSideActions(): Array<Action> {
        val openFolderAction = object : AbstractAction("Open presets folder") {
            init {
                putValue(SMALL_ICON, AllIcons.Nodes.Folder)
            }
            
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                openPresetsFolder()
            }
        }
        return arrayOf(openFolderAction)
    }
    
    private fun openPresetsFolder() {
        try {
            val presetsDir = PresetListService.getPresetsDirectory()
            if (!presetsDir.exists()) {
                presetsDir.mkdirs()
            }
            
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(presetsDir)
            } else {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Desktop operations are not supported on this system",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                contentPane,
                "Failed to open presets folder: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
    
    private fun addHoverEffectToLeftButton() {
        // Найдем нашу кнопку в левой панели и добавим ховер эффект
        SwingUtilities.invokeLater {
            val rootPane = this.rootPane
            if (rootPane != null) {
                findOpenPresetsFolderButton(rootPane)?.let { button ->
                    ButtonUtils.addHoverEffect(button)
                }
            }
        }
    }
    
    private fun findOpenPresetsFolderButton(container: java.awt.Container): JButton? {
        for (component in container.components) {
            when (component) {
                is JButton -> {
                    if (component.text == "Open presets folder") {
                        return component
                    }
                }
                is java.awt.Container -> {
                    val found = findOpenPresetsFolderButton(component)
                    if (found != null) return found
                }
            }
        }
        return null
    }
}
