package io.github.qavlad.adbrandomizer.ui.handlers

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.TransferHandler
import javax.swing.table.DefaultTableModel

class PresetTransferHandler(
    private val onRowMoved: ((Int, Int) -> Unit)? = null,
    private val onDragStarted: (() -> Unit)? = null,
    private val onDragEnded: (() -> Unit)? = null
) : TransferHandler() {
    override fun getSourceActions(c: JComponent?) = MOVE

    override fun createTransferable(c: JComponent?): Transferable? {
        val table = c as? JTable ?: return null
        val selectedRow = table.selectedRow
        
        // Проверяем, что это не строка с кнопкой
        if (selectedRow >= 0 && selectedRow < table.rowCount) {
            val firstColumnValue = table.getValueAt(selectedRow, 0)
            if (firstColumnValue == "+") {
                return null // Не позволяем перетаскивать строку с кнопкой
            }
        }
        
        // Уведомляем о начале drag операции
        onDragStarted?.invoke()
        
        return StringSelection(selectedRow.toString())
    }

    override fun canImport(support: TransferSupport): Boolean {
        val canImport = support.component is JTable && support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

        if (canImport) {
            // Проверяем, что не пытаемся сбросить на строку с кнопкой
            val table = support.component as? JTable
            val dropLocation = support.dropLocation as? JTable.DropLocation
            if (table != null && dropLocation != null) {
                val targetRow = dropLocation.row
                
                println("ADB_DEBUG: canImport - targetRow: $targetRow, rowCount: ${table.rowCount}")
                
                // Проверяем, есть ли строка с плюсиком
                val lastRow = table.rowCount - 1
                if (lastRow >= 0) {
                    val lastRowFirstColumn = table.getValueAt(lastRow, 0)
                    if (lastRowFirstColumn == "+") {
                        // В режиме INSERT_ROWS targetRow может быть равен rowCount (вставка после последней строки)
                        // Разрешаем drop только если targetRow < lastRow (перед строкой с плюсиком)
                        if (targetRow > lastRow) {
                            println("ADB_DEBUG: canImport - false, targetRow > lastRow with + button")
                            return false
                        }
                    }
                }
            }
            
            support.setShowDropLocation(true)
        }

        println("ADB_DEBUG: canImport - returning $canImport")
        return canImport
    }

    override fun importData(support: TransferSupport): Boolean {
        val table = support.component as? JTable ?: return false
        val model = table.model as? DefaultTableModel ?: return false

        try {
            val fromIndex = (support.transferable.getTransferData(DataFlavor.stringFlavor) as String).toInt()

            // Получаем правильный индекс для INSERT_ROWS режима
            val dropLocation = support.dropLocation as? JTable.DropLocation
            var toIndex = dropLocation?.row ?: -1

            // Если drop location недоступен, используем старый метод
            if (toIndex == -1) {
                toIndex = table.rowAtPoint(support.dropLocation.dropPoint)
                if (toIndex == -1) {
                    toIndex = model.rowCount
                }
            }

            println("ADB_DEBUG: DnD - fromIndex: $fromIndex, initial toIndex: $toIndex, rowCount: ${model.rowCount}")

            // Проверяем наличие строки с плюсиком
            val lastRow = model.rowCount - 1
            var hasAddButton = false
            
            if (lastRow >= 0) {
                val lastRowFirstColumn = model.getValueAt(lastRow, 0)
                if (lastRowFirstColumn == "+") {
                    hasAddButton = true
                    
                    // Не позволяем перемещать саму строку с плюсиком
                    if (fromIndex == lastRow) {
                        println("ADB_DEBUG: DnD - Cannot move add button row")
                        return false
                    }
                }
            }
            
            // Корректируем toIndex для режима INSERT_ROWS
            // В этом режиме toIndex указывает позицию ПЕРЕД которой вставляется элемент
            if (hasAddButton && toIndex >= lastRow) {
                // Если пытаемся вставить на позицию плюсика или после, корректируем
                toIndex = lastRow
            }
            
            // Корректируем индекс если перетаскиваем вниз
            // Это нужно потому что после удаления элемента с fromIndex, индексы сдвигаются
            var adjustedToIndex = toIndex
            if (fromIndex < toIndex) {
                adjustedToIndex--
            }
            
            println("ADB_DEBUG: DnD - adjusted toIndex: $adjustedToIndex")

            if (fromIndex != -1 && fromIndex != adjustedToIndex && adjustedToIndex >= 0 && adjustedToIndex < model.rowCount) {
                model.moveRow(fromIndex, fromIndex, adjustedToIndex)
                table.setRowSelectionInterval(adjustedToIndex, adjustedToIndex)
                
                // Уведомляем о перемещении строки
                println("ADB_DEBUG: DnD - Calling onRowMoved, callback is null: ${onRowMoved == null}")
                println("ADB_DEBUG: DnD - Thread: ${Thread.currentThread().name}")
                onRowMoved?.invoke(fromIndex, adjustedToIndex)
                println("ADB_DEBUG: DnD - onRowMoved completed")
                
                println("ADB_DEBUG: DnD - Move successful")
                return true
            } else {
                println("ADB_DEBUG: DnD - Move failed: fromIndex=$fromIndex, adjustedToIndex=$adjustedToIndex")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
    
    override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
        super.exportDone(source, data, action)
        // Уведомляем об окончании drag операции
        onDragEnded?.invoke()
    }
}