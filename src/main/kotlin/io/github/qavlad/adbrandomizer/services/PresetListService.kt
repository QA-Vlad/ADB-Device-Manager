package io.github.qavlad.adbrandomizer.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import io.github.qavlad.adbrandomizer.config.PluginConfig
import java.io.File
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
    private val gson = Gson()
    private val presetsDir = Paths.get(System.getProperty("user.home"), ".adbrandomizer", "presets")
    
    init {
        // Создаем директорию для пресетов если её нет
        Files.createDirectories(presetsDir)
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
        return if (json.isNullOrBlank()) {
            // Инициализируем дефолтными списками
            println("PresetListService: Initializing default lists")
            initializeDefaultLists()
        } else {
            try {
                val type = object : TypeToken<List<ListMetadata>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                println("PresetListService: Error loading metadata, initializing defaults: ${e.message}")
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
        if (defaultLists.isNotEmpty()) {
            setActiveListId(defaultLists.first().id)
        }
        
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
        return loadPresetList(activeId)
    }
    
    /**
     * Загружает список пресетов по ID
     */
    fun loadPresetList(listId: String): PresetList? {
        return try {
            val file = presetsDir.resolve("$listId.json").toFile()
            if (file.exists()) {
                val json = file.readText()
                gson.fromJson(json, PresetList::class.java)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Сохраняет список пресетов
     */
    fun savePresetList(presetList: PresetList) {
        val file = presetsDir.resolve("${presetList.id}.json").toFile()
        file.writeText(gson.toJson(presetList))
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
     * Получает все пресеты из всех списков
     */
    fun getAllPresetsFromAllLists(): List<Pair<String, DevicePreset>> {
        val allPresets = mutableListOf<Pair<String, DevicePreset>>()
        
        getAllListsMetadata().forEach { metadata ->
            loadPresetList(metadata.id)?.let { list ->
                println("PresetListService: Loading presets from list '${list.name}' with ${list.presets.size} items")
                list.presets.forEach { preset ->
                    allPresets.add(metadata.name to preset)
                }
            }
        }
        
        println("PresetListService: Total presets from all lists: ${allPresets.size}")
        return allPresets
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
}
