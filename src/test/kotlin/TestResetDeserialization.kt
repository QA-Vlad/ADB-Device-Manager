package io.github.qavlad.adbrandomizer

import com.google.gson.Gson
import io.github.qavlad.adbrandomizer.services.PresetList
import java.io.File

fun main() {
    val gson = Gson()
    
    // Читаем файл из ресурсов
    val resourceFile = File("src/main/resources/presets/Budget Phones 2024.json")
    if (!resourceFile.exists()) {
        println("File not found: ${resourceFile.absolutePath}")
        return
    }
    
    val json = resourceFile.readText()
    println("JSON length: ${json.length}")
    
    // Десериализуем
    val presetList = gson.fromJson(json, PresetList::class.java)
    
    println("Deserialized list:")
    println("  Name: ${presetList.name}")
    println("  ID: ${presetList.id}")
    println("  isDefault: ${presetList.isDefault}")
    println("  Presets count: ${presetList.presets.size}")
    
    // Выводим первые 5 пресетов
    presetList.presets.take(5).forEachIndexed { i, preset ->
        println("  [$i] ${preset.label} | ${preset.size} | ${preset.dpi} | id=${preset.id}")
    }
    
    // Сериализуем обратно и проверяем
    val jsonBack = gson.toJson(presetList)
    val deserializedAgain = gson.fromJson(jsonBack, PresetList::class.java)
    
    println("\nAfter re-serialization:")
    println("  Presets count: ${deserializedAgain.presets.size}")
}