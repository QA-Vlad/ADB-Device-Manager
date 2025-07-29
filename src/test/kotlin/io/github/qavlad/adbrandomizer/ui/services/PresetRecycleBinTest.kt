package io.github.qavlad.adbrandomizer.ui.services

import io.github.qavlad.adbrandomizer.services.DevicePreset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PresetRecycleBinTest {
    private lateinit var recycleBin: PresetRecycleBin
    
    @Before
    fun setUp() {
        recycleBin = PresetRecycleBin()
    }
    
    @Test
    fun `test move to recycle bin and restore`() {
        // Создаём тестовый пресет
        val preset = DevicePreset(
            size = "1920x1080",
            dpi = "320",
            label = "Test Preset",
            id = "test-id-123"
        )
        
        // Проверяем, что корзина пуста
        assertTrue(recycleBin.isEmpty())
        assertEquals(0, recycleBin.size())
        
        // Перемещаем пресет в корзину
        recycleBin.moveToRecycleBin(preset, "TestList", 5)
        
        // Проверяем, что пресет в корзине
        assertFalse(recycleBin.isEmpty())
        assertEquals(1, recycleBin.size())
        
        // Восстанавливаем пресет
        val restored = recycleBin.restoreFromRecycleBin("TestList", 5, "test-id-123")
        
        // Проверяем, что пресет восстановлен корректно
        assertNotNull(restored)
        assertEquals(preset.size, restored?.size)
        assertEquals(preset.dpi, restored?.dpi)
        assertEquals(preset.label, restored?.label)
        assertEquals(preset.id, restored?.id)
        
        // Проверяем, что корзина снова пуста
        assertTrue(recycleBin.isEmpty())
    }
    
    @Test
    fun `test restore by partial key`() {
        val preset = DevicePreset(
            size = "1080x1920",
            dpi = "240",
            label = "Portrait Preset"
        )
        
        recycleBin.moveToRecycleBin(preset, "PortraitList", 2)
        
        // Восстанавливаем без указания ID
        val restored = recycleBin.restoreFromRecycleBin("PortraitList", 2)
        
        assertNotNull(restored)
        assertEquals(preset.label, restored?.label)
    }
    
    @Test
    fun `test find without removing`() {
        val preset1 = DevicePreset(size = "800x600", dpi = "160", label = "Small")
        val preset2 = DevicePreset(size = "2560x1440", dpi = "480", label = "Large")
        
        recycleBin.moveToRecycleBin(preset1, "List1", 0)
        recycleBin.moveToRecycleBin(preset2, "List2", 3)
        
        // Ищем пресет без удаления
        val found = recycleBin.findDeletedPreset("List1", 0)
        
        assertNotNull(found)
        assertEquals(preset1.label, found?.label)
        
        // Проверяем, что пресет всё ещё в корзине
        assertEquals(2, recycleBin.size())
    }
    
    @Test
    fun `test clear recycle bin`() {
        // Добавляем несколько пресетов
        for (i in 1..5) {
            val preset = DevicePreset(
                size = "${i * 100}x${i * 100}",
                dpi = "${i * 40}",
                label = "Preset $i"
            )
            recycleBin.moveToRecycleBin(preset, "List", i)
        }
        
        assertEquals(5, recycleBin.size())
        
        // Очищаем корзину
        recycleBin.clear()
        
        assertTrue(recycleBin.isEmpty())
        assertEquals(0, recycleBin.size())
    }
    
    @Test
    fun `test restore non-existent preset returns null`() {
        val restored = recycleBin.restoreFromRecycleBin("NonExistentList", 99)
        assertNull(restored)
    }
    
    @Test
    fun `test multiple presets from same list`() {
        val preset1 = DevicePreset(size = "720x1280", dpi = "320", label = "HD", id = "hd-1")
        val preset2 = DevicePreset(size = "1080x1920", dpi = "480", label = "FHD", id = "fhd-1")
        
        recycleBin.moveToRecycleBin(preset1, "SameList", 0)
        recycleBin.moveToRecycleBin(preset2, "SameList", 1)
        
        assertEquals(2, recycleBin.size())
        
        // Восстанавливаем первый
        val restored1 = recycleBin.restoreFromRecycleBin("SameList", 0, "hd-1")
        assertNotNull(restored1)
        assertEquals("HD", restored1?.label)
        
        // Проверяем, что второй всё ещё в корзине
        assertEquals(1, recycleBin.size())
        
        // Восстанавливаем второй
        val restored2 = recycleBin.restoreFromRecycleBin("SameList", 1, "fhd-1")
        assertNotNull(restored2)
        assertEquals("FHD", restored2?.label)
        
        assertTrue(recycleBin.isEmpty())
    }
}