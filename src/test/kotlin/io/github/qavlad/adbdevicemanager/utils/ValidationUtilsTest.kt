package io.github.qavlad.adbdevicemanager.utils

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit тесты для ValidationUtils
 * 
 * Каждый метод с аннотацией @Test - это отдельный тест
 */
class ValidationUtilsTest {
    
    // Тест проверяет, что валидные IP адреса принимаются
    @Test
    fun `isValidIpAddress should accept valid IPs`() {
        // Arrange - подготовка (не нужна в этом случае)
        
        // Act & Assert - выполнение и проверка
        assertTrue("192.168.1.1 должен быть валидным", ValidationUtils.isValidIpAddress("192.168.1.1"))
        assertTrue("10.0.0.1 должен быть валидным", ValidationUtils.isValidIpAddress("10.0.0.1"))
        assertTrue("8.8.8.8 должен быть валидным", ValidationUtils.isValidIpAddress("8.8.8.8"))
    }
    
    // Тест проверяет, что невалидные IP адреса отклоняются
    @Test
    fun `isValidIpAddress should reject invalid IPs`() {
        assertFalse("256.1.1.1 не должен быть валидным", ValidationUtils.isValidIpAddress("256.1.1.1"))
        assertFalse("abc.def.ghi.jkl не должен быть валидным", ValidationUtils.isValidIpAddress("abc.def.ghi.jkl"))
        assertFalse("Пустая строка не должна быть валидной", ValidationUtils.isValidIpAddress(""))
    }
    
    // Тест проверяет парсинг размеров
    @Test
    fun `parseSize should correctly parse valid sizes`() {
        // Проверяем, что метод возвращает правильную пару значений
        assertEquals(Pair(1080, 1920), ValidationUtils.parseSize("1080x1920"))
        assertEquals(Pair(720, 1280), ValidationUtils.parseSize("720x1280"))
        
        // Проверяем с пробелами
        assertEquals(Pair(1080, 1920), ValidationUtils.parseSize("1080 x 1920"))
    }
    
    // Тест проверяет, что невалидные размеры возвращают null
    @Test
    fun `parseSize should return null for invalid sizes`() {
        assertNull("Невалидный формат должен вернуть null", ValidationUtils.parseSize("invalid"))
        assertNull("Пустая строка должна вернуть null", ValidationUtils.parseSize(""))
        assertNull("Только одно число должно вернуть null", ValidationUtils.parseSize("1080"))
    }
}
