// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/PresetTransferHandler.kt
package io.github.qavlad.adbrandomizer.ui

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.TransferHandler
import javax.swing.table.DefaultTableModel

class PresetTransferHandler : TransferHandler() {
    override fun getSourceActions(c: JComponent?) = MOVE
    override fun createTransferable(c: JComponent?): Transferable? {
        val table = c as? JTable ?: return null
        return StringSelection(table.selectedRow.toString())
    }
    override fun canImport(support: TransferSupport): Boolean {
        return support.component is JTable && support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)
    }
    override fun importData(support: TransferSupport): Boolean {
        val table = support.component as? JTable ?: return false
        val model = table.model as? DefaultTableModel ?: return false
        try {
            val fromIndex = (support.transferable.getTransferData(DataFlavor.stringFlavor) as String).toInt()
            var toIndex = table.rowAtPoint(support.dropLocation.dropPoint)
            if (toIndex == -1) {
                toIndex = model.rowCount - 1
            }
            if (fromIndex != -1 && fromIndex != toIndex) {
                model.moveRow(fromIndex, fromIndex, toIndex)
                table.setRowSelectionInterval(toIndex, toIndex)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}