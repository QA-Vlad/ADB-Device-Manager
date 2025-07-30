package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import io.github.qavlad.adbrandomizer.services.PresetStorageService
import io.github.qavlad.adbrandomizer.services.UsageCounterService
import com.google.gson.Gson

/**
 * Сервис для управления сортировкой таблицы пресетов
 */
object TableSortingService {
    
    /**
     * Типы сортировки
     */
    enum class SortType {
        NONE,
        LABEL_ASC,
        LABEL_DESC,
        SIZE_DESC,  // По убыванию суммы (сначала большие)
        SIZE_ASC,   // По возрастанию суммы (сначала маленькие)
        DPI_DESC,   // По убыванию (сначала большие)
        DPI_ASC,    // По возрастанию (сначала маленькие)
        SIZE_USES_DESC,  // По убыванию использований размера
        SIZE_USES_ASC,   // По возрастанию использований размера
        DPI_USES_DESC,   // По убыванию использований DPI
        DPI_USES_ASC,    // По возрастанию использований DPI
        LIST_ASC,
        LIST_DESC
    }
    
    /**
     * Состояние сортировки для каждого режима
     */
    data class SortState(
        var labelSort: SortType = SortType.NONE,
        var sizeSort: SortType = SortType.NONE,
        var dpiSort: SortType = SortType.NONE,
        var sizeUsesSort: SortType = SortType.NONE,
        var dpiUsesSort: SortType = SortType.NONE,
        var listSort: SortType = SortType.NONE,
        var activeColumn: String? = null
    )
    
    private const val NORMAL_MODE_SORT_STATE = "NORMAL_MODE_SORT_STATE"
    private const val SHOW_ALL_SORT_STATE = "SHOW_ALL_SORT_STATE"
    private const val SHOW_ALL_HIDE_DUP_SORT_STATE = "SHOW_ALL_HIDE_DUP_SORT_STATE"
    
    private val gson = Gson()
    
    // Снимок состояний сортировки для отката при Cancel
    private var sortStateSnapshot: Map<String, SortState>? = null
    
    // Состояния сортировки для разных режимов
    private val normalModeSortState = SortState()
    private val showAllModeSortState = SortState()
    private val showAllHideDupSortState = SortState()
    
    init {
        println("ADB_DEBUG: TableSortingService init")
        println("ADB_DEBUG:   normalModeSortState id: ${System.identityHashCode(normalModeSortState)}")
        println("ADB_DEBUG:   showAllModeSortState id: ${System.identityHashCode(showAllModeSortState)}")
        println("ADB_DEBUG:   showAllHideDupSortState id: ${System.identityHashCode(showAllHideDupSortState)}")
        loadSortStates()
    }
    
    /**
     * Получает текущее состояние сортировки для режима
     */
    fun getSortState(isShowAll: Boolean, isHideDuplicates: Boolean): SortState {
        val state = when {
            isShowAll && isHideDuplicates -> showAllHideDupSortState
            isShowAll -> showAllModeSortState
            else -> normalModeSortState
        }
        return state
    }
    
