package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.handlers.DialogNavigationHandler
import io.github.qavlad.adbrandomizer.ui.services.DuplicateManager
import io.github.qavlad.adbrandomizer.ui.services.TempListsManager
import io.github.qavlad.adbrandomizer.ui.services.TableDataSynchronizer
import io.github.qavlad.adbrandomizer.ui.services.ViewModeManager
import io.github.qavlad.adbrandomizer.ui.services.PresetOperationsService
import io.github.qavlad.adbrandomizer.ui.services.TableEventHandler
import io.github.qavlad.adbrandomizer.ui.services.SnapshotManager
import io.github.qavlad.adbrandomizer.ui.services.SelectionTracker
import io.github.qavlad.adbrandomizer.ui.services.ValidationService
import io.github.qavlad.adbrandomizer.ui.services.TableLoader
import io.github.qavlad.adbrandomizer.ui.services.TableDragDropHandler
import io.github.qavlad.adbrandomizer.ui.services.StateManager
import io.github.qavlad.adbrandomizer.ui.services.PresetDistributor
import io.github.qavlad.adbrandomizer.ui.services.TableColumnManager
import io.github.qavlad.adbrandomizer.ui.services.PresetsPersistenceService
import io.github.qavlad.adbrandomizer.ui.services.PresetOrderManager
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService
import io.github.qavlad.adbrandomizer.ui.services.TableStateTracker
import io.github.qavlad.adbrandomizer.ui.services.PresetRecycleBin
import io.github.qavlad.adbrandomizer.ui.services.HoverStateManager
import io.github.qavlad.adbrandomizer.ui.services.CountersStateManager
import io.github.qavlad.adbrandomizer.ui.services.HiddenDuplicatesManager
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import javax.swing.*
import com.intellij.openapi.application.ApplicationManager
import javax.swing.SwingUtilities
import io.github.qavlad.adbrandomizer.ui.components.TableWithAddButtonPanel
import io.github.qavlad.adbrandomizer.ui.commands.CommandContext
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Контроллер для диалога настроек.
 * Управляет всей логикой, обработкой событий и состоянием.
 */
