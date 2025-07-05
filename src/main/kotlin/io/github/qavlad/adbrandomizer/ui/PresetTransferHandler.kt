// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/PresetTransferHandler.kt
package io.github.qavlad.adbrandomizer.ui

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
        return StringSelection(table.selectedRow.toString())
    }

    override fun canImport(support: TransferSupport): Boolean {
        val canImport = support.component is JTable && support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

        if (canImport) {
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