    /**
     * Обрабатывает клик по заголовку колонки и циклически переключает сортировку
     */
    fun handleColumnClick(columnName: String, isShowAll: Boolean, isHideDuplicates: Boolean) {
        val state = getSortState(isShowAll, isHideDuplicates)
        
        // Если кликнули по новой колонке, сбрасываем сортировку других колонок
        if (state.activeColumn != columnName) {
            state.labelSort = SortType.NONE
            state.sizeSort = SortType.NONE
            state.dpiSort = SortType.NONE
            state.sizeUsesSort = SortType.NONE
            state.dpiUsesSort = SortType.NONE
            state.listSort = SortType.NONE
        }
        
        // Устанавливаем активную колонку
        state.activeColumn = columnName
        
        // Циклически переключаем тип сортировки для активной колонки
        when (columnName) {
            "Label" -> {
                state.labelSort = when (state.labelSort) {
                    SortType.NONE -> SortType.LABEL_ASC
                    SortType.LABEL_ASC -> SortType.LABEL_DESC
                    SortType.LABEL_DESC -> SortType.LABEL_ASC  // Возвращаемся к ASC вместо NONE
                    else -> SortType.LABEL_ASC
                }
                // Убираем сброс activeColumn - сортировка всегда активна
            }
            "Size" -> {
                state.sizeSort = when (state.sizeSort) {
                    SortType.NONE -> SortType.SIZE_DESC
                    SortType.SIZE_DESC -> SortType.SIZE_ASC
                    SortType.SIZE_ASC -> SortType.SIZE_DESC  // Возвращаемся к DESC вместо NONE
                    else -> SortType.SIZE_DESC
                }
                // Убираем сброс activeColumn - сортировка всегда активна
            }
            "DPI" -> {
                state.dpiSort = when (state.dpiSort) {
                    SortType.NONE -> SortType.DPI_DESC
                    SortType.DPI_DESC -> SortType.DPI_ASC
                    SortType.DPI_ASC -> SortType.DPI_DESC  // Возвращаемся к DESC вместо NONE
                    else -> SortType.DPI_DESC
                }
                // Убираем сброс activeColumn - сортировка всегда активна
            }
            "Size Uses" -> {
                state.sizeUsesSort = when (state.sizeUsesSort) {
                    SortType.NONE -> SortType.SIZE_USES_DESC
                    SortType.SIZE_USES_DESC -> SortType.SIZE_USES_ASC
                    SortType.SIZE_USES_ASC -> SortType.SIZE_USES_DESC
                    else -> SortType.SIZE_USES_DESC
                }
            }
            "DPI Uses" -> {
                state.dpiUsesSort = when (state.dpiUsesSort) {
                    SortType.NONE -> SortType.DPI_USES_DESC
                    SortType.DPI_USES_DESC -> SortType.DPI_USES_ASC
                    SortType.DPI_USES_ASC -> SortType.DPI_USES_DESC
                    else -> SortType.DPI_USES_DESC
                }
            }
            "List" -> {
                state.listSort = when (state.listSort) {
                    SortType.NONE -> SortType.LIST_ASC
                    SortType.LIST_ASC -> SortType.LIST_DESC
                    SortType.LIST_DESC -> SortType.LIST_ASC  // Возвращаемся к ASC вместо NONE
                    else -> SortType.LIST_ASC
                }
                // Убираем сброс activeColumn - сортировка всегда активна
            }
        }
        
        // Сохраняем состояние
        saveSortStates()
    }

    /**
     * Сортирует список пресетов согласно текущему состоянию
     */
    fun sortPresets(
        presets: List<DevicePreset>,
        isShowAll: Boolean,
        isHideDuplicates: Boolean,
        listNames: List<String>? = null
    ): List<DevicePreset> {
        val state = getSortState(isShowAll, isHideDuplicates)
        
        println("ADB_DEBUG: TableSortingService.sortPresets called")
        println("ADB_DEBUG:   isShowAll: $isShowAll, isHideDuplicates: $isHideDuplicates")
        println("ADB_DEBUG:   activeColumn: ${state.activeColumn}")
        println("ADB_DEBUG:   labelSort: ${state.labelSort}, sizeSort: ${state.sizeSort}, dpiSort: ${state.dpiSort}")
        println("ADB_DEBUG:   sizeUsesSort: ${state.sizeUsesSort}, dpiUsesSort: ${state.dpiUsesSort}")
        
        return when (state.activeColumn) {
            "Label" -> {
                println("ADB_DEBUG:   Sorting by Label with sort type: ${state.labelSort}")
                sortByLabel(presets, state.labelSort)
            }
            "Size" -> {
                println("ADB_DEBUG:   Sorting by Size with sort type: ${state.sizeSort}")
                sortBySize(presets, state.sizeSort)
            }
            "DPI" -> {
                println("ADB_DEBUG:   Sorting by DPI with sort type: ${state.dpiSort}")
                sortByDpi(presets, state.dpiSort)
            }
            "Size Uses" -> {
                println("ADB_DEBUG:   Sorting by Size Uses with sort type: ${state.sizeUsesSort}")
                sortBySizeUses(presets, state.sizeUsesSort)
            }
            "DPI Uses" -> {
                println("ADB_DEBUG:   Sorting by DPI Uses with sort type: ${state.dpiUsesSort}")
                sortByDpiUses(presets, state.dpiUsesSort)
            }
            "List" -> {
                if (isShowAll && listNames != null && listNames.size == presets.size) {
                    println("ADB_DEBUG:   Sorting by List with sort type: ${state.listSort}")
                    sortByList(presets, listNames, state.listSort)
                } else {
                    println("ADB_DEBUG:   List sort not applicable, returning original order")
                    presets
                }
            }
            else -> {
                println("ADB_DEBUG:   No active column for sorting, returning original order")
                presets
            }
        }
    }
    
