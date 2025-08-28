package io.github.qavlad.adbdevicemanager.ui.commands

import io.github.qavlad.adbdevicemanager.services.DevicePreset
import io.github.qavlad.adbdevicemanager.services.PresetList
import io.github.qavlad.adbdevicemanager.ui.components.DevicePresetTableModel
import io.github.qavlad.adbdevicemanager.ui.components.CommandHistoryManager
import javax.swing.JTable

/**
 * Интерфейс для доступа команд к состоянию и функциональности контроллера диалога
 * Обеспечивает изоляцию команд от внутренней реализации контроллера
 */
interface CommandContext {
    // === Доступ к состоянию ===
    
    /**
     * Получить текущий активный список пресетов
     */
    fun getCurrentPresetList(): PresetList?
    
    /**
     * Получить все временные списки пресетов
     */
    fun getTempPresetLists(): Map<String, PresetList>
    
    /**
     * Проверить, включен ли режим показа всех пресетов
     */
    fun isShowAllPresetsMode(): Boolean
    
    /**
     * Проверить, включен ли режим скрытия дубликатов
     */
    fun isHideDuplicatesMode(): Boolean
    
    /**
     * Модель таблицы
     */
    val tableModel: DevicePresetTableModel
    
    /**
     * Получить видимые пресеты в таблице
     */
    fun getVisiblePresets(): List<DevicePreset>
    
    // === Управление состоянием ===
    
    /**
     * Установить флаг обновления таблицы
     */
    fun setTableUpdating(value: Boolean)
    
    /**
     * Установить флаг выполнения операции истории
     */
    fun setPerformingHistoryOperation(value: Boolean)
    
    /**
     * Установить текущий список пресетов
     */
    fun setCurrentPresetList(list: PresetList)
    
    // === Операции с таблицей ===
    
    /**
     * Обновить отображение таблицы
     */
    fun refreshTable()
    
    /**
     * Загрузить пресеты в таблицу
     * @param presets список пресетов для загрузки или null для загрузки из текущего состояния
     */
    fun loadPresetsIntoTable(presets: List<DevicePreset>? = null)
    
    /**
     * Синхронизировать изменения таблицы с временными списками
     */
    fun syncTableChangesToTempLists()
    
    /**
     * Обновить UI для отражения выбранного списка
     */
    fun updateSelectedListInUI()
    
    /**
     * Доступ к таблице
     */
    val table: JTable
    
    /**
     * Доступ к менеджеру истории
     */
    val historyManager: CommandHistoryManager
    
    /**
     * Переключить на указанный список по ID
     */
    fun switchToList(listId: String)
    
    /**
     * Установить режим Show All
     */
    fun setShowAllMode(enabled: Boolean)
    
    /**
     * Получить сервис сортировки таблицы
     */
    fun getTableSortingService(): io.github.qavlad.adbdevicemanager.ui.services.TableSortingService?
    
    /**
     * Получить корзину для удалённых пресетов
     */
    fun getPresetRecycleBin(): io.github.qavlad.adbdevicemanager.ui.services.PresetRecycleBin
}