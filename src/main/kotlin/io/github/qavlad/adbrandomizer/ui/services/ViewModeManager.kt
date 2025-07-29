package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.PresetList
import io.github.qavlad.adbrandomizer.services.DevicePreset

/**
 * Менеджер для управления режимами отображения пресетов
 * Содержит утилитарные методы для работы с режимами Show All и Hide Duplicates
 */
class ViewModeManager(
    private val presetOrderManager: PresetOrderManager = PresetOrderManager()
) {
    
    /**
     * Добавляет новые пресеты, которых нет в указанном порядке
     */
    private fun addNewPresetsNotInOrder(
        tempPresetLists: Map<String, PresetList>,
        allPresets: MutableList<Pair<String, DevicePreset>>,
        orderToCheck: List<String>
    ) {
        println("ADB_DEBUG: addNewPresetsNotInOrder - start")
        println("ADB_DEBUG:   Total presets in all lists: ${tempPresetLists.values.sumOf { it.presets.size }}")
        println("ADB_DEBUG:   Already added presets: ${allPresets.size}")
        
        // Собираем все ID пресетов, которые уже добавлены
        val addedPresetIds = allPresets.map { it.second.id }.toMutableSet()
        println("ADB_DEBUG:   Added preset IDs count: ${addedPresetIds.size}")
        
        // Проходим по всем спискам и добавляем пресеты, которых еще нет
        tempPresetLists.forEach { (_, list) ->
            println("ADB_DEBUG:   Checking list '${list.name}' with ${list.presets.size} presets")
            
            // Находим позицию последнего пресета из этого списка в allPresets
            var lastIndexFromList = -1
            for (i in allPresets.indices.reversed()) {
                if (allPresets[i].first == list.name) {
                    lastIndexFromList = i
                    break
                }
            }
            
            // Проходим по пресетам списка и вставляем недостающие
            list.presets.forEachIndexed { presetIndex, preset ->
                if (!addedPresetIds.contains(preset.id)) {
                    // Специальная логика для бывших дубликатов в режиме Hide Duplicates
                    var insertPosition = -1
                    
                    // Проверяем, является ли этот пресет бывшим дубликатом
                    val duplicateKey = preset.getDuplicateKey()
                    val wasHiddenDuplicate = allPresets.any { (_, existingPreset) ->
                        existingPreset.getDuplicateKey() == duplicateKey && existingPreset.id != preset.id
                    }
                    
                    if (wasHiddenDuplicate) {
                        // Этот пресет был скрыт как дубликат, но теперь он уникален
                        // Ищем похожий пресет (с похожим label) и вставляем после него
                        val baseLabel = preset.label.substringBefore(" (").trim()
                        
                        // Ищем пресет с похожим названием
                        for (i in allPresets.indices) {
                            val (_, existingPreset) = allPresets[i]
                            val existingBaseLabel = existingPreset.label.substringBefore(" (").trim()
                            
                            if (existingBaseLabel == baseLabel || existingPreset.label.contains(baseLabel) || baseLabel.contains(existingBaseLabel)) {
                                // Нашли похожий пресет - вставляем после него
                                insertPosition = i + 1
                                println("ADB_DEBUG:   Found related preset '${existingPreset.label}' at position $i for former duplicate '${preset.label}'")
                                break
                            }
                        }
                    }
                    
                    // Если не нашли позицию для бывшего дубликата, используем обычную логику
                    if (insertPosition == -1) {
                        if (lastIndexFromList >= 0) {
                            // Есть пресеты из этого списка - найдём правильное место
                            insertPosition = lastIndexFromList + 1
                            
                            // Находим правильную позицию на основе порядка в исходном списке
                            // Ищем пресет из того же списка, который идёт непосредственно перед текущим
                            for (i in presetIndex - 1 downTo 0) {
                                val prevPreset = list.presets[i]
                                val prevIndex = allPresets.indexOfFirst { 
                                    it.first == list.name && it.second.id == prevPreset.id 
                                }
                                if (prevIndex >= 0) {
                                    // Нашли предыдущий пресет - вставляем после него
                                    insertPosition = prevIndex + 1
                                    break
                                }
                            }
                            
                            // Если не нашли предыдущий, ищем следующий
                            if (insertPosition == lastIndexFromList + 1) {
                                for (i in presetIndex + 1 until list.presets.size) {
                                    val nextPreset = list.presets[i]
                                    val nextIndex = allPresets.indexOfFirst { 
                                        it.first == list.name && it.second.id == nextPreset.id 
                                    }
                                    if (nextIndex >= 0) {
                                        // Нашли следующий пресет - вставляем перед ним
                                        insertPosition = nextIndex
                                        break
                                    }
                                }
                            }
                        } else {
                            // Нет пресетов из этого списка - добавляем в конец
                            insertPosition = allPresets.size
                        }
                    }
                    
                    allPresets.add(insertPosition, list.name to preset)
                    println("ADB_DEBUG:   Inserted missing preset: ${preset.label} (${preset.size}x${preset.dpi}) from list ${list.name} at position $insertPosition")
                    addedPresetIds.add(preset.id)
                }
            }
        }
        
        println("ADB_DEBUG:   After adding missing presets, total: ${allPresets.size}")
        
        // Старая логика с fixedOrder для обратной совместимости
        val fixedOrder = presetOrderManager.getFixedShowAllOrder()
        if (fixedOrder.isNotEmpty() && fixedOrder.any { it.split("::").size >= 4 }) {
            println("ADB_DEBUG: addNewPresetsNotInOrder - Also checking old fixedOrder format")
            
            fixedOrder.forEach { fixedKey ->
                if (!orderToCheck.contains(fixedKey)) {
                    val parts = fixedKey.split("::")
                    if (parts.size >= 4) {
                        val listName = parts[0]
                        val label = parts[1]
                        val size = parts[2]
                        val dpi = parts[3]
                        
                        val list = tempPresetLists.values.find { it.name == listName }
                        val preset = list?.presets?.find { p ->
                            p.label == label && p.size == size && p.dpi == dpi
                        }
                        
                        if (preset != null && !allPresets.any { it.second.id == preset.id }) {
                            allPresets.add(listName to preset)
                            println("ADB_DEBUG: addNewPresetsNotInOrder - Added preset ${preset.label} from old fixedOrder format")
                        }
                    }
                }
            }
            return
        }
        val addedPresets = mutableListOf<Pair<String, DevicePreset>>()
        allPresets.forEach { pair ->
            addedPresets.add(pair)
        }
        
        tempPresetLists.forEach { (_, list) ->
            // Группируем пресеты для определения дубликатов
            val presetGroups = mutableMapOf<String, MutableList<Int>>()
            list.presets.forEachIndexed { index, preset ->
                val key = "${preset.label}::${preset.size}::${preset.dpi}"
                presetGroups.getOrPut(key) { mutableListOf() }.add(index)
            }
            
            list.presets.forEachIndexed { index, preset ->
                val baseKey = "${list.name}::${preset.label}::${preset.size}::${preset.dpi}"
                val groupKey = "${preset.label}::${preset.size}::${preset.dpi}"
                val group = presetGroups[groupKey] ?: listOf()
                
                // Проверяем, добавлен ли уже этот пресет по ID
                // Проверяем, добавлен ли уже этот пресет
                val alreadyAdded = orderToCheck.contains(baseKey) || 
                    // Проверяем и старый формат с ID для обратной совместимости
                    orderToCheck.contains("${list.name}::${preset.id}") ||
                    // Проверяем старый формат с индексами для дубликатов
                    (if (group.size > 1) {
                        val indexInGroup = group.indexOf(index)
                        val keyWithIndex = "$baseKey::$indexInGroup"
                        orderToCheck.contains(keyWithIndex)
                    } else {
                        false
                    }) || 
                    addedPresets.any { 
                        it.first == list.name && it.second === preset 
                    }
                
                if (!alreadyAdded) {
                    // Для Show all режима добавляем новые пресеты в конец списка их листа
                    // чтобы не использовать измененный порядок из tempPresetLists
                    
                    // println("ADB_DEBUG: addNewPresetsNotInOrder - Adding preset ${preset.label} from list ${list.name}")
                    
                    // Проверяем, является ли этот пресет бывшим дублем (например, Pixel 7 Pro после изменения DPI)
                    // Если да, то ищем видимый пресет с таким же размером и разрешением (но другим DPI)
                    var insertIndex = -1
                    
                    // Ищем последний пресет с тем же size из того же списка
                    for (i in allPresets.size - 1 downTo 0) {
                        val (existingListName, existingPreset) = allPresets[i]
                        if (existingListName == list.name && existingPreset.size == preset.size) {
                            // Нашли пресет с тем же размером из того же списка
                            insertIndex = i + 1
                            println("ADB_DEBUG:   Inserted missing preset: ${preset.label} (${preset.size}x${preset.dpi}) from list ${list.name} at position $insertIndex after similar preset")
                            break
                        }
                    }
                    
                    if (insertIndex == -1) {
                        // Если не нашли похожий пресет, находим последний индекс пресета из того же списка
                        val lastIndexOfSameList = allPresets.indexOfLast { it.first == list.name }
                        
                        if (lastIndexOfSameList >= 0) {
                            insertIndex = lastIndexOfSameList + 1
                            println("ADB_DEBUG:   Inserted missing preset: ${preset.label} (${preset.size}x${preset.dpi}) from list ${list.name} at position $insertIndex")
                        } else {
                            // Если нет пресетов из этого списка, добавляем в конец
                            allPresets.add(list.name to preset)
                            // println("ADB_DEBUG: addNewPresetsNotInOrder - Added to end of list")
                        }
                    }
                    
                    if (insertIndex != -1) {
                        allPresets.add(insertIndex, list.name to preset)
                    }
                }
            }
        }
    }
    
    /**
     * Подготавливает модель таблицы для режима Show All
     */
    fun prepareShowAllTableModel(
        tempPresetLists: Map<String, PresetList>,
        savedOrder: List<String>?,
        hiddenDuplicateIds: Set<String>? = null
    ): List<Pair<String, DevicePreset>> {
        println("ADB_DEBUG: ViewModeManager.prepareShowAllTableModel called")
        println("ADB_DEBUG:   tempPresetLists.size: ${tempPresetLists.size}")
        println("ADB_DEBUG:   tempPresetLists.isEmpty: ${tempPresetLists.isEmpty()}")
        
        // Log tempPresetLists contents
        if (tempPresetLists.isEmpty()) {
            println("ADB_DEBUG:   WARNING: tempPresetLists is EMPTY!")
        } else {
            println("ADB_DEBUG:   tempPresetLists contents:")
            tempPresetLists.forEach { (listId, list) ->
                println("ADB_DEBUG:     List '${list.name}' (id: $listId) has ${list.presets.size} presets")
            }
        }
        
        println("ADB_DEBUG:   savedOrder size: ${savedOrder?.size ?: 0}")
        if (savedOrder != null && savedOrder.isNotEmpty()) {
            println("ADB_DEBUG:   First 5 items from savedOrder:")
            savedOrder.take(5).forEachIndexed { index, item ->
                println("ADB_DEBUG:     [$index] $item")
            }
        }
        
        val allPresets = mutableListOf<Pair<String, DevicePreset>>()
        
        // Приоритет: savedOrder (drag & drop) > fixedOrder > обычный порядок
        if (savedOrder != null && savedOrder.isNotEmpty()) {
            // Проверяем формат ключей
            val hasOldFormat = savedOrder.any { it.startsWith("0::") }
            if (hasOldFormat) {
                println("ADB_DEBUG:   Detected old format savedOrder (starts with '0::'), ignoring it")
                // Если старый формат, игнорируем savedOrder и используем обычный порядок
                tempPresetLists.forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        allPresets.add(list.name to preset)
                    }
                }
                
                // Сохраняем правильный порядок
                println("ADB_DEBUG: ViewModeManager - Saving corrected Show all order")
                presetOrderManager.saveShowAllModeOrder(allPresets)
            } else {
                // Используем сохраненный порядок drag & drop (высший приоритет)
                savedOrder.forEach { key ->
                    parsePresetKeyAndAdd(key, tempPresetLists, allPresets, supportOldFormat = true)
                }
                
                // Debug: проверяем сколько пресетов добавлено из savedOrder
                println("ADB_DEBUG:   After parsing savedOrder, allPresets.size: ${allPresets.size}")
                
                // Подсчитываем общее количество пресетов во всех списках
                val totalPresetsInLists = tempPresetLists.values.sumOf { it.presets.size }
                println("ADB_DEBUG:   Total presets in all lists: $totalPresetsInLists")
                
                // Проверяем каждый список индивидуально
                val presetsFoundPerList = mutableMapOf<String, Int>()
                val presetsExpectedPerList = mutableMapOf<String, Int>()
                
                tempPresetLists.forEach { (_, list) ->
                    presetsExpectedPerList[list.name] = list.presets.size
                    presetsFoundPerList[list.name] = 0
                }
                
                // Подсчитываем сколько пресетов найдено из каждого списка
                allPresets.forEach { (listName, _) ->
                    presetsFoundPerList[listName] = (presetsFoundPerList[listName] ?: 0) + 1
                }
                
                // Проверяем, есть ли списки где найдено менее 50% пресетов
                var hasListWithMissingPresets = false
                presetsExpectedPerList.forEach { (listName, expected) ->
                    val found = presetsFoundPerList[listName] ?: 0
                    println("ADB_DEBUG:   List '$listName': found $found of $expected presets")
                    if (expected > 0 && found < expected * 0.5) {
                        println("ADB_DEBUG:   List '$listName' has significant missing presets!")
                        hasListWithMissingPresets = true
                    }
                }
                
                // Если есть списки с большим количеством отсутствующих пресетов,
                // или загружено значительно меньше пресетов чем есть в списках,
                // значит ID не совпадают и нужно загрузить все заново
                if (hasListWithMissingPresets || allPresets.size < totalPresetsInLists * 0.5) {
                    println("ADB_DEBUG:   ID mismatch detected, loading all presets in default order")
                    
                    // Очищаем и загружаем заново
                    allPresets.clear()
                    tempPresetLists.forEach { (_, list) ->
                        list.presets.forEach { preset ->
                            allPresets.add(list.name to preset)
                        }
                    }
                    // Сохраняем новый порядок
                    presetOrderManager.saveShowAllModeOrder(allPresets)
                } else {
                    // Добавляем новые пресеты, которых нет в сохраненном порядке
                    addNewPresetsNotInOrder(tempPresetLists, allPresets, savedOrder)
                }
            }
        } else {
            // Если нет savedOrder, пытаемся использовать фиксированный порядок
            val fixedOrder = presetOrderManager.getFixedShowAllOrder()
            println("ADB_DEBUG:   fixedOrder size: ${fixedOrder.size}")
            
            if (fixedOrder.isNotEmpty()) {
                // Используем фиксированный порядок
                fixedOrder.forEach { key ->
                    parsePresetKeyAndAdd(key, tempPresetLists, allPresets)
                }
                
                // Добавляем новые пресеты, которых нет в фиксированном порядке
                addNewPresetsNotInOrder(tempPresetLists, allPresets, fixedOrder)
            } else {
                // Обычный порядок - по спискам (и фиксируем его)
                tempPresetLists.forEach { (_, list) ->
                    list.presets.forEach { preset ->
                        allPresets.add(list.name to preset)
                    }
                }
                
                // Фиксируем порядок при первом использовании
                if (!presetOrderManager.hasFixedShowAllOrder()) {
                    presetOrderManager.fixShowAllOrder(tempPresetLists)
                }
                
                // Также сохраняем как savedOrder чтобы порядок был доступен сразу
                println("ADB_DEBUG: ViewModeManager - Saving initial Show all order")
                presetOrderManager.saveShowAllModeOrder(allPresets)
            }
        }
        
        // Группируем дубликаты вместе для правильного отображения
        groupDuplicatesTogether(allPresets)
        
        // Сохраняем исходный порядок перед позиционированием

        // Обрабатываем случай, когда пресет перестал быть дублем и должен появиться рядом с похожим пресетом
        // В Show All режиме не передаем информацию о сортировке, так как здесь мы уже отключили перемещение
        handleFormerDuplicatesPositioning(allPresets, hiddenDuplicateIds)
        
        // В режиме Show All порядок не должен меняться после позиционирования,
        // так как мы отключили автоматическое перемещение пресетов
        // Поэтому пропускаем проверку изменения порядка и сохранение
        println("ADB_DEBUG: Skipping order change check in Show All mode - preserving user-defined order")
        
        return allPresets
    }
    
    /**
     * Парсит ключ пресета и добавляет его в список
     */
    private fun parsePresetKeyAndAdd(
        key: String,
        tempPresetLists: Map<String, PresetList>,
        allPresets: MutableList<Pair<String, DevicePreset>>,
        supportOldFormat: Boolean = false
    ) {
        val parts = key.split("::")
        
        // Новый формат с ID: listName::presetId
        if (parts.size == 2 && parts[1].length == 36) { // UUID has 36 chars
            val listName = parts[0]
            val presetId = parts[1]
            
            // Закомментированы частые логи
            // println("ADB_DEBUG: parsePresetKeyAndAdd - looking for preset with id: $presetId in list: $listName")
            
            val list = tempPresetLists.values.find { it.name == listName }
            if (list == null) {
                println("ADB_DEBUG:   List '$listName' not found!")
            } else {
                // println("ADB_DEBUG:   Found list '$listName', searching among ${list.presets.size} presets")
                // list.presets.forEachIndexed { index, p ->
                //     println("ADB_DEBUG:     [$index] preset id: ${p.id}, label: ${p.label}")
                // }
            }
            
            val preset = list?.presets?.find { p -> p.id == presetId }
            
            if (preset != null) {
                // println("ADB_DEBUG:   Found preset: ${preset.label}")
                allPresets.add(listName to preset)
            } else {
                println("ADB_DEBUG:   Preset with id $presetId NOT FOUND in list $listName")
                // Попробуем найти пресет по другим атрибутам, если ID изменился
                // Это может произойти при перезапуске приложения
                // Не добавляем здесь, так как addNewPresetsNotInOrder добавит все недостающие пресеты
            }
        }
        // Старый формат для обратной совместимости
        else if (parts.size >= 4) {
            val listName = parts[0]
            val label = parts[1]
            val size = parts[2]
            val dpi = parts[3]
            val index = if (parts.size >= 5) parts[4].toIntOrNull() else null
            
            val list = tempPresetLists.values.find { it.name == listName }
            if (list != null) {
                // Находим все пресеты с такими же параметрами
                val matchingPresets = list.presets.filter { p ->
                    p.label == label && p.size == size && p.dpi == dpi
                }
                
                // Если есть индекс, используем его, иначе берем первый
                val preset = if (index != null && index < matchingPresets.size) {
                    matchingPresets[index]
                } else {
                    matchingPresets.firstOrNull()
                }
                
                if (preset != null) {
                    allPresets.add(listName to preset)
                }
            }
        } else if (supportOldFormat && parts.size >= 2) {
            // Обратная совместимость со старым форматом
            val listName = parts[0]
            val label = parts[1]
            
            val list = tempPresetLists.values.find { it.name == listName }
            val preset = list?.presets?.find { p -> p.label == label }
            
            if (preset != null) {
                allPresets.add(listName to preset)
            }
        }
    }
    
    /**
     * Обрабатывает позиционирование пресетов, которые перестали быть дубликатами
     */
    fun handleFormerDuplicatesPositioning(
        allPresets: MutableList<Pair<String, DevicePreset>>,
        hiddenDuplicateIds: Set<String>? = null,
        isShowAllMode: Boolean = true,
        isSortingActive: Boolean = false
    ) {
        println("ADB_DEBUG: handleFormerDuplicatesPositioning - start")
        if (hiddenDuplicateIds != null) {
            println("ADB_DEBUG:   hiddenDuplicateIds: $hiddenDuplicateIds")
        }
        
        // Если нет информации о скрытых дублях, выходим
        if (hiddenDuplicateIds == null || hiddenDuplicateIds.isEmpty()) {
            println("ADB_DEBUG: handleFormerDuplicatesPositioning - no hidden duplicates info, skipping")
            return
        }
        
        // Создаём карту всех пресетов по size|dpi для быстрого поиска дубликатов
        val presetsBySizeDpi = mutableMapOf<String, MutableList<Int>>()
        allPresets.forEachIndexed { index, (_, preset) ->
            val key = preset.getDuplicateKey()
            presetsBySizeDpi.getOrPut(key) { mutableListOf() }.add(index)
        }
        
        // Находим пресеты, которые были скрыты как дубли и теперь видимы
        val formerDuplicatesToMove = mutableListOf<Int>()
        
        allPresets.forEachIndexed { index, (_, preset) ->
            val key = preset.getDuplicateKey()
            val presetsWithSameKey = presetsBySizeDpi[key] ?: emptyList()
            
            // Проверяем: был ли пресет скрыт как дубль
            if (preset.id in hiddenDuplicateIds) {
                // Проверяем, является ли он теперь первым видимым с таким ключом
                // (т.е. он в списке allPresets и идёт первым среди пресетов с таким же ключом)
                val isFirstWithKey = presetsWithSameKey.firstOrNull() == index
                
                if (isFirstWithKey) {
                    formerDuplicatesToMove.add(index)
                    println("ADB_DEBUG:   Found former hidden duplicate now visible: ${preset.label} (${preset.size}x${preset.dpi}) at index $index")
                }
            }
        }
        
        // Перемещаем бывшие дубли к похожим пресетам
        formerDuplicatesToMove.sortedDescending().forEach { indexToMove ->
            val (listName, presetToMove) = allPresets[indexToMove]
            
            // Ищем лучшую позицию рядом с похожим пресетом
            var bestPosition = -1
            var bestScore = -1
            
            allPresets.forEachIndexed { i, (otherListName, otherPreset) ->
                if (i != indexToMove && otherListName == listName && isRelatedPreset(presetToMove, otherPreset)) {
                    val score = calculateRelatednessScore(presetToMove, otherPreset)
                    if (score > bestScore) {
                        bestScore = score
                        bestPosition = i
                        println("ADB_DEBUG:   Found related preset: ${otherPreset.label} at index $i with score $score")
                    }
                }
            }
            
            // В режиме Show All НЕ перемещаем пресеты автоматически
            // Пользователь сам управляет порядком в этом режиме
            if (bestPosition != -1) {
                // Проверяем, действительно ли нужно перемещать
                // Если пресет уже находится рядом (в пределах 2 позиций), не перемещаем
                val distance = kotlin.math.abs(bestPosition - indexToMove)
                if (distance <= 2) {
                    println("ADB_DEBUG:   Skipping move for ${presetToMove.label} - already near related preset (distance: $distance)")
                    return@forEach
                }
                
                // В режиме Show All сохраняем пользовательский порядок
                if (isShowAllMode) {
                    println("ADB_DEBUG:   NOT moving ${presetToMove.label} - preserving user order in Show All mode")
                } else if (isSortingActive) {
                    // При активной сортировке не перемещаем пресеты, чтобы не нарушить порядок сортировки
                    println("ADB_DEBUG:   NOT moving ${presetToMove.label} - sorting is active, preserving sort order")
                } else {
                    // В обычном режиме без сортировки перемещаем пресет к связанному
                    println("ADB_DEBUG:   Moving ${presetToMove.label} to position after related preset")
                    
                    // Удаляем пресет из текущей позиции
                    allPresets.removeAt(indexToMove)
                    
                    // Определяем позицию для вставки - ПОСЛЕ (ПОД) найденного пресета
                    val insertPosition = if (bestPosition > indexToMove) {
                        bestPosition // Если перемещаем вниз, позиция уже сдвинулась после удаления
                    } else {
                        bestPosition + 1 // Если перемещаем вверх
                    }
                    
                    // Вставляем пресет в новую позицию
                    allPresets.add(insertPosition, listName to presetToMove)
                    println("ADB_DEBUG:   Moved ${presetToMove.label} from index $indexToMove to $insertPosition")
                }
                // val insertPosition = if (indexToMove < bestPosition) {
                //     bestPosition  // Если удаляли до bestPosition, индекс уже сдвинулся
                // } else {
                //     bestPosition + 1  // Если удаляли после, вставляем после bestPosition
                // }
                // 
                // // Сохраняем имя пресета, после которого вставляем, до вставки
                // val afterPresetLabel = if (insertPosition > 0) {
                //     allPresets[insertPosition - 1].second.label
                // } else {
                //     "beginning"
                // }
                // 
                // allPresets.add(insertPosition, listName to presetToMove)
                // println("ADB_DEBUG:   Moved ${presetToMove.label} from index $indexToMove to $insertPosition (after $afterPresetLabel)")
            }
        }
        
        println("ADB_DEBUG: handleFormerDuplicatesPositioning - end")
    }
    
    /**
     * Проверяет, являются ли два пресета связанными (похожими)
     */
    private fun isRelatedPreset(preset1: DevicePreset, preset2: DevicePreset): Boolean {
        // Пресеты связаны, если у них:
        // 1. Одинаковый базовый label (без суффикса вроде "(copy)")
        val baseLabel1 = preset1.label.substringBefore(" (")
        val baseLabel2 = preset2.label.substringBefore(" (")
        if (baseLabel1 == baseLabel2) return true
        
        // 2. Проверяем, являются ли они из одной серии устройств
        // Например, Pixel 7 Pro и Pixel 8 Pro
        val words1 = preset1.label.split(" ")
        val words2 = preset2.label.split(" ")
        
        // Если оба начинаются с одного и того же слова (например, "Pixel", "Galaxy")
        if (words1.isNotEmpty() && words2.isNotEmpty() && words1[0] == words2[0]) {
            // И оба заканчиваются на одно и то же слово (например, "Pro")
            if (words1.size >= 2 && words2.size >= 2) {
                val lastWord1 = words1.last()
                val lastWord2 = words2.last()
                if (lastWord1 == lastWord2 && lastWord1 in listOf("Pro", "Plus", "Ultra", "Max", "Mini")) {
                    return true
                }
            }
        }
        
        // 3. Одинаковый size
        if (preset1.size == preset2.size) return true
        
        // 4. Близкие размеры (например, 1080x2340 и 1080x2400)
        val parts1 = preset1.size.split("x")
        val parts2 = preset2.size.split("x")
        
        if (parts1.size == 2 && parts2.size == 2) {
            val width1 = parts1[0].toIntOrNull() ?: 0
            val height1 = parts1[1].toIntOrNull() ?: 0
            val width2 = parts2[0].toIntOrNull() ?: 0
            val height2 = parts2[1].toIntOrNull() ?: 0
            
            if (width1 > 0 && width2 > 0 && height1 > 0 && height2 > 0) {
                if (width1 == width2 && kotlin.math.abs(height1 - height2) <= 100) return true
                if (height1 == height2 && kotlin.math.abs(width1 - width2) <= 100) return true
            }
        }
        
        return false
    }
    
    /**
     * Вычисляет оценку связанности двух пресетов (чем выше, тем более связаны)
     */
    private fun calculateRelatednessScore(preset1: DevicePreset, preset2: DevicePreset): Int {
        var score = 0
        
        // Одинаковый базовый label - высший приоритет
        val baseLabel1 = preset1.label.substringBefore(" (")
        val baseLabel2 = preset2.label.substringBefore(" (")
        if (baseLabel1 == baseLabel2) score += 1000
        
        // Пресеты из одной серии - высокий приоритет
        val words1 = preset1.label.split(" ")
        val words2 = preset2.label.split(" ")
        
        if (words1.isNotEmpty() && words2.isNotEmpty() && words1[0] == words2[0]) {
            score += 500 // За одинаковый бренд/серию
            
            // Дополнительные баллы за одинаковый суффикс (Pro, Plus, etc.)
            if (words1.size >= 2 && words2.size >= 2) {
                val lastWord1 = words1.last()
                val lastWord2 = words2.last()
                if (lastWord1 == lastWord2 && lastWord1 in listOf("Pro", "Plus", "Ultra", "Max", "Mini")) {
                    score += 300
                }
            }
        }
        
        // Одинаковый size - средний приоритет
        if (preset1.size == preset2.size) score += 100
        
        // Близкие размеры - низкий приоритет
        val parts1 = preset1.size.split("x")
        val parts2 = preset2.size.split("x")
        
        if (parts1.size == 2 && parts2.size == 2) {
            val width1 = parts1[0].toIntOrNull() ?: 0
            val height1 = parts1[1].toIntOrNull() ?: 0
            val width2 = parts2[0].toIntOrNull() ?: 0
            val height2 = parts2[1].toIntOrNull() ?: 0
            
            if (width1 > 0 && width2 > 0 && height1 > 0 && height2 > 0) {
                if (width1 == width2) score += 10
                if (height1 == height2) score += 10
                
                // Штраф за расстояние в размерах
                score -= kotlin.math.abs(width1 - width2) / 100
                score -= kotlin.math.abs(height1 - height2) / 100
            }
        }
        
        return score
    }
    
    /**
     * Группирует дубликаты вместе, чтобы дубли шли сразу после первого экземпляра
     */
    private fun groupDuplicatesTogether(allPresets: MutableList<Pair<String, DevicePreset>>) {
        val result = mutableListOf<Pair<String, DevicePreset>>()
        val processedIds = mutableSetOf<String>()
        
        for (i in allPresets.indices) {
            val (listName, preset) = allPresets[i]
            
            // Пропускаем уже обработанные пресеты
            if (preset.id in processedIds) continue
            
            // Добавляем текущий пресет
            result.add(listName to preset)
            processedIds.add(preset.id)
            
            // Ищем все дубликаты (такие же size и dpi)
            val duplicateKey = preset.getDuplicateKey()
            
            // Собираем все дубликаты из оставшихся пресетов
            for (j in (i + 1) until allPresets.size) {
                val (dupListName, dupPreset) = allPresets[j]
                
                // Проверяем, что это дубликат и еще не обработан
                if (dupPreset.id !in processedIds && 
                    dupPreset.getDuplicateKey() == duplicateKey) {
                    
                    result.add(dupListName to dupPreset)
                    processedIds.add(dupPreset.id)
                }
            }
        }
        
        // Заменяем содержимое оригинального списка
        allPresets.clear()
        allPresets.addAll(result)
        
        println("ADB_DEBUG: Grouped duplicates together, total presets: ${allPresets.size}")
    }
}
