package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.handlers.DialogNavigationHandler
import io.github.qavlad.adbrandomizer.ui.services.TableDataSynchronizer
import io.github.qavlad.adbrandomizer.ui.services.SelectionTracker
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService
import io.github.qavlad.adbrandomizer.ui.services.PresetRecycleBin
import io.github.qavlad.adbrandomizer.ui.services.PresetsDialogServiceLocator
import io.github.qavlad.adbrandomizer.ui.services.ControllerStateManager
import io.github.qavlad.adbrandomizer.ui.services.TableColumnManager
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import io.github.qavlad.adbrandomizer.ui.components.TableWithAddButtonPanel
import io.github.qavlad.adbrandomizer.ui.commands.CommandContext
import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.ui.components.OrientationPanel
import io.github.qavlad.adbrandomizer.ui.components.Orientation

/**
 * Контроллер для диалога настроек.
 * Управляет всей логикой, обработкой событий и состоянием.
 */
class PresetsDialogController(
    project: Project?,
    private val dialog: PresetsDialog,
    getSelectedDevices: (() -> List<com.android.ddmlib.IDevice>)? = null,
    onPresetApplied: ((preset: DevicePreset, listName: String?, setSize: Boolean, setDpi: Boolean) -> Unit)? = null
) : CommandContext {
    // Service Locator и State Manager
    internal val serviceLocator = PresetsDialogServiceLocator(project, getSelectedDevices, onPresetApplied)
    internal val stateManager = ControllerStateManager()
    
    init {
        // Инициализируем historyManager с текущим контроллером
        serviceLocator.initializeHistoryManager(this)
    }
    
    // UI компоненты
    override lateinit var table: JBTable
        private set
    override lateinit var tableModel: DevicePresetTableModel
        internal set
    lateinit var keyboardHandler: KeyboardHandler
        private set
    lateinit var dialogNavigationHandler: DialogNavigationHandler
        private set
    lateinit var tableConfigurator: TableConfigurator
        private set
    lateinit var validationRenderer: ValidationRenderer
        private set
    lateinit var listManagerPanel: PresetListManagerPanel
        private set
    private var tableWithButtonPanel: TableWithAddButtonPanel? = null
    internal var orientationPanel: OrientationPanel? = null

    // Делегаты для удобства доступа
    private val dialogState get() = serviceLocator.dialogState
    private val eventHandlersInitializer = EventHandlersInitializer(this)
    
    override val historyManager: CommandHistoryManager get() = serviceLocator.historyManager
    override fun getPresetRecycleBin(): PresetRecycleBin = serviceLocator.presetRecycleBin

    /**
     * Инициализация контроллера
     */
    fun initialize(orientationPanel: OrientationPanel?) {
        this.orientationPanel = orientationPanel
        serviceLocator.componentInitService.performInitialSetup()
        
        // Загружаем сохранённую ориентацию
        orientationPanel?.let { panel ->
            val savedOrientation = PresetStorageService.getOrientation()
            println("ADB_DEBUG: PresetsDialogController.initialize - savedOrientation: $savedOrientation")
            try {
                val orientation = Orientation.valueOf(savedOrientation)
                // Устанавливаем визуальное состояние панели
                panel.setOrientation(orientation, applyToTable = false)
                PluginLogger.debug(LogCategory.UI_EVENTS, "Loaded saved orientation: $orientation")
                println("ADB_DEBUG: Set orientation panel to $orientation")
                
                // Данные уже будут конвертированы в initializeTempPresetLists если нужно
            } catch (e: IllegalArgumentException) {
                // Если сохранённое значение некорректно, используем PORTRAIT по умолчанию
                panel.setOrientation(Orientation.PORTRAIT, applyToTable = false)
                PluginLogger.error(LogCategory.UI_EVENTS, "Invalid saved orientation: $savedOrientation", e)
            }
            
            // ВАЖНО: НЕ устанавливаем слушатель здесь, так как таблица еще не готова
            // Слушатель будет установлен позже в setupOrientationListener()
        }
    }
    
    /**
     * Устанавливает слушатель изменения ориентации после полной инициализации
     */
    fun setupOrientationListener() {
        orientationPanel?.let { panel ->
            println("ADB_DEBUG: Setting up orientation change listener")
            panel.setOrientationChangeListener { newOrientation ->
                println("ADB_DEBUG: Orientation changed to: $newOrientation")
                
                // Применяем новую ориентацию ко всем спискам в памяти
                applyOrientationToAllTempLists(newOrientation)
                
                // Синхронизируем изменения из таблицы в tempLists
                syncTableChangesToTempListsInternal()
                
                // Если мы в режиме Show All, нужно обновить таблицу
                if (dialogState.isShowAllPresetsMode()) {
                    loadPresetsIntoTable()
                }
            }
            // Активируем слушатель после его установки
            panel.activateListener()
        }
    }

    /**
     * Применяет ориентацию ко всем временным спискам
     */
    private fun applyOrientationToAllTempLists(orientation: Orientation) {
        println("ADB_DEBUG: applyOrientationToAllTempLists called with orientation: $orientation")
        val isLandscape = orientation == Orientation.LANDSCAPE
        
        serviceLocator.tempListsManager.getTempLists().values.forEach { list ->
            println("ADB_DEBUG:   Applying $orientation to list: ${list.name}")
            list.presets.forEach { preset ->
                val parts = preset.size.split("x")
                if (parts.size == 2) {
                    val width = parts[0].toIntOrNull()
                    val height = parts[1].toIntOrNull()
                    if (width != null && height != null) {
                        val isCurrentlyPortrait = height > width
                        
                        // Если нужна LANDSCAPE и сейчас PORTRAIT, или нужна PORTRAIT и сейчас LANDSCAPE
                        if ((isLandscape && isCurrentlyPortrait) || (!isLandscape && !isCurrentlyPortrait)) {
                            val oldSize = preset.size
                            preset.size = "${height}x${width}"
                            println("ADB_DEBUG:     Converted ${preset.label}: $oldSize -> ${preset.size}")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Создает панель управления списками пресетов
     */
    fun createListManagerPanel(): PresetListManagerPanel {
        listManagerPanel = eventHandlersInitializer.createAndInitializeListManagerPanel(
            dialogState = dialogState,
            duplicateManager = serviceLocator.duplicateManager,
            tempListsManager = serviceLocator.tempListsManager,
            tableWithButtonPanel = tableWithButtonPanel,
            onCurrentListChanged = { newList -> stateManager.currentPresetList = newList },
            onLoadPresetsIntoTable = { loadPresetsIntoTable() },
            onSyncTableChanges = { syncTableChangesToTempListsInternal() },
            onSetupTableColumns = { setupTableColumns() },
            onResetSorting = { handleResetSorting() }
        )

        return listManagerPanel
    }

    /**
     * Создает модель таблицы с начальными данными
     */
    fun createTableModel(): DevicePresetTableModel {
        tableModel = serviceLocator.componentInitService.createTableModel(historyManager)
        // НЕ добавляем слушатель здесь, так как table еще не создана

        // НЕ загружаем список здесь, так как временные списки еще не созданы
        // currentPresetList будет установлен в initializeTempPresetLists

        return tableModel
    }

    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(model: DevicePresetTableModel): JBTable {
        val tableInitResult = serviceLocator.componentInitService.createAndInitializeTable(
            model = model,
            historyManager = historyManager,
            getHoverState = { stateManager.hoverState },
            onHoverStateChanged = { newState -> stateManager.updateHoverState(newState) }
        )
        
        table = tableInitResult.table
        
        // Временно сохраняем только hoverStateManager и tableEventHandler
        // tableColumnManager будет создан и установлен позже в initializeHandlers
        val tempTableConfigurator = TableConfigurator(
            table, 
            { stateManager.hoverState }, 
            { }, 
            { _, _ -> }, 
            { _, _, _ -> }, 
            { }, 
            ValidationRenderer({ stateManager.hoverState }, { DevicePreset("", "", "") }, { emptyMap() }, serviceLocator.validationService), 
            { }, 
            { false }, 
            { }, 
            { }, 
            { }, 
            { 0 }
        )
        
        // Сохраняем UI компоненты в ServiceLocator (tableColumnManager будет установлен позже в initializeHandlers)
        serviceLocator.setUIComponents(
            hoverStateManager = tableInitResult.hoverStateManager,
            tableEventHandler = tableInitResult.tableEventHandler,
            tableColumnManager = TableColumnManager(
                tempTableConfigurator,
                { false },
                { false },
                { false }
            )
        )
        
        // Инициализируем ExtendedTableLoader
        serviceLocator.initializeExtendedTableLoader(tableInitResult.hoverStateManager)
        
        return table
    }

    /**
     * Инициализирует обработчики и конфигураторы
     */
    fun initializeHandlers() {
        println("ADB_DEBUG: initializeHandlers called")

        // Очищаем SelectionTracker при инициализации нового диалога
        SelectionTracker.clearSelection()
        
        // ВАЖНО: инициализируем временные списки ДО загрузки таблицы
        initializeTempPresetLists()
        // Гарантируем, что currentPresetList валиден
        if (stateManager.currentPresetList == null && serviceLocator.tempListsManager.isNotEmpty()) {
            stateManager.currentPresetList = serviceLocator.tempListsManager.getTempLists().values.first()
        }

        // Инициализируем обработчики через сервис
        val handlersResult = serviceLocator.componentInitService.initializeHandlers(
            table = table,
            tableModel = tableModel,
            hoverStateManager = serviceLocator.hoverStateManager,
            historyManager = historyManager,
            getHoverState = { stateManager.hoverState },
            getPresetAtRow = ::getPresetAtRow,
            onValidateFields = { validateFields() },
            onCellClicked = ::handleCellClick,
            onRowMoved = ::onRowMoved,
            onShowContextMenu = ::showContextMenu,
            onDuplicate = ::duplicatePreset,
            onDeleteFromEditor = ::deletePresetFromEditor,
            onSyncTableChanges = { syncTableChangesToTempListsInternal() },
            onDragStarted = { 
                dialogState.startDragAndDrop()
            },
            onDragEnded = { 
                println("ADB_DEBUG: Drag and drop ended, dialogState.isDragAndDropInProgress() remains true")
                // В режиме скрытия дубликатов сохраняем полное состояние всех списков
                val orderAfter = if (dialogState.isHideDuplicatesMode()) {
                    getAllPresetsOrder()
                } else {
                    tableModel.getPresets().map { it.label }
                }
                historyManager.updateLastMoveCommandOrderAfter(orderAfter)
                
                // Состояние после операции будет сохранено после синхронизации в syncTableChangesToTempLists
                
                // Не сбрасываем флаг здесь - он будет сброшен после обработки всех обновлений
            },
            onForceSyncBeforeHistory = ::forceSyncBeforeHistoryOperation
        )
        
        // Сохраняем созданные компоненты
        keyboardHandler = handlersResult.keyboardHandler
        dialogNavigationHandler = handlersResult.dialogNavigationHandler
        tableConfigurator = handlersResult.tableConfigurator
        validationRenderer = handlersResult.validationRenderer
        handlersResult.tableColumnManager.let { 
            serviceLocator.setUIComponents(
                serviceLocator.hoverStateManager,
                serviceLocator.tableEventHandler,
                it
            )
        }
        stateManager.tableModelListenerWithTimer = handlersResult.tableModelListenerWithTimer
        
        // Сохраняем ссылку на слушатель
        stateManager.tableModelListener = stateManager.tableModelListenerWithTimer?.listener
        
        // Добавляем слушатель к модели таблицы
        tableModel.addTableModelListener(stateManager.tableModelListener)

        println("ADB_DEBUG: After tableConfigurator.configure() - currentPresetList: ${stateManager.currentPresetList?.name}, presets: ${stateManager.currentPresetList?.presets?.size}")

        // Настраиваем колонки таблицы, включая заголовки для сортировки
        setupTableColumns()
        
        // Устанавливаем функцию обновления HoverState для SelectionTracker
        serviceLocator.hoverStateManager.setupSelectionTracker()
        
        // Загружаем данные после полной инициализации
        println("ADB_DEBUG: Before loadPresetsIntoTable in initializeHandlers - currentPresetList: ${stateManager.currentPresetList?.name}, presets: ${stateManager.currentPresetList?.presets?.size}")
        loadPresetsIntoTable()
        
        // Если мы в режиме Show All с Hide Duplicates при первой загрузке,
        // нужно сохранить полный порядок всех пресетов
        if (dialogState.isShowAllPresetsMode() && dialogState.isHideDuplicatesMode() && dialogState.isFirstLoad()) {
            println("ADB_DEBUG: First load in Show All with Hide Duplicates - saving full order")
            val existingOrder = PresetListService.getShowAllPresetsOrder()
            if (existingOrder.isEmpty()) {
                // Создаём и сохраняем полный порядок всех пресетов
                val fullOrder = mutableListOf<Pair<String, DevicePreset>>()
                serviceLocator.tempListsManager.getTempLists().forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        fullOrder.add(list.name to preset)
                    }
                }
                serviceLocator.presetOrderManager.saveShowAllModeOrder(fullOrder)
                println("ADB_DEBUG: Saved initial full order with ${fullOrder.size} presets")
            }
        }

        // Устанавливаем состояния чекбоксов после полной инициализации
        println("ADB_DEBUG: Setting checkbox states - showAll: ${dialogState.isShowAllPresetsMode()}, hideDuplicates: ${dialogState.isHideDuplicatesMode()}")
        listManagerPanel.setShowAllPresets(dialogState.isShowAllPresetsMode())
        listManagerPanel.setHideDuplicates(dialogState.isHideDuplicatesMode())
        listManagerPanel.setShowCounters(PresetStorageService.getShowCounters())

        // Сбрасываем флаг первой загрузки после небольшой задержки чтобы таблица успела полностью загрузиться
        SwingUtilities.invokeLater {
            dialogState.completeFirstLoad()
        }

        table.addKeyListener(keyboardHandler.createTableKeyListener())
        keyboardHandler.addGlobalKeyListener()
        
        // Устанавливаем обработчик навигации по диалогу
        dialogNavigationHandler.install()
        
        // Добавляем обработчик кликов по заголовкам таблицы
        serviceLocator.componentInitService.setupTableHeaderClickListener(table, ::handleHeaderClick)
        
        // Настраиваем слушатель обновлений счётчиков
        serviceLocator.countersStateManager.setupUpdateListener(table, tableModel) {
            loadPresetsIntoTable()
        }
        
        validateFields()
    }

    /**
     * Настройка колонок таблицы в зависимости от режима
     */
    private fun setupTableColumns() {
        serviceLocator.tableSortingController.setupTableColumns(table, tableModel)
    }
    
    /**
     * Обрабатывает клик по заголовку колонки
     */
    private fun handleHeaderClick(columnIndex: Int) {
        serviceLocator.tableSortingController.handleHeaderClick(
            columnIndex = columnIndex,
            table = table,
            onApplySorting = { loadPresetsIntoTable() }
        )
    }
    
    /**
     * Обрабатывает сброс сортировки
     */
    private fun handleResetSorting() {
        serviceLocator.tableSortingController.handleResetSorting(
            table = table,
            onLoadPresetsIntoTable = { loadPresetsIntoTable() }
        )
    }
    
    /**
     * Синхронизирует состояние сортировки при переключении режима Hide Duplicates
     */
    fun syncSortStateForHideDuplicatesToggle(hideDuplicates: Boolean) {
        serviceLocator.tableSortingController.syncSortStateForHideDuplicatesToggle(hideDuplicates)
    }

    /**
     * Устанавливает фокус на таблицу
     */
    fun setFocusToTable() {
        table.requestFocusInWindow()
        println("ADB_DEBUG: Focus set to table")
    }

    /**
     * Устанавливает ссылку на панель с таблицей и кнопкой
     */
    fun setTablePanelReference(panel: TableWithAddButtonPanel) {
        tableWithButtonPanel = panel
    }

    // === Загрузка данных в таблицу ===
    /**
     * Сохраняет текущее состояние таблицы во временные списки
     */
    private fun saveCurrentTableState() {
        serviceLocator.tableSynchronizer.saveCurrentTableState(
            tableModel = tableModel,
            currentPresetList = stateManager.currentPresetList,
            tempListsManager = serviceLocator.tempListsManager,
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            getListNameAtRow = ::getListNameAtRow,
            saveVisiblePresetsSnapshotForAllLists = ::saveVisiblePresetsSnapshotForAllLists
        )
    }

    /**
     * Принудительная синхронизация перед операциями истории
     */
    fun forceSyncBeforeHistoryOperation() {
        stateManager.tableModelListenerWithTimer?.forceSyncPendingUpdates()
    }

    /**
     * Синхронизирует изменения из таблицы с временными списками
     */
    private fun syncTableChangesToTempListsInternal() {
        val context = TableDataSynchronizer.SyncContext(
            isTableUpdating = dialogState.isTableUpdating(),
            isSwitchingMode = dialogState.isSwitchingMode(),
            isSwitchingList = dialogState.isSwitchingList(),
            isSwitchingDuplicatesFilter = dialogState.isSwitchingDuplicatesFilter(),
            isPerformingHistoryOperation = dialogState.isPerformingHistoryOperation(),
            isFirstLoad = dialogState.isFirstLoad(),
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isDragAndDropInProgress = dialogState.isDragAndDropInProgress()
        )
        
        serviceLocator.tableSynchronizer.syncTableChangesToTempLists(
            tableModel = tableModel,
            tempListsManager = serviceLocator.tempListsManager,
            currentPresetList = stateManager.currentPresetList,
            context = context,
            getListNameAtRow = ::getListNameAtRow,
            saveVisiblePresetsSnapshotForAllLists = ::saveVisiblePresetsSnapshotForAllLists,
            onReloadRequired = { reloadTableWithoutListeners() },
            historyManager = historyManager,
            dialogState = dialogState
        )
        
        // Обновляем снимок видимых пресетов после синхронизации
        if (dialogState.isHideDuplicatesMode()) {
            saveVisiblePresetsSnapshot()
        }
        
        // Не сохраняем порядок после синхронизации - используем сохранённый при инициализации
        if (!context.isSwitchingMode && !context.isSwitchingList && !dialogState.isShowAllPresetsMode() && !context.isPerformingHistoryOperation) {
            // Но если мы делаем дублирование, нужно обновить порядок в памяти
            if (stateManager.isDuplicatingPreset && stateManager.currentPresetList != null) {
                val tablePresets = tableModel.getPresets()
                serviceLocator.presetOrderManager.updateNormalModeOrderInMemory(stateManager.currentPresetList!!.id, tablePresets)
                stateManager.markNormalModeOrderChanged()
                stateManager.addModifiedListId(stateManager.currentPresetList!!.id)
                println("ADB_DEBUG: Updated normal mode order after sync during duplication for list '${stateManager.currentPresetList!!.name}' with ${tablePresets.size} presets")
            } else {
                println("ADB_DEBUG: Not saving order after sync - using initial order")
            }
        } else if (context.isPerformingHistoryOperation) {
            println("ADB_DEBUG: Not saving order during history operation - preserving original order")
        }
    }


    /**
     * Инициализирует временные копии всех списков для работы в памяти
     */
    private fun initializeTempPresetLists() {
        val initResult = serviceLocator.presetListInitializationService.initializeTempPresetLists()
        
        // Применяем результаты инициализации
        stateManager.saveOriginalState(initResult.originalPresetLists)
        stateManager.currentPresetList = initResult.currentPresetList
        if (initResult.normalModeOrderChanged) {
            stateManager.markNormalModeOrderChanged()
        }
        stateManager.modifiedListIds.clear()
        stateManager.modifiedListIds.addAll(initResult.modifiedListIds)
        
        // Применяем сохраненную ориентацию ко всем загруженным спискам
        // Файлы всегда хранятся в портретной ориентации, применяем текущую при загрузке
        val savedOrientation = PresetStorageService.getOrientation()
        println("ADB_DEBUG: initializeTempPresetLists - savedOrientation: $savedOrientation")
        if (savedOrientation == "LANDSCAPE") {
            PluginLogger.debug(LogCategory.UI_EVENTS, "Applying saved LANDSCAPE orientation to all loaded lists")
            println("ADB_DEBUG: Applying LANDSCAPE orientation to all loaded lists after initialization")
            var convertedCount = 0
            serviceLocator.tempListsManager.getTempLists().values.forEach { list ->
                println("ADB_DEBUG:   Processing list for orientation: ${list.name}")
                list.presets.forEach { preset ->
                    val parts = preset.size.split("x")
                    if (parts.size == 2) {
                        val width = parts[0].toIntOrNull()
                        val height = parts[1].toIntOrNull()
                        if (width != null && height != null) {
                            // Конвертируем в LANDSCAPE независимо от текущей ориентации
                            // Если уже в LANDSCAPE (width > height), оставляем как есть
                            // Если в PORTRAIT (height > width), меняем местами
                            if (height > width) {
                                val oldSize = preset.size
                                preset.size = "${height}x${width}"
                                println("ADB_DEBUG:     Converted ${preset.label}: $oldSize -> ${preset.size}")
                                convertedCount++
                            } else {
                                println("ADB_DEBUG:     Already in landscape: ${preset.label}: ${preset.size}")
                            }
                        }
                    }
                }
            }
            println("ADB_DEBUG: Total converted to landscape: $convertedCount presets")
        } else {
            println("ADB_DEBUG: Orientation is PORTRAIT, no conversion needed")
        }
        
        // Обновляем комбобокс если панель уже создана
        if (this::listManagerPanel.isInitialized) {
            println("ADB_DEBUG: Reloading lists in combobox after file system check")
            listManagerPanel.loadLists()
            stateManager.currentPresetList?.let {
                listManagerPanel.selectListByName(it.name)
            }
        }
    }

    /**
     * Загружает пресеты в таблицу в зависимости от текущего режима
     */
    private fun loadPresetsIntoTable() {
        try {
            // println("ADB_DEBUG: loadPresetsIntoTable() - isShowAllPresetsMode: ${dialogState.isShowAllPresetsMode()}")
            // println("ADB_DEBUG: loadPresetsIntoTable() - tempLists size: ${serviceLocator.tempListsManager.getTempLists().size}")
            
            serviceLocator.extendedTableLoader.loadPresetsIntoTable(
                tableModel = tableModel,
                currentPresetList = stateManager.currentPresetList,
                table = table,
                onAddButtonRow = { addButtonRow() },
                inMemoryTableOrder = stateManager.inMemoryTableOrder
            )
            
            // Применяем текущую ориентацию к загруженным пресетам
            PluginLogger.debug(LogCategory.UI_EVENTS, "loadPresetsIntoTable completed, calling applyCurrentOrientationToTable")
            
            // Проверяем, если ориентация должна быть LANDSCAPE, но панель показывает PORTRAIT
            val savedOrientation = PresetStorageService.getOrientation()
            println("ADB_DEBUG: loadPresetsIntoTable - checking orientation consistency, saved: $savedOrientation")
            
            // Не нужно применять ориентацию, если данные уже были конвертированы в initializeTempPresetLists
            // и панель уже установлена в правильную ориентацию в initialize()
            println("ADB_DEBUG: loadPresetsIntoTable - skipping orientation application, already handled in initialization")
        } catch (_: IllegalStateException) {
            println("ADB_DEBUG: ExtendedTableLoader not initialized yet, skipping load")
        }
    }
    
    
    /**
     * Загружает пресеты в таблицу без вызова слушателей
     * Используется для пересортировки после редактирования ячеек
     */
    fun loadPresetsIntoTableWithoutNotification() {
        try {
            serviceLocator.extendedTableLoader.loadPresetsIntoTableWithoutNotification(
                tableModel = tableModel,
                currentPresetList = stateManager.currentPresetList,
                table = table,
                onAddButtonRow = { addButtonRow() },
                inMemoryTableOrder = stateManager.inMemoryTableOrder,
                tableModelListener = stateManager.tableModelListener
            )
        } catch (_: IllegalStateException) {
            println("ADB_DEBUG: ExtendedTableLoader not initialized yet, skipping load")
        }
    }

    /**
     * Добавляет специальную строку с кнопкой добавления
     */
    private fun addButtonRow() {
        try {
            serviceLocator.extendedTableLoader.addButtonRow(tableModel)
        } catch (_: IllegalStateException) {
            // Fallback если ExtendedTableLoader еще не инициализирован
            serviceLocator.tableLoader.addButtonRow(tableModel, dialogState.isShowAllPresetsMode())
            tableModel.updateRowNumbers()
        }
    }


    /**
     * Находит дубликаты в глобальном списке пресетов
     */

    /**
     * Сохраняет снимок видимых пресетов для всех списков перед переключением в режим Show all
     */
    fun saveVisiblePresetsSnapshotForAllLists() {
        serviceLocator.snapshotManager.saveVisiblePresetsSnapshotForAllLists(serviceLocator.tempListsManager.getTempLists())
    }

    /**
     * Сохраняет снимок видимых пресетов для каждого списка
     * Используется для корректного сопоставления при отключении фильтра дубликатов
     */
    private fun saveVisiblePresetsSnapshot() {
        serviceLocator.snapshotManager.saveVisiblePresetsSnapshot(stateManager.currentPresetList)
    }

    // === Обработчики событий ===

    fun handleCellClick(row: Int, column: Int, clickCount: Int) {
        // Проверяем флаг удаления
        if (dialogState.isProcessingDelete()) {
            println("ADB_DEBUG: handleCellClick ignored - delete in progress")
            return
        }
        
        if (row >= 0 && column >= 0) {
            // Обрабатываем клик через tableEventHandler, который сам управляет состоянием
            serviceLocator.tableEventHandler.handleCellClick(
                table = table,
                tableModel = tableModel,
                row = row,
                column = column,
                clickCount = clickCount,
                onAddNewPreset = { addNewPreset() }
            )
        } else {
            serviceLocator.hoverStateManager.clearTableSelection()
        }
    }

    fun setupGlobalClickListener() {
        serviceLocator.globalListenersManager.setupGlobalClickListener(table) {
            if (table.isEditing) {
                table.cellEditor?.stopCellEditing()
            }
        }
    }


    /**
     * Собирает текущий порядок из таблицы без сохранения в файл
     */
    private fun collectCurrentTableOrder(): List<String> {
        val order = mutableListOf<String>()
        
        if (dialogState.isShowAllPresetsMode()) {
            val listColumn = if (tableModel.columnCount > 8) tableModel.columnCount - 1 else -1
            
            for (i in 0 until tableModel.rowCount) {
                // Пропускаем строку с кнопкой "+"
                if (tableModel.getValueAt(i, 0) == "+") {
                    continue
                }
                
                val preset = tableModel.getPresetAt(i) ?: continue
                val listName = if (listColumn >= 0) {
                    tableModel.getValueAt(i, listColumn)?.toString() ?: ""
                } else {
                    ""
                }
                
                if (listName.isNotEmpty() && preset.id.isNotEmpty()) {
                    order.add("${listName}::${preset.id}")
                }
            }
        }
        
        return order
    }
    
    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        serviceLocator.tableDragDropHandler.onRowMoved(
            fromIndex = fromIndex,
            toIndex = toIndex,
            tableModel = tableModel,
            currentPresetList = stateManager.currentPresetList,
            historyManager = historyManager,
            hoverState = stateManager.hoverState,
            getListNameAtRow = ::getListNameAtRow,
            onHoverStateChanged = { newState -> serviceLocator.hoverStateManager.setState(newState) },
            onNormalModeOrderChanged = { stateManager.markNormalModeOrderChanged() },
            onModifiedListAdded = { listId -> stateManager.addModifiedListId(listId) }
        )
        
        serviceLocator.hoverStateManager.updateAfterRowMove(fromIndex, toIndex)
        
        // Обновляем заголовок таблицы, чтобы убрать визуальный индикатор сортировки
        table.tableHeader.repaint()
        
        // Обновляем порядок в памяти для режима Show All (без сохранения в файл)
        if (dialogState.isShowAllPresetsMode()) {
            val currentOrder = collectCurrentTableOrder()
            stateManager.updateInMemoryTableOrder(currentOrder)
            println("ADB_DEBUG: Updated in-memory table order after drag-and-drop with ${currentOrder.size} items")
        }
    }

    fun showContextMenu(e: MouseEvent) {
        serviceLocator.tableEventHandler.showContextMenu(
            e = e,
            table = table,
            tableModel = tableModel,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            canUndo = historyManager.canUndo(),
            canRedo = historyManager.canRedo(),
            onDuplicate = { row -> duplicatePreset(row) },
            onDelete = { row -> 
                val deleted = serviceLocator.presetOperationsService.deletePreset(
                    row = row,
                    tableModel = tableModel,
                    isShowAllMode = dialogState.isShowAllPresetsMode(),
                    tempPresetLists = serviceLocator.tempListsManager.getTempLists(),
                    currentPresetList = stateManager.currentPresetList
                )
                if (deleted) {
                    refreshTableInternal()
                }
            },
            onUndo = { historyManager.undo() },
            onRedo = { historyManager.redo() }
        )
    }

    // === Действия с пресетами ===

    private fun deletePresetFromEditor(row: Int) {
        serviceLocator.presetDeletionService.deletePresetFromEditor(
            row = row,
            table = table,
            dialogState = dialogState,
            currentPresetList = stateManager.currentPresetList,
            getPresetAtRow = ::getPresetAtRow,
            getListNameAtRow = ::getListNameAtRow,
            historyManager = historyManager,
            onReloadTable = ::loadPresetsIntoTable
        )
    }

    fun addNewPreset() {
        serviceLocator.presetOperationsService.addNewPreset(
            tableModel = tableModel,
            table = table,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            currentListName = stateManager.currentPresetList?.name,
            onPresetAdded = { newRowIndex ->
                addButtonRow()
                
                SwingUtilities.invokeLater {
                    serviceLocator.hoverStateManager.setTableSelection(newRowIndex, 2)
                    table.scrollRectToVisible(table.getCellRect(newRowIndex, 2, true))
                    table.editCellAt(newRowIndex, 2)
                    table.editorComponent?.requestFocus()
                    table.repaint()

                    // Обновляем позицию кнопки с задержкой и принудительным скроллом
                    SwingUtilities.invokeLater {
                        tableWithButtonPanel?.updateButtonPosition(forceScroll = true)
                    }
                }
            }
        )
    }


    fun duplicatePreset(row: Int) {
        stateManager.withDuplicatingPreset {
            val duplicated = serviceLocator.presetOperationsService.duplicatePreset(
                row = row,
                tableModel = tableModel,
                table = table,
                isShowAllMode = dialogState.isShowAllPresetsMode(),
                isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
                onDuplicatesFilterToggle = { enabled ->
                    listManagerPanel.setHideDuplicates(enabled)
                },
                currentListName = stateManager.currentPresetList?.name
            )
            
            if (duplicated) {
                // Обновляем порядок в памяти после дублирования
                if (!dialogState.isShowAllPresetsMode() && stateManager.currentPresetList != null) {
                    val tablePresets = tableModel.getPresets()
                    serviceLocator.presetOrderManager.updateNormalModeOrderInMemory(stateManager.currentPresetList!!.id, tablePresets)
                    stateManager.markNormalModeOrderChanged()
                    stateManager.addModifiedListId(stateManager.currentPresetList!!.id)
                    println("ADB_DEBUG: Updated normal mode order after duplication for list '${stateManager.currentPresetList!!.name}' with ${tablePresets.size} presets")
                }
                
                SwingUtilities.invokeLater {
                    val insertIndex = row + 1
                    table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
                    serviceLocator.hoverStateManager.setTableSelection(insertIndex, 2)
                    table.repaint()
                }
            }
        }
    }

    // === Валидация ===

    fun validateFields() {
        // Проверяем, что table инициализирована
        if (!::table.isInitialized || !::tableModel.isInitialized) {
            return
        }

        serviceLocator.validationService.validateFieldsAndUpdateUI(
            tableModel = tableModel,
            onUpdateOKButton = { isValid -> dialog.isOKActionEnabled = isValid },
            onRepaintTable = { table.repaint() }
        )
    }

    // === Сохранение и загрузка ===

    fun saveSettings(orientationPanel: OrientationPanel?) {
        // Сохраняем ориентацию
        orientationPanel?.let {
            val currentOrientation = it.getCurrentOrientation()
            println("ADB_DEBUG: saveSettings - saving orientation: ${currentOrientation.name}")
            PresetStorageService.setOrientation(currentOrientation.name)
            println("ADB_DEBUG: saveSettings - orientation saved, verifying: ${PresetStorageService.getOrientation()}")
        }
        
        serviceLocator.presetSaveManager.saveSettings(
            table = table,
            tableModel = tableModel,
            tempListsManager = serviceLocator.tempListsManager,
            currentPresetList = stateManager.currentPresetList,
            dialogState = dialogState,
            normalModeOrderChanged = stateManager.normalModeOrderChanged,
            modifiedListIds = stateManager.modifiedListIds,
            onSaveCurrentTableState = { saveCurrentTableState() }
        )
        
        // Сбрасываем флаги после сохранения
        stateManager.resetChangeFlags()
    }

    // === Вспомогательные методы ===

    fun addHoverEffectToDialogButtons(container: Container) {
        ButtonUtils.addHoverEffectToDialogButtons(container)
    }

    private fun getPresetAtRow(row: Int): DevicePreset {
        // Используем метод getPresetAt для получения пресета с ID
        return tableModel.getPresetAt(row) ?: DevicePreset("", "", "")
    }

    fun getListNameAtRow(row: Int): String? {
        // В режиме Show all presets получаем название списка из последней колонки
        if (dialogState.isShowAllPresetsMode() && row >= 0 && row < tableModel.rowCount) {
            // Колонка List находится в последней позиции (columnCount - 1)
            val listColumnIndex = tableModel.columnCount - 1
            if (listColumnIndex >= 0) {
                val value = tableModel.getValueAt(row, listColumnIndex)
                return value as? String
            }
        }
        return null
    }


    private fun refreshTableInternal() {
        try {
            serviceLocator.extendedTableLoader.refreshTable(table, ::validateFields)
        } catch (_: IllegalStateException) {
            serviceLocator.tableLoader.refreshTable(table, ::validateFields)
        }
    }

    /**
     * Сохраняет текущий порядок пресетов в режиме Show all presets
     */



    /**
     * Восстанавливает исходное состояние временных списков при отмене
     */
    fun restoreOriginalState() {
        // Восстанавливаем состояние сортировки
        serviceLocator.tableSortingController.restoreSortStateFromSnapshot()
        
        // Восстанавливаем счётчики использования
        serviceLocator.countersStateManager.restoreCountersFromSnapshot()
        
        serviceLocator.settingsPersistenceService.restoreOriginalState(
            tempListsManager = serviceLocator.tempListsManager,
            originalPresetLists = stateManager.originalPresetLists,
            snapshotManager = serviceLocator.snapshotManager
        )
        
        // Восстанавливаем текущий список по ID
        val currentId = stateManager.currentPresetList?.id
        if (currentId != null && serviceLocator.tempListsManager.getTempLists().containsKey(currentId)) {
            stateManager.currentPresetList = serviceLocator.tempListsManager.getTempList(currentId)
            println("ADB_DEBUG: Restored currentPresetList: ${stateManager.currentPresetList?.name}, presets: ${stateManager.currentPresetList?.presets?.size}")
        }
    }

    /**
     * Сохраняет снимок счётчиков для возможности отката
     */
    fun saveCountersSnapshot(snapshot: Pair<Map<String, Int>, Map<String, Int>>) {
        serviceLocator.countersStateManager.saveCountersSnapshot(snapshot)
    }

    fun dispose() {
        keyboardHandler.removeGlobalKeyListener()
        dialogNavigationHandler.uninstall()
        
        // Очищаем все ресурсы через ServiceLocator
        serviceLocator.dispose()
        
        // Очищаем состояние
        stateManager.clear()
        
        println("ADB_DEBUG: SettingsDialogController disposed")
    }

    fun saveCurrentShowAllOrderFromTable() {
        if (!::tableModel.isInitialized || !dialogState.isShowAllPresetsMode()) return
        
        val order = serviceLocator.presetSaveManager.saveCurrentShowAllOrderFromTable(
            tableModel,
            serviceLocator.tempListsManager,
            dialogState
        )
        
        stateManager.updateInMemoryTableOrder(order)
    }
    
    /**
     * Сохраняет текущий порядок таблицы в памяти
     */
    fun saveCurrentTableOrderToMemory() {
        if (!::tableModel.isInitialized) return
        
        if (dialogState.isShowAllPresetsMode()) {
            val order = serviceLocator.presetSaveManager.saveCurrentTableOrderToMemory(
                tableModel,
                serviceLocator.tempListsManager,
                dialogState
            )
            
            stateManager.updateInMemoryTableOrder(order)
        }
    }
    
    /**
     * Обновляет выбор в выпадающем списке в соответствии с текущим состоянием
     */
    private fun updateSelectedListInUIInternal() {
        stateManager.currentPresetList?.let {
            listManagerPanel.selectListByName(it.name)
        }
    }

    /**
     * Рекурсивно добавляет обработчик клика ко всем компонентам для выхода из режима редактирования
     */
    fun addClickListenerRecursively(container: Container, table: JBTable) {
        serviceLocator.globalListenersManager.addClickListenerRecursively(container, table)
    }
    
    /**
     * Получает полный порядок всех пресетов из всех списков
     */
    private fun getAllPresetsOrder(): List<String> {
        val allPresets = mutableListOf<String>()
        serviceLocator.tempListsManager.getTempLists().values.forEach { list ->
            list.presets.forEach { preset ->
                allPresets.add(preset.label)
            }
        }
        return allPresets
    }
    
    /**
     * Синхронно загружает пресеты в таблицу (без invokeLater)
     */
    private fun loadPresetsIntoTableSync(presetsOverride: List<DevicePreset>? = null) {
        try {
            serviceLocator.extendedTableLoader.loadPresetsIntoTableSync(
                tableModel = tableModel,
                currentPresetList = stateManager.currentPresetList,
                table = table,
                onAddButtonRow = { addButtonRow() },
                inMemoryTableOrder = stateManager.inMemoryTableOrder,
                presetsOverride = presetsOverride
            )
        } catch (_: IllegalStateException) {
            println("ADB_DEBUG: ExtendedTableLoader not initialized yet, skipping sync load")
        }
    }
    
    
    /**
     * Перезагружает таблицу с временным отключением слушателей для избежания рекурсии
     */
    private fun reloadTableWithoutListeners() {
        try {
            serviceLocator.extendedTableLoader.reloadTableWithoutListeners(
                table = table,
                tableModel = tableModel,
                currentPresetList = stateManager.currentPresetList,
                onAddButtonRow = ::addButtonRow,
                onSaveCurrentTableOrder = ::saveCurrentTableOrderToMemory,
                tableModelListener = stateManager.tableModelListener,
                inMemoryTableOrder = stateManager.inMemoryTableOrder
            )
        } catch (_: IllegalStateException) {
            println("ADB_DEBUG: ExtendedTableLoader not initialized yet, skipping reload")
        }
    }
    
    // === Реализация CommandContext ===
    
    override fun getCurrentPresetList(): PresetList? = stateManager.currentPresetList
    
    override fun getTempPresetLists(): Map<String, PresetList> = serviceLocator.tempListsManager.getTempLists()
    
    override fun isShowAllPresetsMode(): Boolean = dialogState.isShowAllPresetsMode()
    
    override fun isHideDuplicatesMode(): Boolean = dialogState.isHideDuplicatesMode()
    
    
    override fun getVisiblePresets(): List<DevicePreset> = tableModel.getPresets()
    
    override fun setTableUpdating(value: Boolean) {
        println("ADB_DEBUG: setTableUpdating: $value")
        dialogState.setTableUpdating(value)
    }
    
    override fun setPerformingHistoryOperation(value: Boolean) {
        println("ADB_DEBUG: setPerformingHistoryOperation: $value")
        dialogState.setPerformingHistoryOperation(value)
    }
    
    override fun setCurrentPresetList(list: PresetList) {
        stateManager.currentPresetList = list
    }
    
    override fun refreshTable() {
        refreshTableInternal()
    }
    
    override fun loadPresetsIntoTable(presets: List<DevicePreset>?) {
        loadPresetsIntoTableSync(presets)
    }
    
    override fun syncTableChangesToTempLists() {
        syncTableChangesToTempListsInternal()
    }
    
    
    override fun updateSelectedListInUI() {
        updateSelectedListInUIInternal()
    }
    
    // === Вспомогательные методы для фабрик и инициализаторов ===
    
    fun stopTableEditing() {
        if (::table.isInitialized && table.isEditing) {
            table.cellEditor.stopCellEditing()
        }
    }
    
    fun clearTableSelection() {
        // Очищаем выделение из HoverState
        try {
            serviceLocator.hoverStateManager.clearTableSelection()
    } catch (_: IllegalStateException) {
            // HoverStateManager еще не инициализирован
        }
    }
    
    fun isTableInitialized(): Boolean = ::table.isInitialized
    
    fun isDuplicatingPreset(): Boolean = stateManager.isDuplicatingPreset
    
    fun addTableModelListener() {
        stateManager.tableModelListener?.let { tableModel.addTableModelListener(it) }
    }
    
    fun removeTableModelListener() {
        stateManager.tableModelListener?.let { tableModel.removeTableModelListener(it) }
    }
    
    override fun switchToList(listId: String) {
        println("ADB_DEBUG: switchToList called with listId: $listId")
        
        // Находим список по ID
        val targetList = serviceLocator.tempListsManager.getTempList(listId)
        if (targetList == null) {
            println("ADB_DEBUG: List with id $listId not found")
            return
        }
        
        // Сохраняем текущее состояние, если находимся не в режиме Show All
        if (!dialogState.isShowAllPresetsMode()) {
            syncTableChangesToTempListsInternal()
        }
        
        // Переключаемся на новый список
        stateManager.currentPresetList = targetList
        
        // Обновляем UI
        listManagerPanel.setSelectedList(targetList.name)
        
        // Загружаем данные нового списка
        loadPresetsIntoTable(null)
    }
    
    override fun setShowAllMode(enabled: Boolean) {
        println("ADB_DEBUG: setShowAllMode called with enabled: $enabled")
        
        // Временно отключаем синхронизацию при переключении режима для команд
        dialogState.setPerformingHistoryOperation(true)
        try {
            // Устанавливаем состояние чекбокса
            listManagerPanel.setShowAllPresets(enabled)
            
            // Обработчик чекбокса автоматически выполнит необходимые действия
        } finally {
            // Восстанавливаем флаг после небольшой задержки, чтобы дать время обработчику выполниться
            SwingUtilities.invokeLater {
                dialogState.setPerformingHistoryOperation(false)
            }
        }
    }
    
    override fun getTableSortingService(): TableSortingService {
        return TableSortingService
    }
    
    /**
     * Проверяет, включен ли режим Show All
     */
    fun isShowAllMode(): Boolean {
        return dialogState.isShowAllPresetsMode()
    }
    
    /**
     * Отключает режим Show All
     */
    fun disableShowAllMode() {
        if (dialogState.isShowAllPresetsMode()) {
            // Используем метод из listManagerPanel для отключения Show All
            listManagerPanel.setShowAllPresets(false)
        }
    }

}