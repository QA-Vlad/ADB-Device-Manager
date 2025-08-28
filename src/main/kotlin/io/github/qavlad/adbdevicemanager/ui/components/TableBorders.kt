package io.github.qavlad.adbdevicemanager.ui.components

import com.intellij.util.ui.JBUI
import io.github.qavlad.adbdevicemanager.ui.theme.ColorScheme
import io.github.qavlad.adbdevicemanager.ui.theme.IndicatorType
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.border.Border

class ActiveParameterBorder : Border {
    override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        g?.let { graphics ->
            val g2d = graphics as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.color = ColorScheme.getIndicatorBorderColor(IndicatorType.GREEN)
            g2d.drawRect(x, y, width - 1, height - 1)
        }
    }

    override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
    override fun isBorderOpaque(): Boolean = false
}

class YellowParameterBorder : Border {
    override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        g?.let { graphics ->
            val g2d = graphics as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.color = ColorScheme.getIndicatorBorderColor(IndicatorType.YELLOW)
            g2d.drawRect(x, y, width - 1, height - 1)
        }
    }

    override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
    override fun isBorderOpaque(): Boolean = false
}

class GrayParameterBorder : Border {
    override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        g?.let { graphics ->
            val g2d = graphics as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.color = ColorScheme.getIndicatorBorderColor(IndicatorType.GRAY)
            g2d.drawRect(x, y, width - 1, height - 1)
        }
    }

    override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
    override fun isBorderOpaque(): Boolean = false
}
