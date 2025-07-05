// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DuplicateInfoRenderer.kt
package io.github.qavlad.adbrandomizer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Рендерер для ячеек, которые могут содержать индикатор дубликата.
 */
class DuplicateInfoRenderer : JPanel(BorderLayout(JBUI.scale(4), 0)) {
    private val textLabel = JBLabel()
    private val iconLabel = JBLabel(AllIcons.General.Warning)

    init {
        isOpaque = true
        add(textLabel, BorderLayout.CENTER)
        add(iconLabel, BorderLayout.EAST)
    }

    fun configure(text: String, isDuplicate: Boolean, duplicateRows: List<Int>) {
        textLabel.text = text
        iconLabel.isVisible = isDuplicate

        if (isDuplicate) {
            val otherRows = duplicateRows.joinToString(", ") { "#${it + 1}" }
            toolTipText = "Duplicate of row(s): $otherRows"
        } else {
            toolTipText = null
        }
    }

    fun setColors(background: java.awt.Color, foreground: java.awt.Color) {
        this.background = background
        textLabel.background = background
        textLabel.foreground = foreground
    }
}