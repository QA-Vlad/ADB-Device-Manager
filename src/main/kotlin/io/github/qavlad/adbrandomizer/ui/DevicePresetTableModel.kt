// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/ui/DevicePresetTableModel.kt
package io.github.qavlad.adbrandomizer.ui

import io.github.qavlad.adbrandomizer.services.DevicePreset
import java.util.*
import javax.swing.table.DefaultTableModel

class DevicePresetTableModel(data: Vector<Vector<Any>>, columnNames: Vector<String>) : DefaultTableModel(data, columnNames) {
    override fun isCellEditable(row: Int, column: Int): Boolean {
        return column != 0 // Запрещаем редактирование ручки
    }

    fun getPresets(): List<DevicePreset> {
        return dataVector.map { row ->
            DevicePreset(
                label = row.elementAt(1) as? String ?: "",
                size = row.elementAt(2) as? String ?: "",
                dpi = row.elementAt(3) as? String ?: ""
            )
        }
    }
}