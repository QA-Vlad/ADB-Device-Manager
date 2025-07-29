package io.github.qavlad.adbrandomizer.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DevicePresetSerializationTest {
    
    @Test
    fun `test DevicePreset serialization preserves ID`() {
        // Создаем пресет с ID
        val preset = DevicePreset("Test Label", "1080x1920", "420")
        val originalId = preset.id
        
        // Сериализуем
        val gson = Gson()
        val json = gson.toJson(preset)
        println("Serialized JSON: $json")
        
        // Десериализуем
        val deserializedPreset = gson.fromJson(json, DevicePreset::class.java)
        
        // Проверяем, что ID сохранился
        assertNotNull(deserializedPreset.id)
        assertEquals(originalId, deserializedPreset.id)
        assertEquals(preset.label, deserializedPreset.label)
        assertEquals(preset.size, deserializedPreset.size)
        assertEquals(preset.dpi, deserializedPreset.dpi)
    }
    
    @Test
    fun `test custom deserializer handles missing ID`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(DevicePreset::class.java, PresetListService.DevicePresetDeserializer())
            .create()
            
        // JSON без ID (старый формат)
        val jsonWithoutId = """{"label":"Test","size":"1080x1920","dpi":"420"}"""
        
        val preset = gson.fromJson(jsonWithoutId, DevicePreset::class.java)
        
        assertNotNull(preset.id)
        assertEquals("Test", preset.label)
        assertEquals("1080x1920", preset.size)
        assertEquals("420", preset.dpi)
    }
    
    @Test
    fun `test custom deserializer preserves existing ID`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(DevicePreset::class.java, PresetListService.DevicePresetDeserializer())
            .create()
            
        // JSON с ID
        val testId = "test-id-12345"
        val jsonWithId = """{"label":"Test","size":"1080x1920","dpi":"420","id":"$testId"}"""
        
        val preset = gson.fromJson(jsonWithId, DevicePreset::class.java)
        
        assertEquals(testId, preset.id)
        assertEquals("Test", preset.label)
        assertEquals("1080x1920", preset.size)
        assertEquals("420", preset.dpi)
    }
}