package io.github.qavlad.adbrandomizer.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
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
            g2d.color = JBColor.GREEN
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
            g2d.color = JBColor.ORANGE
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
            g2d.color = JBColor.GRAY
            g2d.drawRect(x, y, width - 1, height - 1)
        }
    }

    override fun getBorderInsets(c: Component?): Insets = JBUI.insets(1)
    override fun isBorderOpaque(): Boolean = false
}