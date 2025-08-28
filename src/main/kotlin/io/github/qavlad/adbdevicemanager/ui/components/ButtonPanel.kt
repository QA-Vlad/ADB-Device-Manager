package io.github.qavlad.adbdevicemanager.ui.components

import io.github.qavlad.adbdevicemanager.utils.ButtonUtils
import java.awt.Component
import javax.swing.*

class ButtonPanel(
    private val onRandomAction: (setSize: Boolean, setDpi: Boolean) -> Unit,
    private val onNextPreset: () -> Unit,
    private val onPreviousPreset: () -> Unit,
    private val onResetAction: (resetSize: Boolean, resetDpi: Boolean) -> Unit,
    private val onOpenPresetSettings: () -> Unit
) : JPanel() {

    init {
        setupUI()
    }

    private fun setupUI() {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createTitledBorder("Controls")
        
        // Отключаем автоматическое получение фокуса панелью
        isFocusable = false

        // Группируем кнопки логически
        add(createCenteredButton("RANDOM SIZE AND DPI") { onRandomAction(true, true) })
        add(createCenteredButton("RANDOM SIZE ONLY") { onRandomAction(true, false) })
        add(createCenteredButton("RANDOM DPI ONLY") { onRandomAction(false, true) })

        add(createCenteredButton("NEXT PRESET") { onNextPreset() })
        add(createCenteredButton("PREVIOUS PRESET") { onPreviousPreset() })

        add(createCenteredButton("Reset size and DPI to default") { onResetAction(true, true) })
        add(createCenteredButton("RESET SIZE ONLY") { onResetAction(true, false) })
        add(createCenteredButton("RESET DPI ONLY") { onResetAction(false, true) })

        add(createCenteredButton("PRESETS") { onOpenPresetSettings() })
    }

    private fun createCenteredButton(text: String, action: () -> Unit): Component {
        val button = JButton(text).apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener { action() }
            // Полностью отключаем возможность получения фокуса
            isFocusable = false
            setFocusable(false)
        }
        ButtonUtils.addHoverEffect(button)
        
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(button)
            add(Box.createHorizontalGlue())
        }
    }
}