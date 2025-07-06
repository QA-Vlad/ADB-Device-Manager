package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.*
import com.intellij.util.ui.JBUI
import javax.swing.border.Border

/**
 * Панель с кнопкой добавления пресета под drag-and-drop иконкой
 */
class AddPresetPanel(
    private val onAddPreset: () -> Unit
) : JPanel() {
    
    private val addButton: JButton
    
    init {
        layout = BorderLayout()
        isOpaque = false
        
        // Создаем кнопку с плюсиком
        addButton = JButton(AllIcons.General.Add).apply {
            toolTipText = "Add new preset"
            preferredSize = Dimension(20, 20)
            addActionListener { onAddPreset() }
            
            // Убираем обводку и фон по умолчанию
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
        }
        
        ButtonUtils.addHoverEffect(addButton)
        
        // Добавляем с отступами
        add(Box.createHorizontalStrut(JBUI.scale(5)), BorderLayout.WEST)
        add(addButton, BorderLayout.CENTER)
        
        // Устанавливаем высоту панели
        preferredSize = Dimension(30, 25)
        maximumSize = preferredSize
    }
    
    fun setButtonEnabled(enabled: Boolean) {
        addButton.isEnabled = enabled
    }
}

/**
 * Кастомная панель для размещения кнопки добавления под таблицей
 */
class TableWithAddButtonPanel(
    private val table: JTable,
    private val scrollPane: JScrollPane,
    private val onAddPreset: () -> Unit
) : JPanel(BorderLayout()) {
    
    private val addPresetPanel = AddPresetPanel(onAddPreset)
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // Основная панель с таблицей
        val tablePanel = JPanel(BorderLayout()).apply {
            add(table.tableHeader, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
        
        // Панель для размещения кнопки под таблицей, прямо под драг-энд-дроп иконкой
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, 30)
            add(addPresetPanel, BorderLayout.WEST)
        }
        
        // Добавляем все компоненты
        add(tablePanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }
    
    /**
     * Обновляет видимость кнопки добавления в зависимости от режима
     */
    fun setAddButtonVisible(visible: Boolean) {
        addPresetPanel.isVisible = visible
        addPresetPanel.setButtonEnabled(visible)
    }
}