    /**
     * Сортирует пресеты с информацией о списках для режима Show All
     */
    fun sortPresetsWithLists(
        presetsWithLists: List<Pair<String, DevicePreset>>,
        isHideDuplicates: Boolean
    ): List<Pair<String, DevicePreset>> {
        val state = getSortState(true, isHideDuplicates)
        
        println("ADB_DEBUG: TableSortingService.sortPresetsWithLists called")
        println("ADB_DEBUG:   isHideDuplicates: $isHideDuplicates")
        println("ADB_DEBUG:   activeColumn: ${state.activeColumn}")
        println("ADB_DEBUG:   labelSort: ${state.labelSort}, sizeSort: ${state.sizeSort}, dpiSort: ${state.dpiSort}, listSort: ${state.listSort}")
        println("ADB_DEBUG:   sizeUsesSort: ${state.sizeUsesSort}, dpiUsesSort: ${state.dpiUsesSort}")
        
        return when (state.activeColumn) {
            "Label" -> {
                when (state.labelSort) {
                    SortType.LABEL_ASC -> presetsWithLists.sortedBy { it.second.label.lowercase() }
                    SortType.LABEL_DESC -> presetsWithLists.sortedByDescending { it.second.label.lowercase() }
                    else -> presetsWithLists
                }
            }
            "Size" -> {
                when (state.sizeSort) {
                    SortType.SIZE_DESC -> presetsWithLists.sortedByDescending { calculateSizeSum(it.second.size) }
                    SortType.SIZE_ASC -> presetsWithLists.sortedBy { calculateSizeSum(it.second.size) }
                    else -> presetsWithLists
                }
            }
            "DPI" -> {
                when (state.dpiSort) {
                    SortType.DPI_DESC -> presetsWithLists.sortedByDescending { parseDpiValue(it.second.dpi) }
                    SortType.DPI_ASC -> presetsWithLists.sortedBy { parseDpiValue(it.second.dpi) }
                    else -> presetsWithLists
                }
            }
            "Size Uses" -> {
                when (state.sizeUsesSort) {
                    SortType.SIZE_USES_DESC -> presetsWithLists.sortedWith(
                        compareByDescending<Pair<String, DevicePreset>> { UsageCounterService.getSizeCounter(it.second.size) }
                            .thenBy { it.second.label.lowercase() }  // Вторичная сортировка по label для стабильности
                    )
                    SortType.SIZE_USES_ASC -> presetsWithLists.sortedWith(
                        compareBy<Pair<String, DevicePreset>> { UsageCounterService.getSizeCounter(it.second.size) }
                            .thenBy { it.second.label.lowercase() }  // Вторичная сортировка по label для стабильности
                    )
                    else -> presetsWithLists
                }
            }
            "DPI Uses" -> {
                when (state.dpiUsesSort) {
                    SortType.DPI_USES_DESC -> presetsWithLists.sortedWith(
                        compareByDescending<Pair<String, DevicePreset>> { UsageCounterService.getDpiCounter(it.second.dpi) }
                            .thenBy { it.second.label.lowercase() }  // Вторичная сортировка по label для стабильности
                    )
                    SortType.DPI_USES_ASC -> presetsWithLists.sortedWith(
                        compareBy<Pair<String, DevicePreset>> { UsageCounterService.getDpiCounter(it.second.dpi) }
                            .thenBy { it.second.label.lowercase() }  // Вторичная сортировка по label для стабильности
                    )
                    else -> presetsWithLists
                }
            }
            "List" -> {
                when (state.listSort) {
                    SortType.LIST_ASC -> presetsWithLists.sortedBy { it.first.lowercase() }
                    SortType.LIST_DESC -> presetsWithLists.sortedByDescending { it.first.lowercase() }
                    else -> presetsWithLists
                }
            }
            else -> presetsWithLists
        }
    }
    
