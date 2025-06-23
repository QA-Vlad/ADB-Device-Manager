// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DevicePresetTableModel.kt
package io.github.qavlad.adbrandomizer.ui

import io.github.qavlad.adbrandomizer.services.DevicePreset
import java.util.*
import javax.swing.table.DefaultTableModel

class DevicePresetTableModel(data: Vector<Vector<Any>>, columnNames: Vector<String>) : DefaultTableModel(data, columnNames) {

    init {
        updateRowNumbers()
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        return column != 0 && column != 1 // Запрещаем редактирование ручки и номера
    }

    fun getPresets(): List<DevicePreset> {
        return dataVector.map { row ->
            DevicePreset(
                label = row.elementAt(2) as? String ?: "", // Теперь Label в колонке 2
                size = row.elementAt(3) as? String ?: "",  // Size в колонке 3
                dpi = row.elementAt(4) as? String ?: ""    // DPI в колонке 4
            )
        }
    }

    override fun addRow(rowData: Vector<*>?) {
        super.addRow(rowData)
        updateRowNumbers()
    }

    override fun removeRow(row: Int) {
        super.removeRow(row)
        updateRowNumbers()
    }

    override fun moveRow(start: Int, end: Int, to: Int) {
        super.moveRow(start, end, to)
        updateRowNumbers()
    }

    private fun updateRowNumbers() {
        for (i in 0 until rowCount) {
            setValueAt(i + 1, i, 1) // Устанавливаем номер строки в колонку 1
        }
    }
}