class PresetsDialogController(
    private val project: Project?,
    private val dialog: PresetsDialog
) : CommandContext {
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

    // Состояние
    private lateinit var hoverStateManager: HoverStateManager
    var hoverState = HoverState.noHover()
        private set
    private var isDuplicatingPreset = false
    override val historyManager = CommandHistoryManager(this)
    private val duplicateManager = DuplicateManager()
    private val presetDistributor = PresetDistributor(duplicateManager)
    private val tableSynchronizer = TableDataSynchronizer(duplicateManager, presetDistributor)
    
    // Менеджер порядка пресетов (перемещаем сюда для правильной инициализации)
    internal val presetOrderManager = PresetOrderManager()
    
    private val viewModeManager = ViewModeManager(presetOrderManager)

    override fun getPresetRecycleBin(): PresetRecycleBin = presetRecycleBin
    private val presetOperationsService = PresetOperationsService(historyManager, presetOrderManager)
    // TableEventHandler будет инициализирован после создания таблицы и HoverStateManager
    private lateinit var tableEventHandler: TableEventHandler
    private val snapshotManager = SnapshotManager(duplicateManager)
    private val validationService = ValidationService()
    private val stateManager = StateManager()
    private val settingsPersistenceService = PresetsPersistenceService()
    private val componentsFactory = DialogComponentsFactory()
    private val eventHandlersInitializer = EventHandlersInitializer(this)
    private val dialogState = DialogStateManager()
    
    // Сервис сортировки таблицы
    private val tableSortingService = TableSortingService
    
    // Трекер состояния таблицы
    private val tableStateTracker = TableStateTracker
    
    // Менеджер состояния счётчиков
    private val countersStateManager = CountersStateManager(tableSortingService, dialogState)
    
    // Менеджер скрытых дубликатов
    private val hiddenDuplicatesManager = HiddenDuplicatesManager()
    
    // Храним текущий порядок таблицы в памяти для сохранения при переключении режимов
    private var inMemoryTableOrder = listOf<String>()
    
    // Флаг для отслеживания изменений порядка в обычном режиме через drag & drop
    internal var normalModeOrderChanged = false
    
    // Набор ID списков, в которых был изменён порядок через drag & drop
    private val modifiedListIds = mutableSetOf<String>()
    
    // Менеджер временных списков (должен быть объявлен до использования)
    internal val tempListsManager = TempListsManager()
    
    // TableLoader зависит от presetOrderManager и tableSortingService
    private val tableLoader = TableLoader(viewModeManager, presetOrderManager, tableSortingService, dialogState)
    
    // Обработчик drag & drop операций
    private val tableDragDropHandler = TableDragDropHandler(
        dialogState,
        presetOrderManager,
        tableSortingService,
        tempListsManager
    )
    private var globalClickListener: java.awt.event.AWTEventListener? = null
    internal var currentPresetList: PresetList? = null
    
    // Менеджер колонок (инициализируется после создания tableConfigurator)
    private lateinit var tableColumnManager: TableColumnManager
    
    // Корзина для удалённых пресетов
    private val presetRecycleBin = PresetRecycleBin()

    // Исходное состояние списков для отката при Cancel
    private val originalPresetLists = mutableMapOf<String, PresetList>()

    // Ссылка на слушатель модели, для временного отключения
    private var tableModelListener: javax.swing.event.TableModelListener? = null

    // Слушатель модели таблицы с таймером
    private var tableModelListenerWithTimer: TableModelListenerWithTimer? = null
    
    // Последняя взаимодействующая строка теперь управляется через HoverStateManager

    /**
     * Инициализация контроллера
     */
    fun initialize() {
        // Очищаем кэши при открытии диалога
        PresetListService.clearAllCaches()
        refreshDeviceStatesIfNeeded()
    }

    /**
     * Создает панель управления списками пресетов
     */
    fun createListManagerPanel(): PresetListManagerPanel {
        listManagerPanel = eventHandlersInitializer.createAndInitializeListManagerPanel(
            dialogState = dialogState,
            duplicateManager = duplicateManager,
            tempListsManager = tempListsManager,
            tableWithButtonPanel = tableWithButtonPanel,
            onCurrentListChanged = { newList -> currentPresetList = newList },
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
        val showCounters = PresetStorageService.getShowCounters()
        tableModel = componentsFactory.createTableModel(historyManager, showCounters)
        // НЕ добавляем слушатель здесь, так как table еще не создана

        // НЕ загружаем список здесь, так как временные списки еще не созданы
        // currentPresetList будет установлен в initializeTempPresetLists

        return tableModel
    }

    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(model: DevicePresetTableModel): JBTable {
        table = componentsFactory.createTable(
            model, 
            { hoverState }, 
            dialogState, 
            historyManager,
            onLastInteractedRowUpdate = { row ->
                if (::hoverStateManager.isInitialized) {
                    hoverStateManager.updateLastInteractedRow(row)
                }
            }
        )
        
        // Инициализируем HoverStateManager после создания таблицы
        hoverStateManager = HoverStateManager(table) { newState ->
            hoverState = newState
        }
        
        // Инициализируем TableEventHandler после создания HoverStateManager
        tableEventHandler = TableEventHandler(
            project = project,
            getHoverState = { hoverState },
            setHoverState = { newState -> hoverStateManager.setState(newState) }
        )
        
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
        if (currentPresetList == null && tempListsManager.isNotEmpty()) {
            currentPresetList = tempListsManager.getTempLists().values.first()
        }

        // Создаем слушатель модели с таймером
        tableModelListenerWithTimer = componentsFactory.createTableModelListener(
            dialogState = dialogState,
            historyManager = historyManager,
            onValidateFields = { validateFields() },
            onSyncTableChanges = { syncTableChangesToTempListsInternal() },
            onTableRepaint = { 
                SwingUtilities.invokeLater {
                    table.repaint()
                }
            },
            controller = this
        )
        
        // Сохраняем ссылку на слушатель
        tableModelListener = tableModelListenerWithTimer?.listener
        
        // Добавляем слушатель к модели таблицы
        tableModel.addTableModelListener(tableModelListener)

        validationRenderer = ValidationRenderer(
            hoverState = { hoverState },
            getPresetAtRow = ::getPresetAtRow,
            findDuplicates = { tableModel.findDuplicates() },
            validationService = validationService
        )

        keyboardHandler = KeyboardHandler(
            table = table,
            tableModel = tableModel,
            hoverState = { hoverState },
            setHoverState = { newState -> hoverStateManager.setState(newState) },
            historyManager = historyManager,
            validateFields = ::validateFields,
            setEditingCellData = { oldValue, row, column ->
                dialogState.setEditingCell(row, column, oldValue)
                // Сохраняем последнюю взаимодействующую строку
                hoverStateManager.updateLastInteractedRow(row)
            },
            onDuplicate = ::duplicatePreset,
            forceSyncBeforeHistory = ::forceSyncBeforeHistoryOperation
        )
        
        dialogNavigationHandler = DialogNavigationHandler(
            table = table,
            setTableFocus = { table.requestFocusInWindow() },
            clearTableSelection = { 
                hoverStateManager.clearTableSelection()
            },
            selectFirstCell = {
                hoverStateManager.selectFirstLabelCell()
            }
        )

        tableConfigurator = TableConfigurator(
            table = table,
            hoverState = { hoverState },
            setHoverState = { newState -> hoverStateManager.setState(newState) },
            onRowMoved = ::onRowMoved,
            onCellClicked = ::handleCellClick,
            onTableExited = { hoverStateManager.handleTableExit() },
            validationRenderer = validationRenderer,
            showContextMenu = ::showContextMenu,
            isShowAllPresetsMode = { dialogState.isShowAllPresetsMode() },
            onPresetDeletedFromEditor = ::deletePresetFromEditor,
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
            getLastInteractedRow = { hoverStateManager.getLastInteractedRow() }
        )

        tableConfigurator.configure()
        
        // Инициализируем менеджер колонок
        tableColumnManager = TableColumnManager(
            tableConfigurator,
            { dialogState.isShowAllPresetsMode() },
            { dialogState.isHideDuplicatesMode() },
            { PresetStorageService.getShowCounters() }
        )

        println("ADB_DEBUG: After tableConfigurator.configure() - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        // Настраиваем колонки таблицы, включая заголовки для сортировки
        setupTableColumns()
        
        // Устанавливаем функцию обновления HoverState для SelectionTracker
        hoverStateManager.setupSelectionTracker()
        
        // Загружаем данные после полной инициализации
        println("ADB_DEBUG: Before loadPresetsIntoTable in initializeHandlers - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        loadPresetsIntoTable()
        
        // Если мы в режиме Show All с Hide Duplicates при первой загрузке,
        // нужно сохранить полный порядок всех пресетов
        if (dialogState.isShowAllPresetsMode() && dialogState.isHideDuplicatesMode() && dialogState.isFirstLoad()) {
            println("ADB_DEBUG: First load in Show All with Hide Duplicates - saving full order")
            val existingOrder = PresetListService.getShowAllPresetsOrder()
            if (existingOrder.isEmpty()) {
                // Создаём и сохраняем полный порядок всех пресетов
                val fullOrder = mutableListOf<Pair<String, DevicePreset>>()
                tempListsManager.getTempLists().forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        fullOrder.add(list.name to preset)
                    }
                }
                presetOrderManager.saveShowAllModeOrder(fullOrder)
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
        setupTableHeaderClickListener()
        
        // Настраиваем слушатель обновлений счётчиков
        countersStateManager.setupUpdateListener(table, tableModel) {
            loadPresetsIntoTable()
        }
        
        validateFields()
    }

    /**
     * Настройка колонок таблицы в зависимости от режима
     */
    private fun setupTableColumns() {
        tableColumnManager.setupTableColumns(table, tableModel, dialogState.isShowAllPresetsMode())
    }
    
    /**
     * Настраивает обработчик кликов по заголовкам таблицы для сортировки
     */
    private fun setupTableHeaderClickListener() {
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val columnIndex = table.columnModel.getColumnIndexAtX(e.x)
                if (columnIndex < 0) return
                
                println("ADB_DEBUG: Header clicked - columnIndex: $columnIndex")
                
                // Проверяем, является ли колонка сортируемой
                val hasCounters = PresetStorageService.getShowCounters()
                val listColumnIndex = if (hasCounters) 8 else 6 // List колонка сдвигается при наличии счетчиков
                
                val isSortable = when (columnIndex) {
                    2, 3, 4 -> true // Label, Size, DPI
                    5, 6 -> hasCounters // Size Uses, DPI Uses только когда счетчики включены
                    listColumnIndex -> dialogState.isShowAllPresetsMode() // List только в режиме Show All
                    else -> false
                }
                
                println("ADB_DEBUG: Header clicked - isSortable: $isSortable, hasCounters: $hasCounters")
                
                if (isSortable) {
                    handleHeaderClick(columnIndex)
                }
            }
        })
    }
    
    /**
     * Обрабатывает клик по заголовку колонки
     */
    private fun handleHeaderClick(columnIndex: Int) {
        // Определяем имя колонки
        val hasCounters = PresetStorageService.getShowCounters()
        val listColumnIndex = if (hasCounters) 8 else 6
        
        println("ADB_DEBUG: handleHeaderClick - columnIndex: $columnIndex, hasCounters: $hasCounters")
        
        val columnName = when (columnIndex) {
            2 -> "Label"
            3 -> "Size"
            4 -> "DPI"
            5 -> if (hasCounters) "Size Uses" else null
            6 -> if (hasCounters) "DPI Uses" else if (dialogState.isShowAllPresetsMode()) "List" else null
            listColumnIndex -> if (dialogState.isShowAllPresetsMode()) "List" else null
            else -> null
        }
        
        println("ADB_DEBUG: handleHeaderClick - columnName: $columnName")
        
        if (columnName != null) {
            // Обрабатываем клик через сервис сортировки
            tableSortingService.handleColumnClick(
                columnName,
                dialogState.isShowAllPresetsMode(),
                dialogState.isHideDuplicatesMode()
            )
            
            // Применяем сортировку
            applySorting()
            
            // Обновляем заголовки таблицы для отображения индикаторов сортировки
            table.tableHeader.repaint()
        }
    }
    
    /**
     * Применяет текущую сортировку к таблице
     */
    private fun applySorting() {
        // Сохраняем текущее состояние
        forceSyncBeforeHistoryOperation()
        
        // Перезагружаем таблицу с учетом сортировки
        loadPresetsIntoTable()
    }
    
    /**
     * Обрабатывает сброс сортировки
     */
    private fun handleResetSorting() {
        // Сбрасываем сортировку для текущего режима
        tableSortingService.resetSort(
            dialogState.isShowAllPresetsMode(),
            dialogState.isHideDuplicatesMode()
        )
        
        // Перезагружаем таблицу без сортировки
        loadPresetsIntoTable()
        
        // Обновляем заголовки таблицы
        table.tableHeader.repaint()
    }
    
    /**
     * Синхронизирует состояние сортировки при переключении режима Hide Duplicates
     */
    fun syncSortStateForHideDuplicatesToggle(hideDuplicates: Boolean) {
        tableSortingService.syncSortStateForHideDuplicatesToggle(
            dialogState.isShowAllPresetsMode(),
            hideDuplicates
        )
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
        tableSynchronizer.saveCurrentTableState(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempListsManager = tempListsManager,
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
        tableModelListenerWithTimer?.forceSyncPendingUpdates()
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
        
        tableSynchronizer.syncTableChangesToTempLists(
            tableModel = tableModel,
            tempListsManager = tempListsManager,
            currentPresetList = currentPresetList,
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
            if (isDuplicatingPreset && currentPresetList != null) {
                val tablePresets = tableModel.getPresets()
                presetOrderManager.updateNormalModeOrderInMemory(currentPresetList!!.id, tablePresets)
                normalModeOrderChanged = true
                modifiedListIds.add(currentPresetList!!.id)
                println("ADB_DEBUG: Updated normal mode order after sync during duplication for list '${currentPresetList!!.name}' with ${tablePresets.size} presets")
            } else {
                println("ADB_DEBUG: Not saving order after sync - using initial order")
            }
        } else if (context.isPerformingHistoryOperation) {
            println("ADB_DEBUG: Not saving order during history operation - preserving original order")
        }
    }



    /**
     * Удаляет пресет из временного списка
     * @return Pair<Boolean, Int?> - успешность удаления и реальный индекс в списке (если отличается от row)
     */
    fun deletePresetFromTempList(row: Int): Pair<Boolean, Int?> {
        val preset = getPresetAtRow(row)

        if (dialogState.isShowAllPresetsMode()) {
            val listName = getListNameAtRow(row) ?: return Pair(false, null)
            // Находим временный список по имени
            val targetList = tempListsManager.getTempLists().values.find { it.name == listName } ?: return Pair(false, null)

            // В режиме Hide Duplicates нужно найти пресет по ID
            if (dialogState.isHideDuplicatesMode()) {
                // Получаем ID пресета из таблицы
                val presetId = tableModel.getPresetIdAt(row)
                if (presetId != null) {
                    // Находим индекс пресета с этим ID в целевом списке
                    val indexToRemove = targetList.presets.indexOfFirst { it.id == presetId }
                    
                    if (indexToRemove >= 0) {
                        val removedPreset = targetList.presets.removeAt(indexToRemove)
                        println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' (id: $presetId) from list $listName at index $indexToRemove (hide duplicates mode)")
                        
                        // Помещаем пресет в корзину
                        presetRecycleBin.moveToRecycleBin(removedPreset, listName, indexToRemove)
                        
                        // Удаляем из фиксированного порядка
                        presetOrderManager.removeFromFixedOrder(listName, removedPreset)
                        return Pair(true, indexToRemove)
                    }
                    println("ADB_DEBUG: deletePresetFromTempList - preset with id $presetId not found in list $listName")
                } else {
                    println("ADB_DEBUG: deletePresetFromTempList - could not get preset ID for row $row")
                }
                return Pair(false, null)
            }

            // В обычном режиме удаляем по точному совпадению
            // Сначала находим индекс пресета для удаления
            val indexToRemove = targetList.presets.indexOfFirst {
                it.label == preset.label &&
                        it.size == preset.size &&
                        it.dpi == preset.dpi
            }
            
            if (indexToRemove >= 0) {
                val removedPreset = targetList.presets.removeAt(indexToRemove)
                println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from list $listName at index $indexToRemove")
                
                // Помещаем пресет в корзину
                presetRecycleBin.moveToRecycleBin(removedPreset, listName, indexToRemove)
                
                // Удаляем из фиксированного порядка
                presetOrderManager.removeFromFixedOrder(listName, removedPreset)
                return Pair(true, indexToRemove)
            }
            
            println("ADB_DEBUG: deletePresetFromTempList - preset not found in list $listName")
            return Pair(false, null)
        } else {
            // В обычном режиме удаляем из текущего списка
            if (currentPresetList != null) {
                // В режиме Hide Duplicates нужно учитывать, что в таблице показаны не все пресеты
                if (dialogState.isHideDuplicatesMode()) {
                    // Находим индексы видимых пресетов
                    val duplicateIndices = duplicateManager.findDuplicateIndices(currentPresetList!!.presets)
                    val visibleIndices = currentPresetList!!.presets.indices.filter { !duplicateIndices.contains(it) }
                    
                    // Проверяем, что row в пределах видимых пресетов
                    if (row < visibleIndices.size) {
                        val actualIndex = visibleIndices[row]
                        val removedPreset = currentPresetList!!.presets.removeAt(actualIndex)
                        println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from current list at index $actualIndex (hide duplicates mode)")
                        
                        // Помещаем пресет в корзину
                        presetRecycleBin.moveToRecycleBin(removedPreset, currentPresetList!!.name, actualIndex)
                        
                        return Pair(true, actualIndex)
                    }
                    return Pair(false, null)
                }
                
                // В обычном режиме удаляем по индексу, чтобы не удалить несколько одинаковых
                if (row >= 0 && row < currentPresetList!!.presets.size) {
                    val removedPreset = currentPresetList!!.presets.removeAt(row)
                    println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from current list at index $row")
                    
                    // Помещаем пресет в корзину
                    presetRecycleBin.moveToRecycleBin(removedPreset, currentPresetList!!.name, row)
                    
                    // Удаляем из фиксированного порядка
                    presetOrderManager.removeFromFixedOrder(currentPresetList!!.name, removedPreset)
                    return Pair(true, null)
                }
                return Pair(false, null)
            }
            return Pair(false, null)
        }
    }

    /**
     * Инициализирует временные копии всех списков для работы в памяти
     */
    private fun initializeTempPresetLists() {
        println("ADB_DEBUG: initializeTempPresetLists - start")
        println("ADB_DEBUG:   tempListsManager.size() before clear: ${tempListsManager.size()}")

        // Включаем кэш для оптимизации производительности
        PresetListService.enableCache()
        
        // Создаем снимок состояния сортировки для возможности отката
        TableSortingService.createSortStateSnapshot()

        // Сбрасываем флаг изменения порядка при инициализации
        normalModeOrderChanged = false
        modifiedListIds.clear()
        println("ADB_DEBUG: Reset normalModeOrderChanged = false and cleared modifiedListIds")

        // Проверяем, что дефолтные списки существуют
        PresetListService.ensureDefaultListsExist()

        tempListsManager.clear()
        originalPresetLists.clear()
        duplicateManager.clearSnapshots()
        
        // Загружаем все списки через StateManager
        val loadedLists = stateManager.initializeTempPresetLists()
        println("ADB_DEBUG:   loadedLists.size: ${loadedLists.size}")
        tempListsManager.setTempLists(loadedLists)
        println("ADB_DEBUG:   tempListsManager.size() after setTempLists: ${tempListsManager.size()}")
        
        // Создаем копии для отката
        originalPresetLists.putAll(snapshotManager.saveOriginalState(tempListsManager.getTempLists()))
        
        // Сохраняем исходный порядок из файлов для каждого списка
        loadedLists.values.forEach { list ->
            presetOrderManager.saveOriginalFileOrder(list.id, list.presets)
        }
        
        // Инициализируем информацию о скрытых дублях
        hiddenDuplicatesManager.initializeHiddenDuplicates(loadedLists)
        
        // Сохраняем начальный порядок для каждого списка в обычном режиме
        // Это нужно для восстановления порядка после режима Show All
        // НО только если порядок ещё не был сохранён ранее
        loadedLists.values.forEach { list ->
            val existingOrder = presetOrderManager.getNormalModeOrder(list.id)
            if (existingOrder == null) {
                // Порядок ещё не сохранён - сохраняем текущий как начальный
                presetOrderManager.saveNormalModeOrder(list.id, list.presets)
                println("ADB_DEBUG: Saved initial normal mode order for list '${list.name}' with ${list.presets.size} presets")
            } else {
                println("ADB_DEBUG: Normal mode order already exists for list '${list.name}' with ${existingOrder.size} items - not overwriting")
                // Загружаем существующий порядок в память для использования при отображении
                val orderedPresets = mutableListOf<DevicePreset>()
                
                // Пробуем сначала как ID (новый формат)
                val presetsById = list.presets.associateBy { it.id }
                var foundByIds = false
                
                existingOrder.forEach { key ->
                    presetsById[key]?.let { 
                        orderedPresets.add(it)
                        foundByIds = true
                    }
                }
                
                // Если не нашли по ID, пробуем старый формат с составным ключом
                if (!foundByIds) {
                    val presetsMap = list.presets.associateBy { "${it.label}|${it.size}|${it.dpi}" }
                    existingOrder.forEach { key ->
                        presetsMap[key]?.let { orderedPresets.add(it) }
                    }
                    // Добавляем пресеты, которых нет в сохранённом порядке
                    list.presets.forEach { preset ->
                        val key = "${preset.label}|${preset.size}|${preset.dpi}"
                        if (key !in existingOrder) {
                            orderedPresets.add(preset)
                        }
                    }
                } else {
                    // Добавляем пресеты, которых нет в сохранённом порядке (новые пресеты по ID)
                    list.presets.forEach { preset ->
                        if (preset.id !in existingOrder) {
                            orderedPresets.add(preset)
                        }
                    }
                }
                
                if (orderedPresets.isNotEmpty()) {
                    presetOrderManager.updateNormalModeOrderInMemory(list.id, orderedPresets)
                }
            }
        }
        
        // Определяем начальный текущий список
        currentPresetList = stateManager.determineInitialCurrentList(tempListsManager.getTempLists())
        
        println("ADB_DEBUG: After determineInitialCurrentList - currentPresetList: ${currentPresetList?.name}")
        println("ADB_DEBUG: currentPresetList contents after assignment:")
        currentPresetList?.presets?.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        
        // Обновляем комбобокс если панель уже создана
        if (this::listManagerPanel.isInitialized) {
            println("ADB_DEBUG: Reloading lists in combobox after file system check")
            listManagerPanel.loadLists()
            currentPresetList?.let {
                listManagerPanel.selectListByName(it.name)
            }
        }
        
        println("ADB_DEBUG: initializeTempPresetLists - done. Current list: ${currentPresetList?.name}, temp lists count: ${tempListsManager.size()}")
    }

    /**
     * Загружает пресеты в таблицу в зависимости от текущего режима
     */
    private fun loadPresetsIntoTable() {
        val tempLists = tempListsManager.getTempLists()
        println("ADB_DEBUG: SettingsDialogController.loadPresetsIntoTable()")
        println("ADB_DEBUG:   tempListsManager.getTempLists().size: ${tempLists.size}")
        println("ADB_DEBUG:   tempListsManager.isEmpty(): ${tempListsManager.isEmpty()}")
        
        if (tempLists.isEmpty()) {
            println("ADB_DEBUG:   WARNING: tempListsManager is EMPTY before passing to TableLoader!")
            println("ADB_DEBUG:   Stack trace:")
            Thread.currentThread().stackTrace.take(10).forEach { element ->
                println("ADB_DEBUG:     at $element")
            }
        }
        
        // Очищаем выделение при смене режимов
        if (dialogState.isSwitchingMode() || dialogState.isSwitchingList()) {
            if (::hoverStateManager.isInitialized) {
                hoverStateManager.resetForModeSwitch()
            }
        }
        
        tableLoader.loadPresetsIntoTable(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempLists,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isFirstLoad = dialogState.isFirstLoad(),
            isSwitchingList = dialogState.isSwitchingList(),
            isSwitchingMode = dialogState.isSwitchingMode(),
            isSwitchingDuplicatesFilter = dialogState.isSwitchingDuplicatesFilter(),
            onTableUpdating = { updating -> dialogState.setTableUpdating(updating) },
            onAddButtonRow = { addButtonRow() },
            inMemoryOrder = if (dialogState.isShowAllPresetsMode()) {
                if (inMemoryTableOrder.isNotEmpty()) {
                    println("ADB_DEBUG: Using existing inMemoryTableOrder with ${inMemoryTableOrder.size} items")
                    inMemoryTableOrder
                } else {
                    // Попробуем загрузить последний сохранённый порядок
                    val savedOrder = PresetListService.getShowAllPresetsOrder()
                    if (savedOrder.isNotEmpty()) {
                        println("ADB_DEBUG: Loading saved Show All order as inMemoryOrder with ${savedOrder.size} items")
                        // Используем формат с ID напрямую
                        savedOrder.ifEmpty {
                            null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            },
            initialHiddenDuplicates = hiddenDuplicatesManager.getHiddenDuplicatesForTableLoader(),
            table = table,
            onClearTableSelection = {
                if (::hoverStateManager.isInitialized) {
                    hoverStateManager.clearTableSelection()
                }
            }
        )
        
        // Обновляем текущее состояние скрытых дублей после загрузки таблицы
        updateCurrentHiddenDuplicates()
    }
    
    /**
     * Обновляет текущее состояние скрытых дублей на основе текущих данных
     */
    private fun updateCurrentHiddenDuplicates() {
        hiddenDuplicatesManager.updateCurrentHiddenDuplicates(
            dialogState.isHideDuplicatesMode(),
            tempListsManager.getTempLists()
        )
    }
    
    /**
     * Загружает пресеты в таблицу без вызова слушателей
     * Используется для пересортировки после редактирования ячеек
     */
    fun loadPresetsIntoTableWithoutNotification() {
        // Временно отключаем слушатель модели
        tableModelListener?.let { tableModel.removeTableModelListener(it) }
        
        dialogState.withTableUpdate {
            loadPresetsIntoTable()
        }
        
        // Возвращаем слушатель на место
        tableModelListener?.let { tableModel.addTableModelListener(it) }
    }

    /**
     * Добавляет специальную строку с кнопкой добавления
     */
    private fun addButtonRow() {
        tableLoader.addButtonRow(tableModel, dialogState.isShowAllPresetsMode())
        tableModel.updateRowNumbers()
    }


    /**
     * Находит дубликаты в глобальном списке пресетов
     */

    /**
     * Сохраняет снимок видимых пресетов для всех списков перед переключением в режим Show all
     */
    fun saveVisiblePresetsSnapshotForAllLists() {
        snapshotManager.saveVisiblePresetsSnapshotForAllLists(tempListsManager.getTempLists())
    }

    /**
     * Сохраняет снимок видимых пресетов для каждого списка
     * Используется для корректного сопоставления при отключении фильтра дубликатов
     */
    private fun saveVisiblePresetsSnapshot() {
        snapshotManager.saveVisiblePresetsSnapshot(currentPresetList)
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
            tableEventHandler.handleCellClick(
                table = table,
                tableModel = tableModel,
                row = row,
                column = column,
                clickCount = clickCount,
                onAddNewPreset = { addNewPreset() }
            )
        } else {
            hoverStateManager.clearTableSelection()
        }
    }

    fun setGlobalClickListener(listener: java.awt.event.AWTEventListener) {
        globalClickListener = listener
    }


    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        tableDragDropHandler.onRowMoved(
            fromIndex = fromIndex,
            toIndex = toIndex,
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            historyManager = historyManager,
            hoverState = hoverState,
            getListNameAtRow = ::getListNameAtRow,
            onHoverStateChanged = { newState -> hoverStateManager.setState(newState) },
            onNormalModeOrderChanged = { normalModeOrderChanged = true },
            onModifiedListAdded = { listId -> modifiedListIds.add(listId) }
        )
        
        hoverStateManager.updateAfterRowMove(fromIndex, toIndex)
        
        // Обновляем заголовок таблицы, чтобы убрать визуальный индикатор сортировки
        table.tableHeader.repaint()
    }

    fun showContextMenu(e: MouseEvent) {
        tableEventHandler.showContextMenu(
            e = e,
            table = table,
            tableModel = tableModel,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            canUndo = historyManager.canUndo(),
            canRedo = historyManager.canRedo(),
            onDuplicate = { row -> duplicatePreset(row) },
            onDelete = { row -> 
                val deleted = presetOperationsService.deletePreset(
                    row = row,
                    tableModel = tableModel,
                    isShowAllMode = dialogState.isShowAllPresetsMode(),
                    tempPresetLists = tempListsManager.getTempLists(),
                    currentPresetList = currentPresetList
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
        println("ADB_DEBUG: deletePresetFromEditor called for row: $row")
        val model = table.model as? javax.swing.table.DefaultTableModel ?: return

        if (row != -1) {
            // Проверяем, что это не строка с кнопкой
            if (model.getValueAt(row, 0) == "+") {
                return // Не удаляем строку с кнопкой
            }

            // Устанавливаем флаг для предотвращения обработки клика
            dialogState.withDeleteProcessing {
                // Получаем данные пресета перед удалением для истории
                val preset = getPresetAtRow(row)
                val listNameForHistory = if (dialogState.isShowAllPresetsMode()) {
                    getListNameAtRow(row)
                } else {
                    currentPresetList?.name
                }

                // Удаляем пресет из временного списка (source of truth)
                val (removed, actualIndex) = deletePresetFromTempList(row)

                if (removed) {
                    // Добавляем операцию в историю с реальным индексом если он отличается
                    historyManager.addPresetDelete(row, preset, listNameForHistory, actualIndex)

                    // Перезагружаем таблицу из источника правды
                    loadPresetsIntoTable()
                }
            }
        }
    }

    fun addNewPreset() {
        presetOperationsService.addNewPreset(
            tableModel = tableModel,
            table = table,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            currentListName = currentPresetList?.name,
            onPresetAdded = { newRowIndex ->
                addButtonRow()
                
                SwingUtilities.invokeLater {
                    hoverStateManager.setTableSelection(newRowIndex, 2)
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
        // Устанавливаем флаг, чтобы заблокировать перезагрузку таблицы
        isDuplicatingPreset = true
        
        try {
            val duplicated = presetOperationsService.duplicatePreset(
                row = row,
                tableModel = tableModel,
                table = table,
                isShowAllMode = dialogState.isShowAllPresetsMode(),
                isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
                onDuplicatesFilterToggle = { enabled ->
                    listManagerPanel.setHideDuplicates(enabled)
                },
                currentListName = currentPresetList?.name
            )
            
            if (duplicated) {
                // Обновляем порядок в памяти после дублирования
                if (!dialogState.isShowAllPresetsMode() && currentPresetList != null) {
                    val tablePresets = tableModel.getPresets()
                    presetOrderManager.updateNormalModeOrderInMemory(currentPresetList!!.id, tablePresets)
                    normalModeOrderChanged = true
                    modifiedListIds.add(currentPresetList!!.id)
                    println("ADB_DEBUG: Updated normal mode order after duplication for list '${currentPresetList!!.name}' with ${tablePresets.size} presets")
                }
                
                SwingUtilities.invokeLater {
                    val insertIndex = row + 1
                    table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
                    hoverStateManager.setTableSelection(insertIndex, 2)
                    table.repaint()
                }
            }
        } finally {
            // Сбрасываем флаг
            isDuplicatingPreset = false
        }
    }

    // === Валидация ===

    fun validateFields() {
        // Проверяем, что table инициализирована
        if (!::table.isInitialized || !::tableModel.isInitialized) {
            return
        }

        validationService.validateFieldsAndUpdateUI(
            tableModel = tableModel,
            onUpdateOKButton = { isValid -> dialog.isOKActionEnabled = isValid },
            onRepaintTable = { table.repaint() }
        )
    }

    // === Сохранение и загрузка ===

    fun saveSettings() {
        // Если включен режим Show All, нужно сохранить текущий порядок из таблицы
        if (dialogState.isShowAllPresetsMode()) {
            // Собираем порядок из таблицы
            val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
            val listColumn = if (tableModel.columnCount > 6) tableModel.columnCount - 1 else -1
            
            for (i in 0 until tableModel.rowCount) {
                // Пропускаем строку с кнопкой "+"
                if (tableModel.getValueAt(i, 0) == "+") {
                    continue
                }
                
                val listName = if (listColumn >= 0) {
                    tableModel.getValueAt(i, listColumn)?.toString() ?: ""
                } else {
                    ""
                }
                
                if (listName.isNotEmpty()) {
                    val preset = tableModel.getPresetAt(i)
                    if (preset != null) {
                        allPresetsWithLists.add(listName to preset)
                    }
                }
            }
            
            // Сначала сохраняем порядок для обычного режима, если он был изменён
            if (normalModeOrderChanged) {
                // Сохраняем порядок для всех изменённых списков
                modifiedListIds.forEach { listId ->
                    val tempList = tempListsManager.getTempList(listId)
                    if (tempList != null && tempList.presets.isNotEmpty()) {
                        presetOrderManager.saveNormalModeOrder(listId, tempList.presets)
                        println("ADB_DEBUG: Saved normal mode order for modified list '${tempList.name}' in Show All mode")
                    }
                }
                println("ADB_DEBUG: Saved normal mode order for ${modifiedListIds.size} modified lists in Show All mode")
            }
            
            // Теперь сохраняем порядок при Save
            if (dialogState.isHideDuplicatesMode()) {
                // В режиме Hide Duplicates нужно восстановить полный порядок с дубликатами
                val fullOrderWithDuplicates = mutableListOf<Pair<String, DevicePreset>>()
                val processedPresetIds = mutableSetOf<String>()
                
                // Проходим по видимым пресетам из таблицы
                allPresetsWithLists.forEach { (listName, preset) ->
                    fullOrderWithDuplicates.add(listName to preset)
                    processedPresetIds.add(preset.id)
                    
                    // Находим все дубликаты этого пресета
                    val duplicateKey = preset.getDuplicateKey()
                    tempListsManager.getTempLists().forEach { (_, list) ->
                        list.presets.forEach { duplicatePreset ->
                            if (duplicatePreset.id != preset.id && 
                                duplicatePreset.getDuplicateKey() == duplicateKey &&
                                duplicatePreset.id !in processedPresetIds) {
                                fullOrderWithDuplicates.add(list.name to duplicatePreset)
                                processedPresetIds.add(duplicatePreset.id)
                            }
                        }
                    }
                }
                
                // Добавляем оставшиеся пресеты
                tempListsManager.getTempLists().forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        if (preset.id !in processedPresetIds) {
                            fullOrderWithDuplicates.add(list.name to preset)
                            processedPresetIds.add(preset.id)
                        }
                    }
                }
                
                presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
                presetOrderManager.saveShowAllHideDuplicatesOrder(allPresetsWithLists)
            } else {
                // В обычном режиме Show All сохраняем как есть
                presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
                presetOrderManager.updateFixedShowAllOrder(allPresetsWithLists)
            }
        } else {
            // В обычном режиме сохраняем порядок для всех изменённых списков
            if (normalModeOrderChanged) {
                // Сохраняем порядок для текущего списка
                if (currentPresetList != null) {
                    // В режиме скрытия дубликатов используем полный порядок из памяти
                    if (dialogState.isHideDuplicatesMode()) {
                        val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(currentPresetList!!.id)
                        if (memoryOrder != null) {
                            val tempList = tempListsManager.getTempList(currentPresetList!!.id)
                            if (tempList != null) {
                                val orderedPresets = mutableListOf<DevicePreset>()
                                
                                // Восстанавливаем порядок из памяти
                                memoryOrder.forEach { key ->
                                    val preset = tempList.presets.find { p ->
                                        "${p.label}|${p.size}|${p.dpi}" == key
                                    }
                                    if (preset != null) {
                                        orderedPresets.add(preset)
                                    }
                                }
                                
                                // Добавляем новые пресеты, которых не было в сохранённом порядке
                                val savedKeys = memoryOrder.toSet()
                                tempList.presets.forEach { preset ->
                                    val key = "${preset.label}|${preset.size}|${preset.dpi}"
                                    if (key !in savedKeys) {
                                        orderedPresets.add(preset)
                                    }
                                }
                                
                                if (orderedPresets.isNotEmpty()) {
                                    presetOrderManager.saveNormalModeOrder(currentPresetList!!.id, orderedPresets)
                                    println("ADB_DEBUG: Saved normal mode order for current list '${currentPresetList!!.name}' with ${orderedPresets.size} presets (from memory)")
                                }
                            }
                        }
                    } else {
                        // Без скрытия дубликатов используем порядок из таблицы
                        val tablePresets = tableModel.getPresets()
                        if (tablePresets.isNotEmpty()) {
                            presetOrderManager.saveNormalModeOrder(currentPresetList!!.id, tablePresets)
                            println("ADB_DEBUG: Saved normal mode order for current list '${currentPresetList!!.name}' with ${tablePresets.size} presets")
                        }
                    }
                }
                
                // Сохраняем порядок для всех изменённых списков
                modifiedListIds.forEach { listId ->
                    if (listId != currentPresetList?.id) { // Текущий список уже сохранён выше
                        val memoryOrder = presetOrderManager.getNormalModeOrderInMemory(listId)
                        if (memoryOrder != null) {
                            val tempList = tempListsManager.getTempList(listId)
                            if (tempList != null) {
                                val orderedPresets = mutableListOf<DevicePreset>()
                                
                                // Восстанавливаем порядок из памяти (по ID)
                                memoryOrder.forEach { presetId ->
                                    val preset = tempList.presets.find { p -> p.id == presetId }
                                    if (preset != null) {
                                        orderedPresets.add(preset)
                                    }
                                }
                                
                                // Добавляем новые пресеты, которых не было в сохранённом порядке
                                val savedIds = memoryOrder.toSet()
                                tempList.presets.forEach { preset ->
                                    if (preset.id !in savedIds) {
                                        orderedPresets.add(preset)
                                    }
                                }
                                
                                if (orderedPresets.isNotEmpty()) {
                                    presetOrderManager.saveNormalModeOrder(listId, orderedPresets)
                                    println("ADB_DEBUG: Saved normal mode order for modified list '${tempList.name}' with ${orderedPresets.size} presets")
                                }
                            }
                        }
                    }
                }
                
                println("ADB_DEBUG: Saved normal mode order for ${modifiedListIds.size} modified lists")
            } else {
                println("ADB_DEBUG: In normal mode - order not changed via drag & drop, using initial order")
            }
        }
        
        settingsPersistenceService.saveSettings(
            table = table,
            tempLists = tempListsManager.getTempLists(),
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            presetOrderManager = presetOrderManager,
            onSaveCurrentTableState = { saveCurrentTableState() },
            onSaveShowAllPresetsOrder = { 
                // Порядок уже сохранен выше
            }
        )
        
        // Сбрасываем флаг и набор после сохранения
        normalModeOrderChanged = false
        modifiedListIds.clear()
        println("ADB_DEBUG: Reset normalModeOrderChanged flag and cleared modifiedListIds after saving settings")
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
        tableLoader.refreshTable(table, ::validateFields)
    }

    /**
     * Сохраняет текущий порядок пресетов в режиме Show all presets
     */


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

    /**
     * Восстанавливает исходное состояние временных списков при отмене
     */
    fun restoreOriginalState() {
        // Восстанавливаем состояние сортировки
        TableSortingService.restoreSortStateFromSnapshot()
        
        // Восстанавливаем счётчики использования
        countersStateManager.restoreCountersFromSnapshot()
        
        settingsPersistenceService.restoreOriginalState(
            tempListsManager = tempListsManager,
            originalPresetLists = originalPresetLists,
            snapshotManager = snapshotManager
        )
        
        // Восстанавливаем текущий список по ID
        val currentId = currentPresetList?.id
        if (currentId != null && tempListsManager.getTempLists().containsKey(currentId)) {
            currentPresetList = tempListsManager.getTempList(currentId)
            println("ADB_DEBUG: Restored currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        }
    }

    /**
     * Сохраняет снимок счётчиков для возможности отката
     */
    fun saveCountersSnapshot(snapshot: Pair<Map<String, Int>, Map<String, Int>>) {
        countersStateManager.saveCountersSnapshot(snapshot)
    }

    fun dispose() {
        countersStateManager.dispose()
        keyboardHandler.removeGlobalKeyListener()
        
        // Отключаем кэш и очищаем его
        PresetListService.disableCache()
        
        // Очищаем корзину при закрытии диалога
        presetRecycleBin.clear()
        // Очищаем исходные порядки из файлов
        presetOrderManager.clearOriginalFileOrders()
        println("ADB_DEBUG: SettingsDialogController disposed - recycle bin and original orders cleared")
        dialogNavigationHandler.uninstall()

        // Удаляем глобальный обработчик кликов
        globalClickListener?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
    }

    fun saveCurrentShowAllOrderFromTable() {
        if (!::tableModel.isInitialized || !dialogState.isShowAllPresetsMode()) return
        
        println("ADB_DEBUG: saveCurrentShowAllOrderFromTable - saving Show All order to file")
        
        // Собираем порядок из таблицы
        val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
        val listColumn = if (tableModel.columnCount > 6) tableModel.columnCount - 1 else -1
        
        for (i in 0 until tableModel.rowCount) {
            // Пропускаем строку с кнопкой "+"
            if (tableModel.getValueAt(i, 0) == "+") {
                continue
            }
            
            val listName = if (listColumn >= 0) {
                tableModel.getValueAt(i, listColumn)?.toString() ?: ""
            } else {
                ""
            }
            
            if (listName.isNotEmpty()) {
                val preset = tableModel.getPresetAt(i)
                if (preset != null) {
                    allPresetsWithLists.add(listName to preset)
                }
            }
        }
        
        if (dialogState.isHideDuplicatesMode()) {
            // В режиме Hide Duplicates сохраняем текущий видимый порядок,
            // но полный порядок со всеми дубликатами уже должен быть сохранён
            // Мы НЕ должны перестраивать полный порядок здесь
            
            // Просто обновляем inMemoryTableOrder из существующего полного порядка
            val existingFullOrder = PresetListService.getShowAllPresetsOrder()
            if (existingFullOrder.isNotEmpty()) {
                println("ADB_DEBUG: Using existing full order for Hide Duplicates mode: ${existingFullOrder.size} items")
                inMemoryTableOrder = existingFullOrder
            } else {
                // Если по какой-то причине нет полного порядка, создаём его
                println("ADB_DEBUG: WARNING: No existing full order in Hide Duplicates mode, creating from temp lists")
                val fullOrderWithDuplicates = mutableListOf<Pair<String, DevicePreset>>()
                
                // Собираем все пресеты из всех списков в их текущем порядке
                tempListsManager.getTempLists().forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        fullOrderWithDuplicates.add(list.name to preset)
                    }
                }
                
                presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
                println("ADB_DEBUG: Created and saved full order: ${fullOrderWithDuplicates.size} items")
                
                inMemoryTableOrder = fullOrderWithDuplicates.map { (listName, preset) ->
                    "${listName}::${preset.id}"
                }
            }
        } else {
            // В обычном режиме Show All сохраняем как есть
            presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
            println("ADB_DEBUG: Saved Show All order: ${allPresetsWithLists.size} items")
            
            // Также обновляем inMemoryTableOrder для использования при возврате
            inMemoryTableOrder = allPresetsWithLists.map { (listName, preset) ->
                "${listName}::${preset.id}"
            }
        }
        
        println("ADB_DEBUG: Updated inMemoryTableOrder with ${inMemoryTableOrder.size} items")
    }
    
    /**
     * Сохраняет текущий порядок таблицы в памяти
     */
    fun saveCurrentTableOrderToMemory() {
        if (!::tableModel.isInitialized) return
        
        if (dialogState.isShowAllPresetsMode()) {
            // В режиме Show All сохраняем полный порядок всех пресетов
            val allPresetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
            val listColumn = if (tableModel.columnCount > 6) tableModel.columnCount - 1 else -1
            
            for (i in 0 until tableModel.rowCount) {
                // Пропускаем строку с кнопкой "+"
                if (tableModel.getValueAt(i, 0) == "+") {
                    continue
                }
                
                val listName = if (listColumn >= 0) {
                    tableModel.getValueAt(i, listColumn)?.toString() ?: ""
                } else {
                    ""
                }
                
                if (listName.isNotEmpty()) {
                    val preset = tableModel.getPresetAt(i)
                    if (preset != null) {
                        allPresetsWithLists.add(listName to preset)
                    }
                }
            }
            
            // Если включен Hide Duplicates, нужно восстановить полный порядок с дубликатами
            if (dialogState.isHideDuplicatesMode()) {
                // Получаем текущий сохранённый порядок Show All (который включает все пресеты)
                val existingFullOrder = PresetListService.getShowAllPresetsOrder()
                
                if (existingFullOrder.isNotEmpty()) {
                    // Используем существующий полный порядок, который уже содержит все пресеты
                    // включая скрытые дубликаты в их оригинальных позициях
                    println("ADB_DEBUG: Using existing full order with ${existingFullOrder.size} items")
                    inMemoryTableOrder = existingFullOrder
                    
                    // Не нужно пересохранять - порядок уже правильный
                } else {
                    // Если нет сохранённого порядка, создаём новый на основе всех пресетов
                    println("ADB_DEBUG: No existing full order, creating new one")
                    val fullOrderWithDuplicates = mutableListOf<Pair<String, DevicePreset>>()
                    
                    // Собираем все пресеты из всех списков в их текущем порядке
                    tempListsManager.getTempLists().forEach { (_, list) ->
                        list.presets.forEach { preset ->
                            fullOrderWithDuplicates.add(list.name to preset)
                        }
                    }
                    
                    inMemoryTableOrder = fullOrderWithDuplicates.map { (listName, preset) ->
                        "${listName}::${preset.id}"
                    }
                    
                    // Сохраняем полный порядок
                    presetOrderManager.saveShowAllModeOrder(fullOrderWithDuplicates)
                }
            } else {
                // В обычном режиме Show All сохраняем как есть
                inMemoryTableOrder = allPresetsWithLists.map { (listName, preset) ->
                    "${listName}::${preset.id}"
                }
                
                // Также сохраняем в файл для переключения режимов
                presetOrderManager.saveShowAllModeOrder(allPresetsWithLists)
            }
            
            println("ADB_DEBUG: Saved table order to memory: ${inMemoryTableOrder.size} items")
        }
    }
    
    /**
     * Обновляет выбор в выпадающем списке в соответствии с текущим состоянием
     */
    private fun updateSelectedListInUIInternal() {
        currentPresetList?.let {
            listManagerPanel.selectListByName(it.name)
        }
    }

    /**
     * Рекурсивно добавляет обработчик клика ко всем компонентам для выхода из режима редактирования
     */
    fun addClickListenerRecursively(container: Container, table: JBTable) {
        var listenerCount = 0

        val mouseListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Проверяем, что клик не по самой таблице и не по кнопкам
                if (e.source !is JBTable && e.source !is JButton && table.isEditing) {
                    println("ADB_DEBUG: Cell editing stopped by recursive click listener")
                    table.cellEditor?.stopCellEditing()
                }
            }
        }

        fun addListenersRecursive(comp: Container) {
            // Добавляем обработчик к контейнеру
            comp.addMouseListener(mouseListener)
            listenerCount++

            // Рекурсивно обрабатываем все дочерние компоненты
            for (component in comp.components) {
                when (component) {
                    is JBTable -> {
                        // Пропускаем таблицу
                    }
                    is JButton -> {
                        // Пропускаем кнопки
                    }
                    is Container -> {
                        addListenersRecursive(component)
                    }
                    else -> {
                        // Добавляем обработчик к обычным компонентам
                        component.addMouseListener(mouseListener)
                        listenerCount++
                    }
                }
            }
        }

        addListenersRecursive(container)
        println("ADB_DEBUG: Added click listeners to $listenerCount components")
    }
    
    /**
     * Получает полный порядок всех пресетов из всех списков
     */
    private fun getAllPresetsOrder(): List<String> {
        val allPresets = mutableListOf<String>()
        tempListsManager.getTempLists().values.forEach { list ->
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
        println("ADB_DEBUG: loadPresetsIntoTableSync() - Start, presetsOverride: ${presetsOverride?.size}")
        println("ADB_DEBUG: dialogState.isShowAllPresetsMode(): ${dialogState.isShowAllPresetsMode()}")
        println("ADB_DEBUG: dialogState.isHideDuplicatesMode(): ${dialogState.isHideDuplicatesMode()}")
        
        // Используем TableLoader для загрузки пресетов с применением сортировки
        tableLoader.loadPresetsIntoTable(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempListsManager.getTempLists(),
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isFirstLoad = false,
            isSwitchingList = false,
            isSwitchingMode = false,
            isSwitchingDuplicatesFilter = false,
            onTableUpdating = { updating -> dialogState.setTableUpdating(updating) },
            onAddButtonRow = { addButtonRow() },
            inMemoryOrder = if (dialogState.isShowAllPresetsMode()) {
                inMemoryTableOrder.ifEmpty {
                    // Используем ту же логику, что и в основном методе
                    val savedOrder = PresetListService.getShowAllPresetsOrder()
                    if (savedOrder.isNotEmpty()) {
                        val convertedOrder = savedOrder.mapNotNull { key ->
                            val parts = key.split("::")
                            if (parts.size == 2) {
                                val listName = parts[0]
                                val presetId = parts[1]
                                tempListsManager.getTempLists().values.find { it.name == listName }?.presets?.find { it.id == presetId }
                                    ?.let { preset ->
                                        "${listName}::${preset.label}::${preset.size}::${preset.dpi}"
                                    }
                            } else null
                        }

                        convertedOrder.ifEmpty {
                            null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            },
            initialHiddenDuplicates = hiddenDuplicatesManager.getHiddenDuplicatesForTableLoader(),
            table = table,
            onClearTableSelection = {
                if (::hoverStateManager.isInitialized) {
                    hoverStateManager.clearTableSelection()
                }
            }
        )
        
        // Обновляем текущее состояние скрытых дублей после загрузки таблицы
        updateCurrentHiddenDuplicates()
        
        // Обновляем состояние трекера
        tableStateTracker.updateTableState(tableModel)
    }
    
    
    /**
     * Перезагружает таблицу с временным отключением слушателей для избежания рекурсии
     */
    private fun reloadTableWithoutListeners() {
        tableLoader.reloadTableWithoutListeners(
            table = table,
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempListsManager.getTempLists(),
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            onTableUpdating = { dialogState.setTableUpdating(it) },
            onAddButtonRow = ::addButtonRow,
            onSaveCurrentTableOrder = ::saveCurrentTableOrderToMemory,
            onClearLastInteractedRow = { 
                if (::hoverStateManager.isInitialized) {
                    hoverStateManager.updateLastInteractedRow(-1)
                }
            },
            tableModelListener = tableModelListener,
            inMemoryOrder = inMemoryTableOrder,
            initialHiddenDuplicates = hiddenDuplicatesManager.getInitialHiddenDuplicates()
        )
    }
    
    // === Реализация CommandContext ===
    
    override fun getCurrentPresetList(): PresetList? = currentPresetList
    
    override fun getTempPresetLists(): Map<String, PresetList> = tempListsManager.getTempLists()
    
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
        currentPresetList = list
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
        if (::hoverStateManager.isInitialized) {
            hoverStateManager.clearTableSelection()
        }
    }
    
    fun isTableInitialized(): Boolean = ::table.isInitialized
    
    fun isDuplicatingPreset(): Boolean = isDuplicatingPreset
    
    fun addTableModelListener() {
        tableModelListener?.let { tableModel.addTableModelListener(it) }
    }
    
    fun removeTableModelListener() {
        tableModelListener?.let { tableModel.removeTableModelListener(it) }
    }
    
    override fun switchToList(listId: String) {
        println("ADB_DEBUG: switchToList called with listId: $listId")
        
        // Находим список по ID
        val targetList = tempListsManager.getTempList(listId)
        if (targetList == null) {
            println("ADB_DEBUG: List with id $listId not found")
            return
        }
        
        // Сохраняем текущее состояние, если находимся не в режиме Show All
        if (!dialogState.isShowAllPresetsMode()) {
            syncTableChangesToTempListsInternal()
        }
        
        // Переключаемся на новый список
        currentPresetList = targetList
        
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
    
    override fun getTableSortingService(): TableSortingService? {
        return tableSortingService
    }
}