package io.github.qavlad.adbrandomizer.utils

import com.intellij.ui.JBColor

/**
 * Класс для работы с темами IDE и получения правильных цветов
 */
object ThemeUtils {

    /**
     * Получить JBColor для неактивных иконок
     */
    fun getInactiveIconJBColor(): JBColor {
        return JBColor.GRAY
    }
}