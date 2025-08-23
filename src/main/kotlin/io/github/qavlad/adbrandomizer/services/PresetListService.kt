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
    
    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance()
    
    // Комбинированный адаптер для DevicePreset
    class DevicePresetAdapter : JsonSerializer<DevicePreset>, JsonDeserializer<DevicePreset> {
        override fun serialize(src: DevicePreset, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val jsonObject = JsonObject()
            jsonObject.addProperty("label", src.label)
            jsonObject.addProperty("size", src.size)
            jsonObject.addProperty("dpi", src.dpi)
            jsonObject.addProperty("id", src.id)
            return jsonObject
        }
        
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
    
    private val gson = GsonBuilder()
        .registerTypeAdapter(DevicePreset::class.java, DevicePresetAdapter())
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
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "=== PresetListService Initialization ===")
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "Presets directory: %s", presetsDir.toAbsolutePath())
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "PathManager.getConfigPath(): %s", PathManager.getConfigPath())
        // Создаем директорию для пресетов если её нет
        Files.createDirectories(presetsDir)
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "Directory exists: %s", presetsDir.toFile().exists())
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
    fun initializeDefaultLists(): List<ListMetadata> {
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
    fun saveListsMetadata(metadata: List<ListMetadata>) {
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
        // Всегда проверяем кэш сначала (для удаленных списков)
        if (loadedListsCache.containsKey(listId)) {
            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Returning cached list for id: %s", listId)
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
                
                // Восстанавливаем флаги isDefault и isImported из метаданных
                val metadata = getAllListsMetadata().find { it.id == listId }
                if (metadata != null) {
                    presetList.isDefault = metadata.isDefault
                    // isImported должен сохраняться в самом списке, но на всякий случай проверяем
                    // Если в метаданных есть информация об импорте, используем её
                    // Проверяем флаги импорта и дефолта
                    // Если список не дефолтный и не помечен как импортированный,
                    // но имеет файл - это может быть импортированный список
                    // Оставляем как есть из файла
                }
                
                // Отладочная информация о загруженных ID - закомментировано из-за спама в Show All режиме
                // println("ADB_DEBUG: Loaded preset list ${presetList.name} from file with ${presetList.presets.size} presets")
                // presetList.presets.forEachIndexed { index, preset ->
                //     println("ADB_DEBUG:   [$index] ${preset.label} | ${preset.size} | ${preset.dpi}")
                // }
                
                if (!cacheEnabled) {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, "Successfully loaded list %s with %d presets (isDefault=%s)", 
                        presetList.name, presetList.presets.size, presetList.isDefault)
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
        // Сохраняем флаг isDefault из метаданных если он не установлен
        val metadata = getAllListsMetadata().find { it.id == presetList.id }
        if (metadata != null && metadata.isDefault && !presetList.isDefault) {
            presetList.isDefault = true
            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                "Restoring isDefault flag for list '%s' from metadata", presetList.name)
        }
        
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
        
        PluginLogger.debug(LogCategory.PRESET_SERVICE, 
            "Saving preset list '%s' to: %s (isDefault: %s)", 
            presetList.name, file.absolutePath, presetList.isDefault)
        
        // ВАЖНОЕ ЛОГИРОВАНИЕ для отладки проблемы с сохранением
        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
            "SAVE_DEBUG: About to save list '%s' with %d presets", 
            presetList.name, presetList.presets.size)
        
        if (presetList.presets.size < 5) {
            // Если мало пресетов - выводим их все для отладки
            presetList.presets.forEachIndexed { index, preset ->
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "SAVE_DEBUG:   [%d] %s | %s | %s", 
                    index, preset.label, preset.size, preset.dpi)
            }
        }
        
        try {
            // Убеждаемся, что директория существует
            file.parentFile.mkdirs()
            
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "SAVE_DEBUG: Writing file to: %s", file.absolutePath)
            
            // КРИТИЧЕСКОЕ ЛОГИРОВАНИЕ - проверяем что именно сериализуется
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "SAVE_DEBUG: Right before toJson - list has %d presets", presetList.presets.size)
            
            val json = gson.toJson(presetList)
            
            // Логируем размер JSON и парсим обратно для проверки
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "SAVE_DEBUG: JSON length: %d chars", json.length)
            
            // Проверяем что в JSON
            val parsedBack = gson.fromJson(json, PresetList::class.java)
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "SAVE_DEBUG: Parsed back from JSON - %d presets", parsedBack.presets.size)
            
            file.writeText(json)
            
            // Проверяем что файл действительно существует после записи
            if (file.exists()) {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "SAVE_DEBUG: File exists after write: %s (size: %d bytes)", 
                    file.absolutePath, file.length())
            } else {
                PluginLogger.error(LogCategory.PRESET_SERVICE, 
                    "SAVE_DEBUG: FILE NOT CREATED: %s", null, file.absolutePath)
            }
            
            // Проверяем что записалось в файл
            val writtenContent = file.readText()
            val writtenList = gson.fromJson(writtenContent, PresetList::class.java)
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "SAVE_DEBUG: Actually written to file - %d presets", writtenList.presets.size)
            
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
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Creating new list '%s' with id: %s", name, newList.id)
        
        // Обновляем метаданные
        val metadata = getAllListsMetadata().toMutableList()
        metadata.add(ListMetadata(newList.id, newList.name))
        saveListsMetadata(metadata)
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Updated metadata, now have %d lists", metadata.size)
        
        // Сохраняем список
        savePresetList(newList)
        
        // Проверяем что файл действительно создался
        val file = getFileForListId(newList.id)
        if (file != null && file.exists()) {
            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                "Successfully created file for new list: %s", file.absolutePath)
        } else {
            PluginLogger.error(LogCategory.PRESET_SERVICE, 
                "Failed to create file for new list '%s'", null, name)
        }
        
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
        
        // Загружаем список в кэш перед удалением файла
        // Это позволит экспортировать удаленные списки до закрытия диалога
        if (!loadedListsCache.containsKey(listId)) {
            val presetList = loadPresetList(listId)
            if (presetList != null) {
                loadedListsCache[listId] = presetList
                PluginLogger.info(LogCategory.PRESET_SERVICE, "Cached list '%s' before deletion for potential export", presetList.name)
            }
        }
        
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
        
        // НЕ очищаем кэш сразу - это позволит экспортировать удаленные списки
        // Кэш будет очищен при перезапуске плагина или закрытии диалога
        // loadedListsCache.remove(listId)
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
     * Очищает кэш удаленных списков
     * Вызывается при закрытии диалога пресетов
     */
    fun clearDeletedListsCache() {
        val metadata = getAllListsMetadata()
        val existingIds = metadata.map { it.id }.toSet()
        
        // Удаляем из кэша только те списки, которых нет в метаданных
        val idsToRemove = loadedListsCache.keys.filter { id -> id !in existingIds }
        idsToRemove.forEach { id ->
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Removing deleted list from cache: %s", id)
            loadedListsCache.remove(id)
        }
    }
    
    /**
     * Возвращает все доступные списки (из кэша и с диска)
     */
    fun getAllAvailableLists(): List<PresetList> {
        val result = mutableListOf<PresetList>()
        val metadata = getAllListsMetadata()
        
        // Загружаем все списки по метаданным
        metadata.forEach { meta ->
            val list = loadPresetList(meta.id)
            if (list != null) {
                result.add(list)
            }
        }
        
        // Добавляем списки из кэша, которых нет в метаданных (удаленные)
        loadedListsCache.forEach { (id, list) ->
            if (result.none { it.id == id }) {
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Adding cached (deleted) list: %s", list.name)
                result.add(list)
            }
        }
        
        return result
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
     * Экспортирует списки пресетов напрямую (из памяти)
     */
    fun exportListsDirectly(lists: List<PresetList>, targetFile: File) {
        val json = gson.toJson(lists)
        targetFile.writeText(json)
    }
    
    /**
     * Импортирует списки пресетов
     */
    fun importLists(sourceFile: File): List<PresetList> {
        val json = sourceFile.readText()
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Importing lists from file, JSON length: %d", json.length)
        
        val type = object : TypeToken<List<PresetList>>() {}.type
        val importedLists: List<PresetList> = gson.fromJson(json, type)
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, "Parsed %d lists from JSON", importedLists.size)
        importedLists.forEachIndexed { index, list ->
            PluginLogger.info(LogCategory.PRESET_SERVICE, "List %d: %s with %d presets", index, list.name, list.presets.size)
            list.presets.forEachIndexed { presetIndex, preset ->
                PluginLogger.debug(LogCategory.PRESET_SERVICE, "  Preset %d: %s | %s | %s", 
                    presetIndex, preset.label, preset.size, preset.dpi)
            }
        }
        
        val metadata = getAllListsMetadata().toMutableList()
        val resultLists = mutableListOf<PresetList>()
        
        // Собираем все существующие ID листов для проверки дубликатов
        val existingListIds = metadata.map { it.id }.toSet()
        
        importedLists.forEach { importedList ->
            // Проверяем, существует ли лист с таким же ID
            val hasDuplicateId = existingListIds.contains(importedList.id)
            
            if (hasDuplicateId) {
                // Если ID дублируется, регенерируем все ID
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Detected duplicate list ID %s for list %s, regenerating IDs", 
                    importedList.id, importedList.name)
                importedList.regenerateIds()
            }
            
            // Генерируем новый ID для импортированного списка и добавляем (imported) к имени
            val newList = importedList.copy(name = "${importedList.name} (imported)").apply {
                isImported = true
            }
            
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Created copy of list %s with %d presets", 
                newList.name, newList.presets.size)
            
            savePresetList(newList)
            metadata.add(ListMetadata(newList.id, newList.name))
            resultLists.add(newList)
        }
        
        saveListsMetadata(metadata)
        return resultLists
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
     * Проверяет что есть хотя бы один список, если нет - восстанавливает дефолтные
     */
    fun ensureDefaultListsExist() {
        val metadata = getAllListsMetadata()
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "ensureDefaultListsExist: Found %d lists in metadata", metadata.size)
        
        if (metadata.isEmpty()) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "No lists found, restoring default presets")
            // Восстанавливаем дефолтные пресеты из ресурсов
            initializeDefaultLists()
        } else {
            // Проверяем, что файлы существуют для всех метаданных
            var hasValidLists = false
            var hasAnyDefaultLists = false
            val validMetadata = mutableListOf<ListMetadata>()
            
            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                "Checking %d lists from metadata", metadata.size)
            
            metadata.forEach { listMeta ->
                val file = getFileForListId(listMeta.id)
                if (file != null && file.exists()) {
                    hasValidLists = true
                    validMetadata.add(listMeta)
                    if (listMeta.isDefault) {
                        hasAnyDefaultLists = true
                    }
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Found file for list '%s' (isDefault=%s): %s", 
                        listMeta.name, listMeta.isDefault, file.name)
                } else {
                    // Файл не существует - сохраняем в метаданных только если это кастомный список
                    if (!listMeta.isDefault) {
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Custom list '%s' (id=%s) file not found, keeping in metadata for future restoration", 
                            listMeta.name, listMeta.id)
                        validMetadata.add(listMeta)
                    } else {
                        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                            "Default list '%s' file not found, will be restored", 
                            listMeta.name)
                    }
                }
            }
            
            // Если ни одного валидного файла нет или нет дефолтных списков, восстанавливаем дефолтные
            if (!hasValidLists || !hasAnyDefaultLists) {
                PluginLogger.warn(LogCategory.PRESET_SERVICE, "No valid preset files or default lists found, restoring defaults")
                
                // Восстанавливаем дефолтные пресеты
                initializeDefaultLists()
                
                // Добавляем дефолтные списки к существующим кастомным в метаданных
                val allMetadata = getAllListsMetadata().toMutableList()
                
                // Добавляем кастомные списки обратно если они были
                validMetadata.forEach { customMeta ->
                    if (!customMeta.isDefault && allMetadata.none { it.id == customMeta.id }) {
                        allMetadata.add(customMeta)
                    }
                }
                
                saveListsMetadata(allMetadata)
            } else {
                // Обновляем метаданные только если что-то изменилось
                if (validMetadata.size != metadata.size) {
                    saveListsMetadata(validMetadata)
                }
            }
        }
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
        // Проверяем различные признаки режима разработки
        val isInternal = System.getProperty("idea.is.internal") == "true"
        val isSandbox = System.getProperty("idea.plugins.path")?.contains("sandbox") == true
        val hasGradleFile = Paths.get(System.getProperty("user.dir"), "build.gradle.kts").toFile().exists()
        val hasSrcFolder = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "presets").toFile().exists()
        
        val result = isInternal || isSandbox || hasGradleFile || hasSrcFolder
        
        if (result) {
            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                "Development mode detected: internal=%s, sandbox=%s, gradle=%s, src=%s",
                isInternal, isSandbox, hasGradleFile, hasSrcFolder)
        }
        
        return result
    }
    
    /**
     * Загружает пресеты из ресурсов плагина
     */
    fun loadPresetsFromResources(): List<PresetList> {
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
    
    /**
     * Тестовый метод для отладки проблемы с ресетом
     */
    fun debugResourceLoading() {
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "=== DEBUG RESOURCE LOADING ===")
        
        // Загружаем из ресурсов
        val resourceFile = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "presets", "Budget Phones 2024.json").toFile()
        if (resourceFile.exists()) {
            val json = resourceFile.readText()
            val deserializedList = gson.fromJson(json, PresetList::class.java)
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "DEBUG: Loaded from file - presets count: %d", deserializedList.presets.size)
            
            // Логируем все пресеты
            deserializedList.presets.forEachIndexed { i, preset ->
                PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                    "DEBUG: Preset[%d]: %s", i, preset.label)
            }
            
            // Сериализуем обратно
            val jsonBack = gson.toJson(deserializedList)
            val deserializedAgain = gson.fromJson(jsonBack, PresetList::class.java)
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "DEBUG: After re-serialization - presets count: %d", deserializedAgain.presets.size)
        } else {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, "DEBUG: Resource file not found")
        }
    }
    
    /**
     * Загружает дефолтный список из ресурсов по ID
     */
    fun loadDefaultListFromResources(listId: String): PresetList? {
        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
            "=== RESET: Loading default list from resources for ID: %s ===", listId)
            
        // Сначала пытаемся найти по ID
        val metadata = getAllListsMetadata().find { it.id == listId }
        if (metadata == null || !metadata.isDefault) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "RESET: Cannot find default list metadata for ID: %s (metadata exists: %s, isDefault: %s)", 
                listId, metadata != null, metadata?.isDefault)
            return null
        }
        
        PluginLogger.warn(LogCategory.PRESET_SERVICE, 
            "RESET: Found metadata for list: name='%s', id='%s', isDefault=%s", 
            metadata.name, metadata.id, true
        )
        
        // В режиме разработки также проверяем папку src/main/resources/presets
        val devMode = isDevelopmentMode()
        PluginLogger.warn(LogCategory.PRESET_SERVICE, "RESET: Development mode: %s", devMode)
        
        if (devMode) {
            // В режиме разработки используем текущую директорию проекта
            val projectDir = System.getProperty("user.dir")
            val resourcesPresetsDir = Paths.get(projectDir, "src", "main", "resources", "presets").toFile()
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "RESET: user.dir = %s", System.getProperty("user.dir"))
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "RESET: Using project dir: %s", projectDir)
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "RESET: Checking dev resources at: %s, exists: %s", 
                resourcesPresetsDir.absolutePath, resourcesPresetsDir.exists())
                
            if (resourcesPresetsDir.exists() && resourcesPresetsDir.isDirectory) {
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Development mode: loading from src/main/resources/presets")
                val devLists = loadPresetsFromDirectory(resourcesPresetsDir)
                PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                    "Found %d lists in dev resources", devLists.size)
                    
                val targetList = devLists.find { list ->
                    val matches = list.name == metadata.name || 
                                 list.id == listId ||
                                 list.id == metadata.id
                    if (matches) {
                        PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                            "Found matching list: %s (id: %s)", list.name, list.id)
                    }
                    matches
                }
                if (targetList != null) {
                    // Создаем глубокую копию, чтобы избежать модификации оригинала
                    val resultList = PresetList(
                        id = listId,
                        name = targetList.name,
                        presets = targetList.presets.map { preset -> 
                            preset.copy(id = preset.id)
                        }.toMutableList(),
                        isDefault = true,
                        isImported = false
                    )
                    PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                        "RESET: SUCCESS! Loaded from dev resources: '%s' with %d presets", 
                        resultList.name, resultList.presets.size)
                    return resultList
                } else {
                    PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                        "RESET: FAILED! Could not find list '%s' in dev resources", metadata.name)
                }
            }
        }
        
        // Загружаем все дефолтные списки из ресурсов
        val defaultLists = loadPresetsFromResources()
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Loaded %d default lists from resources", defaultLists.size)
        
        // Ищем список по имени из метаданных
        val targetList = defaultLists.find { list ->
            list.name == metadata.name
        }
        
        if (targetList == null) {
            PluginLogger.warn(LogCategory.PRESET_SERVICE, 
                "Cannot find list with name '%s' in resources", metadata.name)
            // Пытаемся найти по sanitized имени или ID
            return defaultLists.find { list ->
                sanitizeFileName(list.name) == listId || 
                sanitizeFileName(list.name) == sanitizeFileName(metadata.name) ||
                list.id == listId
            }?.apply {
                // Устанавливаем правильный ID и флаг isDefault
                this.id = listId
                this.isDefault = true
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Found list by alternative match, name: %s, presets count: %d", 
                    this.name, this.presets.size)
            }
        }
        
        return targetList.apply {
            // Устанавливаем правильный ID и флаг isDefault
            this.id = listId
            this.isDefault = true
            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                "Successfully loaded default list '%s' with %d presets", 
                this.name, this.presets.size)
        }
    }
}
