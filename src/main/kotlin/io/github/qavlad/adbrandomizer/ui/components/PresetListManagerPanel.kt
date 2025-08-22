package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.ui.dialogs.ExportPresetListsDialog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File
import java.awt.Dimension
import com.intellij.util.ui.JBUI

/**
 * Панель для управления списками пресетов
 */
class PresetListManagerPanel(
    private val onListChanged: (PresetList) -> Unit,
    private val onShowAllPresetsChanged: (Boolean) -> Unit,
    private val onHideDuplicatesChanged: (Boolean) -> Unit,
    private val onResetSorting: () -> Unit = {},
    private val onShowCountersChanged: (Boolean) -> Unit = {},
    private val onResetCounters: () -> Unit = {},
    private val onListDeleted: ((String) -> Unit)? = null,
    private val onListReset: ((String, PresetList) -> Unit)? = null,
    private val onListCreated: ((PresetList) -> Unit)? = null,
    private val onClearListOrderInMemory: ((String) -> Unit)? = null,
    private val onResetOrientation: (() -> Unit)? = null
) : JPanel(BorderLayout()) {
    
    private val listComboBox = ComboBox<PresetListItem>()
    private val showAllPresetsCheckbox = JBCheckBox("Show all presets", false).apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val hideDuplicatesCheckbox = JBCheckBox("Hide duplicates", false).apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val showCountersCheckbox = JBCheckBox("Show usage counters", false).apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    
    private lateinit var resetButton: JButton
    private lateinit var renameButton: JButton
    private lateinit var deleteButton: JButton
    
    private var isUpdatingComboBox = false
    
    init {
        setupUI()
        loadLists()
        
        // Устанавливаем правильную подсказку для кнопки Reset в зависимости от начального состояния
        if (showAllPresetsCheckbox.isSelected) {
            resetButton.toolTipText = "Reset all default preset lists to original values"
        }
    }
    
    private fun setupUI() {
        // Верхняя панель с комбобоксом и кнопками управления
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        
        // Комбобокс для выбора списка
        listComboBox.preferredSize = Dimension(200, listComboBox.preferredSize.height)
        listComboBox.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED && !isUpdatingComboBox) {
                handleListSelection()
            }
        }
        topPanel.add(JLabel("Preset List:"))
        topPanel.add(listComboBox)
        
        // Кнопки управления списками
        val addListButton = JButton(AllIcons.General.Add).apply {
            toolTipText = "Create new preset list"
            preferredSize = Dimension(24, 24)
            addActionListener { createNewList() }
        }
        ButtonUtils.addHoverEffect(addListButton)
        topPanel.add(addListButton)
        
        renameButton = JButton(AllIcons.Actions.Edit).apply {
            toolTipText = "Rename current list"
            preferredSize = Dimension(24, 24)
            addActionListener { renameCurrentList() }
        }
        ButtonUtils.addHoverEffect(renameButton)
        topPanel.add(renameButton)
        
        deleteButton = JButton(AllIcons.General.Remove).apply {
            toolTipText = "Delete current list"
            preferredSize = Dimension(24, 24)
            addActionListener { deleteCurrentList() }
        }
        ButtonUtils.addHoverEffect(deleteButton)
        topPanel.add(deleteButton)
        
        topPanel.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 20)
        })
        
        // Кнопки экспорта/импорта
        val exportButton = JButton(AllIcons.ToolbarDecorator.Export).apply {
            toolTipText = "Export preset lists"
            preferredSize = Dimension(24, 24)
            addActionListener { exportLists() }
        }
        ButtonUtils.addHoverEffect(exportButton)
        topPanel.add(exportButton)
        
        val importButton = JButton(AllIcons.ToolbarDecorator.Import).apply {
            toolTipText = "Import preset lists"
            preferredSize = Dimension(24, 24)
            addActionListener { importLists() }
        }
        ButtonUtils.addHoverEffect(importButton)
        topPanel.add(importButton)
        
        topPanel.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(1, 20)
        })
        
        // Кнопка сброса пресетов
        resetButton = JButton(AllIcons.General.Reset).apply {
            toolTipText = "Reset presets to defaults"
            preferredSize = Dimension(24, 24)
            addActionListener { resetPresets() }
        }
        ButtonUtils.addHoverEffect(resetButton)
        topPanel.add(resetButton)
        
        // Нижняя панель с чекбоксами
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        
        showAllPresetsCheckbox.addItemListener { event ->
            val isSelected = event.stateChange == ItemEvent.SELECTED
            onShowAllPresetsChanged(isSelected)
            
            // Включаем/выключаем комбобокс в зависимости от состояния
            listComboBox.isEnabled = !isSelected
            
            // Обновляем подсказку кнопки Reset в зависимости от режима
            resetButton.toolTipText = if (isSelected) {
                "Reset all default preset lists to original values"
            } else {
                "Reset presets to defaults"
            }
            renameButton.isEnabled = !isSelected
            deleteButton.isEnabled = !isSelected
        }
        
        // Добавляем hover эффект как у кнопок
        setupCheckboxHoverEffect(showAllPresetsCheckbox)
        
        bottomPanel.add(showAllPresetsCheckbox)
        
        hideDuplicatesCheckbox.addItemListener { event ->
            onHideDuplicatesChanged(event.stateChange == ItemEvent.SELECTED)
        }
        
        // Добавляем hover эффект как у кнопок
        setupCheckboxHoverEffect(hideDuplicatesCheckbox)
        
        bottomPanel.add(hideDuplicatesCheckbox)
        
        showCountersCheckbox.addItemListener { event ->
            onShowCountersChanged(event.stateChange == ItemEvent.SELECTED)
        }
        
        // Добавляем hover эффект как у кнопок
        setupCheckboxHoverEffect(showCountersCheckbox)
        
        bottomPanel.add(showCountersCheckbox)
        
        // Добавляем кнопку сброса сортировки
        bottomPanel.add(Box.createHorizontalStrut(20)) // Отступ
        val resetSortButton = JButton("Reset Sorting").apply {
            toolTipText = "Reset all column sorting"
            addActionListener { 
                onResetSorting()
            }
        }
        ButtonUtils.addHoverEffect(resetSortButton)
        bottomPanel.add(resetSortButton)
        
        // Добавляем кнопку сброса счетчиков
        val resetCountersButton = JButton("Reset Counters").apply {
            toolTipText = "Reset all usage counters to zero"
            addActionListener { 
                resetUsageCounters()
            }
        }
        ButtonUtils.addHoverEffect(resetCountersButton)
        bottomPanel.add(resetCountersButton)
        
        // Компонуем все вместе
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
        
        add(mainPanel, BorderLayout.CENTER)
        border = JBUI.Borders.emptyBottom(10)
    }
    
    fun loadLists() {
        isUpdatingComboBox = true
        try {
            listComboBox.removeAllItems()
            
            val metadata = PresetListService.getAllListsMetadata()
            metadata.forEach { meta ->
                listComboBox.addItem(PresetListItem(meta.id, meta.name))
            }
            
            // Выбираем активный список
            val activeId = PresetListService.getActiveListId()
            if (activeId != null) {
                for (i in 0 until listComboBox.itemCount) {
                    if (listComboBox.getItemAt(i).id == activeId) {
                        listComboBox.selectedIndex = i
                        // Обновляем состояние кнопки сброса
                        PresetListService.loadPresetList(activeId)?.let { list ->
                            updateResetButtonState(list)
                        }
                        break
                    }
                }
            }
        } finally {
            isUpdatingComboBox = false
        }
    }
    
    private fun handleListSelection() {
        val selectedItem = listComboBox.selectedItem as? PresetListItem ?: return
        
        // Сохраняем выбранный список как активный
        PresetListService.setActiveListId(selectedItem.id)
        
        // Загружаем список и передаем его в callback
        PresetListService.loadPresetList(selectedItem.id)?.let { list ->
            onListChanged(list)
            updateResetButtonState(list)
        }
    }
    
    private fun updateResetButtonState(list: PresetList) {
        // Кнопка сброса активна только для дефолтных или импортированных списков
        resetButton.isEnabled = list.isDefault || list.isImported
        resetButton.toolTipText = when {
            list.isDefault -> "Reset this default preset list to original values"
            list.isImported -> "Reset this imported preset list to original values"
            else -> "Reset is only available for default or imported lists"
        }
    }
    
    private fun createNewList() {
        val name = Messages.showInputDialog(
            this,
            "Enter name for new preset list:",
            "New Preset List",
            Messages.getQuestionIcon()
        )
        
        if (!name.isNullOrBlank()) {
            // Проверяем уникальность имени
            if (PresetListService.isListNameExists(name)) {
                Messages.showErrorDialog(
                    this,
                    "A list with name '$name' already exists",
                    "Duplicate Name"
                )
                return
            }
            
            val newList = PresetListService.createNewList(name)
            PresetListService.setActiveListId(newList.id)
            
            // Добавляем новый список в tempLists если есть callback
            onListCreated?.invoke(newList)
            
            loadLists()
            onListChanged(newList)
        }
    }
    
    private fun renameCurrentList() {
        val selectedItem = listComboBox.selectedItem as? PresetListItem ?: return
        
        val newName = Messages.showInputDialog(
            this,
            "Enter new name for preset list:",
            "Rename Preset List",
            Messages.getQuestionIcon(),
            selectedItem.name,
            null
        )
        
        if (!newName.isNullOrBlank() && newName != selectedItem.name) {
            // Проверяем уникальность имени
            if (PresetListService.isListNameExists(newName, selectedItem.id)) {
                Messages.showErrorDialog(
                    this,
                    "A list with name '$newName' already exists",
                    "Duplicate Name"
                )
                return
            }
            
            if (PresetListService.renameList(selectedItem.id, newName)) {
                loadLists()
            }
        }
    }
    
    private fun deleteCurrentList() {
        val selectedItem = listComboBox.selectedItem as? PresetListItem ?: return
        
        val result = Messages.showYesNoDialog(
            this,
            "Are you sure you want to delete the list '${selectedItem.name}'?",
            "Delete Preset List",
            Messages.getWarningIcon()
        )
        
        if (result == Messages.YES) {
            // Удаляем список ID для обработки в EventHandlersInitializer
            val deletedListId = selectedItem.id
            
            // Проверяем, что это не последний список
            if (listComboBox.itemCount <= 1) {
                // Создаем новый пустой список
                val newList = PresetListService.createNewList("Empty List")
                PresetListService.setActiveListId(newList.id)
                
                // Добавляем новый список в tempLists
                onListCreated?.invoke(newList)
            }
            
            // Удаляем список из сервиса
            PresetListService.deleteList(deletedListId)
            loadLists()
            
            // Уведомляем контроллер об удалении для обновления tempLists
            onListDeleted?.invoke(deletedListId)
            
            // Загружаем новый активный список
            PresetListService.getActivePresetList()?.let { list ->
                onListChanged(list)
            }
        }
    }
    
    private fun exportLists() {
        // Диалог выбора списков для экспорта
        val metadata = PresetListService.getAllListsMetadata()
        val listNames = metadata.map { it.name }.toTypedArray()
        
        // Используем новый диалог с поддержкой навигации
        val dialog = ExportPresetListsDialog(listNames)
        
        if (dialog.showAndGet()) { // OK button clicked
            val selectedListNames = dialog.getSelectedLists()
            val selectedLists = mutableListOf<String>()
            
            // Сопоставляем имена с ID
            selectedListNames.forEach { name ->
                metadata.find { it.name == name }?.let {
                    selectedLists.add(it.id)
                }
            }
            
            if (selectedLists.isEmpty()) {
                Messages.showWarningDialog(
                    this,
                    "No lists selected for export",
                    "Export Warning"
                )
                return
            }
            
            // Выбор файла для сохранения
            val fileChooser = JFileChooser().apply {
                dialogTitle = "Export Preset Lists"
                fileFilter = FileNameExtensionFilter("JSON Files", "json")
                selectedFile = File("preset_lists_export.json")
            }
            
            if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                var file = fileChooser.selectedFile
                if (!file.name.endsWith(".json")) {
                    file = File(file.absolutePath + ".json")
                }
                
                try {
                    PresetListService.exportLists(selectedLists, file)
                    Messages.showInfoMessage(
                        this,
                        "Successfully exported ${selectedLists.size} preset list(s)",
                        "Export Success"
                    )
                } catch (e: Exception) {
                    Messages.showErrorDialog(
                        this,
                        "Failed to export preset lists: ${e.message}",
                        "Export Error"
                    )
                }
            }
        }
    }
    
    private fun importLists() {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Import Preset Lists"
            fileFilter = FileNameExtensionFilter("JSON Files", "json")
        }
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                val importedLists = PresetListService.importLists(fileChooser.selectedFile)
                Messages.showInfoMessage(
                    this,
                    "Successfully imported ${importedLists.size} preset list(s)",
                    "Import Success"
                )
                loadLists()
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    this,
                    "Failed to import preset lists: ${e.message}",
                    "Import Error"
                )
            }
        }
    }
    
    private fun resetUsageCounters() {
        val result = Messages.showYesNoDialog(
            this,
            "Are you sure you want to reset all usage counters to zero?",
            "Reset Usage Counters",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            onResetCounters()
        }
    }

    
    /**
     * Устанавливает состояние чекбокса "Show all presets"
     */
    fun setShowAllPresets(enabled: Boolean) {
        if (showAllPresetsCheckbox.isSelected != enabled) {
            showAllPresetsCheckbox.isSelected = enabled
        }
    }
    
    /**
     * Устанавливает состояние чекбокса "Hide duplicates"
     */
    fun setHideDuplicates(enabled: Boolean) {
        if (hideDuplicatesCheckbox.isSelected != enabled) {
            hideDuplicatesCheckbox.isSelected = enabled
            // Явно вызываем обработчик, так как программное изменение может не вызвать ItemListener
            onHideDuplicatesChanged(enabled)
        }
    }
    
    /**
     * Устанавливает состояние чекбокса "Show usage counters"
     */
    fun setShowCounters(enabled: Boolean) {
        if (showCountersCheckbox.isSelected != enabled) {
            showCountersCheckbox.isSelected = enabled
        }
    }
    
    /**
     * Выбирает список по имени
     */
    fun selectListByName(listName: String) {
        for (i in 0 until listComboBox.itemCount) {
            if (listComboBox.getItemAt(i).name == listName) {
                listComboBox.selectedIndex = i
                break
            }
        }
    }
    
    private fun resetPresets() {
        // В режиме Show All сразу показываем диалог подтверждения сброса всех списков
        if (showAllPresetsCheckbox.isSelected) {
            val result = Messages.showYesNoDialog(
                this,
                "This will reset ALL default preset lists to their original values.\n" +
                "Any custom presets you added will be removed.\n\n" +
                "Are you sure you want to continue?",
                "Confirm Reset All Default Lists",
                Messages.getWarningIcon()
            )
            
            if (result == Messages.YES) {
                resetAllDefaultLists()
            }
            return
        }
        
        val selectedItem = listComboBox.selectedItem as? PresetListItem ?: return
        val list = PresetListService.loadPresetList(selectedItem.id) ?: return
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Reset button clicked for list: %s (id: %s), isDefault: %s, isImported: %s", 
            selectedItem.name, selectedItem.id, list.isDefault, list.isImported)
        
        // Проверяем, что это дефолтный или импортированный список
        if (!list.isDefault && !list.isImported) {
            Messages.showWarningDialog(
                this,
                "Reset is only available for default or imported preset lists.",
                "Cannot Reset"
            )
            return
        }
        
        // Показываем диалог выбора: сбросить только текущий список или все
        val options = arrayOf("Reset Current List", "Reset All Default Lists", "Cancel")
        val choice = Messages.showDialog(
            this,
            "Choose reset option:\n\n" +
            "• Reset Current List - Reset only '${selectedItem.name}' to default values\n" +
            "• Reset All Default Lists - Reset all default and imported lists to original values",
            "Reset Presets",
            options,
            0,
            AllIcons.General.Reset
        )
        
        when (choice) {
            0 -> { // Reset Current List
                if (list.isDefault) {
                    // Для дефолтного списка загружаем из ресурсов
                    resetDefaultList(selectedItem.id, selectedItem.name)
                } else if (list.isImported) {
                    // Для импортированного пытаемся восстановить из оригинального файла
                    Messages.showInfoMessage(
                        this,
                        "Resetting imported lists is not yet implemented.\n" +
                        "Please re-import the list from the original file.",
                        "Reset Imported List"
                    )
                }
            }
            1 -> { // Reset All Default Lists
                val confirm = Messages.showYesNoDialog(
                    this,
                    "This will reset ALL default preset lists to their original values.\n" +
                    "Custom lists will not be affected.\n\n" +
                    "Are you sure you want to continue?",
                    "Confirm Reset All",
                    AllIcons.General.WarningDialog
                )
                
                if (confirm == Messages.YES) {
                    resetAllDefaultLists()
                }
            }
            // 2 или -1 (Cancel или закрытие диалога) - ничего не делаем
        }
    }
    
    private fun resetDefaultList(listId: String, listName: String) {
        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
            "=== RESET BUTTON: Starting reset for list '%s' (id: %s) ===", listName, listId)
        
        // Вызываем тестовый метод для отладки
        PresetListService.debugResourceLoading()
        
        // Загружаем текущий список для сравнения
        val currentList = PresetListService.loadPresetList(listId)
        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
            "RESET BUTTON: Current list has %d presets before reset", 
            currentList?.presets?.size ?: 0)
        
        try {
            // Загружаем оригинальный список из ресурсов
            val originalList = PresetListService.loadDefaultListFromResources(listId)
            if (originalList != null) {
                // Логируем количество пресетов для отладки
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "RESET BUTTON: Got list '%s' with %d presets from resources", 
                    listName, originalList.presets.size)
                
                // Логируем первые несколько пресетов для проверки
                originalList.presets.take(3).forEachIndexed { i, preset ->
                    PluginLogger.warn(LogCategory.PRESET_SERVICE,
                        "RESET BUTTON: Preset[%d]: %s | %s | %s",
                        i, preset.label, preset.size, preset.dpi)
                }
                
                // Сохраняем оригинальный список вместо текущего
                PresetListService.savePresetList(originalList)
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "RESET: Saved list to file with %d presets", originalList.presets.size)
                
                // Очищаем кэш, чтобы форсировать перезагрузку
                PresetListService.clearAllCaches()
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "RESET: Cleared all caches")
                
                Messages.showInfoMessage(
                    this,
                    "Preset list '$listName' has been reset to default values.\nRestored ${originalList.presets.size} presets.",
                    "Reset Successful"
                )
                
                // Перезагружаем список заново после сохранения
                val reloadedList = PresetListService.loadPresetList(listId)
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "RESET: Reloaded list - null: %s, presets: %d", 
                    reloadedList == null, reloadedList?.presets?.size ?: 0)
                if (reloadedList != null) {
                    // Обновляем состояние кнопки сброса
                    updateResetButtonState(reloadedList)
                    // Очищаем сохранённый порядок drag & drop для этого списка
                    onClearListOrderInMemory?.invoke(listId)
                    // Сбрасываем ориентацию в вертикальную
                    onResetOrientation?.invoke()
                    // Обновляем временный список после сброса ПЕРЕД вызовом onListChanged
                    onListReset?.invoke(listId, reloadedList)
                    // Уведомляем об изменении списка
                    onListChanged(reloadedList)
                } else {
                    PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                        "Failed to reload list after reset: %s", listId)
                }
            } else {
                PluginLogger.error(LogCategory.PRESET_SERVICE, 
                    "RESET BUTTON: Failed - originalList is NULL for '%s'", null, listName)
                Messages.showErrorDialog(
                    this,
                    "Could not find default values for list '$listName'.\nPlease check that the preset files are included in the plugin resources.",
                    "Reset Failed"
                )
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.PRESET_SERVICE, 
                "Error resetting list '%s': %s", e, listName, e.message)
            Messages.showErrorDialog(
                this,
                "Failed to reset list: ${e.message}",
                "Reset Error"
            )
        }
    }
    
    private fun resetAllDefaultLists() {
        try {
            // Загружаем ВСЕ дефолтные списки из ресурсов (включая удаленные)
            val allDefaultLists = PresetListService.loadPresetsFromResources()
            var resetCount = 0
            val resetDetails = mutableListOf<String>()
            
            // Сбрасываем или восстанавливаем каждый дефолтный список
            allDefaultLists.forEach { defaultList ->
                if (defaultList.isDefault) {
                    // Сохраняем список (это перезапишет существующий или создаст новый)
                    PresetListService.savePresetList(defaultList)
                    resetCount++
                    resetDetails.add("${defaultList.name}: ${defaultList.presets.size} presets")
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Reset/restored list '%s' to %d presets", 
                        defaultList.name, defaultList.presets.size)
                    
                    // Очищаем сохранённый порядок drag & drop для этого списка
                    onClearListOrderInMemory?.invoke(defaultList.id)
                    // Обновляем временный список для каждого сброшенного списка
                    onListReset?.invoke(defaultList.id, defaultList)
                }
            }
            
            // Обновляем метаданные, чтобы включить восстановленные списки
            if (resetCount > 0) {
                val existingMetadata = PresetListService.getAllListsMetadata()
                val restoredIds = allDefaultLists.filter { it.isDefault }.map { it.id }.toSet()
                
                // Создаём новые метаданные, объединяя существующие кастомные списки и восстановленные дефолтные
                val newMetadata = mutableListOf<PresetListService.ListMetadata>()
                
                // Добавляем все восстановленные дефолтные списки
                allDefaultLists.filter { it.isDefault }.forEach { list ->
                    newMetadata.add(PresetListService.ListMetadata(list.id, list.name, true))
                }
                
                // Добавляем существующие кастомные списки (не дефолтные)
                existingMetadata.filter { !it.isDefault && !restoredIds.contains(it.id) }.forEach { meta ->
                    newMetadata.add(meta)
                }
                
                PresetListService.saveListsMetadata(newMetadata)
            }
            
            if (resetCount > 0) {
                // Сбрасываем ориентацию в вертикальную после сброса всех списков
                onResetOrientation?.invoke()
                
                Messages.showInfoMessage(
                    this,
                    "$resetCount default preset list(s) have been reset to original values:\n\n${resetDetails.joinToString("\n")}",
                    "Reset Complete"
                )
            } else {
                Messages.showWarningDialog(
                    this,
                    "No default lists were found to reset.",
                    "Nothing to Reset"
                )
            }
            
            // Перезагружаем списки и обновляем состояние кнопки
            loadLists()
            
            // Обновляем текущий выбранный список
            val selectedItem = listComboBox.selectedItem as? PresetListItem
            if (selectedItem != null) {
                val reloadedList = PresetListService.loadPresetList(selectedItem.id)
                if (reloadedList != null) {
                    updateResetButtonState(reloadedList)
                    onListChanged(reloadedList)
                }
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                this,
                "Failed to reset default lists: ${e.message}",
                "Reset Error"
            )
        }
    }
    
    /**
     * Элемент для ComboBox
     */
    private data class PresetListItem(
        val id: String,
        val name: String
    ) {
        override fun toString(): String = name
    }
    
    /**
     * Устанавливает выбранный список по имени
     */
    fun setSelectedList(name: String) {
        isUpdatingComboBox = true
        try {
            for (i in 0 until listComboBox.itemCount) {
                val item = listComboBox.getItemAt(i)
                if (item.name == name) {
                    listComboBox.selectedItem = item
                    break
                }
            }
        } finally {
            isUpdatingComboBox = false
        }
    }
    
    /**
     * Настраивает визуальный hover эффект для чекбокса
     */
    private fun setupCheckboxHoverEffect(checkBox: JBCheckBox) {
        val originalBackground = checkBox.background
        val hoverBackground = originalBackground?.brighter()
        
        checkBox.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                if (checkBox.isEnabled) {
                    // Применяем эффект похожий на кнопки
                    checkBox.isOpaque = true
                    if (hoverBackground != null) {
                        checkBox.background = hoverBackground
                    }
                    // Включаем rollover для визуального эффекта на самом чекбоксе
                    checkBox.model.isRollover = true
                }
            }
            
            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                // Восстанавливаем исходное состояние
                checkBox.background = originalBackground
                checkBox.isOpaque = false
                checkBox.model.isRollover = false
            }
        })
    }

}
