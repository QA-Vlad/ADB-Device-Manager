package io.github.qavlad.adbdevicemanager.utils

import com.intellij.ui.JBColor
import javax.swing.UIManager
import kotlin.math.pow

/**
 * Утилитный класс для проверки текущей темы IDE.
 * Заменяет deprecated UIUtil.isUnderDarcula()
 */
object ThemeChecker {
    /**
     * Проверяет, используется ли тёмная тема.
     * Использует современный API вместо deprecated UIUtil.isUnderDarcula()
     */
    fun isDarkTheme(): Boolean {
        // Способ 1: Проверка через JBColor
        // JBColor автоматически адаптируется к теме
        val testColor = JBColor.background()
        val luminance = calculateLuminance(testColor.red, testColor.green, testColor.blue)
        
        // Если яркость фона меньше 0.5, то это тёмная тема
        if (luminance < 0.5) {
            return true
        }
        
        // Способ 2: Проверка через UIManager как fallback
        val lafName = UIManager.getLookAndFeel()?.name?.lowercase() ?: ""
        return lafName.contains("dark") || lafName.contains("darcula")
    }
    
    /**
     * Вычисляет относительную яркость цвета.
     * Формула из W3C: https://www.w3.org/TR/WCAG20/#relativeluminancedef
     */
    private fun calculateLuminance(r: Int, g: Int, b: Int): Double {
        val red = r / 255.0
        val green = g / 255.0  
        val blue = b / 255.0
        
        // Применяем гамма-коррекцию
        val rLinear = if (red <= 0.03928) red / 12.92 else ((red + 0.055) / 1.055).pow(2.4)
        val gLinear = if (green <= 0.03928) green / 12.92 else ((green + 0.055) / 1.055).pow(2.4)
        val bLinear = if (blue <= 0.03928) blue / 12.92 else ((blue + 0.055) / 1.055).pow(2.4)
        
        // Возвращаем взвешенную сумму
        return 0.2126 * rLinear + 0.7152 * gLinear + 0.0722 * bLinear
    }
}