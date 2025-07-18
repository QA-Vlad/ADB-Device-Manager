package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.TableConfigurator
import io.github.qavlad.adbrandomizer.ui.renderers.ListColumnHeaderRenderer
import java.util.Vector
import javax.swing.table.TableColumn

/**
 * Менеджер для управления колонками таблицы пресетов.
 * Централизует логику настройки и перенастройки колонок в зависимости от режима отображения.
 */
class TableColumnManager(
    private val tableConfigurator: TableConfigurator
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
        println("ADB_DEBUG: reconfigureColumns - start")
        
        // Настраиваем стандартные колонки через конфигуратор
        tableConfigurator.configureColumns()
        
        // Настраиваем кастомный рендерер для заголовка колонки List в режиме Show All
        if (isShowAllPresetsMode && table.columnModel.columnCount > 6) {
            val listColumn = table.columnModel.getColumn(6)
            listColumn.headerRenderer = ListColumnHeaderRenderer()
        }
        
        println("ADB_DEBUG: reconfigureColumns - done")
    }
    
    /**
     * Получает имена колонок в виде Vector для текущего режима
     */
    private fun getColumnNamesVector(isShowAllPresetsMode: Boolean): Vector<String> {
        return if (isShowAllPresetsMode) {
            Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  ", "List"))
        } else {
            Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        }
    }
}