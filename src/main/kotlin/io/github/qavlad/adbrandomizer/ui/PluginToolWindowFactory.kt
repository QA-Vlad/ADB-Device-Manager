package io.github.qavlad.adbrandomizer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.qavlad.adbrandomizer.ui.panels.AdbControlsPanel
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import java.awt.BorderLayout
import javax.swing.JPanel

class PluginToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        PluginLogger.info("Creating tool window content for ADB Randomizer")
        val mainPanel = JPanel(BorderLayout())
        val adbControlsPanel = AdbControlsPanel(project)

        mainPanel.add(adbControlsPanel, BorderLayout.CENTER)
        PluginLogger.info("ADB Randomizer tool window content created")

        // Получаем фабрику для создания контента
        val contentFactory = ContentFactory.getInstance()

        // Создаем "контент" из нашей панели
        val content = contentFactory.createContent(mainPanel, "", false)

        // Регистрируем dispose listener для очистки ресурсов
        content.setDisposer {
            adbControlsPanel.dispose()
        }

        // Добавляем этот контент в наше Tool Window
        toolWindow.contentManager.addContent(content)
    }
}
