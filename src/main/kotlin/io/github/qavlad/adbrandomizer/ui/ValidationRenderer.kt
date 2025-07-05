package io.github.qavlad.adbrandomizer.ui

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.DeviceStateService
import java.awt.Color
import java.awt.Component
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

enum class IndicatorType {
    NONE, GREEN, YELLOW, GRAY
}

@Suppress("DEPRECATION")
class ValidationRenderer(
    private val hoverState: () -> HoverState,
    private val getPresetAtRow: (Int) -> DevicePreset,
    private val findDuplicates: () -> Map<Int, List<Int>> // Добавляем функцию поиска дубликатов
) : DefaultTableCellRenderer() {

    private val duplicateInfoRenderer = DuplicateInfoRenderer()

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val isHovered = hoverState().isTableCellHovered(row, column)
        val isSelectedCell = hoverState().isTableCellSelected(row, column)

        val cellBackground = when {
            isSelectedCell -> JBColor(Color(230, 230, 250), Color(80, 80, 100))
            isHovered -> JBColor(Gray._240, Gray._70)
            else -> UIManager.getColor("Table.background") ?: JBColor.WHITE
        }
        var cellForeground = UIManager.getColor("Table.foreground") ?: JBColor.BLACK

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

            cellForeground = when (indicatorType) {
                IndicatorType.YELLOW -> JBColor.ORANGE
                IndicatorType.GREEN -> JBColor.GREEN.darker()
                else -> cellForeground
            }

            duplicateInfoRenderer.setColors(cellBackground, cellForeground)

            return duplicateInfoRenderer
        }

        // Для остальных колонок используем стандартный рендерер
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        component.background = cellBackground
        component.foreground = cellForeground

        // Добавляем рамку и для Label-колонки, если нужно
        if (column == 2) {
            val preset = getPresetAtRow(row)
            val activePresets = DeviceStateService.getCurrentActivePresets()
            val sizeIndicator = getSizeIndicatorType(preset, activePresets)
            val dpiIndicator = getDpiIndicatorType(preset, activePresets)

            if (sizeIndicator == IndicatorType.GRAY || dpiIndicator == IndicatorType.GRAY) {
                border = GrayParameterBorder()
            } else if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                border = YellowParameterBorder()
                component.foreground = JBColor.ORANGE
            } else if (sizeIndicator == IndicatorType.GREEN && dpiIndicator == IndicatorType.GREEN) {
                border = ActiveParameterBorder()
                component.foreground = JBColor.GREEN.darker()
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
            originalPreset = activePresets.originalSizePreset,
            originalValue = activePresets.originalSizePreset?.size
        )
    }

    private fun getDpiIndicatorType(preset: DevicePreset, activePresets: DeviceStateService.ActivePresetInfo): IndicatorType {
        return getPresetIndicatorType(
            presetValue = preset.dpi,
            preset = preset,
            resetPreset = activePresets.resetDpiPreset,
            resetValue = activePresets.resetDpiValue,
            activeValue = activePresets.activeDpiPreset?.dpi,
            originalPreset = activePresets.originalDpiPreset,
            originalValue = activePresets.originalDpiPreset?.dpi
        )
    }

    private fun getPresetIndicatorType(
        presetValue: String,
        preset: DevicePreset,
        resetPreset: DevicePreset?,
        resetValue: String?,
        activeValue: String?,
        originalPreset: DevicePreset?,
        originalValue: String?
    ): IndicatorType {
        if (presetValue.isBlank()) return IndicatorType.NONE

        if (resetValue != null && presetValue == resetValue && resetPreset?.label == preset.label) {
            return IndicatorType.GRAY
        }

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
        val isCurrentlyActive = activeValue == presetValue

        if (isCurrentlyActive) {
            val wasFromThisPreset = originalPreset?.label == preset.label

            if (wasFromThisPreset) {
                val isModified = presetValue != originalValue
                return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
            }
            // Если значение совпадает, но оно не из этого пресета - не выделяем
        }

        val isFromOriginalPreset = originalPreset?.label == preset.label

        if (isFromOriginalPreset) {
            val isModified = presetValue != originalValue
            return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
        }

        return IndicatorType.NONE
    }
}