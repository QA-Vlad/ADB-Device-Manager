package io.github.qavlad.adbrandomizer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel

// Этот класс будет вызываться, когда пользователь кликнет на иконку нашего плагина в боковой панели.
class PluginToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Создаем экземпляр нашей новой панели с кнопками
        val adbControlsPanel = AdbControlsPanel(project)

        // Получаем фабрику для создания контента
        val contentFactory = ContentFactory.getInstance()

        // Создаем "контент" из нашей панели
        val content = contentFactory.createContent(adbControlsPanel, "", false)

        // Добавляем этот контент в наше Tool Window
        toolWindow.contentManager.addContent(content)
    }
}