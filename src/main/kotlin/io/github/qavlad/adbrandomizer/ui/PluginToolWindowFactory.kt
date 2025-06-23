package io.github.qavlad.adbrandomizer.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

// Этот класс будет вызываться, когда пользователь кликнет на иконку нашего плагина в боковой панели.
class PluginToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Создаем главную панель, которая будет содержать все элементы.
        // Это хорошая практика, чтобы иметь корневой контейнер.
        val mainPanel = JPanel(BorderLayout())

        // Создаем экземпляр нашей основной панели.
        // Теперь она содержит оба блока (кнопки и список устройств).
        val adbControlsPanel = AdbControlsPanel(project)

        mainPanel.add(adbControlsPanel, BorderLayout.CENTER)

        // Получаем фабрику для создания контента
        val contentFactory = ContentFactory.getInstance()

        // Создаем "контент" из нашей панели
        val content = contentFactory.createContent(mainPanel, "", false)

        // Добавляем этот контент в наше Tool Window
        toolWindow.contentManager.addContent(content)
    }
}
