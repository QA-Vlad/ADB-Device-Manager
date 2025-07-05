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
    private val getPresetAtRow: (Int) -> DevicePreset
) : DefaultTableCellRenderer() {
    
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val isHovered = hoverState().isTableCellHovered(row, column)
        val isSelectedCell = hoverState().isTableCellSelected(row, column)
        
        if (isHovered || isSelectedCell) {
            println("ADB_DEBUG: Rendering ACTIVE cell row=$row, column=$column, isHovered=$isHovered, isSelectedCell=$isSelectedCell")
        }
        
        val cellBackground = when {
            isSelectedCell -> JBColor(Color(230, 230, 250), Color(80, 80, 100))
            isHovered -> JBColor(Gray._240, Gray._70)
            else -> UIManager.getColor("Table.background") ?: JBColor.WHITE
        }
        val cellForeground = UIManager.getColor("Table.foreground") ?: JBColor.BLACK
        
        isOpaque = true
        background = cellBackground
        foreground = cellForeground
        text = value?.toString() ?: ""
        horizontalAlignment = LEFT
        border = null

        when (column) {
            0, 1, 5 -> {
                border = null
            }
            2 -> {
                val preset = getPresetAtRow(row)
                val activePresets = DeviceStateService.getCurrentActivePresets()
                
                val sizeIndicator = getSizeIndicatorType(preset, activePresets)
                val dpiIndicator = getDpiIndicatorType(preset, activePresets)
                
                val text = value as? String ?: ""
                
                if (sizeIndicator != IndicatorType.NONE && dpiIndicator != IndicatorType.NONE && text.isNotBlank()) {
                    if (sizeIndicator == IndicatorType.GRAY || dpiIndicator == IndicatorType.GRAY) {
                        println("ADB_DEBUG: Рендерим СЕРУЮ рамку для ${preset.label} (sizeIndicator=$sizeIndicator, dpiIndicator=$dpiIndicator)")
                        this.text = text
                        foreground = table.foreground
                        border = GrayParameterBorder()
                    } else {
                        val indicator = "✓ "
                        this.text = indicator + text
                        foreground = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                            JBColor.ORANGE
                        } else {
                            JBColor.GREEN.darker()
                        }
                        
                        border = if (sizeIndicator == IndicatorType.YELLOW || dpiIndicator == IndicatorType.YELLOW) {
                            YellowParameterBorder()
                        } else {
                            ActiveParameterBorder()
                        }
                    }
                } else {
                    this.text = text
                    foreground = cellForeground
                    border = null
                }
            }
            3, 4 -> {
                val text = value as? String ?: ""
                val preset = getPresetAtRow(row)
                val activePresets = DeviceStateService.getCurrentActivePresets()
                
                val isSize = column == 3
                val indicatorType = getIndicatorType(preset, activePresets, isSize = isSize)

                border = when (indicatorType) {
                    IndicatorType.GRAY -> GrayParameterBorder()
                    IndicatorType.YELLOW -> YellowParameterBorder()
                    IndicatorType.GREEN -> ActiveParameterBorder()
                    else -> null
                }
                
                if (indicatorType != IndicatorType.NONE && text.isNotBlank()) {
                    if (indicatorType == IndicatorType.GRAY) {
                        println("ADB_DEBUG: Рендерим серую рамку в колонке ${if (isSize) "SIZE" else "DPI"} для пресета с текстом '$text'")
                        this.text = text
                        foreground = cellForeground
                    } else {
                        val indicator = "✓ "
                        this.text = indicator + text
                        foreground = when (indicatorType) {
                            IndicatorType.YELLOW -> JBColor.ORANGE
                            else -> JBColor.GREEN.darker()
                        }
                    }
                } else {
                    this.text = text
                    foreground = cellForeground
                }
            }
        }

        return this
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
        resetValue: String?, // <-- Новое поле в сигнатуре
        activeValue: String?,
        originalPreset: DevicePreset?,
        originalValue: String?
    ): IndicatorType {
        if (presetValue.isBlank()) return IndicatorType.NONE

        // 1. Сначала проверяем состояние "сброшено"
        // Если есть сброшенное значение и оно совпадает с текущим значением в ячейке,
        // а также label пресета совпадает с тем, который был сброшен, то рисуем серую рамку.
        if (resetValue != null && presetValue == resetValue && resetPreset?.label == preset.label) {
            return IndicatorType.GRAY
        }

        // 2. Если не сброшено, продолжаем с логикой для активных/измененных пресетов
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
        }
        
        val isFromOriginalPreset = originalPreset?.label == preset.label
        
        if (isFromOriginalPreset) {
            val isModified = presetValue != originalValue
            return if (isModified) IndicatorType.YELLOW else IndicatorType.GREEN
        }
        
        return IndicatorType.NONE
    }
}