package io.github.qavlad.adbrandomizer.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.qavlad.adbrandomizer.services.*
import io.github.qavlad.adbrandomizer.ui.components.*
import io.github.qavlad.adbrandomizer.ui.handlers.KeyboardHandler
import io.github.qavlad.adbrandomizer.ui.renderers.ValidationRenderer
import io.github.qavlad.adbrandomizer.ui.renderers.ListColumnHeaderRenderer
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
import javax.swing.table.TableColumn
import io.github.qavlad.adbrandomizer.ui.components.TableWithAddButtonPanel

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
    lateinit var listManagerPanel: PresetListManagerPanel
        private set
    private var tableWithButtonPanel: TableWithAddButtonPanel? = null

    // Состояние
    var hoverState = HoverState.noHover()
        private set
    val historyManager = HistoryManager()
    private var updateListener: (() -> Unit)? = null
    private var globalClickListener: java.awt.event.AWTEventListener? = null
    private var currentPresetList: PresetList? = null
    private var isShowAllPresetsMode = false
    private var isHideDuplicatesMode = false
    
    // Карта для отслеживания какие пресеты были видимы в таблице при редактировании
    // Хранит для каждого списка полный порядок элементов и индексы видимых элементов
    private val visiblePresetsSnapshot = mutableMapOf<String, List<String>>()
    private val presetsOrderSnapshot = mutableMapOf<String, List<String>>()

    // Временное хранилище всех списков для режима "Show all presets"
    private val tempPresetLists = mutableMapOf<String, PresetList>()

    // Исходное состояние списков для отката при Cancel
    private val originalPresetLists = mutableMapOf<String, PresetList>()

    // Состояние редактирования ячейки
    private var editingCellOldValue: String? = null
    private var editingCellRow: Int = -1
    private var editingCellColumn: Int = -1

    // Флаг для отслеживания первой загрузки
    private var isFirstLoad = true

    // === Вспомогательный флаг для блокировки TableModelListener ===
    private var isTableUpdating = false

    // Флаг для отслеживания переключения режимов
    private var isSwitchingMode = false

    // Флаг для отслеживания переключения списков
    private var isSwitchingList = false

    // Флаг для отслеживания переключения фильтра дубликатов
    private var isSwitchingDuplicatesFilter = false

    // Ссылка на слушатель модели, для временного отключения
    private var tableModelListener: javax.swing.event.TableModelListener? = null

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
        // Загружаем сохраненные состояния чекбоксов
        isShowAllPresetsMode = SettingsService.getShowAllPresetsMode()
        isHideDuplicatesMode = SettingsService.getHideDuplicatesMode()

        listManagerPanel = PresetListManagerPanel(
            onListChanged = { presetList ->
                println("ADB_DEBUG: onListChanged called with: ${presetList.name}")
                // Останавливаем редактирование если оно активно
                if (::table.isInitialized && table.isEditing) {
                    table.cellEditor.stopCellEditing()
                }

        // Добавляем слушатель к модели
        tableModel.addTableModelListener(tableModelListener!!)

                isSwitchingList = true
                // Переключаемся на временную копию нового списка
                currentPresetList = tempPresetLists[presetList.id]
                println("ADB_DEBUG: onListChanged - set currentPresetList to: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
                // Очищаем кэш активного списка при переключении
                PresetListService.clearAllCaches()
                // Очищаем снимок при переключении списка только если не в режиме скрытия дубликатов
                // В режиме скрытия дубликатов снимок нужно сохранять для корректной работы
                if (!isShowAllPresetsMode && !isHideDuplicatesMode) {
                    visiblePresetsSnapshot.clear()
                    presetsOrderSnapshot.clear()
                }
                if (::table.isInitialized) {
                    loadPresetsIntoTable()
                }
            },
            onShowAllPresetsChanged = { showAll ->
                if (::table.isInitialized && table.isEditing) {
                    table.cellEditor.stopCellEditing()
                }

                // Сохраняем текущее состояние перед переключением только если не первая загрузка
                if (::table.isInitialized && !isFirstLoad) {
                    // Синхронизируем только если переключаемся В режим Show all или если мы в нем находимся
                    if (showAll || isShowAllPresetsMode) {
                        syncTableChangesToTempLists()
                    }
                }

                // ВАЖНО: Сохраняем снимок видимых пресетов ПЕРЕД переключением режима
                // чтобы он был доступен при обработке удаления в режиме Show all
                // Сохраняем только если снимок еще не был сохранен
                if (showAll && isHideDuplicatesMode && !isShowAllPresetsMode && visiblePresetsSnapshot.isEmpty()) {
                    println("ADB_DEBUG: Saving snapshot before switching to Show all mode (snapshot was empty)")
                    println("ADB_DEBUG: Current visiblePresetsSnapshot size before save: ${visiblePresetsSnapshot.size}")
                    saveVisiblePresetsSnapshotForAllLists()
                    println("ADB_DEBUG: After saving, visiblePresetsSnapshot size: ${visiblePresetsSnapshot.size}")
                } else if (showAll && isHideDuplicatesMode && !isShowAllPresetsMode) {
                    println("ADB_DEBUG: NOT saving snapshot before Show all - already have ${visiblePresetsSnapshot.size} snapshots")
                }

                isSwitchingMode = true
                isTableUpdating = true
                try {
                    isShowAllPresetsMode = showAll
                    SettingsService.setShowAllPresetsMode(showAll)
                    tableWithButtonPanel?.setAddButtonVisible(!showAll)
                    if (::table.isInitialized) {
                        loadPresetsIntoTable()
                    }
                } finally {
                    isTableUpdating = false
                    isSwitchingMode = false
                    // Очищаем снимок при выходе из режима Show all
                    // Он будет пересоздан при необходимости из актуального состояния
                    if (!showAll) {
                        println("ADB_DEBUG: Clearing snapshot when exiting Show all mode")
                        visiblePresetsSnapshot.clear()
                        presetsOrderSnapshot.clear()
                    }
                }
            },
            onHideDuplicatesChanged = { hideDuplicates ->
                println("ADB_DEBUG: onHideDuplicatesChanged called with: $hideDuplicates")
                
                // Останавливаем редактирование если оно активно
                if (::table.isInitialized && table.isEditing) {
                    table.cellEditor.stopCellEditing()
                }

                // Отладка: выводим состояние списка до синхронизации
                currentPresetList?.let { list ->
                    println("ADB_DEBUG: Before sync - list ${list.name} has ${list.presets.size} presets:")
                    list.presets.forEachIndexed { index, preset ->
                        println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
                    }
                }
                
                // Синхронизируем состояние таблицы только при ВКЛЮЧЕНИИ фильтра дубликатов
                // При отключении фильтра синхронизация не нужна, так как все пресеты уже есть в списке
                if (::table.isInitialized && !isFirstLoad && hideDuplicates) {
                    syncTableChangesToTempLists()
                }
                
                isHideDuplicatesMode = hideDuplicates
                SettingsService.setHideDuplicatesMode(hideDuplicates)
                
                // ВАЖНО: После включения фильтра дублей сохраняем снимок для всех списков
                // Это нужно для корректной работы при последующем переключении в Show all
                if (hideDuplicates && !isFirstLoad) {
                    println("ADB_DEBUG: Saving snapshot after enabling hide duplicates")
                    println("ADB_DEBUG: tempPresetLists size: ${tempPresetLists.size}")
                    saveVisiblePresetsSnapshotForAllLists()
                    println("ADB_DEBUG: After saving snapshot, visiblePresetsSnapshot size: ${visiblePresetsSnapshot.size}")
                    visiblePresetsSnapshot.forEach { (listName, presets) ->
                        println("ADB_DEBUG:   Snapshot for $listName: ${presets.size} presets")
                    }
                }
                // Проверяем, что таблица инициализирована
                if (::table.isInitialized) {
                    // Временно отключаем слушатель модели
                    tableModelListener?.let { tableModel.removeTableModelListener(it) }

                    isSwitchingDuplicatesFilter = true
                    isTableUpdating = true
                    try {
                        loadPresetsIntoTable()
                    } finally {
                        isTableUpdating = false
                        isSwitchingDuplicatesFilter = false
                        // Возвращаем слушатель на место
                        tableModelListener?.let { tableModel.addTableModelListener(it) }
                        // Очищаем снимок при отключении фильтра дубликатов
                        if (!hideDuplicates) {
                            visiblePresetsSnapshot.clear()
                            presetsOrderSnapshot.clear()
                        }
                    }
                }
            }
        )

        // НЕ устанавливаем состояния чекбоксов здесь, так как это вызовет callbacks
        // Вместо этого сделаем это после инициализации таблицы

        return listManagerPanel
    }

    /**
     * Создает модель таблицы с начальными данными
     */
    fun createTableModel(): DevicePresetTableModel {
        val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
        tableModel = DevicePresetTableModel(Vector<Vector<Any>>(), columnNames, historyManager)
        // НЕ добавляем слушатель здесь, так как table еще не создана

        // НЕ загружаем список здесь, так как временные списки еще не созданы
        // currentPresetList будет установлен в initializeTempPresetLists

        return tableModel
    }

    /**
     * Создает кастомную таблицу с переопределенными методами рендеринга
     */
    fun createTable(model: DevicePresetTableModel): JBTable {
        table = object : JBTable(model) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                // Проверяем, что это не строка с кнопкой
                if (row >= 0 && row < rowCount) {
                    val firstColumnValue = model.getValueAt(row, 0)
                    if (firstColumnValue == "+") {
                        return false // Не позволяем редактировать строку с кнопкой
                    }
                }

                return super.isCellEditable(row, column)
            }

            @Suppress("DEPRECATION")
            override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
                // Проверяем, что индексы в допустимых пределах
                if (row >= rowCount || column >= columnCount) {
                    return super.prepareRenderer(renderer, row, column)
                }

                val component = super.prepareRenderer(renderer, row, column)

                if (component is JComponent) {
                    // Проверяем, является ли это строкой с кнопкой
                    val firstColumnValue = if (row >= 0 && row < rowCount) model.getValueAt(row, 0) else ""
                    val isButtonRow = firstColumnValue == "+"

                    if (isButtonRow && column == 0) {
                        // Для ячейки с плюсиком проверяем hover состояние
                        val currentHoverState = hoverState
                        val isHovered = currentHoverState.isTableCellHovered(row, column)

                        // Применяем hover эффект только если мышь именно на этой ячейке
                        if (isHovered) {
                            val normalBg = table.background ?: background
                            val hoverBg = normalBg?.brighter()
                            component.background = hoverBg ?: normalBg
                        } else {
                            component.background = table.background ?: background
                        }
                        component.isOpaque = true
                        return component
                    } else if (isButtonRow) {
                        // Для остальных ячеек строки с кнопкой - обычный фон, НЕ применяем hover
                        component.background = background
                        component.foreground = foreground
                        component.isOpaque = true
                    } else {
                        // Обычная логика для других строк
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

        // ВАЖНО: инициализируем временные списки ДО загрузки таблицы
        initializeTempPresetLists()
        // Гарантируем, что currentPresetList валиден
        if (currentPresetList == null && tempPresetLists.isNotEmpty()) {
            currentPresetList = tempPresetLists.values.first()
        }

        // Коллектор для группировки множественных событий TableModel
        var pendingTableUpdates = 0
        var lastUpdateTimer: javax.swing.Timer? = null

        // Создаем слушатель модели и сохраняем ссылку
        tableModelListener = javax.swing.event.TableModelListener { e ->
            if (isTableUpdating) {
                return@TableModelListener
            }

            pendingTableUpdates++

            // Делаем локальную копию для безопасного использования
            val currentTimer = lastUpdateTimer
            currentTimer?.stop()

            // Всегда выполняем валидацию сразу
            validateFields()

            // Создаем новый таймер для группировки обновлений
            val newTimer = javax.swing.Timer(50) {
                if (pendingTableUpdates > 0) {
                    println("ADB_DEBUG: TableModelListener batch update - processing $pendingTableUpdates updates")

                    // Синхронизируем изменения с временными списками
                    if (e.type == javax.swing.event.TableModelEvent.UPDATE) {
                        syncTableChangesToTempLists()
                    }

                    SwingUtilities.invokeLater {
                        table.repaint()
                    }

                    pendingTableUpdates = 0
                }
            }
            newTimer.isRepeats = false
            newTimer.start()

            // Сохраняем ссылку на новый таймер
            lastUpdateTimer = newTimer
        }

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
            getPresetAtRow = ::getPresetAtRow,
            isShowAllPresetsMode = { isShowAllPresetsMode },
            getListNameAtRow = ::getListNameAtRow,
            onPresetDeleted = { /* Не сохраняем порядок при удалении */ },
            deletePresetFromTempList = ::deletePresetFromTempList
        )

        tableConfigurator.configure()

        println("ADB_DEBUG: After tableConfigurator.configure() - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        // Загружаем данные после полной инициализации
        println("ADB_DEBUG: Before loadPresetsIntoTable in initializeHandlers - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        loadPresetsIntoTable()

        // Устанавливаем состояния чекбоксов после полной инициализации
        println("ADB_DEBUG: Setting checkbox states - showAll: $isShowAllPresetsMode, hideDuplicates: $isHideDuplicatesMode")
        listManagerPanel.setShowAllPresets(isShowAllPresetsMode)
        listManagerPanel.setHideDuplicates(isHideDuplicatesMode)

        // Сбрасываем флаг первой загрузки после небольшой задержки чтобы таблица успела полностью загрузиться
        SwingUtilities.invokeLater {
            isFirstLoad = false
        }

        table.addKeyListener(keyboardHandler.createTableKeyListener())
        keyboardHandler.addGlobalKeyListener()
        validateFields()
    }

    /**
     * Настройка колонок таблицы в зависимости от режима
     */
    private fun setupTableColumns() {
        // Сохраняем текущее состояние флага
        val wasUpdating = isTableUpdating
        if (!wasUpdating) {
            isTableUpdating = true
        }
        try {
            val currentColumnCount = table.columnModel.columnCount
            println("ADB_DEBUG: setupTableColumns - current column count: $currentColumnCount, isShowAllPresetsMode: $isShowAllPresetsMode")
            println("ADB_DEBUG: setupTableColumns - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

            // Отладка: выводим текущие колонки
            for (i in 0 until currentColumnCount) {
                println("ADB_DEBUG: Before setup - Column $i: ${table.columnModel.getColumn(i).headerValue}")
            }

            if (isShowAllPresetsMode) {
                // Обновляем заголовок модели
                val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  ", "List"))
                tableModel.setColumnIdentifiers(columnNames)
                println("ADB_DEBUG: Set model column count to: ${tableModel.columnCount}")

                // Удаляем все лишние колонки сначала
                while (table.columnModel.columnCount > tableModel.columnCount) {
                    val lastIndex = table.columnModel.columnCount - 1
                    println("ADB_DEBUG: Removing extra column $lastIndex: ${table.columnModel.getColumn(lastIndex).headerValue}")
                    table.columnModel.removeColumn(table.columnModel.getColumn(lastIndex))
                }

                // Теперь добавляем недостающие колонки
                if (table.columnModel.columnCount < tableModel.columnCount) {
                    for (i in table.columnModel.columnCount until tableModel.columnCount) {
                        val column = TableColumn(i)
                        column.headerValue = tableModel.getColumnName(i)
                        if (i == 6) { // Колонка List
                            column.preferredWidth = JBUI.scale(150)
                            column.minWidth = JBUI.scale(100)
                        }
                        table.columnModel.addColumn(column)
                        println("ADB_DEBUG: Added column $i: ${column.headerValue}")
                    }
                }

                println("ADB_DEBUG: After setup - column count: ${table.columnModel.columnCount}")

                // Перенастраиваем все колонки
                reconfigureColumns()
            } else {
                // Обновляем заголовок модели обратно
                val columnNames = Vector(listOf(" ", "№", "Label", "Size (e.g., 1080x1920)", "DPI (e.g., 480)", "  "))
                println("ADB_DEBUG: Before setColumnIdentifiers - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
                tableModel.setColumnIdentifiers(columnNames)
                println("ADB_DEBUG: After setColumnIdentifiers - currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

                // Удаляем лишние колонки
                while (table.columnModel.columnCount > tableModel.columnCount) {
                    table.columnModel.removeColumn(table.columnModel.getColumn(table.columnModel.columnCount - 1))
                }
                println("ADB_DEBUG: Removed extra columns, new column count: ${table.columnModel.columnCount}")

                // Перенастраиваем все колонки
                reconfigureColumns()
            }
        } finally {
            // Сбрасываем флаг только если мы его установили
            if (!wasUpdating) {
                isTableUpdating = false
            }
        }
    }

    /**
     * Перенастраивает рендереры и редакторы колонок
     */
    private fun reconfigureColumns() {
        println("ADB_DEBUG: reconfigureColumns - before, currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        // Настраиваем стандартные колонки
        tableConfigurator.configureColumns()
        println("ADB_DEBUG: reconfigureColumns - after configureColumns, currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        // Настраиваем кастомный рендерер для заголовка колонки List
        if (table.columnModel.columnCount > 6) {
            val listColumn = table.columnModel.getColumn(6)
            listColumn.headerRenderer = ListColumnHeaderRenderer()
        }
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
        println("ADB_DEBUG: saveCurrentTableState - start, isShowAllPresetsMode: $isShowAllPresetsMode")
        println("ADB_DEBUG: saveCurrentTableState - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")

        if (tableModel.rowCount == 0 && currentPresetList?.presets?.isNotEmpty() == true) {
            println("ADB_DEBUG: skip saveCurrentTableState, table is empty but current list is not")
            return
        }

        if (isShowAllPresetsMode) {
            // В режиме "Show all presets" распределяем изменения по спискам
            distributePresetsToTempLists()
        } else {
            // В обычном режиме обновляем только текущий список
            currentPresetList?.let { list ->
                // Используем ту же логику, что и в syncTableChangesToTempLists
                if (isHideDuplicatesMode) {
                    // Используем ту же логику, что и в syncTableChangesToTempLists
                    val originalPresets = list.presets.map { it.copy() }
                    
                    // Определяем какие индексы были видимы в таблице
                    val visibleIndices = mutableListOf<Int>()
                    val seenKeys = mutableSetOf<String>()
                    
                    originalPresets.forEachIndexed { index, preset ->
                        val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                            "${preset.size}|${preset.dpi}"
                        } else {
                            "unique_$index"
                        }
                        
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key)
                            visibleIndices.add(index)
                        }
                    }
                    
                    // Получаем обновленные пресеты из таблицы
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    val updatedTablePresets = tableModel.getPresets()
                    
                    // Создаем новый список, обновляя только видимые элементы
                    val newPresets = mutableListOf<DevicePreset>()
                    var tableIndex = 0
                    
                    originalPresets.forEachIndexed { index, originalPreset ->
                        if (visibleIndices.contains(index) && tableIndex < updatedTablePresets.size) {
                            // Это был видимый пресет - берем обновленную версию из таблицы
                            newPresets.add(updatedTablePresets[tableIndex])
                            tableIndex++
                        } else {
                            // Это был скрытый дубликат - сохраняем как есть
                            newPresets.add(originalPreset)
                        }
                    }
                    
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
     * Синхронизирует изменения из таблицы с временными списками
     */
    private fun syncTableChangesToTempLists() {
        if (isTableUpdating) {
            println("ADB_DEBUG: syncTableChangesToTempLists: isTableUpdating=true, skip (global)")
            return
        }

        // Не синхронизируем во время переключения режимов
        if (isSwitchingMode) {
            println("ADB_DEBUG: syncTableChangesToTempLists: isSwitchingMode=true, skip")
            return
        }

        // Не синхронизируем во время переключения списков
        if (isSwitchingList) {
            println("ADB_DEBUG: syncTableChangesToTempLists: isSwitchingList=true, skip")
            return
        }

        // Не синхронизируем во время переключения фильтра дубликатов
        if (isSwitchingDuplicatesFilter) {
            println("ADB_DEBUG: syncTableChangesToTempLists: isSwitchingDuplicatesFilter=true, skip")
            return
        }



        println("ADB_DEBUG: syncTableChangesToTempLists - start, isShowAllPresetsMode: $isShowAllPresetsMode")
        println("ADB_DEBUG: syncTableChangesToTempLists - currentPresetList before: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempPresetLists.forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }

        // Не синхронизируем во время первой загрузки и во время фильтрации дубликатов
        if (isFirstLoad || isSwitchingDuplicatesFilter) {
            println("ADB_DEBUG: skip sync - first load or switching duplicates filter")
            return
        }
        
        // Не синхронизируем при отключении фильтра дубликатов, если количество строк в таблице меньше количества пресетов
        // Это означает, что в таблице показаны только видимые пресеты, а синхронизация испортит скрытые
        if (!isHideDuplicatesMode && currentPresetList != null) {
            val tablePresetCount = (0 until tableModel.rowCount).count { row ->
                val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
                firstColumn != "+"
            }
            if (tablePresetCount < currentPresetList!!.presets.size) {
                println("ADB_DEBUG: skip sync - filter just disabled, table has $tablePresetCount rows but list has ${currentPresetList!!.presets.size} presets")
                return
            }
        }

        // Проверяем количество строк без строки с кнопкой
        val realRowCount = (0 until tableModel.rowCount).count { row ->
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            firstColumn != "+"
        }

        if (isShowAllPresetsMode) {
            // В режиме "Show all presets" — очищаем все списки и распределяем по таблице
            distributePresetsToTempLists()
        } else {
            // В обычном режиме - не затираем если в таблице меньше строк, чем в списке
            if (realRowCount == 0 && currentPresetList?.presets?.isNotEmpty() == true) {
                println("ADB_DEBUG: skip sync, table is empty but current list is not")
                return
            }

            val tablePresets = tableModel.getPresets()
            currentPresetList?.let { list ->
                // Получаем снимок видимых пресетов для текущего списка
                val visibleSnapshot = visiblePresetsSnapshot[list.name]
                
                if (isHideDuplicatesMode) {
                    // В режиме скрытия дубликатов нужно правильно обновить видимые элементы
                    // и сохранить скрытые дубликаты
                    val originalPresets = list.presets.map { it.copy() }
                    
                    // Получаем обновленные пресеты из таблицы
                    // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                    val updatedTablePresets = tablePresets
                    
                    println("ADB_DEBUG: syncTableChangesToTempLists - hide duplicates mode")
                    println("ADB_DEBUG:   original presets count: ${originalPresets.size}")
                    println("ADB_DEBUG:   updated table presets count: ${updatedTablePresets.size}")
                    
                    // Определяем видимые пресеты в оригинальном списке
                    val visibleOriginalPresets = mutableListOf<Pair<Int, DevicePreset>>()
                    val seenKeys = mutableSetOf<String>()
                    
                    originalPresets.forEachIndexed { index, preset ->
                        val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                            "${preset.size}|${preset.dpi}"
                        } else {
                            "unique_$index"
                        }
                        
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key)
                            visibleOriginalPresets.add(index to preset)
                        }
                    }
                    
                    println("ADB_DEBUG:   visible original presets count: ${visibleOriginalPresets.size}")
                    
                    // Создаем карту для сопоставления старых видимых пресетов с новыми
                    val updatedPresetsMap = mutableMapOf<Int, DevicePreset>()
                    
                    // Если количество видимых пресетов уменьшилось, значит был удален элемент
                    if (updatedTablePresets.size < visibleOriginalPresets.size) {
                        // Находим какой пресет был удален
                        val deletedCount = visibleOriginalPresets.size - updatedTablePresets.size
                        println("ADB_DEBUG:   Detected deletion of $deletedCount preset(s)")
                        
                        // Находим индекс удаленного элемента путем сравнения
                        var deletedVisibleIndex = -1
                        for (i in visibleOriginalPresets.indices) {
                            if (i >= updatedTablePresets.size || 
                                visibleOriginalPresets[i].second.label != updatedTablePresets[i].label) {
                                deletedVisibleIndex = i
                                break
                            }
                        }
                        
                        println("ADB_DEBUG:   Deleted visible index: $deletedVisibleIndex")
                        
                        // Сопоставляем оставшиеся видимые элементы
                        var tableIndex = 0
                        visibleOriginalPresets.forEachIndexed { visibleIndex, (originalIndex, _) ->
                            if (visibleIndex != deletedVisibleIndex && tableIndex < updatedTablePresets.size) {
                                updatedPresetsMap[originalIndex] = updatedTablePresets[tableIndex]
                                tableIndex++
                            }
                        }
                    } else if (updatedTablePresets.size > visibleOriginalPresets.size) {
                        // Количество элементов увеличилось
                        val addedCount = updatedTablePresets.size - visibleOriginalPresets.size
                        println("ADB_DEBUG:   Detected addition of $addedCount preset(s)")
                        
                        // Проверяем если это добавление новых пустых пресетов через кнопку "+"
                        val hasNewEmptyPresets = updatedTablePresets.takeLast(addedCount).any { preset ->
                            preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()
                        }
                        
                        if (hasNewEmptyPresets) {
                            println("ADB_DEBUG:   New empty preset(s) detected from '+' button")
                            // Добавляем новые пресеты в конец списка
                            // Сохраняем все существующие пресеты
                            val newPresets = originalPresets.toMutableList()
                            
                            // Добавляем новые пресеты из таблицы
                            val newPresetsFromTable = updatedTablePresets.takeLast(addedCount)
                            newPresets.addAll(newPresetsFromTable)
                            
                            // Заменяем список
                            list.presets.clear()
                            list.presets.addAll(newPresets)
                            
                            println("ADB_DEBUG:   Updated list now has ${list.presets.size} presets")
                        } else {
                            // Это восстановление после undo или другая операция
                            println("ADB_DEBUG:   Addition/restore detected (not from '+' button)")
                            
                            // НЕ изменяем список здесь - он уже был обновлен в performUndo
                            // Просто перезагружаем таблицу
                            println("ADB_DEBUG:   List already updated by performUndo, just reload table")
                            
                            // Заново загружаем таблицу, чтобы отобразить правильный набор видимых элементов
                            SwingUtilities.invokeLater {
                                tableModelListener?.let { tableModel.removeTableModelListener(it) }
                                isTableUpdating = true
                                try {
                                    loadPresetsIntoTable()
                                } finally {
                                    isTableUpdating = false
                                    tableModelListener?.let { tableModel.addTableModelListener(it) }
                                }
                            }
                        }
                        return // Выходим, так как таблица будет перезагружена
                    } else {
                        // Обычное обновление без удаления или добавления
                        visibleOriginalPresets.forEachIndexed { index, (originalIndex, _) ->
                            if (index < updatedTablePresets.size) {
                                updatedPresetsMap[originalIndex] = updatedTablePresets[index]
                            }
                        }
                    }
                    
                    // Создаем новый список
                    val newPresets = mutableListOf<DevicePreset>()
                    
                    // Находим удаленный элемент и его дубликаты
                    var deletedPresetKey: String? = null
                    var firstHiddenDuplicateIndex = -1
                    
                    if (updatedTablePresets.size < visibleOriginalPresets.size) {
                        // Определяем какой элемент был удален
                        val deletedIndices = mutableListOf<Int>()
                        visibleOriginalPresets.forEach { (originalIndex, preset) ->
                            if (!updatedPresetsMap.containsKey(originalIndex)) {
                                deletedIndices.add(originalIndex)
                                if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                                    deletedPresetKey = "${preset.size}|${preset.dpi}"
                                }
                            }
                        }
                        
                        // Если удален элемент с ключом, ищем ВСЕ скрытые дубликаты
                        val allHiddenDuplicatesIndices = mutableListOf<Int>()
                        if (deletedPresetKey != null) {
                            originalPresets.forEachIndexed { index, preset ->
                                val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                                    "${preset.size}|${preset.dpi}"
                                } else {
                                    null
                                }
                                
                                if (key == deletedPresetKey && !visibleOriginalPresets.any { it.first == index }) {
                                    allHiddenDuplicatesIndices.add(index)
                                    println("ADB_DEBUG:   Found hidden duplicate at index $index: ${preset.label}")
                                }
                            }
                            if (allHiddenDuplicatesIndices.isNotEmpty()) {
                                firstHiddenDuplicateIndex = allHiddenDuplicatesIndices.first()
                                println("ADB_DEBUG:   Total hidden duplicates found: ${allHiddenDuplicatesIndices.size}")
                            }
                        }
                    }
                    
                    // Сначала собираем все индексы, которые должны быть в итоговом списке
                    val finalPresetIndices = mutableListOf<Int>()
                    val skippedIndices = mutableSetOf<Int>()
                    
                    originalPresets.forEachIndexed { index, originalPreset ->
                        val wasVisible = visibleOriginalPresets.any { it.first == index }
                        val isInUpdatedMap = updatedPresetsMap.containsKey(index)
                        
                        println("ADB_DEBUG:   Processing index $index: ${originalPreset.label}, wasVisible=$wasVisible, isInUpdatedMap=$isInUpdatedMap")
                        
                        if (isInUpdatedMap) {
                            // Это видимый пресет, который остался в таблице
                            finalPresetIndices.add(index)
                        } else if (!wasVisible) {
                            // Это скрытый пресет - всегда сохраняем
                            finalPresetIndices.add(index)
                        } else {
                            // Это был видимый пресет, который удалили
                            skippedIndices.add(index)
                        }
                    }
                    
                    // Если были удалены видимые элементы и есть скрытые дубликаты,
                    // то первый скрытый дубликат должен стать видимым
                    if (skippedIndices.isNotEmpty() && firstHiddenDuplicateIndex >= 0) {
                        // Убираем первый скрытый дубликат из итогового списка,
                        // так как он станет видимым вместо удаленного
                        // Но НЕ делаем этого, если этот дубликат уже в таблице
                        if (!updatedPresetsMap.containsKey(firstHiddenDuplicateIndex)) {
                            // Ничего не делаем - дубликат уже в finalPresetIndices
                            println("ADB_DEBUG:   First hidden duplicate at index $firstHiddenDuplicateIndex will become visible")
                        }
                    }
                    
                    // Теперь собираем итоговый список
                    finalPresetIndices.forEach { index ->
                        if (updatedPresetsMap.containsKey(index)) {
                            // Берем обновленную версию из таблицы
                            val updatedPreset = updatedPresetsMap[index]!!
                            newPresets.add(updatedPreset)
                            println("ADB_DEBUG:   Added updated preset from index $index: ${updatedPreset.label}")
                        } else {
                            // Берем оригинальную версию
                            val originalPreset = originalPresets[index]
                            newPresets.add(originalPreset)
                            println("ADB_DEBUG:   Added original preset from index $index: ${originalPreset.label}")
                        }
                    }
                    
                    // Заменяем список
                    list.presets.clear()
                    list.presets.addAll(newPresets)
                } else {
                    // В обычном режиме без скрытия дубликатов
                    // Проверяем, есть ли снимок скрытых дубликатов
                    if (visibleSnapshot != null && !isFirstLoad && !isSwitchingDuplicatesFilter) {
                        // Если есть снимок, значит дубликаты были скрыты ранее
                        val originalPresets = list.presets.map { it.copy() }
                        val updatedTablePresets = tablePresets.filter { preset ->
                            preset.label.isNotBlank() || preset.size.isNotBlank() || preset.dpi.isNotBlank()
                        }
                        
                        // Создаем новый список с учетом снимка
                        val newPresets = mutableListOf<DevicePreset>()
                        var tableIndex = 0
                        
                        originalPresets.forEach { originalPreset ->
                            val presetKey = "${originalPreset.label}|${originalPreset.size}|${originalPreset.dpi}"
                            if (visibleSnapshot.contains(presetKey) && tableIndex < updatedTablePresets.size) {
                                // Этот пресет был видим - обновляем из таблицы
                                newPresets.add(updatedTablePresets[tableIndex])
                                tableIndex++
                            } else {
                                // Этот пресет был скрыт - сохраняем оригинал
                                newPresets.add(originalPreset)
                            }
                        }
                        
                        // Заменяем список
                        list.presets.clear()
                        list.presets.addAll(newPresets)
                    } else {
                        // Обычное обновление без скрытых дубликатов
                        list.presets.clear()
                        // Включаем все пресеты, даже полностью пустые (для поддержки кнопки "+")
                        list.presets.addAll(tablePresets)
                    }
                }
            }
        }

        println("ADB_DEBUG: syncTableChangesToTempLists - after, currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        tempPresetLists.forEach { (k, v) -> println("ADB_DEBUG: TEMP_LIST $k: ${v.name}, presets: ${v.presets.size}") }
        
        // Проверяем, нужно ли обновить таблицу из-за изменения статуса дублей
        if (isHideDuplicatesMode && !isFirstLoad && !isSwitchingDuplicatesFilter) {
            // В режиме Show all всегда проверяем изменение статуса дублей
            // так как дубликаты могут быть в разных списках
            // Также всегда обновляем если был вызван distributePresetsToTempLists (в режиме Show all)
            if (isShowAllPresetsMode) {
                println("ADB_DEBUG: In Show all mode with hide duplicates, always reload table after sync")
                // Перезагружаем таблицу для отображения изменений
                SwingUtilities.invokeLater {
                    // Временно отключаем слушатель модели, чтобы избежать рекурсии
                    tableModelListener?.let { tableModel.removeTableModelListener(it) }
                    
                    isTableUpdating = true
                    try {
                        loadPresetsIntoTable()
                    } finally {
                        isTableUpdating = false
                        // Возвращаем слушатель на место
                        tableModelListener?.let { tableModel.addTableModelListener(it) }
                    }
                }
            } else if (checkIfDuplicateStatusChanged()) {
                println("ADB_DEBUG: Duplicate status changed after edit, reloading table")
                // Перезагружаем таблицу для отображения изменений
                SwingUtilities.invokeLater {
                    // Временно отключаем слушатель модели, чтобы избежать рекурсии
                    tableModelListener?.let { tableModel.removeTableModelListener(it) }
                    
                    isTableUpdating = true
                    try {
                        loadPresetsIntoTable()
                    } finally {
                        isTableUpdating = false
                        // Возвращаем слушатель на место
                        tableModelListener?.let { tableModel.addTableModelListener(it) }
                    }
                }
            }
        }
        
        // Обновляем снимок видимых пресетов после синхронизации
        if (isHideDuplicatesMode) {
            saveVisiblePresetsSnapshot()
        }
    }

    /**
     * Проверяет, изменился ли статус дублей после редактирования
     * @return true если статус изменился (появились новые не-дубли или исчезли старые дубли)
     */
    private fun checkIfDuplicateStatusChanged(): Boolean {
        if (isShowAllPresetsMode) {
            // В режиме Show all проверяем все пресеты из всех списков
            val allPresets = mutableListOf<DevicePreset>()
            tempPresetLists.values.forEach { list ->
                allPresets.addAll(list.presets)
            }
            
            // Получаем текущие дубликаты
            val currentDuplicates = findDuplicatesInList(allPresets)
            
            // Сравниваем с количеством видимых строк в таблице
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
                
                // Сравниваем с количеством видимых строк в таблице
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
        val seen = mutableSetOf<String>()
        val duplicateIndices = mutableSetOf<Int>()
        
        presets.forEachIndexed { index, preset ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = "${preset.size}|${preset.dpi}"
                if (seen.contains(key)) {
                    duplicateIndices.add(index)
                } else {
                    seen.add(key)
                }
            }
        }
        
        return duplicateIndices
    }

    /**
     * Удаляет пресет из временного списка в режиме Show all presets
     */
    fun deletePresetFromTempList(row: Int): Boolean {
        if (!isShowAllPresetsMode) return false

        // В режиме скрытия дубликатов не удаляем элемент здесь,
        // чтобы syncTableChangesToTempLists мог правильно обработать удаление
        if (isHideDuplicatesMode) {
            println("ADB_DEBUG: deletePresetFromTempList - skipping deletion in hide duplicates mode")
            return true // Возвращаем true, чтобы продолжить обработку
        }

        val preset = getPresetAtRow(row)
        val listName = getListNameAtRow(row) ?: return false

        // Находим временный список по имени
        val targetList = tempPresetLists.values.find { it.name == listName } ?: return false

        // Удаляем пресет из временного списка
        return targetList.presets.removeIf {
            it.label == preset.label &&
            it.size == preset.size &&
            it.dpi == preset.dpi
        }
    }

    /**
     * Инициализирует временные копии всех списков для работы в памяти
     */
    private fun initializeTempPresetLists() {
        println("ADB_DEBUG: initializeTempPresetLists - start")

        // Проверяем, что дефолтные списки существуют
        PresetListService.ensureDefaultListsExist()

        tempPresetLists.clear()
        originalPresetLists.clear()
        visiblePresetsSnapshot.clear()
        presetsOrderSnapshot.clear()

        // Загружаем все списки и создаем их копии
        val allMetadata = PresetListService.getAllListsMetadata()
        println("ADB_DEBUG: Found ${allMetadata.size} lists")

        // Включаем кэш для массовой загрузки
        PresetListService.enableCache()
        try {
            allMetadata.forEach { metadata ->
                val list = PresetListService.loadPresetList(metadata.id)
                if (list != null) {
                    // Создаем глубокую копию списка для работы
                    val copyList = PresetList(
                        id = list.id,
                        name = list.name,
                        presets = list.presets.map { preset ->
                            DevicePreset(
                                label = preset.label,
                                size = preset.size,
                                dpi = preset.dpi
                            )
                        }.toMutableList()
                    )
                    tempPresetLists[list.id] = copyList

                    // Создаем еще одну копию для сохранения исходного состояния
                    val originalList = PresetList(
                        id = list.id,
                        name = list.name,
                        presets = list.presets.map { preset ->
                            DevicePreset(
                                label = preset.label,
                                size = preset.size,
                                dpi = preset.dpi
                            )
                        }.toMutableList()
                    )
                    originalPresetLists[list.id] = originalList
                }
            }
        } finally {
            // Выключаем кэш после массовой загрузки
            PresetListService.disableCache()
        }

        // Получаем активный список из сервиса
        val activeList = PresetListService.getActivePresetList()
        println("ADB_DEBUG: Active list from service: ${activeList?.name}, id: ${activeList?.id}")

        // Находим соответствующий временный список
        if (activeList != null) {
            currentPresetList = tempPresetLists[activeList.id]
            println("ADB_DEBUG: Set currentPresetList to temp list: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        }

        // Если currentPresetList все еще null (нет активного списка), выбираем первый список
        if (currentPresetList == null && tempPresetLists.isNotEmpty()) {
            currentPresetList = tempPresetLists.values.first()
            println("ADB_DEBUG: Set currentPresetList to first available: ${currentPresetList?.name}")
        }

        println("ADB_DEBUG: initializeTempPresetLists - done. Current list: ${currentPresetList?.name}, temp lists count: ${tempPresetLists.size}")
    }

    /**
     * Загружает пресеты в таблицу в зависимости от текущего режима
     */
    private fun loadPresetsIntoTable() {
        println("ADB_DEBUG: loadPresetsIntoTable - currentPresetList: ${currentPresetList?.name}, isShowAllPresetsMode: $isShowAllPresetsMode")
        println("ADB_DEBUG: loadPresetsIntoTable - currentPresetList size: ${currentPresetList?.presets?.size}")
        println("ADB_DEBUG: loadPresetsIntoTable - isSwitchingMode: $isSwitchingMode")
        println("ADB_DEBUG: loadPresetsIntoTable - isSwitchingDuplicatesFilter: $isSwitchingDuplicatesFilter")
        
        // Сохраняем снимок видимых пресетов перед обновлением таблицы
        // только если мы уже в режиме скрытия дубликатов и не переключаемся
        if (isHideDuplicatesMode && !isSwitchingDuplicatesFilter && !isFirstLoad) {
            saveVisiblePresetsSnapshot()
        }

        // При переключении из Show all в обычный режим, убедимся что мы используем правильные данные
        if (!isShowAllPresetsMode && currentPresetList != null) {
            println("ADB_DEBUG: Loading presets from current list: ${currentPresetList?.name}")
            tempPresetLists[currentPresetList?.id]?.let { tempList ->
                println("ADB_DEBUG: Temp list has ${tempList.presets.size} presets")
                tempList.presets.forEach { preset ->
                    println("ADB_DEBUG: - ${preset.label} | ${preset.size} | ${preset.dpi}")
                }
            }
        }

        // Устанавливаем флаг блокировки обновлений только если он еще не установлен
        val wasTableUpdating = isTableUpdating
        if (!wasTableUpdating) {
            isTableUpdating = true
        }

        try {
            // Сначала настраиваем колонки в зависимости от режима
            setupTableColumns()

            tableModel.rowCount = 0

            if (isShowAllPresetsMode) {
                // В режиме "Show all presets" собираем все пресеты со всех списков
                val allPresetsWithListNames = mutableListOf<Pair<String, DevicePreset>>()

                // Если есть сохраненный порядок, используем его
                val savedOrder = PresetListService.getShowAllPresetsOrder()
                if (savedOrder.isNotEmpty()) {
                    // Сначала загружаем пресеты в сохраненном порядке
                    val addedPresets = mutableSetOf<String>()

                    savedOrder.forEach { key ->
                        val parts = key.split(":")
                        if (parts.size >= 4) {
                            val listName = parts[0]
                            val label = parts[1]
                            val size = parts[2]
                            val dpi = parts[3]

                            // Находим соответствующий пресет
                            tempPresetLists.values.find { it.name == listName }?.let { list ->
                                list.presets.find { preset ->
                                    preset.label == label && preset.size == size && preset.dpi == dpi
                                }?.let { preset ->
                                    allPresetsWithListNames.add(listName to preset)
                                    addedPresets.add(key)
                                }
                            }
                        }
                    }

                    // Теперь добавляем все пресеты, которых нет в сохраненном порядке
                    // Важно: добавляем их в правильном порядке, сохраняя позиции внутри списков
                    tempPresetLists.values.forEach { list ->
                        
                        // Проходим по всем пресетам списка в их оригинальном порядке
                        list.presets.forEach { preset ->
                            val key = "${list.name}:${preset.label}:${preset.size}:${preset.dpi}"
                            
                            if (!addedPresets.contains(key)) {
                                // Находим позицию для вставки
                                var insertPosition = allPresetsWithListNames.size
                                
                                // Ищем позицию после последнего добавленного элемента этого списка
                                for (i in allPresetsWithListNames.indices.reversed()) {
                                    if (allPresetsWithListNames[i].first == list.name) {
                                        insertPosition = i + 1
                                        break
                                    }
                                }
                                
                                // Вставляем на найденную позицию
                                allPresetsWithListNames.add(insertPosition, list.name to preset)
                                addedPresets.add(key)
                            }
                        }
                    }
                } else {
                    // Если сохраненного порядка нет, используем обычный порядок
                    tempPresetLists.values.forEach { list ->
                        list.presets.forEach { preset ->
                            allPresetsWithListNames.add(list.name to preset)
                        }
                    }
                }

                // Фильтруем дубликаты если включена галка "Hide duplicates"
                val presetsToShow = if (isHideDuplicatesMode) {
                    val duplicatesToHide = findGlobalDuplicates(allPresetsWithListNames)
                    allPresetsWithListNames.filterIndexed { index, (_, preset) ->
                        val key = "${preset.size}|${preset.dpi}#$index"
                        !duplicatesToHide.contains(key)
                    }
                } else {
                    allPresetsWithListNames
                }

                // Добавляем пресеты в таблицу
                presetsToShow.forEachIndexed { idx, (listName, preset) ->
                    val rowVector = Vector<Any>()
                    rowVector.add("☰")
                    rowVector.add(idx + 1)
                    rowVector.add(preset.label)
                    rowVector.add(preset.size)
                    rowVector.add(preset.dpi)
                    rowVector.add("Delete")
                    rowVector.add(listName) // Добавляем имя списка в колонку List
                    tableModel.addRow(rowVector)
                }
            } else {
                // В обычном режиме загружаем только текущий список
                val presets = currentPresetList?.presets ?: emptyList()

                // Фильтруем дубликаты если включена галка "Hide duplicates"
                val presetsToShow = if (isHideDuplicatesMode) {
                    val duplicatesToHide = findLocalDuplicates(presets)
                    presets.filterIndexed { index, preset ->
                        val key = "${preset.size}|${preset.dpi}#$index"
                        !duplicatesToHide.contains(key)
                    }
                } else {
                    presets
                }

                // Добавляем пресеты в таблицу
                presetsToShow.forEachIndexed { idx, preset ->
                    tableModel.addRow(arrayOf(
                        "☰",
                        idx + 1,
                        preset.label,
                        preset.size,
                        preset.dpi,
                        "Delete"
                    ))
                }
            }

            // Добавляем строку с кнопкой только в обычном режиме
            if (!isShowAllPresetsMode) {
                addButtonRow()
            }

            println("ADB_DEBUG: loadPresetsIntoTable - loaded ${tableModel.rowCount} rows")

            // При переключении списка временно устанавливаем флаг первой загрузки
            if (isSwitchingList && !isFirstLoad) {
                SwingUtilities.invokeLater {
                    // Сбрасываем после полной загрузки таблицы
                    isSwitchingList = false
                }
            }
        } finally {
            isTableUpdating = false
        }
    }

    /**
     * Добавляет специальную строку с кнопкой добавления
     */
    private fun addButtonRow() {
        val buttonRowVector = Vector<Any>()
        buttonRowVector.add("+") // Специальный маркер для рендерера
        buttonRowVector.add("") // Пустой номер
        buttonRowVector.add("") // Пустой label
        buttonRowVector.add("") // Пустой size
        buttonRowVector.add("") // Пустой dpi
        buttonRowVector.add("") // Пустая кнопка удаления

        // Добавляем пустое значение для колонки List если модель имеет 7 колонок
        if (tableModel.columnCount == 7) {
            buttonRowVector.add("")
        }

        tableModel.addRow(buttonRowVector)
        // Теперь обновляем номера строк для всех строк кроме строки с кнопкой
        tableModel.updateRowNumbers()
    }

    /**
     * Находит дубликаты среди пресетов по размеру и DPI
     * Возвращает Set ключей, которые нужно скрыть (все дубликаты кроме первого)
     */
    private fun findDuplicates(items: List<Pair<Int, DevicePreset>>): Set<String> {
        val seen = mutableSetOf<String>()
        val duplicatesToHide = mutableSetOf<String>()

        items.forEach { (index, preset) ->
            if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                val key = "${preset.size}|${preset.dpi}"
                if (seen.contains(key)) {
                    // Это дубликат - добавляем в список для скрытия с индексом
                    duplicatesToHide.add("$key#$index")
                } else {
                    // Первое вхождение - запоминаем
                    seen.add(key)
                }
            }
        }

        return duplicatesToHide
    }

    /**
     * Находит дубликаты в глобальном списке пресетов
     */
    private fun findGlobalDuplicates(allPresets: List<Pair<String, DevicePreset>>): Set<String> {
        val indexedPresets = allPresets.withIndex().map { (index, presetPair) ->
            index to presetPair.second
        }
        return findDuplicates(indexedPresets)
    }

    /**
     * Находит дубликаты в локальном списке пресетов
     */
    private fun findLocalDuplicates(presets: List<DevicePreset>): Set<String> {
        val indexedPresets = presets.withIndex().map { (index, preset) ->
            index to preset
        }
        return findDuplicates(indexedPresets)
    }
    
    /**
     * Сохраняет снимок видимых пресетов для всех списков перед переключением в режим Show all
     */
    private fun saveVisiblePresetsSnapshotForAllLists() {
        println("ADB_DEBUG: saveVisiblePresetsSnapshotForAllLists called")
        visiblePresetsSnapshot.clear()
        presetsOrderSnapshot.clear()
        
        if (!isHideDuplicatesMode) {
            return
        }
        
        // Если мы еще не в режиме Show all, нужно загрузить все списки
        val listsToProcess = if (tempPresetLists.isNotEmpty()) {
            tempPresetLists
        } else {
            // Загружаем все списки для сохранения снимка
            val allLists = mutableMapOf<String, PresetList>()
            val allMetadata = PresetListService.getAllListsMetadata()
            allMetadata.forEach { metadata ->
                val list = PresetListService.loadPresetList(metadata.id)
                if (list != null) {
                    allLists[list.id] = list
                }
            }
            allLists
        }
        
        // Проходим по всем спискам и сохраняем их видимые пресеты и полный порядок
        listsToProcess.forEach { (_, list) ->
            val visiblePresetKeys = mutableListOf<String>()
            val allPresetKeys = mutableListOf<String>()
            val seenKeys = mutableSetOf<String>()
            
            list.presets.forEach { preset ->
                val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                    "${preset.size}|${preset.dpi}"
                } else {
                    "unique_${preset.label}"
                }
                
                val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                allPresetKeys.add(presetKey)
                
                // Первое вхождение каждой комбинации size|dpi считается видимым
                if (!seenKeys.contains(key)) {
                    seenKeys.add(key)
                    visiblePresetKeys.add(presetKey)
                    println("ADB_DEBUG:   ${list.name} - visible: $presetKey")
                } else {
                    println("ADB_DEBUG:   ${list.name} - hidden duplicate: $presetKey")
                }
            }
            
            visiblePresetsSnapshot[list.name] = visiblePresetKeys
            presetsOrderSnapshot[list.name] = allPresetKeys
        }
        
        println("ADB_DEBUG: Saved snapshot for all lists:")
        visiblePresetsSnapshot.forEach { (listName, presets) ->
            println("ADB_DEBUG:   $listName: ${presets.size} visible presets")
        }
    }

    /**
     * Сохраняет снимок видимых пресетов для каждого списка
     * Используется для корректного сопоставления при отключении фильтра дубликатов
     */
    private fun saveVisiblePresetsSnapshot() {
        println("ADB_DEBUG: saveVisiblePresetsSnapshot called, isHideDuplicatesMode=$isHideDuplicatesMode")
        
        if (!isHideDuplicatesMode) {
            visiblePresetsSnapshot.clear()
            presetsOrderSnapshot.clear()
            return
        }
        
        if (isShowAllPresetsMode) {
            // В режиме Show all, если снимок уже сохранен saveVisiblePresetsSnapshotForAllLists,
            // не перезаписываем его
            if (visiblePresetsSnapshot.isNotEmpty()) {
                println("ADB_DEBUG: Snapshot already exists in Show all mode, not overwriting")
                println("ADB_DEBUG: Current snapshot has ${visiblePresetsSnapshot.size} lists")
                return
            }
            
            // В режиме Show all проходим по всем строкам таблицы
            val listVisiblePresets = mutableMapOf<String, MutableList<String>>()
            
            for (i in 0 until tableModel.rowCount) {
                val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
                if (firstColumn == "+") continue
                
                val listName = getListNameAtRow(i) ?: continue
                val preset = getPresetAtRow(i)
                
                if (preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()) {
                    continue
                }
                
                val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                listVisiblePresets.getOrPut(listName) { mutableListOf() }.add(presetKey)
            }
            
            // Сохраняем снимок
            listVisiblePresets.forEach { (listName, presetKeys) ->
                visiblePresetsSnapshot[listName] = presetKeys.toList()
            }
        } else {
            // В обычном режиме сохраняем только для текущего списка
            currentPresetList?.let { list ->
                val visiblePresetKeys = mutableListOf<String>()
                
                println("ADB_DEBUG: Saving snapshot for list: ${list.name}")
                
                for (i in 0 until tableModel.rowCount) {
                    val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
                    if (firstColumn == "+") continue
                    
                    val preset = getPresetAtRow(i)
                    
                    if (preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()) {
                        continue
                    }
                    
                    val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                    println("ADB_DEBUG:   Adding to snapshot: $presetKey")
                    visiblePresetKeys.add(presetKey)
                }
                
                visiblePresetsSnapshot[list.name] = visiblePresetKeys
            }
        }
        
        println("ADB_DEBUG: Saved visible presets snapshot:")
        visiblePresetsSnapshot.forEach { (listName, presets) ->
            println("ADB_DEBUG:   $listName: ${presets.size} visible presets")
            presets.forEach { preset ->
                println("ADB_DEBUG:     - $preset")
            }
        }
    }

    // === Обработчики событий ===

    fun handleCellClick(row: Int, column: Int, clickCount: Int) {
        if (row >= 0 && column >= 0) {
            // Проверяем, является ли это строкой с кнопкой
            val isButtonRow = if (row < tableModel.rowCount) {
                tableModel.getValueAt(row, 0) == "+"
            } else {
                false
            }

            // Не позволяем выбирать строку с кнопкой
            if (isButtonRow) {
                return
            }

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

        // Не сохраняем порядок при перемещении - только при Save

        refreshTable()
    }

    fun showContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row == -1) return

        // Проверяем, что это не строка с кнопкой
        if (row >= 0 && row < tableModel.rowCount) {
            val firstColumnValue = tableModel.getValueAt(row, 0)
            if (firstColumnValue == "+") {
                return // Не показываем контекстное меню для строки с кнопкой
            }
        }

        val preset = getPresetAtRow(row)
        val popupMenu = JPopupMenu()

        // В режиме "Show all presets" не показываем опцию дублирования
        if (!isShowAllPresetsMode) {
            val shortcut = if (SystemInfo.isMac) "Cmd+D" else "Ctrl+D"
            val duplicateItem = JMenuItem("Duplicate ($shortcut)")
            duplicateItem.addActionListener {
                duplicatePreset(row)
            }
            popupMenu.add(duplicateItem)
            popupMenu.addSeparator()
        }

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

        if (popupMenu.componentCount > 0) {
            popupMenu.show(e.component, e.x, e.y)
        }
    }

    // === Действия с пресетами ===

    fun addNewPreset() {
        // Запрещаем добавление в режиме "Show all presets"
        if (isShowAllPresetsMode) {
            JOptionPane.showMessageDialog(
                table,
                "Cannot add presets in 'Show all presets' mode.\nPlease switch to a specific list.",
                "Action Not Available",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }



        // Удаляем строку с кнопкой, если она есть
        val lastRowIndex = tableModel.rowCount - 1
        if (lastRowIndex >= 0 && tableModel.getValueAt(lastRowIndex, 0) == "+") {
            tableModel.removeRow(lastRowIndex)
        }

        val newRowIndex = tableModel.rowCount
        val newPreset = DevicePreset("", "", "")
        val newRowVector = createRowVector(newPreset, newRowIndex + 1)

        // Добавляем новую строку и строку с кнопкой одновременно
        tableModel.addRow(newRowVector)
        addButtonRow()

        historyManager.addPresetAdd(newRowIndex, newPreset)

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


    fun duplicatePreset(row: Int) {
        if (row < 0 || row >= tableModel.rowCount) return

        // Проверяем, что это не строка с кнопкой
        val firstColumnValue = tableModel.getValueAt(row, 0)
        if (firstColumnValue == "+") {
            return // Не дублируем строку с кнопкой
        }

        // Запрещаем дублирование в режиме "Show all presets"
        if (isShowAllPresetsMode) {
            JOptionPane.showMessageDialog(
                table,
                "Cannot duplicate presets in 'Show all presets' mode.\nPlease switch to a specific list.",
                "Action Not Available",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        // Если включен режим скрытия дублей, сначала отключаем его
        if (isHideDuplicatesMode) {
            listManagerPanel.setHideDuplicates(false)
        }

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
                // Определяем к какому списку принадлежит восстанавливаемый пресет
                val listName = if (isShowAllPresetsMode) {
                    // Сначала используем сохраненное имя списка из операции
                    operation.listName ?: run {
                        // Если имя списка не сохранено, пытаемся найти его
                        val listNameFromTable = if (operation.rowIndex < tableModel.rowCount && tableModel.columnCount > 6) {
                            // Если есть элементы после удаленной строки, берем список из следующей строки того же списка
                            var foundListName: String? = null
                            for (i in operation.rowIndex until tableModel.rowCount) {
                                val rowListName = tableModel.getValueAt(i, 6) as? String
                                if (!rowListName.isNullOrBlank()) {
                                    foundListName = rowListName
                                    break
                                }
                            }
                            // Если не нашли после, ищем перед удаленной строкой
                            if (foundListName == null && operation.rowIndex > 0) {
                                for (i in operation.rowIndex - 1 downTo 0) {
                                    val rowListName = tableModel.getValueAt(i, 6) as? String
                                    if (!rowListName.isNullOrBlank()) {
                                        foundListName = rowListName
                                        break
                                    }
                                }
                            }
                            foundListName
                        } else {
                            null
                        }
                        
                        // Если не нашли в таблице, ищем в списках по характеристикам пресета
                        listNameFromTable ?: tempPresetLists.entries.find { (_, list) ->
                            // Ищем список, где может быть такой пресет (включая скрытые дубли)
                            list.presets.any { p -> 
                                (p.size == operation.presetData.size && p.dpi == operation.presetData.dpi) ||
                                (p.label == operation.presetData.label)
                            }
                        }?.value?.name ?: currentPresetList?.name
                    }
                } else {
                    currentPresetList?.name
                }
                
                val newRowVector = createRowVector(operation.presetData, operation.rowIndex)
                // Если в режиме Show all, добавляем имя списка в последнюю колонку
                if (isShowAllPresetsMode && tableModel.columnCount == 7) {
                    newRowVector[6] = listName ?: ""
                }
                tableModel.insertRow(operation.rowIndex, newRowVector)
                
                // Восстанавливаем элемент в соответствующем списке
                if (isShowAllPresetsMode && listName != null) {
                    val targetList = tempPresetLists.values.find { it.name == listName }
                    if (targetList != null) {
                        // Определяем позицию для вставки
                        // Нужно найти правильную позицию на основе текущих элементов в таблице
                        var insertIndex = 0
                        
                        // Проходим по строкам таблицы до восстановленного элемента
                        for (i in 0 until operation.rowIndex) {
                            val rowListName = if (tableModel.columnCount > 6) {
                                tableModel.getValueAt(i, 6) as? String
                            } else null
                            
                            if (rowListName == listName) {
                                insertIndex++
                            }
                        }
                        
                        // Вставляем элемент в список
                        if (insertIndex <= targetList.presets.size) {
                            targetList.presets.add(insertIndex, operation.presetData.copy())
                            println("ADB_DEBUG: Restored preset to list $listName at position $insertIndex")
                        } else {
                            targetList.presets.add(operation.presetData.copy())
                            println("ADB_DEBUG: Restored preset to end of list $listName")
                        }
                    } else {
                        println("ADB_DEBUG: WARNING - Could not find target list: $listName")
                    }
                }
                
                // При восстановлении удаленного элемента в режиме скрытия дублей
                // нужно обновить снимок, чтобы включить восстановленный элемент
                if (isHideDuplicatesMode) {
                    println("ADB_DEBUG: Undo PresetDelete - updating snapshot after restore (isShowAllPresetsMode=$isShowAllPresetsMode)")
                    
                    if (isShowAllPresetsMode) {
                        println("ADB_DEBUG: Looking for list name: $listName")
                        println("ADB_DEBUG: Snapshot contains key? ${visiblePresetsSnapshot.containsKey(listName)}")
                        println("ADB_DEBUG: All snapshot keys: ${visiblePresetsSnapshot.keys}")
                        
                        if (listName != null) {
                            val targetList = tempPresetLists.values.find { it.name == listName }
                            if (targetList != null) {
                                // Пересоздаем снимок на основе актуального состояния списка
                                // а не таблицы, чтобы учесть все скрытые элементы
                                val updatedVisibleKeys = mutableListOf<String>()
                                val seenKeys = mutableSetOf<String>()
                                
                                targetList.presets.forEach { preset ->
                                    val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                                        "${preset.size}|${preset.dpi}"
                                    } else {
                                        "unique_${preset.label}"
                                    }
                                    
                                    val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                                    
                                    // Первое вхождение каждой комбинации size|dpi считается видимым
                                    if (!seenKeys.contains(key)) {
                                        seenKeys.add(key)
                                        updatedVisibleKeys.add(presetKey)
                                    }
                                }
                                
                                visiblePresetsSnapshot[listName] = updatedVisibleKeys
                                
                                // Также обновляем полный порядок элементов
                                val fullOrder = targetList.presets.map { preset ->
                                    "${preset.label}|${preset.size}|${preset.dpi}"
                                }
                                presetsOrderSnapshot[listName] = fullOrder
                                
                                println("ADB_DEBUG: Updated snapshot for $listName after undo:")
                                println("ADB_DEBUG:   Visible keys: $updatedVisibleKeys")
                                println("ADB_DEBUG:   Full order: $fullOrder")
                            } else {
                                println("ADB_DEBUG: WARNING - Could not find target list for snapshot update: $listName")
                            }
                        }
                    } else {
                        // В обычном режиме (не Show all) также нужно правильно восстановить элемент
                        currentPresetList?.let { list ->
                            // Восстанавливаем элемент в правильную позицию
                            if (operation.rowIndex <= list.presets.size) {
                                list.presets.add(operation.rowIndex, operation.presetData.copy())
                                println("ADB_DEBUG: Restored preset to current list at position ${operation.rowIndex}")
                            } else {
                                list.presets.add(operation.presetData.copy())
                                println("ADB_DEBUG: Restored preset to end of current list")
                            }
                            
                            // Обновляем снимок для текущего списка
                            val updatedVisibleKeys = mutableListOf<String>()
                            val seenKeys = mutableSetOf<String>()
                            
                            list.presets.forEach { preset ->
                                val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                                    "${preset.size}|${preset.dpi}"
                                } else {
                                    "unique_${preset.label}"
                                }
                                
                                val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                                
                                // Первое вхождение каждой комбинации size|dpi считается видимым
                                if (!seenKeys.contains(key)) {
                                    seenKeys.add(key)
                                    updatedVisibleKeys.add(presetKey)
                                }
                            }
                            
                            visiblePresetsSnapshot[list.name] = updatedVisibleKeys
                            
                            // Также обновляем полный порядок элементов
                            val fullOrder = list.presets.map { preset ->
                                "${preset.label}|${preset.size}|${preset.dpi}"
                            }
                            presetsOrderSnapshot[list.name] = fullOrder
                            
                            println("ADB_DEBUG: Updated snapshot for ${list.name} after undo in non-Show all mode:")
                            println("ADB_DEBUG:   Visible keys: $updatedVisibleKeys")
                            println("ADB_DEBUG:   Full order: $fullOrder")
                            println("ADB_DEBUG:   Total presets in list: ${list.presets.size}")
                        }
                    }
                }
                
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
                    // Удаляем строку из таблицы
                    tableModel.removeRow(operation.duplicateIndex)
                    
                    // Удаляем дублированный пресет из временного списка
                    currentPresetList?.let { list ->
                        val duplicatePreset = operation.presetData.copy(label = "${operation.presetData.label} (copy)")
                        list.presets.remove(duplicatePreset)
                        println("ADB_DEBUG: Removed duplicate preset from temp list: ${duplicatePreset.label}")
                    }
                    
                    // В режиме скрытия дублей нужно перезагрузить таблицу,
                    // чтобы правильно отобразить изменения
                    if (isHideDuplicatesMode) {
                        loadPresetsIntoTable()
                    } else {
                        refreshTable()
                    }
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
                
                // Добавляем дублированный пресет во временный список
                currentPresetList?.let { list ->
                    list.presets.add(operation.duplicateIndex, newPreset)
                    println("ADB_DEBUG: Added duplicate preset to temp list: ${newPreset.label}")
                }
                
                // В режиме скрытия дублей нужно перезагрузить таблицу,
                // чтобы правильно отобразить изменения
                if (isHideDuplicatesMode) {
                    loadPresetsIntoTable()
                } else {
                    refreshTable()
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

        var allValid = true
        for (i in 0 until tableModel.rowCount) {
            // Пропускаем строку с кнопкой
            val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
            if (firstColumn == "+") {
                continue
            }

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

        // Сохраняем текущее состояние таблицы
        saveCurrentTableState()

        // Удаляем пустые строки из всех временных списков
        tempPresetLists.values.forEach { list ->
            list.presets.removeIf { preset ->
                preset.label.trim().isEmpty() &&
                preset.size.trim().isEmpty() &&
                preset.dpi.trim().isEmpty()
            }
        }

        // Сохраняем все временные списки в файлы
        tempPresetLists.values.forEach { list ->
            PresetListService.savePresetList(list)
        }

        // Сохраняем порядок для режима "Show all presets" только при сохранении
        if (isShowAllPresetsMode) {
            saveShowAllPresetsOrder()
        }
    }

    /**
     * Распределяет пресеты из таблицы по временным спискам в режиме Show all presets
     */
    private fun distributePresetsToTempLists() {
        if (isHideDuplicatesMode) {
            // Проверяем наличие снимка и создаем его при необходимости
            if (visiblePresetsSnapshot.isEmpty()) {
                println("ADB_DEBUG: distributePresetsToTempLists - snapshot is empty, creating from current state")
                saveVisiblePresetsSnapshotForAllLists()
            }
            
            // В режиме скрытия дубликатов используем снимок видимых пресетов
            // Сначала определяем какие пресеты должны остаться видимыми
            val listVisibleIndices = mutableMapOf<String, MutableList<Int>>()
            
            tempPresetLists.forEach { (listId, list) ->
                val visibleIndices = mutableListOf<Int>()
                val seenKeys = mutableSetOf<String>()
                
                list.presets.forEachIndexed { index, preset ->
                    val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        "${preset.size}|${preset.dpi}"
                    } else {
                        "unique_${listId}_$index"
                    }
                    
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key)
                        visibleIndices.add(index)
                    }
                }
                
                listVisibleIndices[list.name] = visibleIndices
            }
            
            // Сохраняем копии оригинальных списков
            val originalLists = mutableMapOf<String, List<DevicePreset>>()
            tempPresetLists.forEach { (_, list) ->
                originalLists[list.name] = list.presets.map { it.copy() }
            }
            
            // Собираем обновленные данные из таблицы по спискам
            // Собираем обновленные данные из таблицы
            val updatedPresetsPerList = mutableMapOf<String, MutableList<DevicePreset>>()
            
            for (i in 0 until tableModel.rowCount) {
                val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
                if (firstColumn == "+") continue
                
                val listName = getListNameAtRow(i) ?: continue
                val preset = tableModel.getPresetAt(i) ?: continue
                
                if (preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()) {
                    continue
                }
                
                updatedPresetsPerList.getOrPut(listName) { mutableListOf() }.add(preset)
            }
            
            // Обновляем каждый список, сохраняя скрытые элементы
            tempPresetLists.forEach { (_, list) ->
                val originalPresets = originalLists[list.name] ?: emptyList()
                val visibleIndices = listVisibleIndices[list.name] ?: emptyList()
                
                // Используем снимок видимых пресетов если он есть
                val visibleSnapshot = visiblePresetsSnapshot[list.name]
                val updatedPresets = updatedPresetsPerList[list.name] ?: emptyList()
                
                println("ADB_DEBUG: distributePresetsToTempLists - list: ${list.name}")
                println("ADB_DEBUG:   original presets: ${originalPresets.size}")
                println("ADB_DEBUG:   visible indices: $visibleIndices")
                println("ADB_DEBUG:   visible snapshot size: ${visibleSnapshot?.size ?: "none"}")
                if (visibleSnapshot != null) {
                    println("ADB_DEBUG:   visible snapshot content:")
                    visibleSnapshot.forEach { key ->
                        println("ADB_DEBUG:     - $key")
                    }
                }
                println("ADB_DEBUG:   updated presets: ${updatedPresets.size}")
                
                // НЕ создаем новый список - будем обновлять существующий на месте
                // чтобы сохранить порядок элементов
                
                // Improved logic: properly handle deletions and updates
                if (visibleSnapshot != null) {
                    println("ADB_DEBUG:   Using snapshot-based update logic")
                    
                    // Step 1: Determine if elements were deleted
                    val wasDeleted = updatedPresets.size < visibleSnapshot.size
                    println("ADB_DEBUG:   Elements deleted: $wasDeleted (snapshot: ${visibleSnapshot.size}, updated: ${updatedPresets.size})")
                    
                    if (wasDeleted) {
                        // Handle deletion case: rebuild list maintaining hidden elements
                        println("ADB_DEBUG:   Handling deletion case")
                        
                        // Create a map of current table presets for quick lookup
                        val tablePresetsMap = updatedPresets.associateBy { 
                            "${it.label}|${it.size}|${it.dpi}" 
                        }
                        
                        // Build new list maintaining order and hidden elements
                        val newPresets = mutableListOf<DevicePreset>()
                        val processedTableKeys = mutableSetOf<String>()
                        
                        // First pass: preserve all elements that still exist in table or were hidden
                        list.presets.forEach { preset ->
                            val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                            
                            when {
                                // Element exists in table - use updated version
                                tablePresetsMap.containsKey(presetKey) -> {
                                    newPresets.add(tablePresetsMap[presetKey]!!.copy())
                                    processedTableKeys.add(presetKey)
                                    println("ADB_DEBUG:   Preserved visible element: $presetKey")
                                }
                                // Element was hidden (not in snapshot) - preserve it
                                !visibleSnapshot.contains(presetKey) -> {
                                    newPresets.add(preset.copy())
                                    println("ADB_DEBUG:   Preserved hidden element: $presetKey")
                                }
                                // Element was visible but now deleted - check if hidden duplicate should be revealed
                                else -> {
                                    println("ADB_DEBUG:   Element deleted from table: $presetKey")
                                    // Check if there's a hidden duplicate that should be revealed
                                    val presetCombination = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                                        "${preset.size}|${preset.dpi}"
                                    } else null
                                    
                                    if (presetCombination != null) {
                                        // Find first hidden duplicate with same size|dpi
                                        val hiddenDuplicate = list.presets.find { p ->
                                            val pKey = "${p.label}|${p.size}|${p.dpi}"
                                            p.size.isNotBlank() && p.dpi.isNotBlank() &&
                                            "${p.size}|${p.dpi}" == presetCombination &&
                                            !visibleSnapshot.contains(pKey) &&
                                            !newPresets.any { np -> 
                                                "${np.label}|${np.size}|${np.dpi}" == pKey 
                                            }
                                        }
                                        
                                        if (hiddenDuplicate != null) {
                                            val hiddenKey = "${hiddenDuplicate.label}|${hiddenDuplicate.size}|${hiddenDuplicate.dpi}"
                                            println("ADB_DEBUG:   Found hidden duplicate to reveal: $hiddenKey")
                                            // Hidden duplicate will be added when we process it in the loop
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Second pass: add any new elements from table that weren't processed
                        updatedPresets.forEach { preset ->
                            val key = "${preset.label}|${preset.size}|${preset.dpi}"
                            if (!processedTableKeys.contains(key)) {
                                newPresets.add(preset.copy())
                                println("ADB_DEBUG:   Added new element: $key")
                            }
                        }
                        
                        // Replace the list
                        list.presets.clear()
                        list.presets.addAll(newPresets)
                        
                    } else {
                        // Handle update/add case: update visible elements in place
                        println("ADB_DEBUG:   Handling update/add case")
                        
                        // Map snapshot indices to table indices for proper ordering
                        val snapshotToTableIndex = mutableMapOf<String, Int>()
                        updatedPresets.forEachIndexed { index, preset ->
                            val key = "${preset.label}|${preset.size}|${preset.dpi}"
                            snapshotToTableIndex[key] = index
                        }
                        
                        // First, check what elements are in table vs snapshot
                        val tableKeys = updatedPresets.map { "${it.label}|${it.size}|${it.dpi}" }.toSet()
                        val snapshotKeys = visibleSnapshot.toSet()
                        val missingKeys = snapshotKeys - tableKeys
                        
                        // If we have missing keys from snapshot, it might be a restore operation
                        if (missingKeys.isNotEmpty()) {
                            println("ADB_DEBUG:   Keys missing from table (might be restore): $missingKeys")
                        }
                        
                        // Create mapping of table presets for quick lookup
                        val tablePresetsMap = updatedPresets.mapIndexed { index, preset ->
                            "${preset.label}|${preset.size}|${preset.dpi}" to (index to preset)
                        }.toMap()
                        
                        // When elements are edited, we need to match by position in snapshot
                        val processedListIndices = mutableSetOf<Int>()
                        val processedTableIndices = mutableSetOf<Int>()
                        
                        // First pass: update elements that match exactly (no edits)
                        list.presets.forEachIndexed { index, preset ->
                            val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                            
                            if (visibleSnapshot.contains(presetKey)) {
                                // This element should be visible - find it in table
                                val tableData = tablePresetsMap[presetKey]
                                if (tableData != null) {
                                    val (tableIndex, tablePreset) = tableData
                                    list.presets[index] = tablePreset.copy()
                                    processedTableIndices.add(tableIndex)
                                    processedListIndices.add(index)
                                    println("ADB_DEBUG:   Updated visible element at index $index: $presetKey")
                                }
                            }
                        }
                        
                        // Second pass: handle edited elements by matching snapshot position to table position
                        visibleSnapshot.forEachIndexed { snapshotIndex, snapshotKey ->
                            if (snapshotIndex < updatedPresets.size) {
                                val tablePreset = updatedPresets[snapshotIndex]
                                val tableKey = "${tablePreset.label}|${tablePreset.size}|${tablePreset.dpi}"
                                
                                // If this table element wasn't processed yet, it might be an edit
                                if (!processedTableIndices.contains(snapshotIndex) && snapshotKey != tableKey) {
                                    // Find the original element in the list by snapshot key
                                    val listIndex = list.presets.indexOfFirst { p ->
                                        "${p.label}|${p.size}|${p.dpi}" == snapshotKey
                                    }
                                    
                                    if (listIndex >= 0 && !processedListIndices.contains(listIndex)) {
                                        // This is an edited element - update it
                                        list.presets[listIndex] = tablePreset.copy()
                                        processedTableIndices.add(snapshotIndex)
                                        processedListIndices.add(listIndex)
                                        println("ADB_DEBUG:   Updated edited element at index $listIndex: $snapshotKey -> $tableKey")
                                    }
                                }
                            }
                        }
                        
                        // Third pass: add truly new elements (that aren't edits of existing elements)
                        updatedPresets.forEachIndexed { tableIndex, preset ->
                            if (!processedTableIndices.contains(tableIndex)) {
                                val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                                list.presets.add(preset.copy())
                                println("ADB_DEBUG:   Added truly new element: $presetKey")
                            }
                        }
                    }
                } else {
                    // Если снимка нет, просто обновляем видимые элементы
                    var visibleIndex = 0
                    originalPresets.forEachIndexed { index, _ ->
                        if (visibleIndices.contains(index) && visibleIndex < updatedPresets.size) {
                            list.presets[index] = updatedPresets[visibleIndex].copy()
                            visibleIndex++
                        }
                    }
                }
                
                println("ADB_DEBUG:   final list size: ${list.presets.size}")
                
                // Обновляем снимок после любых изменений
                val updatedVisibleKeys = mutableListOf<String>()
                val seenKeys = mutableSetOf<String>()
                
                list.presets.forEach { preset ->
                    val key = if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        "${preset.size}|${preset.dpi}"
                    } else {
                        "unique_${preset.label}"
                    }
                    
                    val presetKey = "${preset.label}|${preset.size}|${preset.dpi}"
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key)
                        updatedVisibleKeys.add(presetKey)
                    }
                }
                
                visiblePresetsSnapshot[list.name] = updatedVisibleKeys
                
                // Обновляем также полный снимок порядка элементов
                val fullOrder = list.presets.map { preset ->
                    "${preset.label}|${preset.size}|${preset.dpi}"
                }
                presetsOrderSnapshot[list.name] = fullOrder
                
                println("ADB_DEBUG:   Updated snapshot with ${updatedVisibleKeys.size} visible presets")
            }
        } else {
            // В обычном режиме без скрытия дубликатов
            // Проверяем, вызывается ли это сразу после отключения Hide duplicates
            if (isSwitchingDuplicatesFilter) {
                println("ADB_DEBUG: distributePresetsToTempLists - called after switching Hide duplicates off, skipping")
                // Не делаем ничего - данные уже синхронизированы
                return
            }
            
            // Обычная логика для режима Show all без Hide duplicates
            tempPresetLists.values.forEach { it.presets.clear() }

            for (i in 0 until tableModel.rowCount) {
                val firstColumn = tableModel.getValueAt(i, 0) as? String ?: ""
                if (firstColumn == "+") continue

                val listName = getListNameAtRow(i) ?: continue
                val preset = tableModel.getPresetAt(i) ?: continue

                if (preset.label.isBlank() && preset.size.isBlank() && preset.dpi.isBlank()) {
                    continue
                }

                val targetList = tempPresetLists.values.find { it.name == listName }
                targetList?.presets?.add(preset)
            }
        }
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
            label = tableModel.getValueAt(row, 2) as? String ?: "",
            size = tableModel.getValueAt(row, 3) as? String ?: "",
            dpi = tableModel.getValueAt(row, 4) as? String ?: ""
        )
    }

    private fun getListNameAtRow(row: Int): String? {
        // В режиме Show all presets получаем название списка из последней колонки
        if (isShowAllPresetsMode && row >= 0 && row < tableModel.rowCount && tableModel.columnCount > 6) {
            val value = tableModel.getValueAt(row, 6)
            return value as? String
        }
        return null
    }

    private fun createRowVector(preset: DevicePreset, rowNumber: Int = 0): Vector<Any> {
        val vector = DevicePresetTableModel.createRowVector(preset, rowNumber)
        // Добавляем пустое значение для колонки List если модель имеет 7 колонок
        if (isShowAllPresetsMode && tableModel.columnCount == 7) {
            vector.add(currentPresetList?.name ?: "")
        }
        return vector
    }

    private fun refreshTable() {
        SwingUtilities.invokeLater {
            validateFields()
            table.repaint()
        }
    }

    /**
     * Сохраняет текущий порядок пресетов в режиме Show all presets
     */
    private fun saveShowAllPresetsOrder() {
        // Если включен режим скрытия дубликатов, не сохраняем порядок
        // так как в таблице показаны не все пресеты
        if (isHideDuplicatesMode) {
            // Очищаем сохраненный порядок, чтобы при следующей загрузке
            // использовался обычный порядок со всеми пресетами
            PresetListService.saveShowAllPresetsOrder(emptyList())
            return
        }

        val order = mutableListOf<String>()
        for (row in 0 until tableModel.rowCount) {
            // Пропускаем строку с кнопкой
            val firstColumn = tableModel.getValueAt(row, 0) as? String ?: ""
            if (firstColumn == "+") continue

            val listName = getListNameAtRow(row) ?: continue
            val preset = getPresetAtRow(row)
            val key = "$listName:${preset.label}:${preset.size}:${preset.dpi}"
            order.add(key)
        }
        PresetListService.saveShowAllPresetsOrder(order)
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

    /**
     * Восстанавливает исходное состояние временных списков при отмене
     */
    fun restoreOriginalState() {
        println("ADB_DEBUG: Restoring original state")

        // Очищаем текущие временные списки и снимки
        tempPresetLists.clear()
        visiblePresetsSnapshot.clear()
        presetsOrderSnapshot.clear()

        // Восстанавливаем из сохраненных оригиналов
        originalPresetLists.forEach { (id, originalList) ->
            val restoredList = PresetList(
                id = originalList.id,
                name = originalList.name,
                presets = originalList.presets.map { preset ->
                    DevicePreset(
                        label = preset.label,
                        size = preset.size,
                        dpi = preset.dpi
                    )
                }.toMutableList()
            )
            tempPresetLists[id] = restoredList
        }

        // Восстанавливаем текущий список по ID
        val currentId = currentPresetList?.id
        if (currentId != null && tempPresetLists.containsKey(currentId)) {
            currentPresetList = tempPresetLists[currentId]
            println("ADB_DEBUG: Restored currentPresetList: ${currentPresetList?.name}, presets: ${currentPresetList?.presets?.size}")
        }

        // Очищаем сохраненный порядок для "Show all presets", чтобы вернуться к исходному состоянию
        PresetListService.saveShowAllPresetsOrder(emptyList())

        println("ADB_DEBUG: Original state restored")
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
}