    /**
     * Сбрасывает состояние сортировки
     */
    private fun resetSortState(state: SortState) {
        state.labelSort = SortType.NONE
        state.sizeSort = SortType.NONE
        state.dpiSort = SortType.NONE
        state.sizeUsesSort = SortType.NONE
        state.dpiUsesSort = SortType.NONE
        state.listSort = SortType.NONE
        state.activeColumn = null
    }
    
    /**
     * Копирует состояние сортировки из одного объекта в другой
     */
    private fun copySortState(from: SortState, to: SortState) {
        to.labelSort = from.labelSort
        to.sizeSort = from.sizeSort
        to.dpiSort = from.dpiSort
        to.sizeUsesSort = from.sizeUsesSort
        to.dpiUsesSort = from.dpiUsesSort
        to.listSort = from.listSort
        to.activeColumn = from.activeColumn
    }
    
    /**
     * Сбрасывает все сортировки для текущего режима
     */
    fun resetSort(isShowAll: Boolean, isHideDuplicates: Boolean) {
        val state = getSortState(isShowAll, isHideDuplicates)
        resetSortState(state)
        
        // Если мы в режиме Show All, сбрасываем сортировку для обоих состояний
        // (с Hide Duplicates и без), чтобы сброс сохранялся при переключении
        if (isShowAll) {
            resetSortState(showAllModeSortState)
            resetSortState(showAllHideDupSortState)
        }
        
        saveSortStates()
    }
    
    /**
     * Синхронизирует состояние сортировки при переключении режима Hide Duplicates
     */
    fun syncSortStateForHideDuplicatesToggle(isShowAll: Boolean, isHideDuplicates: Boolean) {
        if (!isShowAll) return // Синхронизация нужна только для режима Show All
        
        val fromState = if (isHideDuplicates) showAllModeSortState else showAllHideDupSortState
        val toState = if (isHideDuplicates) showAllHideDupSortState else showAllModeSortState
        
        // Проверяем, была ли сброшена сортировка в исходном состоянии
        // Если да, то не копируем старое состояние
        if (fromState.activeColumn == null) {
            // Сбрасываем целевое состояние тоже
            resetSortState(toState)
        } else {
            // Копируем состояние сортировки только если она активна
            copySortState(fromState, toState)
        }
        
        saveSortStates()
        
        println("ADB_DEBUG: Synced sort state for Hide Duplicates toggle")
        println("ADB_DEBUG:   fromState.activeColumn: ${fromState.activeColumn}")
        println("ADB_DEBUG:   toState.activeColumn: ${toState.activeColumn}")
        println("ADB_DEBUG:   labelSort: ${toState.labelSort}, sizeSort: ${toState.sizeSort}, dpiSort: ${toState.dpiSort}")
        println("ADB_DEBUG:   sizeUsesSort: ${toState.sizeUsesSort}, dpiUsesSort: ${toState.dpiUsesSort}")
    }

