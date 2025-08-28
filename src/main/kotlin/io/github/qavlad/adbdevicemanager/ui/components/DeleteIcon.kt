package io.github.qavlad.adbdevicemanager.ui.components

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.Icon

/**
 * Кастомная иконка корзины для кнопки удаления
 */
class DeleteIcon : Icon {
    private val size = JBUI.scale(16)
    
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            // Включаем антиалиасинг
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            
            // Цвет иконки берем от компонента
            g2.color = c?.foreground ?: JBColor.BLACK
            
            val scale = size / 16.0
            g2.translate(x, y)
            
            // Рисуем корзину
            val stroke = BasicStroke((1.5f * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.stroke = stroke
            
            // Крышка корзины
            val lidY = (4 * scale).toInt()
            g2.drawLine((2 * scale).toInt(), lidY, (14 * scale).toInt(), lidY)
            
            // Ручка
            val handleX1 = (6 * scale).toInt()
            val handleX2 = (10 * scale).toInt()
            val handleY = (2 * scale).toInt()
            g2.drawLine(handleX1, lidY, handleX1, handleY)
            g2.drawLine(handleX1, handleY, handleX2, handleY)
            g2.drawLine(handleX2, handleY, handleX2, lidY)
            
            // Корпус корзины (трапеция)
            val bodyTop = (6 * scale).toInt()
            val bodyBottom = (14 * scale).toInt()
            val bodyLeft = (4 * scale).toInt()
            val bodyRight = (12 * scale).toInt()
            val bodyLeftBottom = (5 * scale).toInt()
            val bodyRightBottom = (11 * scale).toInt()
            
            // Левая сторона
            g2.drawLine(bodyLeft, bodyTop, bodyLeftBottom, bodyBottom)
            // Правая сторона
            g2.drawLine(bodyRight, bodyTop, bodyRightBottom, bodyBottom)
            // Дно
            g2.drawLine(bodyLeftBottom, bodyBottom, bodyRightBottom, bodyBottom)
            
            // Вертикальные линии внутри
            val line1X = (7 * scale).toInt()
            val line2X = (9 * scale).toInt()
            g2.drawLine(line1X, (8 * scale).toInt(), line1X, (12 * scale).toInt())
            g2.drawLine(line2X, (8 * scale).toInt(), line2X, (12 * scale).toInt())
            
        } finally {
            g2.dispose()
        }
    }
    
    override fun getIconWidth(): Int = size
    
    override fun getIconHeight(): Int = size
}