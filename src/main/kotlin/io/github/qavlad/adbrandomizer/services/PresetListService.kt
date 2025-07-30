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
import com.intellij.openapi.util.io.FileUtil
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
            PluginLogger.info(LogCategory.PRESET_SERVICE, "No saved metadata found, initializing with presets from resources")
            // При первом запуске загружаем пресеты из ресурсов
            initializeDefaultLists()
        } else {
            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Found saved metadata")
            // Мигрируем существующие файлы на новое именование
            migrateAllFilesToNewNaming()
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
            // Возвращаем пустой список если нет метаданных
            PluginLogger.info(LogCategory.PRESET_SERVICE, "No metadata found, returning empty list")
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<ListMetadata>>() {}.type
                val metadata: List<ListMetadata> = gson.fromJson(json, type)
                PluginLogger.debugWithRateLimit(LogCategory.PRESET_SERVICE, "metadata_load", "Loaded %d lists from metadata", metadata.size)
                metadata
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.PRESET_SERVICE, "Error loading metadata: %s", e, e.message)
                emptyList()
            }
        }
    }
    
    /**
     * Инициализирует дефолтные списки пресетов из JSON файлов
     */
    private fun initializeDefaultLists(): List<ListMetadata> {
        val defaultLists = mutableListOf<PresetList>()
        
        // Сначала пытаемся загрузить из ресурсов
        val loadedFromResources = loadPresetsFromResources()
        if (loadedFromResources.isNotEmpty()) {
            defaultLists.addAll(loadedFromResources)
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Loaded %d preset lists from resources", loadedFromResources.size)
        }
        
        // В режиме разработки также проверяем папку проекта
        if (isDevelopmentMode()) {
            val projectPresetsDir = Paths.get(System.getProperty("user.dir"), "presets").toFile()
            if (projectPresetsDir.exists() && projectPresetsDir.isDirectory) {
                val loadedFromProject = loadPresetsFromDirectory(projectPresetsDir)
                if (loadedFromProject.isNotEmpty()) {
                    // Добавляем только те, которых еще нет
                    loadedFromProject.forEach { projectList ->
                        if (defaultLists.none { it.name == projectList.name }) {
                            defaultLists.add(projectList)
                        }
                    }
                    PluginLogger.info(LogCategory.PRESET_SERVICE, "Loaded %d additional preset lists from project directory", loadedFromProject.size)
                }
            }
        }
        
        // Если ничего не загрузилось, создаем минимальный набор
        if (defaultLists.isEmpty()) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "No preset files found, creating minimal default list")
            defaultLists.add(
                PresetList(
                    name = "Default Presets",
                    presets = mutableListOf(
                        DevicePreset("Pixel 5", "1080x2340", "432"),
                        DevicePreset("Generic Tablet", "1200x1920", "240")
                    ),
                    isDefault = true
                )
            )
        }
        
        // Убеждаемся, что есть хотя бы один список с isDefault = true
        if (defaultLists.none { it.isDefault }) {
            defaultLists.firstOrNull()?.isDefault = true
        }
        
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
            val file = getFileForListId(listId)
            // Логируем только при отсутствии в кэше
            if (!cacheEnabled && file != null) {
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Loading preset list from: %s", file.absolutePath)
            }
            if (file != null && file.exists()) {
                val json = file.readText()
                val presetList = gson.fromJson(json, PresetList::class.java)
                
                // Отладочная информация о загруженных ID - закомментировано из-за спама в Show All режиме
                // println("ADB_DEBUG: Loaded preset list ${presetList.name} from file with ${presetList.presets.size} presets")
                // presetList.presets.forEachIndexed { index, preset ->
                //     println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
                // }
                
                if (!cacheEnabled) {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "Successfully loaded list %s with %d presets", presetList.name, presetList.presets.size)
                }
                // Сохраняем в кэш
                if (cacheEnabled) {
                    loadedListsCache[listId] = presetList
                }
                presetList
            } else {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "File does not exist for list ID: %s", listId)
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
        // Сначала проверяем, нужна ли миграция
        migrateFileIfNeeded(presetList.id, presetList)
        
        // Получаем файл с новым именованием
        val newFileName = getFileNameForList(presetList)
        val file = presetsDir.resolve(newFileName).toFile()
        
        // Удаляем старый файл если он все еще существует с UUID именем
        val oldFile = presetsDir.resolve("${presetList.id}.json").toFile()
        if (oldFile.exists() && !FileUtil.filesEqual(oldFile, file)) {
            oldFile.delete()
        }
        
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "Saving preset list '%s' to: %s", presetList.name, file.absolutePath)
        
        // Отладочная информация о сохраняемых ID - закомментировано из-за спама в Show All режиме
        // println("ADB_DEBUG: Saving preset list ${presetList.name} with ${presetList.presets.size} presets")
        // presetList.presets.forEachIndexed { index, preset ->
        //     println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
        // }
        
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
        PluginLogger.info(LogCategory.PRESET_SERVICE, "deleteList called for listId: %s", listId)
        
        val metadata = getAllListsMetadata().toMutableList()
        val listToDelete = metadata.find { it.id == listId }
        if (listToDelete == null) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "List with id %s not found in metadata", listId)
            return false
        }
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Deleting list '%s' (id: %s)", listToDelete.name, listId)
        
        // Удаляем из метаданных
        metadata.removeIf { it.id == listId }
        saveListsMetadata(metadata)
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Removed list from metadata")
        
        // Удаляем файл (проверяем оба варианта именования)
        val file = getFileForListId(listId)
        if (file != null && file.exists()) {
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Found file to delete: %s", file.absolutePath)
            try {
                val deleted = file.delete()
                PluginLogger.info(LogCategory.PRESET_SERVICE, "File deletion result: %s", deleted)
                if (!deleted) {
                    PluginLogger.error(LogCategory.PRESET_SERVICE, "Failed to delete file: %s", null, file.absolutePath)
                }
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.PRESET_SERVICE, "Exception while deleting file: %s", e, e.message)
            }
        } else {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "File for list %s not found by getFileForListId", listId)
            // Логируем все файлы в директории для отладки
            val allFiles = presetsDir.toFile().listFiles()
            if (allFiles != null) {
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Files in presets directory:")
                allFiles.forEach { f ->
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "  - %s", f.name)
                }
            }
        }
        
        // Также удаляем старый файл с UUID если он есть
        val oldFile = presetsDir.resolve("$listId.json").toFile()
        if (oldFile.exists()) {
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Deleting old UUID file: %s", oldFile.absolutePath)
            val deleted = oldFile.delete()
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Old file deletion result: %s", deleted)
        }
        
        // Очищаем кэши
        loadedListsCache.remove(listId)
        if (activeListCacheId == listId) {
            activeListCache = null
            activeListCacheId = null
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
        
        // Получаем старый файл для удаления
        val oldFile = getFileForListId(listId)
        
        // Обновляем имя списка
        list.name = newName
        
        // Сохраняем с новым именем файла
        savePresetList(list)
        
        // Удаляем старый файл если он отличается от нового
        if (oldFile != null && oldFile.exists()) {
            val newFileName = getFileNameForList(list)
            val newFile = presetsDir.resolve(newFileName).toFile()
            if (!FileUtil.filesEqual(oldFile, newFile)) {
                oldFile.delete()
            }
        }
        
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
     * Включает кэширование для оптимизации производительности
     */
    fun enableCache() {
        cacheEnabled = true
    }
    
    /**
     * Отключает кэширование
     */
    fun disableCache() {
        cacheEnabled = false
        clearAllCaches()
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
        val isShowAllMode = PresetStorageService.getShowAllPresetsMode()
        val isHideDuplicatesMode = PresetStorageService.getHideDuplicatesMode()
        
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
                    if (parts.size == 2) {
                        // Новый формат: listName::presetId
                        val listName = parts[0]
                        val presetId = parts[1]
                        
                        val listMeta = allLists[listName]
                        if (listMeta != null) {
                            val list = loadPresetList(listMeta.id)
                            val preset = list?.presets?.find { p -> p.id == presetId }
                            if (preset != null) {
                                presetsWithLists.add(listName to preset)
                            } else {
                                println("ADB_DEBUG: Preset with id $presetId NOT FOUND in list $listName")
                            }
                        }
                    } else if (parts.size >= 4) {
                        // Старый формат для обратной совместимости: listName::label::size::dpi
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
     * Проверяет что есть хотя бы один список, если нет - создает пустой
     */
    fun ensureDefaultListsExist() {
        val metadata = getAllListsMetadata()
        if (metadata.isEmpty()) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "No lists found, creating minimal empty list")
            // Создаем только один пустой список
            val emptyList = PresetList(
                name = "My Presets",
                presets = mutableListOf(),
                isDefault = false
            )
            
            // Сохраняем метаданные
            val newMetadata = listOf(ListMetadata(emptyList.id, emptyList.name, emptyList.isDefault))
            saveListsMetadata(newMetadata)
            
            // Сохраняем сам список
            savePresetList(emptyList)
            
            // Устанавливаем как активный
            setActiveListId(emptyList.id)
        }
        // Больше не проверяем наличие файлов и не восстанавливаем удаленные
    }
    
    /**
     * Возвращает путь к директории с пресетами
     */
    fun getPresetsDirectory(): File {
        return presetsDir.toFile()
    }
    
    /**
     * Преобразует имя списка в безопасное имя файла
     */
    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_") // Заменяем недопустимые символы
            .replace(Regex("\\s+"), "_") // Заменяем пробелы на подчеркивания
            .replace(Regex("_{2,}"), "_") // Убираем повторяющиеся подчеркивания
            .trim('_') // Убираем подчеркивания в начале и конце
            .take(200) // Ограничиваем длину имени файла
    }
    
    /**
     * Получает имя файла для списка
     */
    private fun getFileNameForList(presetList: PresetList): String {
        val sanitizedName = sanitizeFileName(presetList.name)
        return "$sanitizedName.json"
    }
    
    /**
     * Получает файл для списка по ID (проверяет как старое, так и новое именование)
     */
    private fun getFileForListId(listId: String): File? {
        // Сначала проверяем старое именование (UUID.json)
        val oldFile = presetsDir.resolve("$listId.json").toFile()
        if (oldFile.exists()) {
            return oldFile
        }
        
        // Если старого файла нет, ищем по метаданным
        val metadata = getAllListsMetadata()
        val listMeta = metadata.find { it.id == listId }
        if (listMeta != null) {
            val newFileName = sanitizeFileName(listMeta.name) + ".json"
            val newFile = presetsDir.resolve(newFileName).toFile()
            if (newFile.exists()) {
                return newFile
            }
        }
        
        // Дополнительно проверяем все файлы в директории
        // на случай если файл был переименован после сохранения
        val allFiles = presetsDir.toFile().listFiles { file -> 
            file.name.endsWith(".json") && file.isFile 
        }
        
        allFiles?.forEach { file ->
            try {
                // Читаем файл и проверяем ID
                val json = file.readText()
                val presetList = gson.fromJson(json, PresetList::class.java)
                if (presetList.id == listId) {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "Found file for list %s by content: %s", listId, file.name)
                    return file
                }
            } catch (e: Exception) {
                // Игнорируем ошибки чтения отдельных файлов
                PluginLogger.trace(LogCategory.PRESET_SERVICE, "Error reading file %s: %s", file.name, e.message)
            }
        }
        
        return null
    }
    
    /**
     * Мигрирует файл со старым именованием на новое
     */
    private fun migrateFileIfNeeded(listId: String, presetList: PresetList) {
        val oldFile = presetsDir.resolve("$listId.json").toFile()
        if (oldFile.exists()) {
            val newFileName = getFileNameForList(presetList)
            val newFile = presetsDir.resolve(newFileName).toFile()
            
            // Если файл с новым именем уже существует, добавляем суффикс
            var finalFile = newFile
            var counter = 1
            while (finalFile.exists() && !FileUtil.filesEqual(oldFile, finalFile)) {
                val nameWithoutExt = newFileName.substringBeforeLast(".")
                finalFile = presetsDir.resolve("${nameWithoutExt}_$counter.json").toFile()
                counter++
            }
            
            if (!FileUtil.filesEqual(oldFile, finalFile)) {
                PluginLogger.info(LogCategory.PRESET_SERVICE, "Migrating preset file from %s to %s", oldFile.name, finalFile.name)
                oldFile.renameTo(finalFile)
            }
        }
    }
    
    /**
     * Мигрирует все существующие файлы на новое именование при запуске
     */
    private fun migrateAllFilesToNewNaming() {
        val metadata = getAllListsMetadata()
        metadata.forEach { meta ->
            // Пытаемся загрузить список
            val list = loadPresetList(meta.id)
            if (list != null) {
                // Проверяем, нужна ли миграция
                val oldFile = presetsDir.resolve("${meta.id}.json").toFile()
                if (oldFile.exists()) {
                    migrateFileIfNeeded(meta.id, list)
                }
            }
        }
    }
    
    /**
     * Проверяет, запущен ли плагин в режиме разработки
     */
    private fun isDevelopmentMode(): Boolean {
        // Проверяем, запущен ли из IDE (обычно есть системное свойство idea.is.internal)
        return System.getProperty("idea.is.internal") == "true" ||
               System.getProperty("idea.plugins.path")?.contains("sandbox") == true ||
               // Дополнительная проверка - если текущая директория содержит build.gradle.kts
               Paths.get(System.getProperty("user.dir"), "build.gradle.kts").toFile().exists()
    }
    
    /**
     * Загружает пресеты из ресурсов плагина
     */
    private fun loadPresetsFromResources(): List<PresetList> {
        val presetLists = mutableListOf<PresetList>()
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Starting to load presets from resources...")
        
        try {
            // Загружаем все JSON файлы из папки presets динамически
            val resourceUrl = javaClass.classLoader.getResource("presets")
            if (resourceUrl != null) {
                PluginLogger.info(LogCategory.PRESET_SERVICE, "Found presets resource URL: %s", resourceUrl.toString())
                val uri = resourceUrl.toURI()
                
                if (uri.scheme == "jar") {
                    // Загрузка из JAR файла
                    val jarPath = uri.schemeSpecificPart.substringBefore("!")
                    val jarFile = java.util.jar.JarFile(jarPath.substringAfter("file:"))
                    
                    jarFile.entries().asSequence()
                        .filter { it.name.startsWith("presets/") && it.name.endsWith(".json") }
                        .forEach { entry ->
                            jarFile.getInputStream(entry).use { inputStream ->
                                val json = inputStream.bufferedReader().readText()
                                val presetList = parsePresetListFromJson(json)
                                if (presetList != null) {
                                    presetLists.add(presetList)
                                    PluginLogger.info(LogCategory.PRESET_SERVICE, "Loaded preset from JAR: %s", entry.name)
                                }
                            }
                        }
                    jarFile.close()
                } else {
                    // Загрузка из файловой системы (режим разработки)
                    val presetsDir = Paths.get(uri).toFile()
                    if (presetsDir.exists() && presetsDir.isDirectory) {
                        presetLists.addAll(loadPresetsFromDirectory(presetsDir))
                    }
                }
            } else {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "Presets resource directory not found in classpath")
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.PRESET_SERVICE, "Error loading presets from resources: %s", e, e.message)
        }
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Total presets loaded from resources: %d", presetLists.size)
        return presetLists
    }
    
    /**
     * Загружает пресеты из директории
     */
    private fun loadPresetsFromDirectory(directory: File): List<PresetList> {
        val presetLists = mutableListOf<PresetList>()
        
        directory.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
            try {
                val json = file.readText()
                val presetList = parsePresetListFromJson(json)
                if (presetList != null) {
                    presetLists.add(presetList)
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "Loaded preset list '%s' from %s", presetList.name, file.name)
                }
            } catch (e: Exception) {
                PluginLogger.error(LogCategory.PRESET_SERVICE, "Error loading preset file %s: %s", e, file.name, e.message)
            }
        }
        
        return presetLists
    }
    
    /**
     * Парсит PresetList из JSON строки
     */
    private fun parsePresetListFromJson(json: String): PresetList? {
        return try {
            gson.fromJson(json, PresetList::class.java)
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.PRESET_SERVICE, "Error parsing preset JSON: %s", e, e.message)
            null
        }
    }
}
