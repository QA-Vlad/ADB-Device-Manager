package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.ui.components.TableWithAddButtonPanel
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

/**
 * Диалог настроек ADB Randomizer.
 * Использует SettingsDialogController для управления логикой.
 */
class SettingsDialog(project: Project?) : DialogWrapper(project) {
    
    private val controller = SettingsDialogController(project, this)

    init {
        PluginLogger.debug(LogCategory.UI_EVENTS, "SettingsDialog constructor called")
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
            preferredSize = Dimension(PluginConfig.UI.SETTINGS_DIALOG_WIDTH, PluginConfig.UI.SETTINGS_DIALOG_HEIGHT - 90) 
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
    private fun setupGlobalClickListener(table: JTable) {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        
        val eventListener = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.MouseEvent) {
                when (event.id) {
                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                    java.awt.event.MouseEvent.MOUSE_PRESSED -> {
                        if (table.isEditing) {
                            // Проверяем, что клик не по самой таблице и не по редактору ячеек
                            val clickedComponent = event.source as? Component
                            val isTableClick = clickedComponent is JBTable
                            val isEditorClick = table.editorComponent != null && 
                                                (clickedComponent == table.editorComponent ||
                                                 SwingUtilities.isDescendingFrom(clickedComponent, table.editorComponent))
                            val isDialogClick = shouldStopEditingForComponent(clickedComponent)
                            
                            if (!isTableClick && !isEditorClick && isDialogClick) {
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
        
        // Возвращаем результат без логирования
        return !dontStopEditingComponents.contains(componentName)
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
}
