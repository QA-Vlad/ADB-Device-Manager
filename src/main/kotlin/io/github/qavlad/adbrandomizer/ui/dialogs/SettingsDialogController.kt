package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.services.DuplicateManager
import io.github.qavlad.adbrandomizer.ui.services.TempListsManager
import io.github.qavlad.adbrandomizer.ui.services.TableDataSynchronizer
import io.github.qavlad.adbrandomizer.ui.services.ViewModeManager
import io.github.qavlad.adbrandomizer.ui.services.PresetOperationsService
import io.github.qavlad.adbrandomizer.ui.services.TableEventHandler
import io.github.qavlad.adbrandomizer.ui.services.SnapshotManager
import io.github.qavlad.adbrandomizer.ui.services.ValidationService
import io.github.qavlad.adbrandomizer.ui.services.TableLoader
import io.github.qavlad.adbrandomizer.ui.services.StateManager
import io.github.qavlad.adbrandomizer.ui.services.PresetDistributor
import io.github.qavlad.adbrandomizer.ui.services.TableColumnManager
import io.github.qavlad.adbrandomizer.ui.services.SettingsPersistenceService
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.utils.ButtonUtils
import io.github.qavlad.adbrandomizer.utils.PresetUpdateUtils
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
class SettingsDialogController(
    private val project: Project?,
    private val dialog: SettingsDialog
) : CommandContext {
    // UI компоненты
    override lateinit var table: JBTable
        private set
    override lateinit var tableModel: DevicePresetTableModel
        private set
    lateinit var keyboardHandler: KeyboardHandler
        private set
    lateinit var tableConfigurator: TableConfigurator
        private set
    lateinit var validationRenderer: ValidationRenderer
        private set
    lateinit var listManagerPanel: PresetListManagerPanel
        private set
    private var tableWithButtonPanel: TableWithAddButtonPanel? = null

    // Состояние
    var hoverState = HoverState.noHover()
        private set
    override val historyManager = CommandHistoryManager(this)
    private val duplicateManager = DuplicateManager()
    private val tableSynchronizer = TableDataSynchronizer(duplicateManager)
    private val viewModeManager = ViewModeManager()
    private val presetOperationsService = PresetOperationsService(historyManager)
    private val tableEventHandler = TableEventHandler(project)
    private val snapshotManager = SnapshotManager(duplicateManager)
    private val validationService = ValidationService()
    private val tableLoader = TableLoader(duplicateManager, viewModeManager)
    private val stateManager = StateManager()
    private val presetDistributor = PresetDistributor(duplicateManager)
    private val settingsPersistenceService = SettingsPersistenceService()
    private val componentsFactory = DialogComponentsFactory()
    private val eventHandlersInitializer = EventHandlersInitializer(this)
    private val dialogState = DialogStateManager()
    private var updateListener: (() -> Unit)? = null
    private var globalClickListener: java.awt.event.AWTEventListener? = null
    private var currentPresetList: PresetList? = null
    
    // Менеджер колонок (инициализируется после создания tableConfigurator)
    private lateinit var tableColumnManager: TableColumnManager

    // Менеджер временных списков
    private val tempListsManager = TempListsManager()

    // Исходное состояние списков для отката при Cancel
    private val originalPresetLists = mutableMapOf<String, PresetList>()

    // Ссылка на слушатель модели, для временного отключения
    private var tableModelListener: javax.swing.event.TableModelListener? = null

    // Слушатель модели таблицы с таймером
    private var tableModelListenerWithTimer: TableModelListenerWithTimer? = null

    /**
     * Инициализация контроллера
     */
    fun initialize() {
        // Очищаем кэши при открытии диалога
        PresetListService.clearAllCaches()
        setupUpdateListener()
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
            onSetupTableColumns = { setupTableColumns() }
        )

        return listManagerPanel
    }

    /**
     * Создает модель таблицы с начальными данными
     */
    fun createTableModel(): DevicePresetTableModel {
        tableModel = componentsFactory.createTableModel(historyManager)
        // НЕ добавляем слушатель здесь, так как table еще не создана

        // НЕ загружаем список здесь, так как временные списки еще не созданы
        // currentPresetList будет установлен в initializeTempPresetLists

        return tableModel
    }

    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(model: DevicePresetTableModel): JBTable {
        table = componentsFactory.createTable(model, { hoverState }, dialogState, historyManager)
        return table
    }

    /**
     * Инициализирует обработчики и конфигураторы
     */
    fun initializeHandlers() {
        println("ADB_DEBUG: initializeHandlers called")

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
            }
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
            historyManager = historyManager,
            validateFields = ::validateFields,
            setEditingCellData = { oldValue, row, column ->
                dialogState.setEditingCell(row, column, oldValue)
            },
            onDuplicate = ::duplicatePreset,
            forceSyncBeforeHistory = ::forceSyncBeforeHistoryOperation
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
            }
        )

        tableConfigurator.configure()
        
        // Инициализируем менеджер колонок
        tableColumnManager = TableColumnManager(tableConfigurator)

        println("ADB_DEBUG: After tableConfigurator.configure() - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        // Загружаем данные после полной инициализации
        println("ADB_DEBUG: Before loadPresetsIntoTable in initializeHandlers - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        loadPresetsIntoTable()

        // Устанавливаем состояния чекбоксов после полной инициализации
        println("ADB_DEBUG: Setting checkbox states - showAll: ${dialogState.isShowAllPresetsMode()}, hideDuplicates: ${dialogState.isHideDuplicatesMode()}")
        listManagerPanel.setShowAllPresets(dialogState.isShowAllPresetsMode())
        listManagerPanel.setHideDuplicates(dialogState.isHideDuplicatesMode())

        // Сбрасываем флаг первой загрузки после небольшой задержки чтобы таблица успела полностью загрузиться
        SwingUtilities.invokeLater {
            dialogState.completeFirstLoad()
        }

        table.addKeyListener(keyboardHandler.createTableKeyListener())
        keyboardHandler.addGlobalKeyListener()
        validateFields()
    }

    /**
     * Настройка колонок таблицы в зависимости от режима
     */
    private fun setupTableColumns() {
        tableColumnManager.setupTableColumns(table, tableModel, dialogState.isShowAllPresetsMode())
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
        println("ADB_DEBUG: saveCurrentTableState - start, dialogState.isShowAllPresetsMode(): ${dialogState.isShowAllPresetsMode()}")
        println("ADB_DEBUG: saveCurrentTableState - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        if (tableModel.rowCount == 0 && currentPresetList?.presets?.isNotEmpty() == true) {
            println("ADB_DEBUG: skip saveCurrentTableState, table is empty but current list is not")
            return
        }

        if (dialogState.isShowAllPresetsMode()) {
            // В режиме "Show all presets" распределяем изменения по спискам
            presetDistributor.distributePresetsToTempLists(
                tableModel = tableModel,
                tempPresetLists = tempListsManager.getMutableTempLists(),
                isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
                getListNameAtRow = ::getListNameAtRow,
                saveVisiblePresetsSnapshotForAllLists = ::saveVisiblePresetsSnapshotForAllLists
            )
        } else {
            // В обычном режиме обновляем только текущий список
            currentPresetList?.let { list ->
                // Используем ту же логику, что и в syncTableChangesToTempLists
                if (dialogState.isHideDuplicatesMode()) {
                    // Используем ту же логику, что и в syncTableChangesToTempLists
                    val originalPresets = list.presets.map { it.copy() }

                    // Определяем какие индексы были видимы в таблице
                    val visibleIndices = findVisibleIndices(originalPresets)

                    // Получаем обновленные пресеты из таблицы
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    val updatedTablePresets = tableModel.getPresets()

                    // Создаем новый список, обновляя только видимые элементы
                    val newPresets = PresetUpdateUtils.updatePresetsWithVisibleIndices(originalPresets, visibleIndices, updatedTablePresets)

                    // Заменяем список
                    list.presets.clear()
                    list.presets.addAll(newPresets)
                } else {
                    // В обычном режиме без скрытия дубликатов - просто заменяем все
                    list.presets.clear()
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    list.presets.addAll(tableModel.getPresets())
                }
            }
        }
    }

    /**
     * Принудительная синхронизация перед операциями истории
     */
    fun forceSyncBeforeHistoryOperation() {
        tableModelListenerWithTimer?.forceSyncPendingUpdates()
    }
    
    /**
     * Синхронизирует изменения из таблицы с временными списками после drag and drop
     * Используется только для обновления порядка элементов без перезагрузки таблицы
     */
    private fun syncTableChangesToTempListsAfterDragDrop() {
        if (!dialogState.isShowAllPresetsMode() || !dialogState.isHideDuplicatesMode()) {
            return
        }
        
        println("ADB_DEBUG: syncTableChangesToTempListsAfterDragDrop - updating order from table")
        
        // Собираем текущий порядок из таблицы
        val tableOrder = mutableListOf<Pair<String, DevicePreset>>()
        for (i in 0 until tableModel.rowCount) {
            val listName = getListNameAtRow(i) ?: continue
            val preset = getPresetAtRow(i)
            tableOrder.add(listName to preset)
        }
        
        // Обновляем порядок в tempPresetLists
        tempListsManager.getTempLists().values.forEach { list ->
            // Собираем пресеты этого списка из таблицы в правильном порядке
            val presetsFromTable = tableOrder
                .filter { it.first == list.name }
                .map { it.second }
            
            // Также нужно сохранить скрытые дубликаты
            val visibleKeys = presetsFromTable.map { "${it.label}|${it.size}|${it.dpi}" }.toSet()
            val hiddenPresets = list.presets.filter { preset ->
                val key = "${preset.label}|${preset.size}|${preset.dpi}"
                !visibleKeys.contains(key)
            }
            
            // Обновляем список: сначала видимые в новом порядке, потом скрытые
            list.presets.clear()
            list.presets.addAll(presetsFromTable.map { it.copy() })
            list.presets.addAll(hiddenPresets)
            
            println("ADB_DEBUG:   Updated list ${list.name} with ${presetsFromTable.size} visible + ${hiddenPresets.size} hidden presets")
        }
    }
    
    /**
     * Синхронизирует изменения из таблицы с временными списками
     */
    private fun syncTableChangesToTempListsInternal() {
        println("ADB_DEBUG: syncTableChangesToTempLists called")
        println("ADB_DEBUG: Current call stack:")
        Thread.currentThread().stackTrace.take(10).forEach { element ->
            println("ADB_DEBUG:   at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
        }
        
        val context = TableDataSynchronizer.SyncContext(
            isTableUpdating = dialogState.isTableUpdating(),
            isSwitchingMode = dialogState.isSwitchingMode(),
            isSwitchingList = dialogState.isSwitchingList(),
            isSwitchingDuplicatesFilter = dialogState.isSwitchingDuplicatesFilter(),
            isPerformingHistoryOperation = dialogState.isPerformingHistoryOperation(),
            isFirstLoad = dialogState.isFirstLoad(),
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode()
        )
        
        val syncCheck = tableSynchronizer.canSync(context)
        if (!syncCheck.canSync) {
            println("ADB_DEBUG: syncTableChangesToTempLists: ${syncCheck.reason}, skip")
            return
        }

        println("ADB_DEBUG: syncTableChangesToTempLists - start, dialogState.isShowAllPresetsMode(): ${dialogState.isShowAllPresetsMode()}")
        println("ADB_DEBUG: syncTableChangesToTempLists - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempListsManager.getTempLists().forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }

        // Не синхронизируем при отключении фильтра дубликатов, если количество строк в таблице меньше количества пресетов
        // Это означает, что в таблице показаны только видимые пресеты, а синхронизация испортит скрытые
        if (!dialogState.isHideDuplicatesMode() && currentPresetList != null) {
            val tablePresetCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }
            if (tablePresetCount < currentPresetList!!.presets.size) {
                println("ADB_DEBUG: skip sync - filter just disabled, table has $tablePresetCount rows but list has ${currentPresetList!!.presets.size} presets")
                return
            }
        }

        if (dialogState.isShowAllPresetsMode()) {
            presetDistributor.distributePresetsToTempLists(
                tableModel = tableModel,
                tempPresetLists = tempListsManager.getMutableTempLists(),
                isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
                getListNameAtRow = ::getListNameAtRow,
                saveVisiblePresetsSnapshotForAllLists = if (dialogState.isDragAndDropInProgress()) {
                    // Не создаем новый снимок во время drag and drop
                    {}
                } else {
                    ::saveVisiblePresetsSnapshotForAllLists
                }
            )
        } else {
            currentPresetList?.let { list ->
                // Всегда используем tableSynchronizer для корректной синхронизации
                tableSynchronizer.syncCurrentList(tableModel, list, context) {
                    loadPresetsIntoTable()
                }
            }
        }
        
        println("ADB_DEBUG: syncTableChangesToTempLists - after, currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempListsManager.getTempLists().forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }

        // Проверяем, нужно ли обновить таблицу из-за изменения статуса дублей
        if (dialogState.isHideDuplicatesMode() && !dialogState.isFirstLoad() && !dialogState.isSwitchingDuplicatesFilter()) {
            // В режиме Show all всегда проверяем изменение статуса дублей
            // так как дубликаты могут быть в разных списках
            // Также всегда обновляем если был вызван distributePresetsToTempLists (в режиме Show all)
            // НО не во время drag and drop операции
            if (dialogState.isShowAllPresetsMode()) {
                println("ADB_DEBUG: In Show all mode with hide duplicates, dialogState.isDragAndDropInProgress() = ${dialogState.isDragAndDropInProgress()}")
                if (!dialogState.isDragAndDropInProgress()) {
                    println("ADB_DEBUG: Reloading table after sync")
                    // Перезагружаем таблицу для отображения изменений
                    reloadTableWithoutListeners()
                } else {
                    println("ADB_DEBUG: Skipping table reload - drag and drop in progress")
                    // Флаг будет сброшен в конце метода
                }
            } else if (checkIfDuplicateStatusChanged()) {
                println("ADB_DEBUG: Duplicate status changed after edit, reloading table")
                // Перезагружаем таблицу для отображения изменений
                reloadTableWithoutListeners()
            }
        }

        // Обновляем снимок видимых пресетов после синхронизации
        if (dialogState.isHideDuplicatesMode()) {
            saveVisiblePresetsSnapshot()
        }

        // Сбрасываем флаг drag and drop после всех операций
        if (dialogState.isDragAndDropInProgress()) {
            // Сохраняем состояние после операции для режима Hide Duplicates
            if (dialogState.isHideDuplicatesMode()) {
                val lastCommand = historyManager.getLastCommand()
                if (lastCommand is io.github.qavlad.adbrandomizer.ui.commands.PresetMoveCommand) {
                    lastCommand.saveStateAfter()
                }
            }
            
            SwingUtilities.invokeLater {
                dialogState.endDragAndDrop()
            }
        }
    }

    /**
     * Проверяет, изменился ли статус дублей после редактирования
     * @return true если статус изменился (появились новые не-дубли или исчезли старые дубли)
     */
    private fun checkIfDuplicateStatusChanged(): Boolean {
        if (dialogState.isShowAllPresetsMode()) {
            // В режиме Show all проверяем все пресеты из всех списков
            val allPresets = mutableListOf<DevicePreset>()
            tempListsManager.getTempLists().values.forEach { list ->
                allPresets.addAll(list.presets)
            }

            // Проверяем, есть ли в таблице элементы, которые должны быть скрыты как дубликаты
            val seenKeys = mutableSetOf<String>()
            for (row in 0 until tableModel.rowCount) {
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                if (firstColumn != "+") {
                    val preset = tableModel.getPresetAt(row)
                    if (preset != null && preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        val key = createPresetKey(preset)
                        if (seenKeys.contains(key)) {
                            // Найден дубликат в таблице, который должен быть скрыт
                            println("ADB_DEBUG: Found duplicate in table that should be hidden (Show all mode): $key")
                            return true
                        }
                        seenKeys.add(key)
                    }
                }
            }

            // Также проверяем количество видимых строк
            val currentDuplicates = findDuplicatesInList(allPresets)
            val visibleRowCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }

            // Если количество не-дублей изменилось, нужно обновить таблицу
            val expectedVisibleCount = allPresets.size - currentDuplicates.size
            return visibleRowCount != expectedVisibleCount
        } else {
            // В обычном режиме проверяем только текущий список
            currentPresetList?.let { list ->
                // Получаем текущие дубликаты в списке
                val currentDuplicates = findDuplicatesInList(list.presets)
                
                // Проверяем, есть ли в таблице элементы, которые должны быть скрыты как дубликаты
                val seenKeys = mutableSetOf<String>()
                for (row in 0 until tableModel.rowCount) {
                    val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                    if (firstColumn != "+") {
                        val preset = tableModel.getPresetAt(row)
                        if (preset != null && preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                            val key = createPresetKey(preset)
                            if (seenKeys.contains(key)) {
                                // Найден дубликат в таблице, который должен быть скрыт
                                println("ADB_DEBUG: Found duplicate in table that should be hidden: $key")
                                return true
                            }
                            seenKeys.add(key)
                        }
                    }
                }

                // Также проверяем количество видимых строк
                val visibleRowCount = (0 until tableModel.rowCount).count { row ->
                    val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                    firstColumn != "+"
                }

                // Если количество не-дублей изменилось, нужно обновить таблицу
                val expectedVisibleCount = list.presets.size - currentDuplicates.size
                return visibleRowCount != expectedVisibleCount
            }
        }
        return false
    }

    /**
     * Находит дубликаты в списке пресетов
     * @return количество дубликатов (не считая первое вхождение)
     */
    private fun findDuplicatesInList(presets: List<DevicePreset>): Set<Int> {
        return duplicateManager.findDuplicateIndices(presets)
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

            // В режиме Hide Duplicates нужно найти правильный индекс в целевом списке
            if (dialogState.isHideDuplicatesMode()) {
                // Находим все пресеты в списке, которые соответствуют удаляемому
                val matchingIndices = targetList.presets.mapIndexedNotNull { index, p ->
                    if (p.size == preset.size && p.dpi == preset.dpi) index else null
                }
                
                // Удаляем первый найденный пресет с такими size и dpi
                if (matchingIndices.isNotEmpty()) {
                    val indexToRemove = matchingIndices.first()
                    val removedPreset = targetList.presets.removeAt(indexToRemove)
                    println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from list $listName at index $indexToRemove (hide duplicates mode)")
                    return Pair(true, indexToRemove)
                }
                return Pair(false, null)
            }

            // В обычном режиме удаляем по точному совпадению
            val removed = targetList.presets.removeIf {
                it.label == preset.label &&
                        it.size == preset.size &&
                        it.dpi == preset.dpi
            }
            println("ADB_DEBUG: deletePresetFromTempList - removed from list $listName: $removed")
            return Pair(removed, null)
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
                        return Pair(true, actualIndex)
                    }
                    return Pair(false, null)
                }
                
                // В обычном режиме удаляем по индексу, чтобы не удалить несколько одинаковых
                if (row >= 0 && row < currentPresetList!!.presets.size) {
                    val removedPreset = currentPresetList!!.presets.removeAt(row)
                    println("ADB_DEBUG: deletePresetFromTempList - removed preset '${removedPreset.label}' from current list at index $row")
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

        // Проверяем, что дефолтные списки существуют
        PresetListService.ensureDefaultListsExist()

        tempListsManager.clear()
        originalPresetLists.clear()
        duplicateManager.clearSnapshots()
        
        // Загружаем все списки через StateManager
        val loadedLists = stateManager.initializeTempPresetLists()
        tempListsManager.setTempLists(loadedLists)
        
        // Создаем копии для отката
        originalPresetLists.putAll(snapshotManager.saveOriginalState(tempListsManager.getTempLists()))
        
        // Определяем начальный текущий список
        currentPresetList = stateManager.determineInitialCurrentList(tempListsManager.getTempLists())
        
        println("ADB_DEBUG: initializeTempPresetLists - done. Current list: ${currentPresetList?.name}, temp lists count: ${tempListsManager.size()}")
    }

    /**
     * Загружает пресеты в таблицу в зависимости от текущего режима
     */
    private fun loadPresetsIntoTable() {
        tableLoader.loadPresetsIntoTable(
            tableModel = tableModel,
            currentPresetList = currentPresetList,
            tempPresetLists = tempListsManager.getTempLists(),
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            isFirstLoad = dialogState.isFirstLoad(),
            isSwitchingList = dialogState.isSwitchingList(),
            isSwitchingMode = dialogState.isSwitchingMode(),
            isSwitchingDuplicatesFilter = dialogState.isSwitchingDuplicatesFilter(),
            onTableUpdating = { updating -> dialogState.setTableUpdating(updating) },
            onAddButtonRow = { addButtonRow() }
        )
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
            tableEventHandler.handleCellClick(
                table = table,
                tableModel = tableModel,
                row = row,
                column = column,
                clickCount = clickCount,
                onApplyPreset = { preset, setSize, setDpi ->
                    if (project != null) {
                        PresetApplicationService.applyPreset(project, preset, setSize, setDpi)
                        refreshTableInternal()
                    }
                },
                onAddNewPreset = { addNewPreset() }
            )
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
        println("ADB_DEBUG: handleTableExit called")
        val oldHoverState = hoverState
        hoverState = hoverState.clearTableHover()
        println("ADB_DEBUG: Cleared hover state")

        if (oldHoverState.hoveredTableRow >= 0 && oldHoverState.hoveredTableColumn >= 0) {
            val oldRect = table.getCellRect(oldHoverState.hoveredTableRow, oldHoverState.hoveredTableColumn, false)
            table.repaint(oldRect)
        }
    }

    fun onRowMoved(fromIndex: Int, toIndex: Int) {
        val orderAfter = tableModel.getPresets().map { it.label }

        historyManager.addPresetMove(fromIndex, toIndex, orderAfter)
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

        // В режиме Show all нужно сохранить новый порядок для правильной работы
        if (dialogState.isShowAllPresetsMode() && !dialogState.isHideDuplicatesMode()) {
            settingsPersistenceService.saveShowAllPresetsOrder(tableModel)
            
            // Важно: после drag and drop нужно обновить порядок в tempPresetLists
            // чтобы снимок создавался с правильным порядком
            if (dialogState.isHideDuplicatesMode()) {
                // Синхронизируем tempPresetLists с актуальным состоянием таблицы
                syncTableChangesToTempListsAfterDragDrop()
                // Теперь создаем снимок с правильным порядком
                saveVisiblePresetsSnapshotForAllLists()
            }
        }
        
        // Не вызываем refreshTableInternal() так как перемещение уже выполнено в таблице
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
                    hoverState = hoverState.withTableSelection(newRowIndex, 2)
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
        val duplicated = presetOperationsService.duplicatePreset(
            row = row,
            tableModel = tableModel,
            table = table,
            isShowAllMode = dialogState.isShowAllPresetsMode(),
            isHideDuplicatesMode = dialogState.isHideDuplicatesMode(),
            onDuplicatesFilterToggle = { enabled ->
                listManagerPanel.setHideDuplicates(enabled)
            }
        )
        
        if (duplicated) {
            SwingUtilities.invokeLater {
                val insertIndex = row + 1
                table.scrollRectToVisible(table.getCellRect(insertIndex, 0, true))
                hoverState = hoverState.withTableSelection(insertIndex, 2)
                table.repaint()
            }
        }
    }

    // === Валидация ===

    fun validateFields() {
        // Проверяем, что table инициализирована
        if (!::table.isInitialized || !::tableModel.isInitialized) {
            return
        }

        val allValid = validationService.validateAllFields(tableModel)
        dialog.isOKActionEnabled = allValid
        table.repaint()
    }

    // === Сохранение и загрузка ===

    fun saveSettings() {
        settingsPersistenceService.saveSettings(
            table = table,
            tempLists = tempListsManager.getTempLists(),
            isShowAllPresetsMode = dialogState.isShowAllPresetsMode(),
            onSaveCurrentTableState = { saveCurrentTableState() },
            onSaveShowAllPresetsOrder = { 
                // Не сохраняем порядок если включен режим скрытия дубликатов
                if (!dialogState.isHideDuplicatesMode()) {
                    settingsPersistenceService.saveShowAllPresetsOrder(tableModel)
                }
            }
        )
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
        // Проверяем, не является ли это строкой с кнопкой
        if (row >= 0 && row < tableModel.rowCount) {
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            if (firstColumn == "+") {
                // Возвращаем пустой пресет для строки с кнопкой
                return DevicePreset("", "", "")
            }
        }

        return DevicePreset(
            label = tableModel.getValueAt(row, 2) as String,
            size = tableModel.getValueAt(row, 3) as String,
            dpi = tableModel.getValueAt(row, 4) as String
        )
    }

    private fun getListNameAtRow(row: Int): String? {
        // В режиме Show all presets получаем название списка из последней колонки
        if (dialogState.isShowAllPresetsMode() && row >= 0 && row < tableModel.rowCount && tableModel.columnCount > 6) {
            val value = tableModel.getValueAt(row, 6)
            return value as? String
        }
        return null
    }


    private fun refreshTableInternal() {
        SwingUtilities.invokeLater {
            validateFields()
            table.repaint()
        }
    }

    /**
     * Сохраняет текущий порядок пресетов в режиме Show all presets
     */

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

    /**
     * Восстанавливает исходное состояние временных списков при отмене
     */
    fun restoreOriginalState() {
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

    fun dispose() {
        updateListener?.let { SettingsDialogUpdateNotifier.removeListener(it) }
        keyboardHandler.removeGlobalKeyListener()

        // Удаляем глобальный обработчик кликов
        globalClickListener?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
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
        
        dialogState.withTableUpdate {
            // Очищаем таблицу
            while (tableModel.rowCount > 0) {
                tableModel.removeRow(0)
            }

            if (dialogState.isShowAllPresetsMode()) {
                // Для режима Show All загружаем все пресеты синхронно
                loadAllPresetsSync()
            } else {
                currentPresetList?.let { currentList ->
                    // Используем данные из tempPresetLists, а не из currentPresetList
                    val listFromTemp = tempListsManager.getTempList(currentList.id)
                    if (listFromTemp != null) {
                        val sourcePresets = presetsOverride ?: listFromTemp.presets
                        println("ADB_DEBUG: Loading presets from temp list '${listFromTemp.name}' with ${sourcePresets.size} presets")
                        
                        val presetsToLoad = if (dialogState.isHideDuplicatesMode()) {
                            // Фильтруем дубликаты, оставляя только первые вхождения
                            val seenKeys = mutableSetOf<String>()
                            var filteredCount = 0
                            val filtered = sourcePresets.filter { preset ->
                                val key = createPresetKey(preset)
                                if (preset.size.isBlank() || preset.dpi.isBlank() || !seenKeys.contains(key)) {
                                    seenKeys.add(key)
                                    true
                                } else {
                                    filteredCount++
                                    println("ADB_DEBUG: Filtering duplicate: ${preset.label} ($key)")
                                    false
                                }
                            }
                            println("ADB_DEBUG: Filtered $filteredCount duplicates, ${filtered.size} presets remain")
                            filtered
                        } else {
                            listFromTemp.presets
                        }

                        presetsToLoad.forEach { preset ->
                            val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1)
                            tableModel.addRow(rowData)
                        }
                        
                        println("ADB_DEBUG: Added ${presetsToLoad.size} rows to table")
                    } else {
                        println("ADB_DEBUG: WARNING - List not found in tempPresetLists!")
                    }
                }
            }

            // Добавляем строку с кнопкой "+"
            if (!dialogState.isShowAllPresetsMode()) {
                addButtonRow()
            }

            // Обновляем номера строк
            tableModel.updateRowNumbers()

            // Принудительно обновляем UI таблицы
            table.repaint()
            table.revalidate()
        }
    }
    
    /**
     * Синхронно загружает все пресеты для режима Show All
     */
    private fun loadAllPresetsSync() {
        // Получаем сохраненный порядок
        val savedOrder = PresetListService.getShowAllPresetsOrder()
        val allPresets = viewModeManager.prepareShowAllTableModel(tempListsManager.getTempLists(), savedOrder)
        
        if (dialogState.isHideDuplicatesMode()) {
            // Загружаем с учетом скрытых дубликатов
            val duplicateKeys = findGlobalDuplicateKeys(allPresets)
            val processedKeys = mutableSetOf<String>()
            
            allPresets.forEach { presetPair ->
                val listName = presetPair.first
                val preset = presetPair.second
                val key = createPresetKey(preset)
                val isEmptyPreset = preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
                
                val shouldSkip = when {
                    isEmptyPreset -> false
                    key in duplicateKeys && key in processedKeys -> true
                    else -> false
                }
                
                if (!shouldSkip) {
                    if (key in duplicateKeys) {
                        processedKeys.add(key)
                    }
                    val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1)
                    rowData.add(listName)
                    tableModel.addRow(rowData)
                }
            }
        } else {
            // Загружаем все пресеты
            allPresets.forEach { presetPair ->
                val listName = presetPair.first
                val preset = presetPair.second
                val rowData = DevicePresetTableModel.createRowVector(preset, tableModel.rowCount + 1)
                rowData.add(listName)
                tableModel.addRow(rowData)
            }
        }
        
        // Обновляем номера строк
        tableModel.updateRowNumbers()
    }
    
    /**
     * Находит глобальные дубликаты во всех списках
     */
    private fun findGlobalDuplicateKeys(allPresets: List<Pair<String, DevicePreset>>): Set<String> {
        val keyCount = mutableMapOf<String, Int>()
        
        allPresets.forEach { presetPair ->
            val preset = presetPair.second
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = createPresetKey(preset)
                keyCount[key] = keyCount.getOrDefault(key, 0) + 1
            }
        }
        
        return keyCount.filter { it.value > 1 }.keys.toSet()
    }
    
    /**
     * Создает уникальный ключ для пресета на основе размера и DPI
     */
    private fun createPresetKey(preset: DevicePreset, index: Int? = null): String {
        return if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
            "${preset.size}|${preset.dpi}"
        } else {
            "unique_${index ?: System.currentTimeMillis()}"
        }
    }
    
    /**
     * Находит видимые индексы пресетов, исключая дубликаты
     */
    private fun findVisibleIndices(presets: List<DevicePreset>): List<Int> {
        val visibleIndices = mutableListOf<Int>()
        val seenKeys = mutableSetOf<String>()
        
        presets.forEachIndexed { index, preset ->
            val key = createPresetKey(preset, index)
            
            if (!seenKeys.contains(key)) {
                seenKeys.add(key)
                visibleIndices.add(index)
            }
        }
        
        return visibleIndices
    }
    
    /**
     * Перезагружает таблицу с временным отключением слушателей для избежания рекурсии
     */
    private fun reloadTableWithoutListeners() {
        SwingUtilities.invokeLater {
            // Временно отключаем слушатель модели, чтобы избежать рекурсии
            tableModelListener?.let { tableModel.removeTableModelListener(it) }

            dialogState.withTableUpdate {
                loadPresetsIntoTable()
            }
            
            // Возвращаем слушатель на место
            tableModelListener?.let { tableModel.addTableModelListener(it) }
        }
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
        if (dialogState.isShowAllPresetsMode()) {
            syncTableChangesToTempListsInternal()
        } else {
            tempListsManager.syncTableChangesToTempLists(tableModel, currentPresetList, false)
        }
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
    
    fun isTableInitialized(): Boolean = ::table.isInitialized
    
    fun addTableModelListener() {
        tableModelListener?.let { tableModel.addTableModelListener(it) }
    }
    
    fun removeTableModelListener() {
        tableModelListener?.let { tableModel.removeTableModelListener(it) }
    }
}