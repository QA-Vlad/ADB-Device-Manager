// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/utils/ButtonUtils.kt
package io.github.qavlad.adbrandomizer.utils

import javax.swing.JButton

object ButtonUtils {
    fun addHoverEffect(button: JButton) {
        val originalBackground = button.background
        val hoverBackground = originalBackground?.brighter()

        button.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                // Проверяем, что кнопка активна перед применением hover эффекта
                if (button.isEnabled && hoverBackground != null) {
                    button.background = hoverBackground
                    button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                }
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                // Возвращаем исходный фон независимо от состояния
                button.background = originalBackground
                button.cursor = java.awt.Cursor.getDefaultCursor()
            }
        })
    }
}
