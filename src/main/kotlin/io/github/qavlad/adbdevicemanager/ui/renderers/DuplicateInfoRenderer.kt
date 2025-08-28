package io.github.qavlad.adbdevicemanager.ui.renderers

import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Рендерер для ячеек, которые могут содержать индикатор дубликата.
 */
class DuplicateInfoRenderer : JPanel(BorderLayout(0, 0)) {
    private val textLabel = JBLabel()

    init {
        isOpaque = true
        add(textLabel, BorderLayout.CENTER)
    }

    fun configure(text: String, isDuplicate: Boolean, duplicateRows: List<Int>) {
        if (isDuplicate) {
            textLabel.text = "$text⚠️"
            val otherRows = duplicateRows.joinToString(", ") { "#${it + 1}" }
            toolTipText = "Duplicate of row(s): $otherRows"
        } else {
            textLabel.text = text
            toolTipText = null
        }
    }

    fun setColors(background: java.awt.Color, foreground: java.awt.Color) {
        this.background = background
        textLabel.background = background
        textLabel.foreground = foreground
    }
}