    /**
     * Получает текущий тип сортировки для колонки
     */
    fun getCurrentSortType(columnIndex: Int, isShowAll: Boolean, isHideDuplicates: Boolean): SortType {
        val state = getSortState(isShowAll, isHideDuplicates)
        val hasCounters = PresetStorageService.getShowCounters()
        
        return when (columnIndex) {
            2 -> state.labelSort
            3 -> state.sizeSort
            4 -> state.dpiSort
            5 -> if (hasCounters) state.sizeUsesSort else SortType.NONE
            6 -> when {
                hasCounters -> state.dpiUsesSort
                isShowAll -> state.listSort
                else -> SortType.NONE
            }
            8 -> if (isShowAll && hasCounters) state.listSort else SortType.NONE
            else -> SortType.NONE
        }
    }
    
    private fun sortByLabel(presets: List<DevicePreset>, sortType: SortType): List<DevicePreset> {
        return when (sortType) {
            SortType.LABEL_ASC -> presets.sortedBy { it.label.lowercase() }
            SortType.LABEL_DESC -> presets.sortedByDescending { it.label.lowercase() }
            else -> presets
        }
    }
    
    private fun sortBySize(presets: List<DevicePreset>, sortType: SortType): List<DevicePreset> {
        return when (sortType) {
            SortType.SIZE_DESC -> presets.sortedByDescending { calculateSizeSum(it.size) }
            SortType.SIZE_ASC -> presets.sortedBy { calculateSizeSum(it.size) }
            else -> presets
        }
    }
    
    private fun sortByDpi(presets: List<DevicePreset>, sortType: SortType): List<DevicePreset> {
        return when (sortType) {
            SortType.DPI_DESC -> presets.sortedByDescending { parseDpiValue(it.dpi) }
            SortType.DPI_ASC -> presets.sortedBy { parseDpiValue(it.dpi) }
            else -> presets
        }
    }
    
    private fun sortByList(
        presets: List<DevicePreset>,
        listNames: List<String>,
        sortType: SortType
    ): List<DevicePreset> {
        val presetsWithLists = presets.zip(listNames)
        val sorted = when (sortType) {
            SortType.LIST_ASC -> presetsWithLists.sortedBy { it.second.lowercase() }
            SortType.LIST_DESC -> presetsWithLists.sortedByDescending { it.second.lowercase() }
            else -> presetsWithLists
        }
        return sorted.map { it.first }
    }
    
    private fun sortBySizeUses(presets: List<DevicePreset>, sortType: SortType): List<DevicePreset> {
        return when (sortType) {
            SortType.SIZE_USES_DESC -> presets.sortedWith(
                compareByDescending<DevicePreset> { UsageCounterService.getSizeCounter(it.size) }
                    .thenBy { it.label.lowercase() }  // Вторичная сортировка по label для стабильности
            )
            SortType.SIZE_USES_ASC -> presets.sortedWith(
                compareBy<DevicePreset> { UsageCounterService.getSizeCounter(it.size) }
                    .thenBy { it.label.lowercase() }  // Вторичная сортировка по label для стабильности
            )
            else -> presets
        }
    }
    
    private fun sortByDpiUses(presets: List<DevicePreset>, sortType: SortType): List<DevicePreset> {
        return when (sortType) {
            SortType.DPI_USES_DESC -> presets.sortedWith(
                compareByDescending<DevicePreset> { UsageCounterService.getDpiCounter(it.dpi) }
                    .thenBy { it.label.lowercase() }  // Вторичная сортировка по label для стабильности
            )
            SortType.DPI_USES_ASC -> presets.sortedWith(
                compareBy<DevicePreset> { UsageCounterService.getDpiCounter(it.dpi) }
                    .thenBy { it.label.lowercase() }  // Вторичная сортировка по label для стабильности
            )
            else -> presets
        }
    }
    
    /**
     * Вычисляет сумму цифр в размере экрана
     */
    private fun calculateSizeSum(size: String): Int {
        if (size.isBlank()) return 0
        
        // Ищем разделитель (x или X, русская или английская)
        val separatorRegex = "[xXхХ]".toRegex()
        val parts = size.split(separatorRegex)
        
        if (parts.size != 2) return 0
        
        val width = parts[0].trim().toIntOrNull() ?: 0
        val height = parts[1].trim().toIntOrNull() ?: 0
        
        return width + height
    }
    
