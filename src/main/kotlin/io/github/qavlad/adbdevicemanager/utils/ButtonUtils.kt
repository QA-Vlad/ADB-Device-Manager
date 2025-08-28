// Файл: src/main/kotlin/io/github/qavlad/adbdevicemanager/utils/ButtonUtils.kt
package io.github.qavlad.adbdevicemanager.utils

import java.awt.Container
import javax.swing.JButton

object ButtonUtils {
    fun addHoverEffect(button: JButton) {
        val originalBackground = button.background
        val hoverBackground = originalBackground?.brighter()
        val originalContentAreaFilled = button.isContentAreaFilled

        button.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                // Проверяем, что кнопка активна перед применением hover эффекта
                if (button.isEnabled && hoverBackground != null) {
                    button.background = hoverBackground
                    button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    button.isContentAreaFilled = true // Включаем фон при ховвере
                }
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                // Возвращаем исходный фон независимо от состояния
                button.background = originalBackground
                button.cursor = java.awt.Cursor.getDefaultCursor()
                button.isContentAreaFilled = originalContentAreaFilled // Возвращаем исходное состояние
            }
        })
    }

    /**
     * Добавляет эффект наведения на кнопки диалога (Save и Cancel)
     * @param container корневой контейнер для поиска кнопок
     */
    fun addHoverEffectToDialogButtons(container: Container) {
        processButtons(container)
    }
    
    /**
     * Рекурсивно обрабатывает контейнер и его дочерние элементы для поиска кнопок
     */
    private fun processButtons(container: Container) {
        for (component in container.components) {
            when (component) {
                is JButton -> {
                    // Добавляем hover эффект для кнопок диалогов
                    if (component.text == "Save" || component.text == "Cancel" || 
                        component.text == "Open presets folder" || component.text == "OK" ||
                        component.text == "Import" || component.text == "Export") {
                        addHoverEffect(component)
                    }
                }
                is Container -> processButtons(component)
            }
        }
    }
}
