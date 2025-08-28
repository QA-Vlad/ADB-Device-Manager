package io.github.qavlad.adbdevicemanager.ui.services

import io.github.qavlad.adbdevicemanager.services.DevicePreset
import io.github.qavlad.adbdevicemanager.services.PresetList
import io.github.qavlad.adbdevicemanager.services.PresetListService

/**
 * Результат инициализации списков пресетов
 */
data class InitializationResult(
    val tempLists: Map<String, PresetList>,
    val originalPresetLists: Map<String, PresetList>,
    val currentPresetList: PresetList?,
    val normalModeOrderChanged: Boolean = false,
    val modifiedListIds: Set<String> = emptySet()
)

/**
 * Сервис для инициализации временных списков пресетов
 */
class PresetListInitializationService(
    private val tempListsManager: TempListsManager,
    private val snapshotManager: SnapshotManager,
    private val presetOrderManager: PresetOrderManager,
    private val hiddenDuplicatesManager: HiddenDuplicatesManager,
    private val stateManager: StateManager,
    private val duplicateManager: DuplicateManager
) {
    /**
     * Инициализирует временные копии всех списков для работы в памяти
     */
    fun initializeTempPresetLists(): InitializationResult {
        println("ADB_DEBUG: initializeTempPresetLists - start")
        println("ADB_DEBUG:   tempListsManager.size() before clear: ${tempListsManager.size()}")

        // Включаем кэш для оптимизации производительности
        PresetListService.enableCache()
        
        // Создаем снимок состояния сортировки для возможности отката
        TableSortingService.createSortStateSnapshot()

        // Сбрасываем состояние
        val modifiedListIds = mutableSetOf<String>()
        println("ADB_DEBUG: Reset normalModeOrderChanged = false and cleared modifiedListIds")

        // Проверяем, что дефолтные списки существуют
        PresetListService.ensureDefaultListsExist()

        // Очищаем менеджеры
        tempListsManager.clear()
        duplicateManager.clearSnapshots()
        
        // Загружаем все списки через StateManager
        val loadedLists = stateManager.initializeTempPresetLists()
        println("ADB_DEBUG:   loadedLists.size: ${loadedLists.size}")
        tempListsManager.setTempLists(loadedLists)
        println("ADB_DEBUG:   tempListsManager.size() after setTempLists: ${tempListsManager.size()}")
        
        // Создаем копии для отката
        val originalPresetLists = snapshotManager.saveOriginalState(tempListsManager.getTempLists())
        
        // Сохраняем исходный порядок из файлов для каждого списка
        saveOriginalFileOrders(loadedLists)
        
        // Инициализируем информацию о скрытых дублях
        hiddenDuplicatesManager.initializeHiddenDuplicates(loadedLists)
        
        // Сохраняем начальный порядок для каждого списка в обычном режиме
        restoreOrInitializeNormalModeOrders(loadedLists)
        
        // Определяем начальный текущий список
        val currentPresetList = stateManager.determineInitialCurrentList(tempListsManager.getTempLists())
        
        println("ADB_DEBUG: After determineInitialCurrentList - currentPresetList: ${currentPresetList?.name}")
        println("ADB_DEBUG: currentPresetList contents after assignment:")
        currentPresetList?.presets?.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        
        println("ADB_DEBUG: initializeTempPresetLists - done. Current list: ${currentPresetList?.name}, temp lists count: ${tempListsManager.size()}")
        
        return InitializationResult(
            tempLists = loadedLists,
            originalPresetLists = originalPresetLists,
            currentPresetList = currentPresetList,
            normalModeOrderChanged = false,
            modifiedListIds = modifiedListIds
        )
    }
    
    /**
     * Сохраняет исходный порядок из файлов для каждого списка
     */
    private fun saveOriginalFileOrders(loadedLists: Map<String, PresetList>) {
        loadedLists.values.forEach { list ->
            presetOrderManager.saveOriginalFileOrder(list.id, list.presets)
        }
    }
    
    /**
     * Восстанавливает существующий порядок или инициализирует новый для обычного режима
     */
    private fun restoreOrInitializeNormalModeOrders(loadedLists: Map<String, PresetList>) {
        loadedLists.values.forEach { list ->
            val existingOrder = presetOrderManager.getNormalModeOrder(list.id)
            if (existingOrder == null) {
                // Порядок ещё не сохранён - сохраняем текущий как начальный
                presetOrderManager.saveNormalModeOrder(list.id, list.presets)
                println("ADB_DEBUG: Saved initial normal mode order for list '${list.name}' with ${list.presets.size} presets")
            } else {
                println("ADB_DEBUG: Normal mode order already exists for list '${list.name}' with ${existingOrder.size} items - not overwriting")
                // Загружаем существующий порядок в память для использования при отображении
                val orderedPresets = restorePresetsFromOrder(list, existingOrder)
                
                if (orderedPresets.isNotEmpty()) {
                    presetOrderManager.updateNormalModeOrderInMemory(list.id, orderedPresets)
                }
            }
        }
    }
    
    /**
     * Восстанавливает пресеты из сохранённого порядка
     */
    private fun restorePresetsFromOrder(list: PresetList, existingOrder: List<String>): List<DevicePreset> {
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
        
        return orderedPresets
    }
    
    /**
     * Очищает все ресурсы при закрытии
     */
    fun dispose() {
        PresetListService.disableCache()
        presetOrderManager.clearOriginalFileOrders()
    }
}