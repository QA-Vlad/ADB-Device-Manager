package io.github.qavlad.adbrandomizer.ui.renderers

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.DeviceStateService
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.components.ActiveParameterBorder
import io.github.qavlad.adbrandomizer.ui.components.GrayParameterBorder
import io.github.qavlad.adbrandomizer.ui.components.YellowParameterBorder
import io.github.qavlad.adbrandomizer.ui.theme.ColorScheme
import io.github.qavlad.adbrandomizer.ui.theme.IndicatorType
import java.awt.Component
import java.awt.Graphics
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.JLabel
import javax.swing.BorderFactory
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import javax.swing.UIManager

@Suppress("DEPRECATION", "UNUSED_PARAMETER")
class ValidationRenderer(
    private val hoverState: () -> HoverState,
    private val getPresetAtRow: (Int) -> DevicePreset,
    private val findDuplicates: () -> Map<Int, List<Int>>
) : DefaultTableCellRenderer() {

    private val duplicateInfoRenderer = DuplicateInfoRenderer()

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        // Проверяем, является ли это строкой с кнопкой плюсик
        val isButtonRow = if (row >= 0 && row < table.rowCount) {
            table.getValueAt(row, 0) == "+"
        } else {
            false
        }
        
        // Для строки с плюсиком возвращаем обычный компонент без hover эффектов
        if (isButtonRow) {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            component.background = table.background
            component.foreground = table.foreground
            border = null
            return component
        }
        
        val isHovered = hoverState().isTableCellHovered(row, column)
        val isSelectedCell = hoverState().isTableCellSelected(row, column)

        val cellBackground = ColorScheme.getTableCellBackground(
            isSelected = isSelectedCell,
            isHovered = isHovered,
            isError = false
        )
        var cellForeground = ColorScheme.Table.getForeground()

        // Для колонок с возможной индикацией дубликатов используем новый рендерер
        if (column == 3 || column == 4) {
            val text = value as? String ?: ""
            val duplicates = findDuplicates()
            val isDuplicate = duplicates.containsKey(row)
            val duplicateRows = duplicates.getOrDefault(row, emptyList())

            duplicateInfoRenderer.configure(text, isDuplicate, duplicateRows)

            val preset = getPresetAtRow(row)
            val activePresets = DeviceStateService.getCurrentActivePresets()
            val isSizeColumn = column == 3
            val indicatorType = getIndicatorType(preset, activePresets, isSize = isSizeColumn)

            duplicateInfoRenderer.border = when (indicatorType) {
                IndicatorType.GRAY -> GrayParameterBorder()
                IndicatorType.YELLOW -> YellowParameterBorder()
                IndicatorType.GREEN -> ActiveParameterBorder()
                else -> null
            }

            cellForeground = ColorScheme.getTableCellForeground(
                isError = false,
                indicatorType = indicatorType
            )

            duplicateInfoRenderer.setColors(cellBackground, cellForeground)

            return duplicateInfoRenderer
        }

        // Для остальных колонок используем стандартный рендерер
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        
        // Особая обработка для колонки "List" (колонка 6)
        if (column == 6) {
            // Создаем специальный компонент с иконкой и вертикальной линией
            val listComponent = ListColumnComponent(value as? String ?: "")
            listComponent.background = cellBackground
            return listComponent
        } else {
            component.background = cellBackground
            component.foreground = cellForeground
        }
        border = null // Сбрасываем рамку по умолчанию

        // Добавляем рамку и для Label-колонки, если нужно
        if (column == 2) {
            val preset = getPresetAtRow(row)
            val activePresets = DeviceStateService.getCurrentActivePresets()
            val sizeIndicator = getSizeIndicatorType(preset, activePresets)
            val dpiIndicator = getDpiIndicatorType(preset, activePresets)

            // Логика определения общей рамки для строки
            if (sizeIndicator == IndicatorType.GREEN && dpiIndicator == IndicatorType.GREEN) {
                border = ActiveParameterBorder()
                component.foreground = ColorScheme.PresetIndicator.ACTIVE_TEXT
            } else if (sizeIndicator == IndicatorType.YELLOW && dpiIndicator == IndicatorType.YELLOW) {
                border = YellowParameterBorder()
                component.foreground = ColorScheme.PresetIndicator.MODIFIED_TEXT
            } else if (sizeIndicator == IndicatorType.GRAY && dpiIndicator == IndicatorType.GRAY && 
                       preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                // Серая рамка на Label только если:
                // 1. ОБЕ колонки имеют серую рамку
                // 2. Оба значения (size и dpi) не пустые
                border = GrayParameterBorder()
            }
        }

        return component
    }

    private fun getIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo, isSize: Boolean): IndicatorType {
        return if (isSize) {
            getSizeIndicatorType(preset, activePresets)
        } else {
            getDpiIndicatorType(preset, activePresets)
        }
    }

    private fun getSizeIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
        return getPresetIndicatorType(
            presetValue = preset.size,
            preset = preset,
            resetPreset = activePresets.resetSizePreset,
            resetValue = activePresets.resetSizeValue,
            activeValue = activePresets.activeSizePreset?.size,
            originalPreset = activePresets.originalSizePreset
        )
    }

    private fun getDpiIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
        return getPresetIndicatorType(
            presetValue = preset.dpi,
            preset = preset,
            resetPreset = activePresets.resetDpiPreset,
            resetValue = activePresets.resetDpiValue,
            activeValue = activePresets.activeDpiPreset?.dpi,
            originalPreset = activePresets.originalDpiPreset
        )
    }

    private fun getPresetIndicatorType(
        presetValue: String,
        preset: DevicePreset,
        resetPreset: DevicePreset?,
        resetValue: String?,
        activeValue: String?,
        originalPreset: DevicePreset?
    ): IndicatorType {
        if (presetValue.isBlank()) return IndicatorType.NONE

        // Серая рамка: если это пресет, который был сброшен
        if (resetValue != null && presetValue == resetValue && resetPreset?.label == preset.label) {
            return IndicatorType.GRAY
        }

        val originalValue = if (preset.size == presetValue) originalPreset?.size else originalPreset?.dpi

        return getParameterIndicatorType(
            preset = preset,
            presetValue = presetValue,
            activeValue = activeValue,
            originalPreset = originalPreset,
            originalValue = originalValue
        )
    }

    private fun getParameterIndicatorType(
        preset: DevicePreset,
        presetValue: String,
        activeValue: String?,
        originalPreset: DevicePreset?,
        originalValue: String?
    ): IndicatorType {
        val isCurrentlyActive = activeValue != null && activeValue == presetValue

        // Если это тот самый пресет, который был применен изначально
        if (originalPreset?.label == preset.label) {
            val isModified = presetValue != originalValue
            // Зеленая, если не изменен, желтая, если изменен
            return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
        }

        // Если активное значение совпадает со значением в этой строке, но это не тот пресет,
        // который мы применяли, то он не должен подсвечиваться.
        if (isCurrentlyActive) {
            // Это может быть дубликат, который случайно совпал
            return IndicatorType.NONE
        }

        return IndicatorType.NONE
    }
}

