package io.github.qavlad.adbrandomizer.ui.handlers

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.TransferHandler
import javax.swing.table.DefaultTableModel

class PresetTransferHandler(private val onRowMoved: ((Int, Int) -> Unit)? = null) : TransferHandler() {
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
                if (targetRow >= 0 && targetRow < table.rowCount) {
                    val targetValue = table.getValueAt(targetRow, 0)
                    if (targetValue == "+") {
                        return false // Не позволяем drop на строку с кнопкой
                    }
                }
            }
            
            support.setShowDropLocation(true)
        }

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

            // Проверяем, что не пытаемся переместить в строку с кнопкой или саму строку с кнопкой
            val lastRow = model.rowCount - 1
            if (lastRow >= 0) {
                val lastRowFirstColumn = model.getValueAt(lastRow, 0)
                if (lastRowFirstColumn == "+" && (toIndex > lastRow || fromIndex == lastRow)) {
                    return false // Не позволяем перемещать строку с кнопкой или перемещать что-то после неё
                }
            }
            
            // Корректируем индекс если перетаскиваем вниз
            if (fromIndex < toIndex) {
                toIndex--
            }

            if (fromIndex != -1 && fromIndex != toIndex && toIndex >= 0 && toIndex < model.rowCount) {
                model.moveRow(fromIndex, fromIndex, toIndex)
                table.setRowSelectionInterval(toIndex, toIndex)
                
                // Уведомляем о перемещении строки
                onRowMoved?.invoke(fromIndex, toIndex)
                
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}