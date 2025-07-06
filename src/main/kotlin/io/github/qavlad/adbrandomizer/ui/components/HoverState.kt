package io.github.qavlad.adbrandomizer.ui.components

import io.github.qavlad.adbrandomizer.config.PluginConfig

/**
 * Универсальное состояние hover эффектов для всех UI компонентов
 */
data class HoverState(
    // Для списка устройств
    val hoveredDeviceIndex: Int = -1,
    val hoveredButtonType: String? = null,
    
    // Для таблицы пресетов
    val hoveredTableRow: Int = -1,
    val hoveredTableColumn: Int = -1,
    val selectedTableRow: Int = -1,
    val selectedTableColumn: Int = -1
) {

    /**
     * Проверяет, находится ли указанная кнопка устройства в состоянии hover
     */
    fun isDeviceButtonHovered(index: Int, buttonType: String): Boolean {
        return hoveredDeviceIndex == index && hoveredButtonType == buttonType
    }


    
    /**
     * Проверяет, находится ли указанная ячейка таблицы в состоянии hover
     */
    fun isTableCellHovered(row: Int, column: Int): Boolean {
        return hoveredTableRow == row && hoveredTableColumn == column
    }
    
    /**
     * Проверяет, выделена ли указанная ячейка таблицы
     */
    fun isTableCellSelected(row: Int, column: Int): Boolean {
        return selectedTableRow == row && selectedTableColumn == column
    }
    
    /**
     * Создает новое состояние с hover ячейки таблицы
     */
    fun withTableHover(row: Int, column: Int): HoverState {
        return copy(hoveredTableRow = row, hoveredTableColumn = column)
    }
    
    /**
     * Создает новое состояние с выделенной ячейкой таблицы
     */
    fun withTableSelection(row: Int, column: Int): HoverState {
        return copy(selectedTableRow = row, selectedTableColumn = column)
    }
    
    /**
     * Очищает hover таблицы
     */
    fun clearTableHover(): HoverState {
        return copy(hoveredTableRow = -1, hoveredTableColumn = -1)
    }
    
    /**
     * Очищает выделение таблицы
     */
    fun clearTableSelection(): HoverState {
        return copy(selectedTableRow = -1, selectedTableColumn = -1)
    }
    


    companion object {
        const val BUTTON_TYPE_MIRROR = PluginConfig.UIConstants.BUTTON_TYPE_MIRROR
        const val BUTTON_TYPE_WIFI = PluginConfig.UIConstants.BUTTON_TYPE_WIFI

        /**
         * Создает состояние без hover эффектов
         */
        fun noHover(): HoverState = HoverState()


    }
}