    /**
     * Парсит значение DPI для сортировки
     */
    private fun parseDpiValue(dpi: String): Int {
        if (dpi.isBlank()) return 0
        
        // Извлекаем первое число из строки
        val numberRegex = "\\d+".toRegex()
        val match = numberRegex.find(dpi)
        
        return match?.value?.toIntOrNull() ?: 0
    }
    
    /**
     * Сохраняет состояния сортировки
     */
    private fun saveSortStates() {
        val normalJson = gson.toJson(normalModeSortState)
        val showAllJson = gson.toJson(showAllModeSortState)
        val showAllHideDupJson = gson.toJson(showAllHideDupSortState)
        
        println("ADB_DEBUG: Saving sort states:")
        println("ADB_DEBUG:   normalModeSortState - activeColumn: ${normalModeSortState.activeColumn}")
        println("ADB_DEBUG:   showAllModeSortState - activeColumn: ${showAllModeSortState.activeColumn}, sizeUsesSort: ${showAllModeSortState.sizeUsesSort}, dpiUsesSort: ${showAllModeSortState.dpiUsesSort}")
        println("ADB_DEBUG:   showAllHideDupSortState - activeColumn: ${showAllHideDupSortState.activeColumn}, sizeUsesSort: ${showAllHideDupSortState.sizeUsesSort}, dpiUsesSort: ${showAllHideDupSortState.dpiUsesSort}")
        println("ADB_DEBUG:   showAllJson: $showAllJson")
        println("ADB_DEBUG:   showAllHideDupJson: $showAllHideDupJson")
        
        PresetStorageService.setStringList(NORMAL_MODE_SORT_STATE, listOf(normalJson))
        PresetStorageService.setStringList(SHOW_ALL_SORT_STATE, listOf(showAllJson))
        PresetStorageService.setStringList(SHOW_ALL_HIDE_DUP_SORT_STATE, listOf(showAllHideDupJson))
    }
    
    /**
     * Загружает состояния сортировки
     */
    private fun loadSortStates() {
        try {
            loadSortState(NORMAL_MODE_SORT_STATE, normalModeSortState)
            loadSortState(SHOW_ALL_SORT_STATE, showAllModeSortState)
            loadSortState(SHOW_ALL_HIDE_DUP_SORT_STATE, showAllHideDupSortState)
            
            println("ADB_DEBUG: Loaded sort states:")
            println("ADB_DEBUG:   showAllModeSortState - activeColumn: ${showAllModeSortState.activeColumn}, sizeUsesSort: ${showAllModeSortState.sizeUsesSort}, dpiUsesSort: ${showAllModeSortState.dpiUsesSort}")
            println("ADB_DEBUG:   showAllHideDupSortState - activeColumn: ${showAllHideDupSortState.activeColumn}, sizeUsesSort: ${showAllHideDupSortState.sizeUsesSort}, dpiUsesSort: ${showAllHideDupSortState.dpiUsesSort}")
        } catch (e: Exception) {
            // В случае ошибки используем значения по умолчанию
            println("Error loading sort states: ${e.message}")
        }
    }
    
    /**
     * Загружает состояние сортировки из настроек
     */
    private fun loadSortState(key: String, targetState: SortState) {
        val json = PresetStorageService.getStringList(key).firstOrNull()
        println("ADB_DEBUG: Loading sort state for key: $key")
        println("ADB_DEBUG:   json: $json")
        if (json != null) {
            val loaded = gson.fromJson(json, SortState::class.java)
            copySortState(loaded, targetState)
            println("ADB_DEBUG:   loaded activeColumn: ${loaded.activeColumn}")
        }
    }
    
    /**
     * Возвращает текущее состояние сортировки для активного режима
     */
    fun getCurrentSortState(): SortState? {
        // Определяем текущий режим
        val isShowAll = PresetStorageService.getShowAllPresetsMode()
        val isHideDuplicates = PresetStorageService.getHideDuplicatesMode()
        
        val state = getSortState(isShowAll, isHideDuplicates)
        return if (state.activeColumn != null) state else null
    }
    
