package io.github.qavlad.adbrandomizer.ui.commands

import io.github.qavlad.adbrandomizer.ui.dialogs.SettingsDialogController
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Команда для удаления пресета
 */
class PresetDeleteCommand(
    controller: SettingsDialogController,
    private val rowIndex: Int,
    private val presetData: DevicePreset,
    private val listName: String? = null,
    private val actualListIndex: Int? = null // Реальный индекс в списке (может отличаться от rowIndex при скрытии дубликатов)
) : AbstractPresetCommand(controller) {

    override val description: String
        get() = "Delete preset '${presetData.label}' at row $rowIndex"

    override fun execute() {
        // Этот метод вызывается при первоначальном действии
        if (rowIndex < tableModel.rowCount) {
            tableModel.removeRow(rowIndex)
        }
        // TableModelListener должен вызвать syncTableChangesToTempLists и удалить элемент из temp-списка
    }

    override fun undo() {
        val targetListName = listName ?: currentPresetList?.name
        println("ADB_DEBUG: Undoing delete for preset '${presetData.label}' in list '$targetListName'")

        if (targetListName != null) {
            val targetList = tempPresetLists.values.find { it.name == targetListName }
            if (targetList != null) {
                // Используем actualListIndex если он есть (для режима скрытия дубликатов), иначе rowIndex
                val indexToRestore = actualListIndex ?: rowIndex
                if (indexToRestore <= targetList.presets.size) {
                    targetList.presets.add(indexToRestore, presetData.copy())
                    println("ADB_DEBUG: Restored preset to temp list '$targetListName' at index $indexToRestore (actualListIndex=$actualListIndex, rowIndex=$rowIndex)")
                }

                if (!isShowAllPresetsMode && targetListName != currentPresetList?.name) {
                    val listToSwitch = tempPresetLists.values.find { it.name == targetListName }
                    if (listToSwitch != null) {
                        controller.setCurrentPresetListForCommands(listToSwitch)
                    }
                }
            }
        }

        invokeLater {
            withTableUpdateDisabled {
                loadPresetsIntoTable()
                refreshTable()
                updateUI() // Обновляем UI после всех изменений
            }
        }
    }

    override fun redo() {
        val targetListName = listName ?: currentPresetList?.name
        println("ADB_DEBUG: Redoing delete for preset '${presetData.label}' in list '$targetListName'")

        if (targetListName != null) {
            val targetList = tempPresetLists.values.find { it.name == targetListName }
            if (targetList != null) {
                if (rowIndex < targetList.presets.size) {
                    targetList.presets.removeAt(rowIndex)
                    println("ADB_DEBUG: Removed preset from temp list '$targetListName' at index $rowIndex")
                }
            }
        }

        invokeLater {
            withTableUpdateDisabled {
                loadPresetsIntoTable()
                refreshTable()
                updateUI() // Обновляем UI после всех изменений
            }
        }
    }
}