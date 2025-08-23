package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.TableConfigurator
import io.github.qavlad.adbrandomizer.ui.renderers.SortableHeaderRenderer
import java.util.Vector
import javax.swing.table.TableColumn

/**
 * Менеджер для управления колонками таблицы пресетов.
 * Централизует логику настройки и перенастройки колонок в зависимости от режима отображения.
 */
class TableColumnManager(
    private val tableConfigurator: TableConfigurator,
    private val getShowAllMode: () -> Boolean = { false },
    private val getHideDuplicatesMode: () -> Boolean = { false },
    private val getShowCounters: () -> Boolean = { true }
) {
    
    /**
     * Настраивает колонки таблицы в зависимости от режима
     */
    fun setupTableColumns(
        table: JBTable,
        tableModel: DevicePresetTableModel,
        isShowAllPresetsMode: Boolean
    ) {
        // Определяем имена колонок в зависимости от режима
        val columnNames = getColumnNamesVector(isShowAllPresetsMode)
        
        // Устанавливаем новые идентификаторы колонок
        tableModel.setColumnIdentifiers(columnNames)
        
        // Синхронизируем колонки JTable с TableModel
        synchronizeTableColumns(table, tableModel)
        
        // Перенастраиваем свойства колонок
        reconfigureColumns(table, isShowAllPresetsMode)
    }
    
    /**
     * Синхронизирует количество колонок между JTable и TableModel
     */
    private fun synchronizeTableColumns(table: JBTable, tableModel: DevicePresetTableModel) {
        // Удаляем лишние колонки
        while (table.columnModel.columnCount > tableModel.columnCount) {
            table.columnModel.removeColumn(
                table.columnModel.getColumn(table.columnModel.columnCount - 1)
            )
        }
        
        // Добавляем недостающие колонки
        while (table.columnModel.columnCount < tableModel.columnCount) {
            val newColumn = TableColumn(table.columnModel.columnCount)
            table.columnModel.addColumn(newColumn)
        }
    }
    
    /**
     * Перенастраивает рендереры и редакторы колонок
     */
    private fun reconfigureColumns(table: JBTable, isShowAllPresetsMode: Boolean) {
        // println("ADB_DEBUG: reconfigureColumns - start")
        
        // Настраиваем стандартные колонки через конфигуратор
        tableConfigurator.configureColumns()
        
        // Настраиваем рендереры для сортируемых колонок
        val headerRenderer = SortableHeaderRenderer(
            getShowAllMode,
            getHideDuplicatesMode
        )
        
        // Устанавливаем рендерер для сортируемых колонок
        if (table.columnModel.columnCount > 2) {
                table.columnModel.getColumn(2).headerRenderer = headerRenderer // Label
            }
            if (table.columnModel.columnCount > 3) {
                table.columnModel.getColumn(3).headerRenderer = headerRenderer // Size
            }
            if (table.columnModel.columnCount > 4) {
                table.columnModel.getColumn(4).headerRenderer = headerRenderer // DPI
            }
            
            // Для колонок счетчиков, если они включены
            val showCounters = getShowCounters()
            if (showCounters) {
                if (table.columnModel.columnCount > 5) {
                    table.columnModel.getColumn(5).headerRenderer = headerRenderer // Size Uses
                }
                if (table.columnModel.columnCount > 6) {
                    table.columnModel.getColumn(6).headerRenderer = headerRenderer // DPI Uses
                }
            }
            
            // Для колонки List в режиме Show All
            if (isShowAllPresetsMode) {
                val listColumnIndex = if (showCounters) 8 else 6
                if (table.columnModel.columnCount > listColumnIndex) {
                    table.columnModel.getColumn(listColumnIndex).headerRenderer = headerRenderer // List
                }
            }
        
        // println("ADB_DEBUG: reconfigureColumns - done")
    }
    
    /**
     * Получает имена колонок в виде Vector для текущего режима
     */
    private fun getColumnNamesVector(isShowAllPresetsMode: Boolean): Vector<String> {
        val showCounters = getShowCounters()
        
        return when {
            isShowAllPresetsMode && showCounters -> {
                // Show All mode with counters
                Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "Size Uses", "DPI Uses", "  ", "List"))
            }
            isShowAllPresetsMode && !showCounters -> {
                // Show All mode without counters
                Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  ", "List"))
            }
            !isShowAllPresetsMode && showCounters -> {
                // Normal mode with counters
                Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "Size Uses", "DPI Uses", "  "))
            }
            else -> {
                // Normal mode without counters
                Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
            }
        }
    }
}