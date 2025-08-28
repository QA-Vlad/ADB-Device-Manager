package io.github.qavlad.adbdevicemanager.ui.theme

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.UIManager

/**
 * Централизованная схема цветов для UI компонентов
 */
object ColorScheme {
    
    // === Цвета для состояний ячеек таблицы ===
    object Table {
        val HOVER_BACKGROUND: JBColor = JBColor(Gray._240, Gray._70)
        val SELECTED_BACKGROUND: JBColor = JBColor(Color(230, 230, 250), Color(80, 80, 100))
        val ERROR_BACKGROUND: JBColor = JBColor.PINK
        val ERROR_FOREGROUND: JBColor = JBColor.BLACK
        
        fun getBackground(): Color = UIManager.getColor("Table.background") ?: JBColor.WHITE
        fun getForeground(): Color = UIManager.getColor("Table.foreground") ?: JBColor.BLACK
    }
    
    // === Цвета для индикаторов пресетов ===
    object PresetIndicator {
        val ACTIVE_BORDER: JBColor = JBColor.GREEN
        val ACTIVE_TEXT: Color = JBColor.GREEN.darker()
        
        val MODIFIED_BORDER: JBColor = JBColor.ORANGE
        val MODIFIED_TEXT: JBColor = JBColor.ORANGE
        
        val RESET_BORDER: JBColor = JBColor.GRAY
        val RESET_TEXT: JBColor = JBColor.GRAY
    }
    
    // === Методы для получения цвета по состоянию ===
    
    /**
     * Возвращает цвет фона для ячейки таблицы в зависимости от состояния
     */
    fun getTableCellBackground(isSelected: Boolean, isHovered: Boolean, isError: Boolean): Color {
        return when {
            isError -> Table.ERROR_BACKGROUND
            isSelected -> Table.SELECTED_BACKGROUND
            isHovered -> Table.HOVER_BACKGROUND
            else -> Table.getBackground()
        }
    }
    
    /**
     * Возвращает цвет текста для ячейки таблицы в зависимости от состояния
     */
    fun getTableCellForeground(isError: Boolean, indicatorType: IndicatorType? = null): Color {
        return when {
            isError -> Table.ERROR_FOREGROUND
            indicatorType == IndicatorType.GREEN -> PresetIndicator.ACTIVE_TEXT
            indicatorType == IndicatorType.YELLOW -> PresetIndicator.MODIFIED_TEXT
            indicatorType == IndicatorType.GRAY -> PresetIndicator.RESET_TEXT
            else -> Table.getForeground()
        }
    }
    
    /**
     * Возвращает цвет границы для индикатора пресета
     */
    fun getIndicatorBorderColor(type: IndicatorType): Color {
        return when (type) {
            IndicatorType.GREEN -> PresetIndicator.ACTIVE_BORDER
            IndicatorType.YELLOW -> PresetIndicator.MODIFIED_BORDER
            IndicatorType.GRAY -> PresetIndicator.RESET_BORDER
            IndicatorType.NONE -> JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0)) // Прозрачный
        }
    }
}

/**
 * Типы индикаторов для пресетов
 */
enum class IndicatorType {
    NONE,
    GREEN,  // Активный пресет
    YELLOW, // Модифицированный пресет
    GRAY    // Сброшенный пресет
}
