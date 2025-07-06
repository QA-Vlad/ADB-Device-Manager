package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.config.PluginConfig
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.github.qavlad.adbrandomizer.ui.theme.ColorScheme
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import java.util.*
import javax.swing.*
import javax.swing.table.TableCellRenderer
import com.intellij.openapi.application.ApplicationManager
import javax.swing.SwingUtilities

/**
 * Контроллер для диалога настроек.
 * Управляет всей логикой, обработкой событий и состоянием.
 */
class SettingsDialogController(
    private val project: Project?,
    private val dialog: SettingsDialog
) {
    // UI компоненты
    lateinit var table: JBTable
        private set
    lateinit var tableModel: DevicePresetTableModel
        private set
    lateinit var keyboardHandler: KeyboardHandler
        private set
    lateinit var tableConfigurator: TableConfigurator
        private set
    lateinit var validationRenderer: ValidationRenderer
        private set

    // Состояние
    var hoverState = HoverState.noHover()
        private set
    val historyManager = HistoryManager()
    private var updateListener: (() -> Unit)? = null
    private var globalClickListener: java.awt.event.AWTEventListener? = null

    // Состояние редактирования ячейки
    private var editingCellOldValue: String? = null
    private var editingCellRow: Int = -1
    private var editingCellColumn: Int = -1

    /**
     * Инициализация контроллера
     */
    fun initialize() {
        setupUpdateListener()
        refreshDeviceStatesIfNeeded()
    }

    /**
     * Создает модель таблицы с начальными данными
     */
    fun createTableModel(): DevicePresetTableModel {
        val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        val presets = SettingsService.getPresets()
        val dataVector = Vector<Vector<Any>>()
        
        presets.forEachIndexed { index, preset ->
            dataVector.add(createRowVector(preset, index + 1))
        }

        tableModel = DevicePresetTableModel(dataVector, columnNames, historyManager)
        tableModel.addTableModelListener {
            validateFields()
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        
        return tableModel
    }

    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(model: DevicePresetTableModel): JBTable {
        table = object : JBTable(model) {
            @Suppress("DEPRECATION")
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                val component = super.prepareRenderer(renderer, row, column)

                if (component is JComponent) {
                    val isHovered = hoverState.isTableCellHovered(row, column)
                    val isSelectedCell = hoverState.isTableCellSelected(row, column)

                    var isInvalidCell = false
                    if (column in 3..4) {
                        val value = tableModel.getValueAt(row, column)
                        val text = value as? String ?: ""
                        val isValid = if (text.isBlank()) true else when (column) {
                            3 -> ValidationUtils.isValidSizeFormat(text)
                            4 -> ValidationUtils.isValidDpi(text)
                            else -> true
                        }
                        if (!isValid) {
                            isInvalidCell = true
                        }
                    }

                    component.background = ColorScheme.getTableCellBackground(
                        isSelected = isSelectedCell,
                        isHovered = isHovered,
                        isError = isInvalidCell
                    )
                    component.foreground = ColorScheme.getTableCellForeground(
                        isError = isInvalidCell
                    )
                    component.isOpaque = true
                }

                return component
            }

            override fun editCellAt(row: Int, column: Int): Boolean {
                if (row >= 0 && column >= 0) {
                    editingCellOldValue = tableModel.getValueAt(row, column) as? String ?: ""
                    editingCellRow = row
                    editingCellColumn = column
                }
                return super.editCellAt(row, column)
            }

            override fun removeEditor() {
                if (editingCellOldValue != null) {
                    editingCellOldValue = null
                    editingCellRow = -1
                    editingCellColumn = -1
                }
                super.removeEditor()
            }

            override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
                if (rowIndex >= 0 && columnIndex >= 0 && columnIndex in 2..4) {
                    val oldRow = selectionModel.leadSelectionIndex
                    val oldColumn = columnModel.selectionModel.leadSelectionIndex

                    if (oldRow != rowIndex || oldColumn != columnIndex) {
                        editingCellOldValue = tableModel.getValueAt(rowIndex, columnIndex) as? String ?: ""
                        editingCellRow = rowIndex
                        editingCellColumn = columnIndex
                    }
                }
                super.changeSelection(rowIndex, columnIndex, toggle, extend)
            }
        }
        
        return table
    }

    /**
     * Инициализирует обработчики и конфигураторы
     */
    fun initializeHandlers() {
        println("ADB_DEBUG: initializeHandlers called")
        validationRenderer = ValidationRenderer(
            hoverState = { hoverState },
            getPresetAtRow = ::getPresetAtRow,
            findDuplicates = { tableModel.findDuplicates() }
        )

        keyboardHandler = KeyboardHandler(
            table = table,
            tableModel = tableModel,
            hoverState = { hoverState },
            historyManager = historyManager,
            validateFields = ::validateFields,
            setEditingCellData = { oldValue, row, column ->
                editingCellOldValue = oldValue
                editingCellRow = row
                editingCellColumn = column
            },
            onDuplicate = ::duplicatePreset,
            onUndo = ::performUndo,
            onRedo = ::performRedo
        )

        tableConfigurator = TableConfigurator(
            table = table,
            hoverState = { hoverState },
            setHoverState = { newState -> hoverState = newState },
            onRowMoved = ::onRowMoved,
            onCellClicked = ::handleCellClick,
            onTableExited = ::handleTableExit,
            validationRenderer = validationRenderer,
            showContextMenu = ::showContextMenu,
            historyManager = historyManager,
            getPresetAtRow = ::getPresetAtRow
        )

        tableConfigurator.configure()
        table.addKeyListener(keyboardHandler.createTableKeyListener())
        keyboardHandler.addGlobalKeyListener()
        validateFields()
    }

    // === Обработчики событий ===

    fun handleCellClick(row: Int, column: Int, clickCount: Int) {
        if (row >= 0 && column >= 0) {
            val oldSelectedRow = hoverState.selectedTableRow
            val oldSelectedColumn = hoverState.selectedTableColumn

            val newSelectedColumn = if (column in 2..4) column else -1
            hoverState = hoverState.withTableSelection(row, newSelectedColumn)

            if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                val oldRect = table.getCellRect(oldSelectedRow, oldSelectedColumn, false)
                table.repaint(oldRect)
            }

            val newRect = table.getCellRect(row, column, false)
            table.repaint(newRect)

            table.requestFocus()

            if (clickCount == 2 && column in 2..4) {
                table.editCellAt(row, column)
                table.editorComponent?.requestFocus()
            }
        } else {
            val oldSelectedRow = hoverState.selectedTableRow
            val oldSelectedColumn = hoverState.selectedTableColumn

            hoverState = hoverState.clearTableSelection()

            if (oldSelectedRow >= 0 && oldSelectedColumn >= 0) {
                val oldRect = table.getCellRect(oldSelectedRow, oldSelectedColumn, false)
                table.repaint(oldRect)
            }
        }
    }
    
    fun setGlobalClickListener(listener: java.awt.event.AWTEventListener) {
        globalClickListener = listener
    }

    fun handleTableExit() {
        val oldHoverState = hoverState
        hoverState = hoverState.clearTableHover()

        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
            table.repaint(oldRect)
        }
    }

    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        historyManager.addPresetMove(fromIndex, toIndex)
        historyManager.onRowMoved(fromIndex, toIndex)

        if (hoverState.selectedTableRow == fromIndex) {
            hoverState = hoverState.withTableSelection(toIndex, hoverState.selectedTableColumn)
        } else if (hoverState.selectedTableRow != -1) {
            val selectedRow = hoverState.selectedTableRow
            val newSelectedRow = when {
                fromIndex < toIndex && selectedRow in (fromIndex + 1)..toIndex -> selectedRow - 1
                fromIndex > toIndex && selectedRow in toIndex until fromIndex -> selectedRow + 1
                else -> selectedRow
            }
            if (newSelectedRow != selectedRow) {
                hoverState = hoverState.withTableSelection(newSelectedRow, hoverState.selectedTableColumn)
            }
        }

        refreshTable()
    }

    fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return

        val preset = getPresetAtRow(row)
        val popupMenu = JPopupMenu()

        val shortcut = if (SystemInfo.isMac) "Cmd+D" else "Ctrl+D"
        val duplicateItem = JMenuItem("Duplicate ($shortcut)")
        duplicateItem.addActionListener {
            duplicatePreset(row)
        }
        popupMenu.add(duplicateItem)
        popupMenu.addSeparator()

        if (preset.dpi.isNotBlank()) {
            val applyDpiItem = JMenuItem("Apply DPI only (${preset.dpi})")
            applyDpiItem.addActionListener {
                applyPresetFromRow(row, setSize = false, setDpi = true)
            }
            popupMenu.add(applyDpiItem)
        }

        if (preset.size.isNotBlank()) {
            val applySizeItem = JMenuItem("Apply Size only (${preset.size})")
            applySizeItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = false)
            }
            popupMenu.add(applySizeItem)
        }

        if (preset.dpi.isNotBlank() && preset.size.isNotBlank()) {
            val applyBothItem = JMenuItem("Apply Size and DPI")
            applyBothItem.addActionListener {
                applyPresetFromRow(row, setSize = true, setDpi = true)
            }
            popupMenu.add(applyBothItem)
        }

        if (popupMenu.componentCount > 2) {
            popupMenu.show(e.component, e.x, e.y)
        } else if (popupMenu.componentCount > 0 && popupMenu.getComponent(0) == duplicateItem) {
            popupMenu.show(e.component, e.x, e.y)
        }
    }

    // === Действия с пресетами ===

    fun addNewPreset() {
        val newRowIndex = tableModel.rowCount
        val newPreset = DevicePreset("", "", "")
        val newRowVector = createRowVector(newPreset, newRowIndex + 1)
        tableModel.addRow(newRowVector)
        
        historyManager.addPresetAdd(newRowIndex, newPreset)

        SwingUtilities.invokeLater {
            hoverState = hoverState.withTableSelection(newRowIndex, 2)
            table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true))
            table.editCellAt(newRowIndex, 2)
            table.editorComponent?.requestFocus()
            table.repaint()
        }
    }

    fun importCommonDevices() {
        val commonPresets = PluginConfig.DefaultPresets.COMMON_DEVICES.map { (label, size, dpi) ->
            DevicePreset(label, size, dpi)
        }
        val existingLabels = tableModel.getPresets().map { it.label }.toSet()
        val importedPresets = mutableListOf<DevicePreset>()
        val startIndex = tableModel.rowCount
        
        commonPresets.forEach {
            if (!existingLabels.contains(it.label)) {
                val newRowIndex = tableModel.rowCount
                val newRowVector = createRowVector(it, newRowIndex + 1)
                tableModel.addRow(newRowVector)
                importedPresets.add(it)
            }
        }
        
        if (importedPresets.isNotEmpty()) {
            historyManager.addPresetImport(startIndex, importedPresets)
        }
    }

    fun duplicatePreset(row: Int) {
        if (row < 0 || row >= tableModel.rowCount) return

        val originalPreset = getPresetAtRow(row)
        val newPreset = originalPreset.copy(label = "${originalPreset.label} (copy)")

        val insertIndex = row + 1
        val newRowVector = createRowVector(newPreset, 0)
        tableModel.insertRow(insertIndex, newRowVector)

        SwingUtilities.invokeLater {
            table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
            hoverState = hoverState.withTableSelection(insertIndex, 2)
            table.editCellAt(insertIndex, 2)
            table.editorComponent?.requestFocusInWindow()
            table.repaint()
        }
        
        historyManager.addPresetDuplicate(row, insertIndex, originalPreset)
    }

    private fun applyPresetFromRow(row: Int, setSize: Boolean, setDpi: Boolean) {
        if (project != null) {
            val currentPreset = getPresetAtRow(row)
            PresetApplicationService.applyPreset(project, currentPreset, setSize, setDpi)
            refreshTable()
        }
    }

    // === Undo/Redo ===

    fun performUndo(operation: HistoryOperation) {
        when (operation) {
            is HistoryOperation.CellEdit -> {
                val coords = historyManager.findCellCoordinates(operation.cellId)
                if (coords != null) {
                    tableModel.undoValueAt(operation.oldValue, coords.first, coords.second)
                    refreshTable()
                }
            }
            is HistoryOperation.PresetAdd -> {
                if (operation.rowIndex < tableModel.rowCount) {
                    tableModel.removeRow(operation.rowIndex)
                    refreshTable()
                }
            }
            is HistoryOperation.PresetDelete -> {
                val newRowVector = createRowVector(operation.presetData, 0)
                tableModel.insertRow(operation.rowIndex, newRowVector)
                refreshTable()
            }
            is HistoryOperation.PresetMove -> {
                tableModel.moveRow(operation.toIndex, operation.toIndex, operation.fromIndex)
                historyManager.onRowMoved(operation.toIndex, operation.fromIndex)
                refreshTable()
            }
            is HistoryOperation.PresetImport -> {
                val endIndex = operation.startIndex + operation.importedPresets.size - 1
                for (i in endIndex downTo operation.startIndex) {
                    if (i < tableModel.rowCount) {
                        tableModel.removeRow(i)
                    }
                }
                refreshTable()
            }
            is HistoryOperation.PresetDuplicate -> {
                if (operation.duplicateIndex < tableModel.rowCount) {
                    tableModel.removeRow(operation.duplicateIndex)
                    refreshTable()
                }
            }
        }
    }

    fun performRedo(operation: HistoryOperation) {
        when (operation) {
            is HistoryOperation.CellEdit -> {
                val coords = historyManager.findCellCoordinates(operation.cellId)
                if (coords != null) {
                    tableModel.redoValueAt(operation.newValue, coords.first, coords.second)
                    refreshTable()
                }
            }
            is HistoryOperation.PresetAdd -> {
                val newRowVector = createRowVector(operation.presetData, 0)
                tableModel.insertRow(operation.rowIndex, newRowVector)
                refreshTable()
            }
            is HistoryOperation.PresetDelete -> {
                if (operation.rowIndex < tableModel.rowCount) {
                    tableModel.removeRow(operation.rowIndex)
                    refreshTable()
                }
            }
            is HistoryOperation.PresetMove -> {
                tableModel.moveRow(operation.fromIndex, operation.fromIndex, operation.toIndex)
                historyManager.onRowMoved(operation.fromIndex, operation.toIndex)
                refreshTable()
            }
            is HistoryOperation.PresetImport -> {
                for ((index, preset) in operation.importedPresets.withIndex()) {
                    val newRowVector = createRowVector(preset, 0)
                    tableModel.insertRow(operation.startIndex + index, newRowVector)
                }
                refreshTable()
            }
            is HistoryOperation.PresetDuplicate -> {
                val newPreset = operation.presetData.copy(label = "${operation.presetData.label} (copy)")
                val newRowVector = createRowVector(newPreset, 0)
                tableModel.insertRow(operation.duplicateIndex, newRowVector)
                refreshTable()
            }
        }
    }

    // === Валидация ===

    fun validateFields() {
        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            val size = tableModel.getValueAt(i, 3) as? String ?: ""
            val dpi = tableModel.getValueAt(i, 4) as? String ?: ""
            if (size.isNotBlank() && !ValidationUtils.isValidSizeFormat(size)) allValid = false
            if (dpi.isNotBlank() && !ValidationUtils.isValidDpi(dpi)) allValid = false
        }
        dialog.isOKActionEnabled = allValid
        table.repaint()
    }

    // === Сохранение и загрузка ===

    fun saveSettings() {
        if (table.isEditing) table.cellEditor.stopCellEditing()

        val rowsToRemove = mutableListOf<Int>()

        for (i in 0 until tableModel.rowCount) {
            val label = (tableModel.getValueAt(i, 2) as? String ?: "").trim()
            val size = (tableModel.getValueAt(i, 3) as? String ?: "").trim()
            val dpi = (tableModel.getValueAt(i, 4) as? String ?: "").trim()

            if (label.isEmpty() && size.isEmpty() && dpi.isEmpty()) {
                rowsToRemove.add(i)
            }
        }

        rowsToRemove.reversed().forEach { rowIndex ->
            tableModel.removeRow(rowIndex)
        }

        SettingsService.savePresets(tableModel.getPresets())
    }

    // === Вспомогательные методы ===

    fun addHoverEffectToDialogButtons(container: Container) {
        fun processButtons(c: Container) {
            for (component in c.components) {
                when (component) {
                    is JButton -> {
                        if (component.text == "Save" || component.text == "Cancel") {
                            ButtonUtils.addHoverEffect(component)
                        }
                    }
                    is Container -> processButtons(component)
                }
            }
        }
        processButtons(container)
    }

    private fun getPresetAtRow(row: Int): DevicePreset {
        return DevicePreset(
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
    }

    private fun createRowVector(preset: DevicePreset, rowNumber: Int = 0): Vector<Any> {
        return DevicePresetTableModel.createRowVector(preset, rowNumber)
    }

    private fun refreshTable() {
        SwingUtilities.invokeLater {
            validateFields()
            table.repaint()
        }
    }

    private fun setupUpdateListener() {
        updateListener = {
            SwingUtilities.invokeLater {
                table.repaint()
            }
        }
        updateListener?.let { SettingsDialogUpdateNotifier.addListener(it) }
    }

    private fun refreshDeviceStatesIfNeeded() {
        if (project != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                kotlinx.coroutines.runBlocking {
                    DeviceStateService.refreshDeviceStatesAsync()
                }
                SwingUtilities.invokeLater {
                    validateFields()
                    table.repaint()
                }
            }
        }
    }

    fun dispose() {
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        keyboardHandler.removeGlobalKeyListener()
        
        // Удаляем глобальный обработчик кликов
        globalClickListener?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
    }
    
    /**
     * Рекурсивно добавляет обработчик клика ко всем компонентам для выхода из режима редактирования
     */
    fun addClickListenerRecursively(container: Container, table: JBTable) {
        val mouseListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Проверяем, что клик не по самой таблице и не по кнопкам
                if (e.source !is JBTable && e.source !is JButton && table.isEditing) {
                    println("ADB_DEBUG: Cell editing stopped by recursive click listener")
                    table.cellEditor?.stopCellEditing()
                }
            }
        }
        
        println("ADB_DEBUG: Adding mouse listener to: ${container.javaClass.simpleName}")
        // Добавляем обработчик к контейнеру
        container.addMouseListener(mouseListener)
        
        // Рекурсивно обрабатываем все дочерние компоненты
        for (component in container.components) {
            println("ADB_DEBUG: Processing component: ${component.javaClass.simpleName}")
            when (component) {
                is JBTable -> {
                    // Пропускаем таблицу
                }
                is JButton -> {
                    // Пропускаем кнопки
                }
                is Container -> {
                    addClickListenerRecursively(component, table)
                }
                else -> {
                    // Добавляем обработчик к обычным компонентам
                    println("ADB_DEBUG: Adding mouse listener to non-container: ${component.javaClass.simpleName}")
                    component.addMouseListener(mouseListener)
                }
            }
        }
    }
}