/**
 * Компонент для отображения колонки "List" с иконкой папки и вертикальной линией
 */
class ListColumnComponent(listName: String) : JLabel() {
    init {
        text = listName
        // Убираем иконку - она будет только в заголовке
        horizontalAlignment = LEFT // Выравнивание по левому краю
        foreground = foreground?.darker()
        font = font.deriveFont(java.awt.Font.ITALIC) // Курсив
        isOpaque = true
        
        // Добавляем отступы и рамку с вертикальной линией слева
        border = BorderFactory.createCompoundBorder(
            VerticalLineBorder(),
            BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, JBUI.scale(4))
        )
    }
}

/**
 * Кастомная рамка с вертикальной линией слева
 */
class VerticalLineBorder : javax.swing.border.AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create()
        try {
            // Используем цвет разделителя из UIManager или серый по умолчанию
            g2.color = UIManager.getColor("Separator.foreground") ?: JBColor.GRAY
            // Рисуем толстую вертикальную линию слева
            g2.fillRect(x, y, JBUI.scale(2), height)
        } finally {
            g2.dispose()
        }
    }
    
    override fun getBorderInsets(c: Component): java.awt.Insets {
        return JBUI.insetsLeft(2)
    }
    
    override fun isBorderOpaque(): Boolean = true
}