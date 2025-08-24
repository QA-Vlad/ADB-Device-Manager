package io.github.qavlad.adbrandomizer.ui.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.table.JBTable
import io.github.qavlad.adbrandomizer.services.DeviceStateService
import io.github.qavlad.adbrandomizer.services.PresetListService
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.ui.components.DevicePresetTableModel
import io.github.qavlad.adbrandomizer.ui.components.TableConfigurator
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogComponentsFactory
import io.github.qavlad.adbrandomizer.ui.dialogs.DialogStateManager
import io.github.qavlad.adbrandomizer.ui.dialogs.TableModelListenerWithTimer
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.handlers.DialogNavigationHandler
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.ui.components.HoverState
import io.github.qavlad.adbrandomizer.ui.components.CommandHistoryManager
import io.github.qavlad.adbrandomizer.services.DevicePreset
import kotlinx.coroutines.runBlocking
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Сервис для инициализации компонентов диалога пресетов.
 * Выделяет логику создания и настройки компонентов из контроллера.
 */
class ComponentInitializationService(
    private val project: Project?,
    private val getSelectedDevices: (() -> List<com.android.ddmlib.IDevice>)? = null,
    private val onPresetApplied: ((preset: DevicePreset, listName: String?) -> Unit)? = null,
    private val getCurrentListName: (() -> String?)? = null,
    private val dialogState: DialogStateManager,
    private val componentsFactory: DialogComponentsFactory
) {
    
    /**
     * Результат инициализации таблицы
     */
    data class TableInitResult(
        val table: JBTable,
        val hoverStateManager: HoverStateManager,
        val tableEventHandler: TableEventHandler
    )
    
    /**
     * Результат инициализации обработчиков
     */
    data class HandlersInitResult(
        val keyboardHandler: KeyboardHandler,
        val dialogNavigationHandler: DialogNavigationHandler,
        val tableConfigurator: TableConfigurator,
        val validationRenderer: ValidationRenderer,
        val tableColumnManager: TableColumnManager,
        val tableModelListenerWithTimer: TableModelListenerWithTimer?
    )
    
    /**
     * Выполняет начальную инициализацию
     */
    fun performInitialSetup() {
        // Очищаем кэши при открытии диалога
        PresetListService.clearAllCaches()
        
        // Обновляем состояния устройств если нужно
        if (project != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                runBlocking {
                    DeviceStateService.refreshDeviceStatesAsync()
                }
            }
        }
    }
    
    /**
     * Создает модель таблицы
     */
    fun createTableModel(historyManager: CommandHistoryManager): DevicePresetTableModel {
        val showCounters = PresetStorageService.getShowCounters()
        return componentsFactory.createTableModel(historyManager, showCounters)
    }
    
    /**
     * Создает и инициализирует таблицу
     */
    fun createAndInitializeTable(
        model: DevicePresetTableModel,
        historyManager: CommandHistoryManager,
        getHoverState: () -> HoverState,
        onHoverStateChanged: (HoverState) -> Unit
    ): TableInitResult {
        // Создаем таблицу
        val table = componentsFactory.createTable(
            model = model,
            hoverStateProvider = getHoverState,
            dialogState = dialogState,
            historyManager = historyManager,
            onLastInteractedRowUpdate = { _ ->
                // Будет обновлено после создания HoverStateManager
            }
        )
        
        // Создаем HoverStateManager
        val hoverStateManager = HoverStateManager(table, onHoverStateChanged)
        
        // Обработчик lastInteractedRow уже установлен через EditingCallbacks в createTable
        
        // Создаем TableEventHandler
        val tableEventHandler = TableEventHandler(
            project = project,
            getHoverState = getHoverState,
            setHoverState = { newState -> hoverStateManager.setState(newState) },
            getSelectedDevices = getSelectedDevices,
            onPresetApplied = onPresetApplied,
            getCurrentListName = getCurrentListName
        )
        
        return TableInitResult(table, hoverStateManager, tableEventHandler)
    }
    
    /**
     * Инициализирует обработчики для таблицы
     */
    fun initializeHandlers(
        table: JBTable,
        tableModel: DevicePresetTableModel,
        hoverStateManager: HoverStateManager,
        historyManager: CommandHistoryManager,
        getHoverState: () -> HoverState,
        getPresetAtRow: (Int) -> DevicePreset,
        onValidateFields: () -> Unit,
        onCellClicked: (Int, Int, Int) -> Unit,
        onRowMoved: (Int, Int) -> Unit,
        onShowContextMenu: (MouseEvent) -> Unit,
        onDuplicate: (Int) -> Unit,
        onDeleteFromEditor: (Int) -> Unit,
        onSyncTableChanges: () -> Unit,
        onDragStarted: () -> Unit,
        onDragEnded: () -> Unit,
        onForceSyncBeforeHistory: () -> Unit
    ): HandlersInitResult {
        
        // Создаем ValidationRenderer
        val validationRenderer = ValidationRenderer(
            hoverState = getHoverState,
            getPresetAtRow = getPresetAtRow,
            findDuplicates = { tableModel.findDuplicates() },
            validationService = ValidationService()
        )
        
        // Создаем KeyboardHandler
        val keyboardHandler = KeyboardHandler(
            table = table,
            tableModel = tableModel,
            hoverState = getHoverState,
            setHoverState = { newState -> hoverStateManager.setState(newState) },
            historyManager = historyManager,
            validateFields = onValidateFields,
            setEditingCellData = { oldValue, row, column ->
                dialogState.setEditingCell(row, column, oldValue)
                hoverStateManager.updateLastInteractedRow(row)
            },
            onDuplicate = onDuplicate,
            forceSyncBeforeHistory = onForceSyncBeforeHistory
        )
        
        // Создаем DialogNavigationHandler
        val dialogNavigationHandler = DialogNavigationHandler(
            table = table,
            setTableFocus = { table.requestFocusInWindow() },
            clearTableSelection = { 
                hoverStateManager.clearTableSelection()
            },
            selectFirstCell = {
                hoverStateManager.selectFirstLabelCell()
            }
        )
        
        // Создаем TableConfigurator
        val tableConfigurator = TableConfigurator(
            table = table,
            hoverState = getHoverState,
            setHoverState = { newState -> hoverStateManager.setState(newState) },
            onRowMoved = onRowMoved,
            onCellClicked = onCellClicked,
            onTableExited = { hoverStateManager.handleTableExit() },
            validationRenderer = validationRenderer,
            showContextMenu = onShowContextMenu,
            isShowAllPresetsMode = { dialogState.isShowAllPresetsMode() },
            onPresetDeletedFromEditor = onDeleteFromEditor,
            onDragStarted = onDragStarted,
            onDragEnded = onDragEnded,
            getLastInteractedRow = { hoverStateManager.getLastInteractedRow() }
        )
        
        // Настраиваем таблицу
        tableConfigurator.configure()
        
        // Создаем TableColumnManager
        val tableColumnManager = TableColumnManager(
            tableConfigurator,
            { dialogState.isShowAllPresetsMode() },
            { dialogState.isHideDuplicatesMode() },
            { PresetStorageService.getShowCounters() }
        )
        
        // Создаем слушатель модели таблицы
        val tableModelListenerWithTimer = componentsFactory.createTableModelListener(
            dialogState = dialogState,
            historyManager = historyManager,
            onValidateFields = onValidateFields,
            onSyncTableChanges = onSyncTableChanges,
            onTableRepaint = { 
                SwingUtilities.invokeLater {
                    table.repaint()
                }
            },
            controller = null // Будет установлен позже через updateController
        )
        
        return HandlersInitResult(
            keyboardHandler = keyboardHandler,
            dialogNavigationHandler = dialogNavigationHandler,
            tableConfigurator = tableConfigurator,
            validationRenderer = validationRenderer,
            tableColumnManager = tableColumnManager,
            tableModelListenerWithTimer = tableModelListenerWithTimer
        )
    }
    
    /**
     * Настраивает обработчик кликов по заголовкам таблицы
     */
    fun setupTableHeaderClickListener(
        table: JBTable,
        onHeaderClick: (Int) -> Unit
    ) {
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val columnIndex = table.columnModel.getColumnIndexAtX(e.x)
                if (columnIndex < 0) return
                
                println("ADB_DEBUG: Header clicked - columnIndex: $columnIndex")
                
                // Проверяем, является ли колонка сортируемой
                val hasCounters = PresetStorageService.getShowCounters()
                val listColumnIndex = if (hasCounters) 8 else 6
                
                val isSortable = when (columnIndex) {
                    2, 3, 4 -> true // Label, Size, DPI
                    5, 6 -> hasCounters // Size Uses, DPI Uses только когда счетчики включены
                    listColumnIndex -> dialogState.isShowAllPresetsMode() // List только в режиме Show All
                    else -> false
                }
                
                println("ADB_DEBUG: Header clicked - isSortable: $isSortable, hasCounters: $hasCounters")
                
                if (isSortable) {
                    onHeaderClick(columnIndex)
                }
            }
        })
    }
    
}