package io.github.qavlad.adbdevicemanager.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import io.github.qavlad.adbdevicemanager.services.PresetList
import io.github.qavlad.adbdevicemanager.services.PresetListService
import io.github.qavlad.adbdevicemanager.ui.dialogs.ExportPresetListsDialog
import io.github.qavlad.adbdevicemanager.utils.ButtonUtils
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.*

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
    private val onListImported: ((PresetList) -> Unit)? = null,
    private val onClearListOrderInMemory: ((String) -> Unit)? = null,
    private val onResetOrientation: (() -> Unit)? = null,
    private val onLoadPresetsIntoTable: (() -> Unit)? = null
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
    private var isProgrammaticCheckboxChange = false
    
    init {
        setupUI()
        loadLists()
        
        // Устанавливаем правильную подсказку для кнопки Reset в зависимости от начального состояния
        if (showAllPresetsCheckbox.isSelected) {
            resetButton.toolTipText = "Reset all default preset lists to Default values"
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
            if (!isProgrammaticCheckboxChange) {
                val isSelected = event.stateChange == ItemEvent.SELECTED
                onShowAllPresetsChanged(isSelected)
            
            // Включаем/выключаем комбобокс в зависимости от состояния
            listComboBox.isEnabled = !isSelected
            
            // Обновляем подсказку кнопки Reset в зависимости от режима
            resetButton.toolTipText = if (isSelected) {
                "Reset all default preset lists to Default values"
            } else {
                "Reset presets to defaults"
            }
                renameButton.isEnabled = !isSelected
                deleteButton.isEnabled = !isSelected
            }
        }
        
        // Добавляем hover эффект как у кнопок
        setupCheckboxHoverEffect(showAllPresetsCheckbox)
        
        bottomPanel.add(showAllPresetsCheckbox)
        
        hideDuplicatesCheckbox.addItemListener { event ->
            if (!isProgrammaticCheckboxChange) {
                onHideDuplicatesChanged(event.stateChange == ItemEvent.SELECTED)
            }
        }
        
        // Добавляем hover эффект как у кнопок
        setupCheckboxHoverEffect(hideDuplicatesCheckbox)
        
        bottomPanel.add(hideDuplicatesCheckbox)
        
        showCountersCheckbox.addItemListener { event ->
            if (!isProgrammaticCheckboxChange) {
                onShowCountersChanged(event.stateChange == ItemEvent.SELECTED)
            }
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
        // Кнопка сброса активна только для дефолтных списков
        resetButton.isEnabled = list.isDefault
        resetButton.toolTipText = when {
            list.isDefault -> "Reset this default preset list to Default values"
            list.isImported -> "Reset is only available for default lists"
            else -> "Reset is only available for default lists"
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
        
        // Получаем имя активного списка и состояние "Show all"
        val activeListName = PresetListService.getActivePresetList()?.name
        val showAllSelected = showAllPresetsCheckbox.isSelected
        
        // Используем новый диалог с поддержкой навигации
        val dialog = ExportPresetListsDialog(listNames, activeListName, showAllSelected)
        
        if (dialog.showAndGet()) { // OK button clicked
            val selectedListNames = dialog.getSelectedLists()
            val selectedLists = mutableListOf<Pair<String, String>>() // ID to Name
            
            // Сопоставляем имена с ID
            selectedListNames.forEach { name ->
                metadata.find { it.name == name }?.let {
                    selectedLists.add(it.id to it.name)
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
            
            // Загружаем актуальные списки для экспорта
            val allAvailableLists = PresetListService.getAllAvailableLists()
            val listsToExport = selectedLists.mapNotNull { (listId, listName) ->
                allAvailableLists.find { it.id == listId }?.let { it to listName }
            }
            
            if (listsToExport.isEmpty()) {
                Messages.showWarningDialog(
                    this,
                    "No valid lists found to export",
                    "Export Warning"
                )
                return
            }
            
            try {
                if (listsToExport.size == 1) {
                    // Экспорт одного списка - сохраняем как имя_списка.json
                    val (list, name) = listsToExport.first()
                    val sanitizedName = name.replace(Regex("[^a-zA-Z0-9\\s_-]"), "_")
                    
                    // FileSaverDescriptor constructor is deprecated but there's no alternative in Platform 243
                    @Suppress("DEPRECATION")
                    val descriptor = FileSaverDescriptor(
                        "Export Preset List",
                        "Choose location to save preset list",
                        "json"
                    )
                    
                    val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null)
                    val virtualFile = saveDialog.save(null as VirtualFile?, "$sanitizedName.json")
                    
                    virtualFile?.let { vf ->
                        // Экспортируем как массив с одним элементом для совместимости
                        PresetListService.exportListsDirectly(listOf(list), vf.file)
                        Messages.showInfoMessage(
                            this,
                            "Successfully exported preset list: $name",
                            "Export Success"
                        )
                    }
                } else {
                    // Экспорт нескольких списков - создаем папку с отдельными файлами
                    val timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    val folderName = "plugin_export_$timestamp"
                    
                    // Выбор директории для сохранения
                    val descriptor = FileChooserDescriptor(
                        false, // chooseFiles
                        true, // chooseFolders
                        false, // chooseMultiple
                        false, // chooseJarContents
                        false, // chooseJarsAsFiles
                        false  // chooseArchives
                    ).apply {
                        title = "Choose Directory for Export"
                        description = "Select where to create export folder"
                    }
                    
                    val virtualFiles = FileChooserFactory.getInstance()
                        .createFileChooser(descriptor, null, this)
                        .choose(null)
                    
                    virtualFiles.firstOrNull()?.let { baseDir ->
                        val exportDir = File(baseDir.path, folderName)
                        if (!exportDir.exists()) {
                            exportDir.mkdirs()
                        }
                        
                        var exportedCount = 0
                        listsToExport.forEach { (list, name) ->
                            val sanitizedName = name.replace(Regex("[^a-zA-Z0-9\\s_-]"), "_")
                            val file = File(exportDir, "$sanitizedName.json")
                            // Экспортируем каждый список как массив с одним элементом
                            PresetListService.exportListsDirectly(listOf(list), file)
                            exportedCount++
                        }
                        
                        Messages.showInfoMessage(
                            this,
                            "Successfully exported $exportedCount preset list(s) to:\n${exportDir.absolutePath}",
                            "Export Success"
                        )
                    }
                }
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    this,
                    "Failed to export preset lists: ${e.message}",
                    "Export Error"
                )
            }
        }
    }
    
    private fun importLists() {
        // Создаем кастомный диалог для выбора опций импорта
        val importDialog = object : com.intellij.openapi.ui.DialogWrapper(true) {
            private lateinit var fileListModel: DefaultListModel<File>
            private lateinit var fileList: com.intellij.ui.components.JBList<File>
            private lateinit var dropPanel: JPanel
            private lateinit var dropLabel: JLabel
            private var isDragging = false
            
            init {
                title = "Import Preset Lists"
                init()
                // Добавляем hover эффекты к кнопкам диалога
                SwingUtilities.invokeLater {
                    ButtonUtils.addHoverEffectToDialogButtons(rootPane)
                }
            }
            
            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout())
                
                
                // Создаем панель для drag & drop с визуальной индикацией
                val centerPanel = JPanel(BorderLayout())
                
                // Модель и список для отображения выбранных файлов
                fileListModel = DefaultListModel()
                fileList = com.intellij.ui.components.JBList(fileListModel)
                fileList.cellRenderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean
                    ): java.awt.Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is File) {
                            text = value.name
                            icon = if (value.isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Json
                        }
                        return this
                    }
                }
                
                // Создаем панель с индикатором drag & drop
                dropPanel = JPanel(BorderLayout())
                dropPanel.background = com.intellij.util.ui.UIUtil.getPanelBackground()
                dropPanel.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createDashedBorder(
                        com.intellij.util.ui.UIUtil.getBoundsColor(), 
                        2f, 5f, 5f, false
                    ),
                    JBUI.Borders.empty(20)
                )
                
                // Создаем лейбл с иконкой и текстом
                dropLabel = JLabel().apply {
                    horizontalAlignment = SwingConstants.CENTER
                    verticalAlignment = SwingConstants.CENTER
                    icon = AllIcons.Actions.Upload
                    text = "<html><center><br>Drop JSON files or folders here<br>" +
                           "<small style='color:gray'>or use buttons below to select files</small></center></html>"
                    foreground = com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                }
                dropPanel.add(dropLabel, BorderLayout.CENTER)
                
                // Создаем scrollPane для списка файлов
                val scrollPane = com.intellij.ui.components.JBScrollPane(fileList)
                scrollPane.isVisible = false
                scrollPane.isOpaque = false
                scrollPane.viewport.isOpaque = false
                fileList.isOpaque = false
                
                // Создаем контейнер с JLayeredPane для наложения
                val contentContainer = JLayeredPane()
                contentContainer.preferredSize = Dimension(450, 250)
                contentContainer.layout = null
                
                // Устанавливаем размеры и позиции для компонентов
                dropPanel.setBounds(0, 0, 450, 250)
                scrollPane.setBounds(0, 0, 450, 250)
                
                // Добавляем компоненты в разные слои
                contentContainer.add(dropPanel, JLayeredPane.DEFAULT_LAYER)
                contentContainer.add(scrollPane, JLayeredPane.PALETTE_LAYER)
                
                // Слушатель для обновления размеров при изменении контейнера
                contentContainer.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent?) {
                        val width = contentContainer.width
                        val height = contentContainer.height
                        dropPanel.setBounds(0, 0, width, height)
                        scrollPane.setBounds(0, 0, width, height)
                    }
                })
                
                // Функция для сброса визуального состояния drag & drop
                fun resetDragVisualState() {
                    if (isDragging) {
                        isDragging = false
                        // Просто обновляем UI
                        SwingUtilities.invokeLater {
                            contentContainer.repaint()
                        }
                    }
                }
                
                // Добавляем поддержку Drag & Drop с визуальной обратной связью
                val transferHandler = object : TransferHandler() {
                    override fun canImport(support: TransferSupport): Boolean {
                        val canImport = support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                        PluginLogger.info("TransferHandler.canImport called, result: $canImport", LogCategory.DRAG_DROP)
                        return canImport
                    }
                    
                    override fun importData(support: TransferSupport): Boolean {
                        PluginLogger.info("TransferHandler.importData called", LogCategory.DRAG_DROP)
                        
                        if (!canImport(support)) {
                            PluginLogger.warn("TransferHandler.importData: canImport returned false", LogCategory.DRAG_DROP)
                            return false
                        }
                        
                        try {
                            val transferable = support.transferable
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(
                                java.awt.datatransfer.DataFlavor.javaFileListFlavor
                            ) as List<File>
                            
                            PluginLogger.info("TransferHandler.importData: received ${files.size} files", LogCategory.DRAG_DROP)
                            
                            val nonJsonFiles = mutableListOf<String>()
                            val emptyFolders = mutableListOf<String>()
                            var addedCount = 0
                            
                            files.forEach { file ->
                                when {
                                    file.isDirectory -> {
                                        val jsonFiles = file.listFiles { f -> 
                                            f.isFile && f.extension.equals("json", ignoreCase = true)
                                        }
                                        if (jsonFiles.isNullOrEmpty()) {
                                            emptyFolders.add(file.name)
                                        } else {
                                            if (!fileListModel.contains(file)) {
                                                fileListModel.addElement(file)
                                                addedCount++
                                                PluginLogger.info("Added folder to import list: ${file.path}", LogCategory.DRAG_DROP)
                                            }
                                        }
                                    }
                                    file.extension.equals("json", ignoreCase = true) -> {
                                        if (!fileListModel.contains(file)) {
                                            fileListModel.addElement(file)
                                            addedCount++
                                            PluginLogger.info("Added JSON file to import list: ${file.path}", LogCategory.DRAG_DROP)
                                        }
                                    }
                                    else -> {
                                        nonJsonFiles.add(file.name)
                                        PluginLogger.info("Skipped non-JSON file: ${file.name}", LogCategory.DRAG_DROP)
                                    }
                                }
                            }
                            
                            PluginLogger.info("TransferHandler.importData: Added $addedCount items, fileListModel size: ${fileListModel.size()}", LogCategory.DRAG_DROP)
                            
                            // Показываем список файлов и делаем dropPanel полупрозрачной
                            if (fileListModel.size() > 0) {
                                PluginLogger.info("TransferHandler.importData: Showing file list, making drop panel transparent", LogCategory.DRAG_DROP)
                                SwingUtilities.invokeLater {
                                    // Показываем scrollPane (он в верхнем слое)
                                    scrollPane.isVisible = true
                                    // Делаем dropPanel полупрозрачной (она в нижнем слое)
                                    dropPanel.isOpaque = false
                                    dropPanel.background = JBColor(java.awt.Color(0, 0, 0, 0), java.awt.Color(0, 0, 0, 0))
                                    dropLabel.foreground = JBColor(
                                        java.awt.Color(
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                        50
                                        ),
                                        java.awt.Color(
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                            50
                                        )
                                    )
                                    dropLabel.text = "<html><center><br>Drop more JSON files or folders here<br>" +
                                                   "<small style='color:gray'>or use buttons below to add files</small></center></html>"
                                    dropPanel.border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createDashedBorder(
                                            JBColor(
                                                java.awt.Color(
                                                com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                30
                                                ),
                                                java.awt.Color(
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                    30
                                                )
                                            ), 
                                            2f, 5f, 5f, false
                                        ),
                                        JBUI.Borders.empty(20)
                                    )
                                    // Меняем порядок слоев, чтобы dropPanel была видна сквозь scrollPane
                                    contentContainer.setLayer(dropPanel, JLayeredPane.PALETTE_LAYER)
                                    contentContainer.setLayer(scrollPane, JLayeredPane.DEFAULT_LAYER)
                                    contentContainer.revalidate()
                                    contentContainer.repaint()
                                    PluginLogger.info("TransferHandler.importData: UI updated - drop panel made transparent", LogCategory.DRAG_DROP)
                                }
                            } else {
                                PluginLogger.info("TransferHandler.importData: fileListModel is empty, not switching UI", LogCategory.DRAG_DROP)
                            }
                            
                            // Всегда сбрасываем визуальное состояние после обработки
                            SwingUtilities.invokeLater {
                                resetDragVisualState()
                                
                                // Показываем предупреждения
                                if (nonJsonFiles.isNotEmpty()) {
                                    PluginLogger.info("TransferHandler.importData: Showing warning for ${nonJsonFiles.size} non-JSON files", LogCategory.DRAG_DROP)
                                    Messages.showWarningDialog(
                                        contentPanel,
                                        "The following files were skipped (only JSON files are accepted):\n" +
                                        nonJsonFiles.joinToString("\n") { "• $it" },
                                        "Non-JSON Files Detected"
                                    )
                                }
                                
                                if (emptyFolders.isNotEmpty()) {
                                    PluginLogger.info("TransferHandler.importData: Showing warning for ${emptyFolders.size} empty folders", LogCategory.DRAG_DROP)
                                    Messages.showWarningDialog(
                                        contentPanel,
                                        "The following folders contain no JSON files:\n" +
                                        emptyFolders.joinToString("\n") { "• $it" },
                                        "Empty Folders Detected"
                                    )
                                }
                            }
                            
                            val result = addedCount > 0 || nonJsonFiles.isNotEmpty() || emptyFolders.isNotEmpty()
                            PluginLogger.info("TransferHandler.importData: returning $result", LogCategory.DRAG_DROP)
                            return result
                        } catch (e: Exception) {
                            PluginLogger.error("TransferHandler.importData: Exception occurred: ${e.message}", e, LogCategory.DRAG_DROP)
                            return false
                        }
                    }
                }
                
                // Устанавливаем TransferHandler для всех компонентов
                contentContainer.transferHandler = transferHandler
                dropPanel.transferHandler = transferHandler
                fileList.transferHandler = transferHandler
                scrollPane.transferHandler = transferHandler
                centerPanel.transferHandler = transferHandler
                panel.transferHandler = transferHandler
                
                // Комментируем DropTarget - он конфликтует с TransferHandler
                // Вместо этого будем использовать только TransferHandler для всего
                
                centerPanel.add(contentContainer, BorderLayout.CENTER)
                panel.add(centerPanel, BorderLayout.CENTER)
                
                // Панель с кнопками
                val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
                
                val addFilesButton = JButton("Add Files").apply {
                    ButtonUtils.addHoverEffect(this)
                    addActionListener {
                        val descriptor = FileChooserDescriptor(
                            true, false, false, false, false, true
                        ).apply {
                            withFileFilter { it.extension.equals("json", ignoreCase = true) }
                        }
                        
                        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(
                            descriptor, null, this@PresetListManagerPanel, null
                        ) { files ->
                            var added = false
                            files.forEach { vf ->
                                val file = File(vf.path)
                                if (!fileListModel.contains(file)) {
                                    fileListModel.addElement(file)
                                    added = true
                                }
                            }
                            // Показываем список файлов и делаем dropPanel полупрозрачной
                            if (added && fileListModel.size() > 0) {
                                scrollPane.isVisible = true
                                dropPanel.isOpaque = false
                                dropPanel.background = JBColor(java.awt.Color(0, 0, 0, 0), java.awt.Color(0, 0, 0, 0))
                                dropLabel.foreground = JBColor(
                                    java.awt.Color(
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                        50
                                    ),
                                    java.awt.Color(
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                        50
                                    )
                                )
                                dropLabel.text = "<html><center><br>Drop more JSON files or folders here<br>" +
                                               "<small style='color:gray'>or use buttons below to add files</small></center></html>"
                                dropPanel.border = BorderFactory.createCompoundBorder(
                                    BorderFactory.createDashedBorder(
                                        JBColor(
                                            java.awt.Color(
                                                com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                30
                                            ),
                                            java.awt.Color(
                                                com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                30
                                            )
                                        ), 
                                        2f, 5f, 5f, false
                                    ),
                                    JBUI.Borders.empty(20)
                                )
                                // Меняем порядок слоев
                                contentContainer.setLayer(dropPanel, JLayeredPane.PALETTE_LAYER)
                                contentContainer.setLayer(scrollPane, JLayeredPane.DEFAULT_LAYER)
                                contentContainer.revalidate()
                                contentContainer.repaint()
                            }
                        }
                    }
                }
                
                val addFolderButton = JButton("Add Folder").apply {
                    ButtonUtils.addHoverEffect(this)
                    addActionListener {
                        val descriptor = FileChooserDescriptor(
                            false, true, false, false, false, false
                        )
                        
                        val virtualFiles = FileChooserFactory.getInstance()
                            .createFileChooser(descriptor, null, this@PresetListManagerPanel)
                            .choose(null)
                        
                        virtualFiles.firstOrNull()?.let { vf ->
                            val folder = File(vf.path)
                            // Проверяем, содержит ли папка JSON файлы
                            val jsonFiles = folder.listFiles { f -> 
                                f.isFile && f.extension.equals("json", ignoreCase = true)
                            }
                            if (jsonFiles.isNullOrEmpty()) {
                                Messages.showWarningDialog(
                                    contentPanel,
                                    "The selected folder '${folder.name}' contains no JSON files",
                                    "Empty Folder"
                                )
                            } else {
                                if (!fileListModel.contains(folder)) {
                                    fileListModel.addElement(folder)
                                    // Показываем список файлов и делаем dropPanel полупрозрачной
                                    scrollPane.isVisible = true
                                    dropPanel.isOpaque = false
                                    dropPanel.background = JBColor(java.awt.Color(0, 0, 0, 0), java.awt.Color(0, 0, 0, 0))
                                    dropLabel.foreground = JBColor(
                                        java.awt.Color(
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                        com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                        50
                                        ),
                                        java.awt.Color(
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().red,
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().green,
                                            com.intellij.util.ui.UIUtil.getLabelDisabledForeground().blue,
                                            50
                                        )
                                    )
                                    dropLabel.text = "<html><center><br>Drop more JSON files or folders here<br>" +
                                                   "<small style='color:gray'>or use buttons below to add files</small></center></html>"
                                    dropPanel.border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createDashedBorder(
                                            JBColor(
                                                java.awt.Color(
                                                com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                30
                                                ),
                                                java.awt.Color(
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().red,
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().green,
                                                    com.intellij.util.ui.UIUtil.getBoundsColor().blue,
                                                    30
                                                )
                                            ), 
                                            2f, 5f, 5f, false
                                        ),
                                        JBUI.Borders.empty(20)
                                    )
                                    // Меняем порядок слоев
                                    contentContainer.setLayer(dropPanel, JLayeredPane.PALETTE_LAYER)
                                    contentContainer.setLayer(scrollPane, JLayeredPane.DEFAULT_LAYER)
                                    contentContainer.revalidate()
                                    contentContainer.repaint()
                                }
                            }
                        }
                    }
                }
                
                // Функция для удаления выбранных файлов
                fun removeSelectedFiles() {
                    fileList.selectedValuesList.forEach { file ->
                        fileListModel.removeElement(file)
                    }
                    // Если список пуст, восстанавливаем непрозрачность dropPanel
                    if (fileListModel.size() == 0) {
                        scrollPane.isVisible = false
                        dropPanel.isOpaque = true
                        dropPanel.background = com.intellij.util.ui.UIUtil.getPanelBackground()
                        dropLabel.foreground = com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                        dropLabel.text = "<html><center><br>Drop JSON files or folders here<br>" +
                                       "<small style='color:gray'>or use buttons below to select files</small></center></html>"
                        dropPanel.border = BorderFactory.createCompoundBorder(
                            BorderFactory.createDashedBorder(
                                com.intellij.util.ui.UIUtil.getBoundsColor(), 
                                2f, 5f, 5f, false
                            ),
                            JBUI.Borders.empty(20)
                        )
                        // Восстанавливаем слои
                        contentContainer.setLayer(dropPanel, JLayeredPane.DEFAULT_LAYER)
                        contentContainer.setLayer(scrollPane, JLayeredPane.PALETTE_LAYER)
                    }
                }
                
                val removeButton = JButton("Remove").apply {
                    ButtonUtils.addHoverEffect(this)
                    addActionListener {
                        removeSelectedFiles()
                    }
                }
                
                // Добавляем обработку клавиши Delete для списка файлов
                fileList.addKeyListener(object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        if (e.keyCode == java.awt.event.KeyEvent.VK_DELETE) {
                            removeSelectedFiles()
                        }
                    }
                })
                
                buttonPanel.add(addFilesButton)
                buttonPanel.add(addFolderButton)
                buttonPanel.add(removeButton)
                panel.add(buttonPanel, BorderLayout.SOUTH)
                
                return panel
            }
            
            fun getSelectedFiles(): List<File> {
                val result = mutableListOf<File>()
                for (i in 0 until fileListModel.size()) {
                    result.add(fileListModel.getElementAt(i))
                }
                return result
            }
        }
        
        if (importDialog.showAndGet()) {
            val selectedItems = importDialog.getSelectedFiles()
            PluginLogger.info("Import dialog OK clicked, selected items: ${selectedItems.size}", LogCategory.DRAG_DROP)
            selectedItems.forEach { 
                PluginLogger.info("  - ${it.path} (isDirectory=${it.isDirectory})", LogCategory.DRAG_DROP)
            }
            if (selectedItems.isEmpty()) {
                PluginLogger.info("No items selected, returning", LogCategory.DRAG_DROP)
                return
            }
            
            val filesToImport = mutableListOf<File>()
            
            // Обрабатываем все выбранные файлы и папки
            selectedItems.forEach { item ->
                if (item.isDirectory) {
                    // Если выбрана папка, ищем все JSON файлы в ней
                    item.listFiles { f -> 
                        f.isFile && f.extension.equals("json", ignoreCase = true)
                    }?.forEach { filesToImport.add(it) }
                } else if (item.extension.equals("json", ignoreCase = true)) {
                    // Если выбран файл JSON
                    filesToImport.add(item)
                }
            }
            
            if (filesToImport.isEmpty()) {
                Messages.showWarningDialog(
                    this@PresetListManagerPanel,
                    "No JSON files found in the selected location(s)",
                    "Import Warning"
                )
                return
            }
            
            var totalImported = 0
            val errors = mutableListOf<String>()
            
            PluginLogger.info("Processing ${filesToImport.size} files for import", LogCategory.DRAG_DROP)
            filesToImport.forEach { file ->
                try {
                    PluginLogger.info("Importing from file: ${file.path}", LogCategory.DRAG_DROP)
                    val importedLists = PresetListService.importLists(file)
                    PluginLogger.info("Imported ${importedLists.size} lists from ${file.name}", LogCategory.DRAG_DROP)
                    
                    // Добавляем импортированные списки в tempListsManager
                    importedLists.forEach { list ->
                        PluginLogger.info("Adding imported list '${list.name}' with ${list.presets.size} presets to tempListsManager", LogCategory.DRAG_DROP)
                        println("ADB_DEBUG: Before onListImported - list '${list.name}' with ${list.presets.size} presets")
                        onListImported?.invoke(list)
                        println("ADB_DEBUG: After onListImported for list '${list.name}'")
                    }
                    
                    totalImported += importedLists.size
                } catch (e: Exception) {
                    PluginLogger.error("Error importing from ${file.name}: ${e.message}", e, LogCategory.DRAG_DROP)
                    errors.add("${file.name}: ${e.message}")
                }
            }
            
            // Обновляем список после импорта
            if (totalImported > 0) {
                PluginLogger.info("Reloading lists after import (imported $totalImported lists)", LogCategory.DRAG_DROP)
                loadLists()
                
                // Если мы в режиме Show all, нужно обновить таблицу, чтобы показать импортированные списки
                if (showAllPresetsCheckbox.isSelected) {
                    PluginLogger.info("In Show all mode, reloading table to show imported presets", LogCategory.DRAG_DROP)
                    println("ADB_DEBUG: Show all mode is active, calling onLoadPresetsIntoTable to refresh table with imported lists")
                    println("ADB_DEBUG: Before onLoadPresetsIntoTable - imported $totalImported lists")
                    onLoadPresetsIntoTable?.invoke()
                    println("ADB_DEBUG: After onLoadPresetsIntoTable")
                } else {
                    println("ADB_DEBUG: Not in Show all mode, skipping table refresh after import")
                }
            } else {
                PluginLogger.info("No lists imported, not reloading", LogCategory.DRAG_DROP)
            }
            
            // Показываем результат
            if (errors.isEmpty()) {
                Messages.showInfoMessage(
                    this@PresetListManagerPanel,
                    "Successfully imported $totalImported preset list(s) from ${filesToImport.size} file(s)",
                    "Import Success"
                )
            } else {
                val message = buildString {
                    appendLine("Imported $totalImported preset list(s)")
                    if (errors.isNotEmpty()) {
                        appendLine("\nErrors:")
                        errors.forEach { appendLine("  - $it") }
                    }
                }
                if (totalImported > 0) {
                    Messages.showWarningDialog(this@PresetListManagerPanel, message, "Import Completed with Warnings")
                } else {
                    Messages.showErrorDialog(this@PresetListManagerPanel, message, "Import Failed")
                }
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
            isProgrammaticCheckboxChange = true
            showAllPresetsCheckbox.isSelected = enabled
            // Обновляем состояние UI элементов
            listComboBox.isEnabled = !enabled
            resetButton.toolTipText = if (enabled) {
                "Reset all default preset lists to Default values"
            } else {
                "Reset presets to defaults"
            }
            renameButton.isEnabled = !enabled
            deleteButton.isEnabled = !enabled
            isProgrammaticCheckboxChange = false
        }
    }
    
    /**
     * Устанавливает состояние чекбокса "Hide duplicates"
     */
    fun setHideDuplicates(enabled: Boolean) {
        if (hideDuplicatesCheckbox.isSelected != enabled) {
            isProgrammaticCheckboxChange = true
            hideDuplicatesCheckbox.isSelected = enabled
            isProgrammaticCheckboxChange = false
        }
    }
    
    /**
     * Устанавливает состояние чекбокса "Show usage counters"
     */
    fun setShowCounters(enabled: Boolean) {
        if (showCountersCheckbox.isSelected != enabled) {
            isProgrammaticCheckboxChange = true
            showCountersCheckbox.isSelected = enabled
            isProgrammaticCheckboxChange = false
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
                "Imported and custom lists will be preserved.\n" +
                "Any custom presets added to default lists will be removed.\n\n" +
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
        
        // Проверяем, что это дефолтный список
        if (!list.isDefault) {
            Messages.showWarningDialog(
                this,
                "Reset is only available for default preset lists.",
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
            "• Reset All Default Lists - Reset all default lists to Default values (custom lists preserved)",
            "Reset Presets",
            options,
            0,
            AllIcons.General.Reset
        )
        
        when (choice) {
            0 -> { // Reset Current List
                // Для дефолтного списка загружаем из ресурсов
                resetDefaultList(selectedItem.id, selectedItem.name)
            }
            1 -> { // Reset All Default Lists
                val confirm = Messages.showYesNoDialog(
                    this,
                    "This will reset ALL default preset lists to their original values.\n" +
                    "Imported and custom lists will be preserved.\n\n" +
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
            
            // Обновляем метаданные, чтобы включить восстановленные дефолтные списки
            // И СОХРАНИТЬ импортированные и кастомные списки
            if (resetCount > 0) {
                val existingMetadata = PresetListService.getAllListsMetadata()
                val restoredIds = allDefaultLists.filter { it.isDefault }.map { it.id }.toSet()
                
                // Создаём новые метаданные, объединяя восстановленные дефолтные и существующие кастомные/импортированные
                val newMetadata = mutableListOf<PresetListService.ListMetadata>()
                
                // Добавляем все восстановленные дефолтные списки
                allDefaultLists.filter { it.isDefault }.forEach { list ->
                    newMetadata.add(PresetListService.ListMetadata(list.id, list.name, true))
                }
                
                // Сохраняем существующие кастомные и импортированные списки (не дефолтные)
                existingMetadata.filter { !it.isDefault && !restoredIds.contains(it.id) }.forEach { meta ->
                    newMetadata.add(meta)
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Preserved non-default list during reset: %s", meta.name)
                }
                
                PresetListService.saveListsMetadata(newMetadata)
            }
            
            if (resetCount > 0) {
                // Сбрасываем ориентацию в вертикальную после сброса всех списков
                onResetOrientation?.invoke()
                
                Messages.showInfoMessage(
                    this,
                    "$resetCount default preset list(s) have been reset to Default values:\n\n${resetDetails.joinToString("\n")}",
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
