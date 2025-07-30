package io.github.qavlad.adbrandomizer.ui.dialogs

import io.github.qavlad.adbrandomizer.services.PresetStorageService

/**
 * Управляет состоянием диалога настроек
 * Централизует работу с флагами и состоянием
 */
class DialogStateManager {
    
    // Режимы отображения
    private var showAllPresetsMode = false
    private var hideDuplicatesMode = false
    
    // Флаги процессов
    private var firstLoad = true
    private var tableUpdating = false
    private var switchingMode = false
    private var switchingList = false
    private var switchingDuplicatesFilter = false
    private var performingHistoryOperation = false
    private var processingDelete = false
    private var dragAndDropInProgress = false
    
    // Временные состояния редактирования
    private var editingCellOldValue: String? = null
    private var editingCellRow: Int = -1
    private var editingCellColumn: Int = -1
    
    init {
        // Загружаем сохраненные состояния режимов
        showAllPresetsMode = PresetStorageService.getShowAllPresetsMode()
        hideDuplicatesMode = PresetStorageService.getHideDuplicatesMode()
    }
    
    // === Режимы отображения ===
    
    fun isShowAllPresetsMode() = showAllPresetsMode
    
    fun setShowAllPresetsMode(value: Boolean) {
        showAllPresetsMode = value
        PresetStorageService.setShowAllPresetsMode(value)
    }
    
    fun isHideDuplicatesMode() = hideDuplicatesMode
    
    fun setHideDuplicatesMode(value: Boolean) {
        hideDuplicatesMode = value
        PresetStorageService.setHideDuplicatesMode(value)
    }
    
    // === Флаги процессов ===
    
    fun isFirstLoad() = firstLoad
    
    fun completeFirstLoad() {
        firstLoad = false
    }
    
    fun isTableUpdating() = tableUpdating
    
    fun withTableUpdate(block: () -> Unit) {
        tableUpdating = true
        try {
            block()
        } finally {
            tableUpdating = false
        }
    }
    
    // Для команд - прямая установка флага
    fun setTableUpdating(value: Boolean) {
        tableUpdating = value
    }
    
    fun isSwitchingMode() = switchingMode
    
    fun withModeSwitching(block: () -> Unit) {
        switchingMode = true
        try {
            block()
        } finally {
            switchingMode = false
        }
    }
    
    fun isSwitchingList() = switchingList
    
    fun withListSwitching(block: () -> Unit) {
        switchingList = true
        try {
            block()
        } finally {
            switchingList = false
        }
    }
    
    fun isSwitchingDuplicatesFilter() = switchingDuplicatesFilter
    
    fun withDuplicatesFilterSwitching(block: () -> Unit) {
        switchingDuplicatesFilter = true
        try {
            block()
        } finally {
            switchingDuplicatesFilter = false
        }
    }
    
    fun isPerformingHistoryOperation() = performingHistoryOperation
    
    // Для команд - прямая установка флага
    fun setPerformingHistoryOperation(value: Boolean) {
        performingHistoryOperation = value
    }
    
    fun isProcessingDelete() = processingDelete
    
    fun withDeleteProcessing(block: () -> Unit) {
        processingDelete = true
        try {
            block()
        } finally {
            processingDelete = false
        }
    }
    
    fun isDragAndDropInProgress() = dragAndDropInProgress
    
    fun startDragAndDrop() {
        println("ADB_DEBUG: Drag and drop started, setting isDragAndDropInProgress = true")
        dragAndDropInProgress = true
    }
    
    fun endDragAndDrop() {
        println("ADB_DEBUG: Setting isDragAndDropInProgress = false")
        dragAndDropInProgress = false
    }
    
    // === Состояние редактирования ===
    
    fun getEditingCellState() = EditingCellState(editingCellOldValue, editingCellRow, editingCellColumn)
    
    fun setEditingCell(row: Int, column: Int, oldValue: String?) {
        editingCellRow = row
        editingCellColumn = column
        editingCellOldValue = oldValue
    }
    
    fun clearEditingCell() {
        editingCellRow = -1
        editingCellColumn = -1
        editingCellOldValue = null
    }
    
    /**
     * Данные о редактируемой ячейке
     */
    data class EditingCellState(
        val oldValue: String?,
        val row: Int,
        val column: Int
    )
}