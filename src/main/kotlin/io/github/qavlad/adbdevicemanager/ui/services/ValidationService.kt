package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.ui.components.DevicePresetTableModel
import io.github.qavlad.adbdevicemanager.utils.ValidationUtils

/**
 * Сервис для валидации данных в таблице.
 */
class ValidationService {

    private val invalidCells = mutableSetOf<Pair<Int, Int>>()

    /**
     * Проверяет все поля в таблице и обновляет внутренний набор невалидных ячеек.
     * @return true, если все поля валидны, иначе false.
     */
    fun validateAllFields(tableModel: DevicePresetTableModel): Boolean {
        invalidCells.clear()
        var allValid = true

        for (row in 0 until tableModel.rowCount) {
            val preset = tableModel.getPresetAt(row) ?: continue

            // Пропускаем строку с кнопкой добавления
            if (tableModel.getValueAt(row, 0) == "+") continue

            val isSizeValid = validatePresetField(preset.size, ValidationUtils::isValidSizeFormat)
            if (!isSizeValid) {
                invalidCells.add(row to 3)
                allValid = false
            }

            val isDpiValid = validatePresetField(preset.dpi, ValidationUtils::isValidDpi)
            if (!isDpiValid) {
                invalidCells.add(row to 4)
                allValid = false
            }
        }
        return allValid
    }

    /**
     * Проверяет, является ли ячейка невалидной.
     */
    fun isCellInvalid(row: Int, column: Int): Boolean {
        return invalidCells.contains(row to column)
    }

    /**
     * Вспомогательная функция для валидации поля пресета.
     */
    private fun validatePresetField(value: String, validator: (String) -> Boolean): Boolean {
        return value.isBlank() || validator(value)
    }
    
    /**
     * Проверяет поля и обновляет состояние диалога
     * @return true если все поля валидны
     */
    fun validateFieldsAndUpdateUI(
        tableModel: DevicePresetTableModel,
        onUpdateOKButton: (Boolean) -> Unit,
        onRepaintTable: () -> Unit
    ): Boolean {
        val allValid = validateAllFields(tableModel)
        onUpdateOKButton(allValid)
        onRepaintTable()
        return allValid
    }
}