package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.services.PresetList
import io.github.qavlad.adbdevicemanager.ui.components.HoverState
import io.github.qavlad.adbdevicemanager.ui.dialogs.TableModelListenerWithTimer

/**
 * Управляет состоянием PresetsDialogController
 * Централизует все флаги и переменные состояния
 */
class ControllerStateManager {
    // === Текущее состояние ===
    var currentPresetList: PresetList? = null
    var hoverState: HoverState = HoverState.noHover()
    
    // === Флаги состояния ===
    var normalModeOrderChanged = false
        private set
    
    var isDuplicatingPreset = false
        private set
    
    // === Коллекции состояния ===
    val modifiedListIds = mutableSetOf<String>()
    var inMemoryTableOrder = listOf<String>()
    val originalPresetLists = mutableMapOf<String, PresetList>()
    
    // === Слушатели модели таблицы ===
    var tableModelListener: javax.swing.event.TableModelListener? = null
    var tableModelListenerWithTimer: TableModelListenerWithTimer? = null
    
    /**
     * Отмечает, что порядок в обычном режиме был изменён
     */
    fun markNormalModeOrderChanged() {
        normalModeOrderChanged = true
    }
    
    /**
     * Добавляет ID списка в набор изменённых
     */
    fun addModifiedListId(listId: String) {
        modifiedListIds.add(listId)
    }
    
    /**
     * Сбрасывает флаги изменений после сохранения
     */
    fun resetChangeFlags() {
        normalModeOrderChanged = false
        modifiedListIds.clear()
        println("ADB_DEBUG: Reset normalModeOrderChanged flag and cleared modifiedListIds")
    }
    
    /**
     * Устанавливает флаг дублирования пресета
     */
    fun setDuplicatingPreset(value: Boolean) {
        isDuplicatingPreset = value
    }
    
    /**
     * Выполняет операцию с флагом дублирования
     */
    inline fun <T> withDuplicatingPreset(block: () -> T): T {
        setDuplicatingPreset(true)
        return try {
            block()
        } finally {
            setDuplicatingPreset(false)
        }
    }
    
    /**
     * Обновляет состояние hover
     */
    fun updateHoverState(newState: HoverState) {
        hoverState = newState
    }
    
    /**
     * Сохраняет исходное состояние списков
     */
    fun saveOriginalState(lists: Map<String, PresetList>) {
        originalPresetLists.clear()
        originalPresetLists.putAll(lists)
    }
    
    
    /**
     * Обновляет порядок таблицы в памяти
     */
    fun updateInMemoryTableOrder(order: List<String>) {
        inMemoryTableOrder = order
        println("ADB_DEBUG: Updated inMemoryTableOrder with ${order.size} items")
    }
    
    /**
     * Очищает состояние при закрытии
     */
    fun clear() {
        currentPresetList = null
        hoverState = HoverState.noHover()
        normalModeOrderChanged = false
        isDuplicatingPreset = false
        modifiedListIds.clear()
        inMemoryTableOrder = emptyList()
        originalPresetLists.clear()
        tableModelListener = null
        tableModelListenerWithTimer = null
    }
}