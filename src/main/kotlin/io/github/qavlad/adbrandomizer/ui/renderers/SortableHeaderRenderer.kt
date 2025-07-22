package io.github.qavlad.adbrandomizer.ui.renderers

import com.intellij.icons.AllIcons
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService.SortType
import io.github.qavlad.adbrandomizer.services.SettingsService
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

/**
 * Рендерер для заголовков колонок с поддержкой индикаторов сортировки
 */
class SortableHeaderRenderer(
    private val getShowAllMode: () -> Boolean,
    private val getHideDuplicatesMode: () -> Boolean
) : DefaultTableCellRenderer() {
    
    companion object {
        private val SORT_ASC_ICON = AllIcons.General.ArrowUp
        private val SORT_DESC_ICON = AllIcons.General.ArrowDown
    }
    
    init {
        horizontalAlignment = CENTER
        verticalAlignment = CENTER
    }
    
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        
        // Устанавливаем стандартный фон и границу
        background = table.tableHeader.background
        border = UIManager.getBorder("TableHeader.cellBorder")
        
        // Проверяем, является ли колонка сортируемой
        val isSortable = when (column) {
            2, 3, 4 -> true // Label, Size, DPI
            5 -> SettingsService.getShowCounters() // Size Uses только когда счетчики включены
            6 -> SettingsService.getShowCounters() || (getShowAllMode() && !SettingsService.getShowCounters()) // DPI Uses когда счетчики включены, или List в Show All без счетчиков
            8 -> getShowAllMode() && SettingsService.getShowCounters() // List в Show All режиме со счетчиками
            else -> false
        }
        
        if (isSortable) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Получаем текущий тип сортировки для колонки
            val sortType = TableSortingService.getCurrentSortType(
                column,
                getShowAllMode(),
                getHideDuplicatesMode()
            )
            
            // Добавляем индикатор сортировки
            when (sortType) {
                SortType.LABEL_ASC,
                SortType.SIZE_ASC,
                SortType.DPI_ASC,
                SortType.SIZE_USES_ASC,
                SortType.DPI_USES_ASC,
                SortType.LIST_ASC -> {
                    icon = SORT_ASC_ICON
                    horizontalTextPosition = LEFT
                }
                SortType.LABEL_DESC,
                SortType.SIZE_DESC,
                SortType.DPI_DESC,
                SortType.SIZE_USES_DESC,
                SortType.DPI_USES_DESC,
                SortType.LIST_DESC -> {
                    icon = SORT_DESC_ICON
                    horizontalTextPosition = LEFT
                }
                else -> icon = null
            }
            
            // Показываем tooltip с информацией о сортировке
            toolTipText = when (column) {
                2 -> "Click to sort by label"
                3 -> "Click to sort by size (sum of width and height)"
                4 -> "Click to sort by DPI value"
                5 -> if (SettingsService.getShowCounters()) "Click to sort by size usage count" else null
                6 -> when {
                    SettingsService.getShowCounters() -> "Click to sort by DPI usage count"
                    getShowAllMode() -> "Click to sort by list name"
                    else -> null
                }
                8 -> if (getShowAllMode() && SettingsService.getShowCounters()) "Click to sort by list name" else null
                else -> null
            }
        } else {
            cursor = Cursor.getDefaultCursor()
            icon = null
            toolTipText = null
        }
        
        return this
    }
}