    /**
     * Применяет текущую сортировку заново
     * Используется после операций Undo/Redo для восстановления порядка сортировки
     */
    fun reapplyCurrentSort() {
        println("ADB_DEBUG: TableSortingService.reapplyCurrentSort called")
        val currentState = getCurrentSortState()
        if (currentState != null && currentState.activeColumn != null) {
            println("ADB_DEBUG:   Active column: ${currentState.activeColumn}")
            println("ADB_DEBUG:   Sort states - label: ${currentState.labelSort}, size: ${currentState.sizeSort}, dpi: ${currentState.dpiSort}")
            // Сортировка будет применена автоматически при следующей перезагрузке таблицы
            // через вызов sortPresets или sortPresetsWithLists
        } else {
            println("ADB_DEBUG:   No active sort to reapply")
        }
    }
    
    /**
     * Создает снимок текущего состояния сортировки для всех режимов
     */
    fun createSortStateSnapshot() {
        sortStateSnapshot = mapOf(
            "normal" to normalModeSortState.copy(),
            "showAll" to showAllModeSortState.copy(),
            "showAllHideDup" to showAllHideDupSortState.copy()
        )
        println("ADB_DEBUG: Created sort state snapshot")
        println("ADB_DEBUG:   Normal mode: activeColumn=${normalModeSortState.activeColumn}")
        println("ADB_DEBUG:   Show All mode: activeColumn=${showAllModeSortState.activeColumn}")
        println("ADB_DEBUG:   Show All Hide Dup mode: activeColumn=${showAllHideDupSortState.activeColumn}")
    }
    
    /**
     * Восстанавливает состояние сортировки из снимка
     */
    fun restoreSortStateFromSnapshot() {
        sortStateSnapshot?.let { snapshot ->
            snapshot["normal"]?.let { state ->
                normalModeSortState.labelSort = state.labelSort
                normalModeSortState.sizeSort = state.sizeSort
                normalModeSortState.dpiSort = state.dpiSort
                normalModeSortState.sizeUsesSort = state.sizeUsesSort
                normalModeSortState.dpiUsesSort = state.dpiUsesSort
                normalModeSortState.listSort = state.listSort
                normalModeSortState.activeColumn = state.activeColumn
            }
            
            snapshot["showAll"]?.let { state ->
                showAllModeSortState.labelSort = state.labelSort
                showAllModeSortState.sizeSort = state.sizeSort
                showAllModeSortState.dpiSort = state.dpiSort
                showAllModeSortState.sizeUsesSort = state.sizeUsesSort
                showAllModeSortState.dpiUsesSort = state.dpiUsesSort
                showAllModeSortState.listSort = state.listSort
                showAllModeSortState.activeColumn = state.activeColumn
            }
            
            snapshot["showAllHideDup"]?.let { state ->
                showAllHideDupSortState.labelSort = state.labelSort
                showAllHideDupSortState.sizeSort = state.sizeSort
                showAllHideDupSortState.dpiSort = state.dpiSort
                showAllHideDupSortState.sizeUsesSort = state.sizeUsesSort
                showAllHideDupSortState.dpiUsesSort = state.dpiUsesSort
                showAllHideDupSortState.listSort = state.listSort
                showAllHideDupSortState.activeColumn = state.activeColumn
            }
            
            // Сохраняем восстановленные состояния в настройки
            saveSortStates()
            
            println("ADB_DEBUG: Restored sort state from snapshot")
            println("ADB_DEBUG:   Normal mode: activeColumn=${normalModeSortState.activeColumn}")
            println("ADB_DEBUG:   Show All mode: activeColumn=${showAllModeSortState.activeColumn}")
            println("ADB_DEBUG:   Show All Hide Dup mode: activeColumn=${showAllHideDupSortState.activeColumn}")
        } ?: println("ADB_DEBUG: No sort state snapshot to restore")
    }

}