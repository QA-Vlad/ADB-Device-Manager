package io.github.qavlad.adbdevicemanager.ui.services

import com.intellij.openapi.project.Project
import io.github.qavlad.adbdevicemanager.ui.components.CommandHistoryManager
import io.github.qavlad.adbdevicemanager.ui.dialogs.DialogStateManager
import io.github.qavlad.adbdevicemanager.ui.dialogs.DialogComponentsFactory
import io.github.qavlad.adbdevicemanager.ui.dialogs.PresetsDialogController

/**
 * Service Locator для централизованного управления зависимостями в PresetsDialogController
 * Создаёт и предоставляет доступ ко всем необходимым сервисам
 */
class PresetsDialogServiceLocator(
    val project: Project?,
    getSelectedDevices: (() -> List<com.android.ddmlib.IDevice>)? = null,
    onPresetApplied: ((preset: io.github.qavlad.adbdevicemanager.services.DevicePreset, listName: String?, setSize: Boolean, setDpi: Boolean) -> Unit)? = null
) {
    // === Состояние и управление ===
    val dialogState = DialogStateManager()
    val tempListsManager = TempListsManager()
    lateinit var historyManager: CommandHistoryManager
    
    // === Менеджеры данных ===
    val duplicateManager = DuplicateManager()
    val presetRecycleBin = PresetRecycleBin()
    val presetOrderManager = PresetOrderManager()
    val snapshotManager = SnapshotManager(duplicateManager)
    val hiddenDuplicatesManager = HiddenDuplicatesManager()
    val stateManager = StateManager()
    val validationService = ValidationService()
    val settingsPersistenceService = PresetsPersistenceService()
    val countersStateManager = CountersStateManager(TableSortingService, dialogState)
    val globalListenersManager = GlobalListenersManager()
    
    // === Сервисы для работы с пресетами ===
    val presetDistributor = PresetDistributor(duplicateManager)
    val tableSynchronizer = TableDataSynchronizer(duplicateManager, presetDistributor)
    val viewModeManager = ViewModeManager(presetOrderManager)
    val presetOperationsService: PresetOperationsService
        get() = PresetOperationsService(historyManager, presetOrderManager)
    val tableLoader = TableLoader(viewModeManager, presetOrderManager, TableSortingService, dialogState)
    
    // === Сервисы для удаления и инициализации ===
    val presetDeletionService = PresetDeletionService(
        tempListsManager,
        presetRecycleBin,
        presetOrderManager,
        duplicateManager
    )
    
    val presetListInitializationService = PresetListInitializationService(
        tempListsManager,
        snapshotManager,
        presetOrderManager,
        hiddenDuplicatesManager,
        stateManager,
        duplicateManager
    )
    
    // === Сервисы для сохранения ===
    val presetSaveManager = PresetSaveManager(presetOrderManager, settingsPersistenceService)
    
    // === UI компоненты и фабрики ===
    val componentsFactory = DialogComponentsFactory()
    val componentInitService = ComponentInitializationService(
        project, 
        getSelectedDevices, 
        onPresetApplied,
        { 
            // В режиме Show All возвращаем специальное имя
            if (dialogState.isShowAllPresetsMode()) {
                "All presets"
            } else {
                io.github.qavlad.adbdevicemanager.services.PresetListService.getActivePresetList()?.name
            }
        },
        dialogState, 
        componentsFactory
    )
    
    // === Обработчики событий ===
    val tableDragDropHandler = TableDragDropHandler(
        dialogState,
        presetOrderManager,
        TableSortingService,
        tempListsManager
    )
    
    // === Расширенные загрузчики (инициализируются позже) ===
    private var _extendedTableLoader: ExtendedTableLoader? = null
    private var _hoverStateManager: HoverStateManager? = null
    private var _tableEventHandler: TableEventHandler? = null
    private var _tableColumnManager: TableColumnManager? = null
    private var _tableSortingController: TableSortingController? = null
    
    /**
     * Инициализирует ExtendedTableLoader после создания необходимых зависимостей
     */
    fun initializeExtendedTableLoader(hoverStateManager: HoverStateManager) {
        _hoverStateManager = hoverStateManager
        _extendedTableLoader = ExtendedTableLoader(
            tableLoader = tableLoader,
            dialogState = dialogState,
            hoverStateManager = hoverStateManager,
            hiddenDuplicatesManager = hiddenDuplicatesManager,
            tempListsManager = tempListsManager
        )
    }
    
    /**
     * Сохраняет ссылки на UI компоненты после их создания
     */
    fun setUIComponents(
        hoverStateManager: HoverStateManager,
        tableEventHandler: TableEventHandler,
        tableColumnManager: TableColumnManager
    ) {
        _hoverStateManager = hoverStateManager
        _tableEventHandler = tableEventHandler
        _tableColumnManager = tableColumnManager
        _tableSortingController = TableSortingController(dialogState, tableColumnManager)
    }
    
    // === Геттеры для компонентов, инициализируемых позже ===
    
    val extendedTableLoader: ExtendedTableLoader
        get() = _extendedTableLoader ?: throw IllegalStateException("ExtendedTableLoader not initialized")
    
    val hoverStateManager: HoverStateManager
        get() = _hoverStateManager ?: throw IllegalStateException("HoverStateManager not initialized")
    
    val tableEventHandler: TableEventHandler
        get() = _tableEventHandler ?: throw IllegalStateException("TableEventHandler not initialized")
    
    val tableSortingController: TableSortingController
        get() = _tableSortingController ?: throw IllegalStateException("TableSortingController not initialized")
    
    /**
     * Инициализирует CommandHistoryManager с контроллером
     */
    fun initializeHistoryManager(controller: PresetsDialogController) {
        historyManager = CommandHistoryManager(controller)
    }
    
    
    /**
     * Очищает ресурсы при закрытии диалога
     */
    fun dispose() {
        countersStateManager.dispose()
        presetListInitializationService.dispose()
        presetRecycleBin.clear()
        globalListenersManager.dispose()
    }
}