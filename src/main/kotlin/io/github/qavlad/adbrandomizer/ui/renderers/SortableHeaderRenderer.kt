package io.github.qavlad.adbrandomizer.ui.renderers

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

/**
 * Рендерер для заголовков колонок с поддержкой индикаторов сортировки
 */
class SortableHeaderRenderer(
    private val tableSortingService: TableSortingService,
    private val getShowAllMode: () -> Boolean,
    private val getHideDuplicatesMode: () -> Boolean
) : DefaultTableCellRenderer() {
    
    companion object {
        private val SORT_ASC_ICON = AllIcons.General.ArrowUp
        private val SORT_DESC_ICON = AllIcons.General.ArrowDown
        private val HOVER_COLOR = JBColor(Color(230, 240, 250), Color(60, 63, 65))
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
            6 -> getShowAllMode() // List только в режиме Show All
            else -> false
        }
        
        if (isSortable) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // Получаем текущий тип сортировки для колонки
            val sortType = tableSortingService.getCurrentSortType(
                column,
                getShowAllMode(),
                getHideDuplicatesMode()
            )
            
            // Добавляем индикатор сортировки
            when (sortType) {
                TableSortingService.SortType.LABEL_ASC,
                TableSortingService.SortType.SIZE_ASC,
                TableSortingService.SortType.DPI_ASC,
                TableSortingService.SortType.LIST_ASC -> {
                    icon = SORT_ASC_ICON
                    horizontalTextPosition = SwingConstants.LEFT
                }
                TableSortingService.SortType.LABEL_DESC,
                TableSortingService.SortType.SIZE_DESC,
                TableSortingService.SortType.DPI_DESC,
                TableSortingService.SortType.LIST_DESC -> {
                    icon = SORT_DESC_ICON
                    horizontalTextPosition = SwingConstants.LEFT
                }
                else -> icon = null
            }
            
            // Показываем tooltip с информацией о сортировке
            toolTipText = when (column) {
                2 -> "Click to sort by label"
                3 -> "Click to sort by size (sum of width and height)"
                4 -> "Click to sort by DPI value"
                6 -> "Click to sort by list name"
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