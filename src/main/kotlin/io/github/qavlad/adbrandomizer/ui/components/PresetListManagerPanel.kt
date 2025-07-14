package io.github.qavlad.adbrandomizer.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
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
    private val onHideDuplicatesChanged: (Boolean) -> Unit
) : JPanel(BorderLayout()) {
    
    private val listComboBox = ComboBox<PresetListItem>()
    private val showAllPresetsCheckbox = JBCheckBox("Show all presets", false)
    private val hideDuplicatesCheckbox = JBCheckBox("Hide duplicates", false)
    
    private var isUpdatingComboBox = false
    
    init {
        setupUI()
        loadLists()
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
        
        val renameButton = JButton(AllIcons.Actions.Edit).apply {
            toolTipText = "Rename current list"
            preferredSize = Dimension(24, 24)
            addActionListener { renameCurrentList() }
        }
        ButtonUtils.addHoverEffect(renameButton)
        topPanel.add(renameButton)
        
        val deleteButton = JButton(AllIcons.General.Remove).apply {
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
        
        // Нижняя панель с чекбоксами
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        
        showAllPresetsCheckbox.addItemListener { event ->
            val isSelected = event.stateChange == ItemEvent.SELECTED
            onShowAllPresetsChanged(isSelected)
            
            // Включаем/выключаем комбобокс в зависимости от состояния
            listComboBox.isEnabled = !isSelected
            renameButton.isEnabled = !isSelected
            deleteButton.isEnabled = !isSelected
        }
        ButtonUtils.addHoverEffect(showAllPresetsCheckbox)
        bottomPanel.add(showAllPresetsCheckbox)
        
        hideDuplicatesCheckbox.addItemListener { event ->
            onHideDuplicatesChanged(event.stateChange == ItemEvent.SELECTED)
        }
        ButtonUtils.addHoverEffect(hideDuplicatesCheckbox)
        bottomPanel.add(hideDuplicatesCheckbox)
        
        // Компонуем все вместе
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
        
        add(mainPanel, BorderLayout.CENTER)
        border = JBUI.Borders.emptyBottom(10)
    }
    
    private fun loadLists() {
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
            // Проверяем, что это не последний список
            if (listComboBox.itemCount <= 1) {
                // Создаем новый пустой список
                val newList = PresetListService.createNewList("Empty List")
                PresetListService.setActiveListId(newList.id)
            }
            
            if (PresetListService.deleteList(selectedItem.id)) {
                loadLists()
                
                // Загружаем новый активный список
                PresetListService.getActivePresetList()?.let { list ->
                    onListChanged(list)
                }
            } else {
                Messages.showErrorDialog(
                    this,
                    "Cannot delete default preset list",
                    "Delete Error"
                )
            }
        }
    }
    
    private fun exportLists() {
        // Диалог выбора списков для экспорта
        val metadata = PresetListService.getAllListsMetadata()
        val listNames = metadata.map { it.name }.toTypedArray()
        
        // Создаем кастомный диалог с чекбоксами
        val checkBoxes = mutableListOf<JBCheckBox>()
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Select preset lists to export:"))
            add(Box.createVerticalStrut(10))
            
            listNames.forEach { name ->
                val checkBox = JBCheckBox(name, true)
                checkBoxes.add(checkBox)
                add(checkBox)
            }
        }
        
        // Создаем кастомный диалог
        val dialog = object : com.intellij.openapi.ui.DialogWrapper(true) {
            init {
                title = "Export Preset Lists"
                init()
            }
            
            override fun createCenterPanel(): JComponent = panel
        }
        
        if (dialog.showAndGet()) { // OK button clicked
            val selectedLists = mutableListOf<String>()
            checkBoxes.forEachIndexed { index, checkBox ->
                if (checkBox.isSelected) {
                    selectedLists.add(metadata[index].id)
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
    
    /**
     * Элемент для ComboBox
     */
    private data class PresetListItem(
        val id: String,
        val name: String
    ) {
        override fun toString(): String = name
    }
}
