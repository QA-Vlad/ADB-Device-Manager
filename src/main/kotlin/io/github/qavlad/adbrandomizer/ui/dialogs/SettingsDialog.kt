package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.JTable

/**
 * Диалог настроек ADB Randomizer.
 * Использует SettingsDialogController для управления логикой.
 */
class SettingsDialog(project: Project?) : DialogWrapper(project) {
    
    private val controller = SettingsDialogController(project, this)

    init {
        println("ADB_DEBUG: SettingsDialog constructor called")
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
        println("ADB_DEBUG: createCenterPanel called")
        // Создаем модель и таблицу через контроллер
        val tableModel = controller.createTableModel()
        val table = controller.createTable(tableModel)
        
        // Инициализируем обработчики
        controller.initializeHandlers()

        // Создаем UI
        val scrollPane = JBScrollPane(table).apply { 
            preferredSize = Dimension(PluginConfig.UI.SETTINGS_DIALOG_WIDTH, PluginConfig.UI.SETTINGS_DIALOG_HEIGHT) 
        }
        
        val buttonPanel = createButtonPanel()

        val tablePanel = JPanel(BorderLayout()).apply {
            add(table.tableHeader, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(tablePanel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
        }
        
        // Добавляем обработчик клика для выхода из режима редактирования ко всем компонентам
        println("ADB_DEBUG: About to add click listeners recursively")
        controller.addClickListenerRecursively(mainPanel, table)
        println("ADB_DEBUG: Finished adding click listeners")
        
        // Добавляем глобальный обработчик кликов через AWTEventListener
        SwingUtilities.invokeLater {
            setupGlobalClickListener(table)
        }
        
        return mainPanel
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
    
    /**
     * Настраивает глобальный обработчик кликов через AWTEventListener
     */
    private fun setupGlobalClickListener(table: JTable) {
        println("ADB_DEBUG: Setting up global click listener")
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        
        val eventListener = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.MouseEvent) {
                when (event.id) {
                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                    java.awt.event.MouseEvent.MOUSE_PRESSED -> {
                        println("ADB_DEBUG: Global ${if (event.id == java.awt.event.MouseEvent.MOUSE_CLICKED) "click" else "press"} detected on component: ${event.source.javaClass.simpleName}")
                        println("ADB_DEBUG: Table is editing: ${table.isEditing}")
                        
                        if (table.isEditing) {
                            // Проверяем, что клик не по самой таблице и не по редактору ячеек
                            val clickedComponent = event.source as? Component
                            val isTableClick = clickedComponent is JBTable
                            val isEditorClick = table.editorComponent != null && 
                                                (clickedComponent == table.editorComponent ||
                                                 SwingUtilities.isDescendingFrom(clickedComponent, table.editorComponent))
                            val isDialogClick = shouldStopEditingForComponent(clickedComponent)
                            
                            println("ADB_DEBUG: Is table click: $isTableClick, Is editor click: $isEditorClick, Should stop: $isDialogClick")
                            
                            if (!isTableClick && !isEditorClick && isDialogClick) {
                                println("ADB_DEBUG: Stopping cell editing due to global ${if (event.id == java.awt.event.MouseEvent.MOUSE_CLICKED) "click" else "press"}")
                                SwingUtilities.invokeLater {
                                    if (table.isEditing) {
                                        table.cellEditor?.stopCellEditing()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        toolkit.addAWTEventListener(eventListener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
        
        // Сохраняем ссылку, чтобы можно было удалить позже
        controller.setGlobalClickListener(eventListener)
    }
    
    /**
     * Определяет, нужно ли остановить редактирование при клике по компоненту
     */
    private fun shouldStopEditingForComponent(component: Component?): Boolean {
        if (component == null) return true
        
        val componentName = component.javaClass.simpleName
        println("ADB_DEBUG: Checking component: $componentName")
        
        // Список компонентов, клик по которым НЕ должен останавливать редактирование
        val dontStopEditingComponents = setOf(
            "JTextField",      // Редактор текста
            "JTextArea",       // Многострочный редактор
            "JFormattedTextField", // Форматированное поле
            "JComboBox",       // Комбобокс
            "JSpinner",        // Спиннер
            "JList",           // Список
            ""                 // Пустое имя (в случае JTable)
        )
        
        // Если компонент в списке исключений, то НЕ останавливаем редактирование
        if (dontStopEditingComponents.contains(componentName)) {
            println("ADB_DEBUG: Component in exclusion list, not stopping editing")
            return false
        }
        
        // Для всех остальных компонентов - останавливаем редактирование
        println("ADB_DEBUG: Component not in exclusion list, stopping editing")
        return true
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
