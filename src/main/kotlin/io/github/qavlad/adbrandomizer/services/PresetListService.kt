package io.github.qavlad.adbrandomizer.services

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import io.github.qavlad.adbrandomizer.ui.services.TableSortingService
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

/**
 * Сервис для управления несколькими списками пресетов
 */
object PresetListService {
    private const val ACTIVE_LIST_KEY = "ADB_RANDOMIZER_ACTIVE_LIST_ID"
    private const val LISTS_METADATA_KEY = "ADB_RANDOMIZER_LISTS_METADATA"
    
    private val properties = PropertiesComponent.getInstance()
    
    // Кастомный десериализатор для DevicePreset
    class DevicePresetDeserializer : JsonDeserializer<DevicePreset> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): DevicePreset {
            val jsonObject = json.asJsonObject
            val label = jsonObject.get("label").asString
            val size = jsonObject.get("size").asString
            val dpi = jsonObject.get("dpi").asString
            val id = if (jsonObject.has("id") && !jsonObject.get("id").isJsonNull) {
                jsonObject.get("id").asString
            } else {
                UUID.randomUUID().toString()
            }
            return DevicePreset(label, size, dpi, id)
        }
    }
    
    class DevicePresetSerializer : JsonSerializer<DevicePreset> {
        override fun serialize(src: DevicePreset, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val jsonObject = JsonObject()
            jsonObject.addProperty("label", src.label)
            jsonObject.addProperty("size", src.size)
            jsonObject.addProperty("dpi", src.dpi)
            jsonObject.addProperty("id", src.id)
            return jsonObject
        }
    }
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(DevicePreset::class.java, DevicePresetSerializer())
        .registerTypeAdapter(DevicePreset::class.java, DevicePresetDeserializer())
        .serializeNulls()
        .setPrettyPrinting()
        .create()
        
    private val presetsDir = Paths.get(PathManager.getConfigPath(), "ADBRandomizer", "presets")
    
    // Кэш для загруженных списков, чтобы избежать повторного чтения файлов
    private val loadedListsCache = mutableMapOf<String, PresetList>()
    private var cacheEnabled = false
    
    // Кэш для активного списка
    private var activeListCache: PresetList? = null
    private var activeListCacheId: String? = null
    
    init {
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "Initializing with presets directory: %s", presetsDir.toAbsolutePath())
        // Создаем директорию для пресетов если её нет
        Files.createDirectories(presetsDir)
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "Directory created/exists: %s", presetsDir.toFile().exists())
        // Проверяем, есть ли сохраненные метаданные
        val savedMetadata = properties.getValue(LISTS_METADATA_KEY)
        if (savedMetadata.isNullOrBlank()) {
            PluginLogger.info(LogCategory.PRESET_SERVICE, "No saved metadata found, initializing default lists")
            // Только если нет сохраненных метаданных, инициализируем дефолтные списки
            initializeDefaultLists()
        } else {
            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Found saved metadata")
        }
    }
    
    /**
     * Метаданные о списке (без самих пресетов)
     */
    data class ListMetadata(
        val id: String,
        val name: String,
        val isDefault: Boolean = false
    )
    
    /**
     * Получает все метаданные списков
     */
    fun getAllListsMetadata(): List<ListMetadata> {
        val json = properties.getValue(LISTS_METADATA_KEY)
        PluginLogger.trace(LogCategory.PRESET_SERVICE, "getAllListsMetadata - json present: %s", !json.isNullOrBlank())
        return if (json.isNullOrBlank()) {
            // Инициализируем дефолтными списками
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Initializing default lists")
            initializeDefaultLists()
        } else {
            try {
                val type = object : TypeToken<List<ListMetadata>>() {}.type
                val metadata: List<ListMetadata> = gson.fromJson(json, type)
                PluginLogger.debugWithRateLimit(LogCategory.PRESET_SERVICE, "metadata_load", "Loaded %d lists from metadata", metadata.size)
                metadata
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.PRESET_SERVICE, "Error loading metadata, initializing defaults: %s", e, e.message)
                initializeDefaultLists()
            }
        }
    }
    
    /**
     * Инициализирует дефолтные списки пресетов
     */
    private fun initializeDefaultLists(): List<ListMetadata> {
        val defaultLists = listOf(
            PresetList(
                name = "Popular Phones",
                presets = mutableListOf(
                    DevicePreset("Pixel 7 Pro", "1440x3120", "512"),
                    DevicePreset("Pixel 6", "1080x2400", "411"),
                    DevicePreset("Pixel 5", "1080x2340", "432"),
                    DevicePreset("Pixel 4a", "1080x2340", "413"),
                    DevicePreset("Pixel 3a", "1080x2220", "441")
                ),
                isDefault = true
            ),
            PresetList(
                name = "Samsung Devices", 
                presets = mutableListOf(
                    DevicePreset("Galaxy S23 Ultra", "1440x3088", "501"),
                    DevicePreset("Galaxy S22", "1080x2340", "425"),
                    DevicePreset("Galaxy S21", "1080x2400", "421"),
                    DevicePreset("Galaxy S20", "1440x3200", "563"),
                    DevicePreset("Galaxy Note 20", "1080x2400", "393")
                )
            ),
            PresetList(
                name = "Tablets",
                presets = mutableListOf(
                    DevicePreset("iPad Pro 12.9", "2048x2732", "264"),
                    DevicePreset("iPad Air", "1640x2360", "264"),
                    DevicePreset("Galaxy Tab S8", "1752x2800", "266"),
                    DevicePreset("Generic 10\" Tablet", "1200x1920", "224"),
                    DevicePreset("Generic 7\" Tablet", "1024x600", "170")
                )
            ),
            PresetList(
                name = "OnePlus",
                presets = mutableListOf(
                    DevicePreset("OnePlus 11", "1440x3216", "525"),
                    DevicePreset("OnePlus 10 Pro", "1440x3216", "525"),
                    DevicePreset("OnePlus 9", "1080x2400", "402"),
                    DevicePreset("OnePlus 8T", "1080x2400", "402"),
                    DevicePreset("OnePlus 7 Pro", "1440x3120", "516")
                )
            ),
            PresetList(
                name = "Custom Presets",
                presets = mutableListOf()
            )
        )
        
        // Сохраняем метаданные
        val metadata = defaultLists.map { ListMetadata(it.id, it.name, it.isDefault) }
        saveListsMetadata(metadata)
        
        // Сохраняем сами списки
        defaultLists.forEach { savePresetList(it) }
        
        // Устанавливаем первый список как активный
        setActiveListId(defaultLists.first().id)
        
        return metadata
    }
    
    /**
     * Сохраняет метаданные списков
     */
    private fun saveListsMetadata(metadata: List<ListMetadata>) {
        val json = gson.toJson(metadata)
        properties.setValue(LISTS_METADATA_KEY, json)
    }
    
    /**
     * Получает активный ID списка
     */
    fun getActiveListId(): String? {
        return properties.getValue(ACTIVE_LIST_KEY)
    }
    
    /**
     * Устанавливает активный список
     */
    fun setActiveListId(listId: String) {
        properties.setValue(ACTIVE_LIST_KEY, listId)
    }
    
    /**
     * Получает активный список пресетов
     */
    fun getActivePresetList(): PresetList? {
        val activeId = getActiveListId() ?: return null
        
        // Проверяем кэш активного списка
        if (activeListCacheId == activeId && activeListCache != null) {
            return activeListCache
        }
        
        // Загружаем и кэшируем
        val list = loadPresetList(activeId)
        if (list != null) {
            activeListCache = list
            activeListCacheId = activeId
        }
        return list
    }
    
    /**
     * Загружает список пресетов по ID
     */
    fun loadPresetList(listId: String): PresetList? {
        // Проверяем кэш если он включен
        if (cacheEnabled && loadedListsCache.containsKey(listId)) {
            return loadedListsCache[listId]
        }
        
        return try {
            val file = presetsDir.resolve("$listId.json").toFile()
            // Логируем только при отсутствии в кэше
            if (!cacheEnabled) {
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Loading preset list from: %s", file.absolutePath)
            }
            if (file.exists()) {
                val json = file.readText()
                val presetList = gson.fromJson(json, PresetList::class.java)
                
                // Отладочная информация о загруженных ID
                println("ADB_DEBUG: Loaded preset list ${presetList.name} from file with ${presetList.presets.size} presets")
                presetList.presets.forEachIndexed { index, preset ->
                    println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
                }
                
                if (!cacheEnabled) {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "Successfully loaded list %s with %d presets", presetList.name, presetList.presets.size)
                }
                // Сохраняем в кэш
                if (cacheEnabled) {
                    loadedListsCache[listId] = presetList
                }
                presetList
            } else {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "File does not exist: %s", file.absolutePath)
                null
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.PRESET_SERVICE, "Error loading preset list %s: %s", e, listId, e.message)
            null
        }
    }
    
    /**
     * Сохраняет список пресетов
     */
    fun savePresetList(presetList: PresetList) {
        val file = presetsDir.resolve("${presetList.id}.json").toFile()
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "Saving preset list '%s' to: %s", presetList.name, file.absolutePath)
        
        // Отладочная информация о сохраняемых ID
        println("ADB_DEBUG: Saving preset list ${presetList.name} with ${presetList.presets.size} presets")
        presetList.presets.forEachIndexed { index, preset ->
            println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        }
        
        try {
            // Убеждаемся, что директория существует
            file.parentFile.mkdirs()
            val json = gson.toJson(presetList)
            file.writeText(json)
            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Successfully saved %d presets", presetList.presets.size)
            
            // Очищаем кэш для этого списка
            loadedListsCache.remove(presetList.id)
            if (activeListCacheId == presetList.id) {
                activeListCache = null
                activeListCacheId = null
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.PRESET_SERVICE, "Error saving preset list: %s", e, e.message)
        }
    }
    
    /**
     * Создает новый список пресетов
     */
    fun createNewList(name: String): PresetList {
        val newList = PresetList(name = name)
        
        // Обновляем метаданные
        val metadata = getAllListsMetadata().toMutableList()
        metadata.add(ListMetadata(newList.id, newList.name))
        saveListsMetadata(metadata)
        
        // Сохраняем список
        savePresetList(newList)
        
        return newList
    }
    
    /**
     * Удаляет список пресетов
     */
    fun deleteList(listId: String): Boolean {
        val metadata = getAllListsMetadata().toMutableList()
        val listToRemove = metadata.find { it.id == listId } ?: return false
        
        // Не удаляем дефолтные списки
        if (listToRemove.isDefault) return false
        
        // Удаляем из метаданных
        metadata.removeIf { it.id == listId }
        saveListsMetadata(metadata)
        
        // Удаляем файл
        val file = presetsDir.resolve("$listId.json").toFile()
        if (file.exists()) {
            file.delete()
        }
        
        // Если удалили активный список, переключаемся на первый доступный
        if (getActiveListId() == listId && metadata.isNotEmpty()) {
            setActiveListId(metadata.first().id)
        }
        
        return true
    }
    
    /**
     * Переименовывает список
     */
    fun renameList(listId: String, newName: String): Boolean {
        val list = loadPresetList(listId) ?: return false
        list.name = newName
        savePresetList(list)
        
        // Обновляем метаданные
        val metadata = getAllListsMetadata().toMutableList()
        metadata.find { it.id == listId }?.let {
            val index = metadata.indexOf(it)
            metadata[index] = ListMetadata(listId, newName, it.isDefault)
            saveListsMetadata(metadata)
        }
        
        return true
    }
    
    /**
     * Экспортирует списки пресетов
     */
    fun exportLists(listIds: List<String>, targetFile: File) {
        val listsToExport = listIds.mapNotNull { loadPresetList(it) }
        val json = gson.toJson(listsToExport)
        targetFile.writeText(json)
    }
    
    /**
     * Импортирует списки пресетов
     */
    fun importLists(sourceFile: File): List<PresetList> {
        val json = sourceFile.readText()
        val type = object : TypeToken<List<PresetList>>() {}.type
        val importedLists: List<PresetList> = gson.fromJson(json, type)
        
        val metadata = getAllListsMetadata().toMutableList()
        
        importedLists.forEach { importedList ->
            // Генерируем новый ID для импортированного списка
            val newList = importedList.copy("${importedList.name} (imported)")
            savePresetList(newList)
            metadata.add(ListMetadata(newList.id, newList.name))
        }
        
        saveListsMetadata(metadata)
        return importedLists
    }


    /**
     * Проверяет существование списка с таким именем
     */
    fun isListNameExists(name: String, excludeId: String? = null): Boolean {
        return getAllListsMetadata().any { 
            it.name.equals(name, ignoreCase = true) && it.id != excludeId
        }
    }
    
    /**
     * Очищает все кэши
     */
    fun clearAllCaches() {
        loadedListsCache.clear()
        activeListCache = null
        activeListCacheId = null
    }
    
    /**
     * Получает пресеты для обратной совместимости со старым SettingsService
     */
    fun getPresetsForCompatibility(): List<DevicePreset> {
        return getActivePresetList()?.presets ?: emptyList()
    }
    
    /**
     * Сохраняет пресеты для обратной совместимости со старым SettingsService
     */
    fun savePresetsForCompatibility(presets: List<DevicePreset>) {
        val activeList = getActivePresetList() ?: return
        activeList.presets = presets.toMutableList()
        savePresetList(activeList)
    }
    
    /**
     * Получает все пресеты из всех списков
     */
    fun getAllPresetsFromAllLists(): List<DevicePreset> {
        val allPresets = mutableListOf<DevicePreset>()
        val metadata = getAllListsMetadata()
        
        metadata.forEach { listMeta ->
            val list = loadPresetList(listMeta.id)
            list?.presets?.forEach { preset ->
                allPresets.add(preset)
            }
        }
        
        return allPresets
    }
    
    /**
     * Получает отсортированный список пресетов с учетом текущего режима и сортировки
     */
    fun getSortedPresets(): List<DevicePreset> {
        val isShowAllMode = SettingsService.getShowAllPresetsMode()
        val isHideDuplicatesMode = SettingsService.getHideDuplicatesMode()
        
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "getSortedPresets - isShowAllMode: %s, isHideDuplicatesMode: %s", isShowAllMode, isHideDuplicatesMode)
        
        // Получаем базовый список пресетов
        val basePresets = if (isShowAllMode) {
            // Проверяем, есть ли сохраненный порядок после drag & drop
            val savedOrder = getShowAllPresetsOrder()
            
            if (savedOrder.isNotEmpty()) {
                // Используем сохраненный порядок (после drag & drop) как базу
                println("ADB_DEBUG: PresetListService.getSortedPresets - using saved order (${savedOrder.size} items)")
                val presetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
                val allLists = getAllListsMetadata().associateBy { it.name }
                
                savedOrder.forEach { key ->
                    val parts = key.split("::")
                    if (parts.size >= 4) {
                        val listName = parts[0]
                        val label = parts[1]
                        val size = parts[2]
                        val dpi = parts[3]
                        
                        val listMeta = allLists[listName]
                        if (listMeta != null) {
                            val list = loadPresetList(listMeta.id)
                            val preset = list?.presets?.find { p ->
                                p.label == label && p.size == size && p.dpi == dpi
                            }
                            if (preset != null) {
                                presetsWithLists.add(listName to preset)
                            }
                        }
                    }
                }
                
                // Проверяем, есть ли активная сортировка
                val sortState = TableSortingService.getSortState(isShowAll = true, isHideDuplicates = isHideDuplicatesMode)
                
                // Если есть активная сортировка, применяем её
                val sortedPresetsWithLists = if (sortState.activeColumn != null) {
                    println("ADB_DEBUG: PresetListService.getSortedPresets - applying active sort: ${sortState.activeColumn}")
                    TableSortingService.sortPresetsWithLists(presetsWithLists, isHideDuplicatesMode)
                } else {
                    println("ADB_DEBUG: PresetListService.getSortedPresets - no active sort, using saved order")
                    presetsWithLists
                }
                
                processSortedPresetsWithLists(sortedPresetsWithLists, isHideDuplicatesMode)
            } else {
                // Обычная логика - собираем и сортируем
                val allLists = getAllListsMetadata()
                val presetsWithLists = mutableListOf<Pair<String, DevicePreset>>()
                
                allLists.forEach { listMeta ->
                    val list = loadPresetList(listMeta.id)
                    list?.presets?.forEach { preset ->
                        presetsWithLists.add(listMeta.name to preset)
                    }
                }
                
                println("ADB_DEBUG: PresetListService.getSortedPresets - collected ${presetsWithLists.size} presets from all lists")
                
                // Применяем сортировку через TableSortingService
                val sortedPresetsWithLists = TableSortingService.sortPresetsWithLists(presetsWithLists, isHideDuplicatesMode)
                
                processSortedPresetsWithLists(sortedPresetsWithLists, isHideDuplicatesMode)
            }
        } else {
            // В обычном режиме получаем пресеты из активного списка
            val activeList = getActivePresetList()
            val presets = activeList?.presets ?: emptyList()
            
            // Применяем сортировку
            val sortedPresets = TableSortingService.sortPresets(presets, isShowAll = false, isHideDuplicates = isHideDuplicatesMode)
            
            // Фильтруем дубликаты если нужно
            if (isHideDuplicatesMode) {
                val seen = mutableSetOf<String>()
                sortedPresets.filter { preset ->
                    if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                        val key = preset.getDuplicateKey()
                        seen.add(key) // add() возвращает true если элемент был добавлен (не было дубликата)
                    } else {
                        true
                    }
                }
            } else {
                sortedPresets
            }
        }
        
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "getSortedPresets - returning %d presets", basePresets.size)
        return basePresets
    }

    
    private const val SHOW_ALL_PRESETS_ORDER_KEY = "ADB_RANDOMIZER_SHOW_ALL_PRESETS_ORDER"
    
    private fun processSortedPresetsWithLists(
        sortedPresetsWithLists: List<Pair<String, DevicePreset>>,
        isHideDuplicatesMode: Boolean
    ): List<DevicePreset> {
        println("ADB_DEBUG: PresetListService.getSortedPresets - after sorting, first 5 presets:")
        sortedPresetsWithLists.take(5).forEachIndexed { index, (listName, p) ->
            val dpiUses = UsageCounterService.getDpiCounter(p.dpi)
            println("ADB_DEBUG:   ${index + 1}. ${p.label} (${p.size}, ${p.dpi}) from $listName - DPI Uses: $dpiUses")
        }
        
        // Извлекаем только пресеты
        val sortedPresets = sortedPresetsWithLists.map { it.second }
        
        // Фильтруем дубликаты если нужно
        return if (isHideDuplicatesMode) {
            val seen = mutableSetOf<String>()
            sortedPresets.filter { preset ->
                if (preset.size.isNotBlank() && preset.dpi.isNotBlank()) {
                    val key = preset.getDuplicateKey()
                    // Добавляем в seen и возвращаем true только если это первое вхождение
                    seen.add(key) // add() возвращает true если элемент был добавлен (не было дубликата)
                } else {
                    true
                }
            }
        } else {
            sortedPresets
        }
    }
    
    /**
     * Получает сохраненный порядок пресетов для режима Show all presets
     */
    fun getShowAllPresetsOrder(): List<String> {
        val json = properties.getValue(SHOW_ALL_PRESETS_ORDER_KEY)
        return if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    
    /**
     * Сохраняет порядок пресетов для режима Show all presets
     */
    fun saveShowAllPresetsOrder(order: List<String>) {
        val json = gson.toJson(order)
        properties.setValue(SHOW_ALL_PRESETS_ORDER_KEY, json)
    }

    /**
     * Полный сброс всех списков и пересоздание дефолтных
     */
    fun resetToDefaultLists() {
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Resetting to default lists")
        // Очищаем метаданные
        properties.unsetValue(LISTS_METADATA_KEY)
        properties.unsetValue(ACTIVE_LIST_KEY)
        properties.unsetValue(SHOW_ALL_PRESETS_ORDER_KEY)
        // Удаляем все файлы в папке presets
        if (presetsDir.toFile().exists()) {
            presetsDir.toFile().listFiles()?.forEach { 
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Deleting file: %s", it.name)
                it.delete() 
            }
        }
        // Пересоздаём дефолтные списки
        initializeDefaultLists()
    }
    
    /**
     * Проверяет и восстанавливает дефолтные списки если они не существуют
     */
    fun ensureDefaultListsExist() {
        val metadata = getAllListsMetadata()
        if (metadata.isEmpty()) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "No lists found, resetting to defaults")
            resetToDefaultLists()
            return
        }
        
        // Проверяем, что все списки имеют файлы
        var hasValidFiles = false
        metadata.forEach { meta ->
            val file = presetsDir.resolve("${meta.id}.json").toFile()
            if (file.exists()) {
                hasValidFiles = true
            } else {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "Missing file for list '%s' (%s)", meta.name, meta.id)
            }
        }
        
        if (!hasValidFiles) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "No valid preset files found, resetting to defaults")
            resetToDefaultLists()
        }
    }
}
