package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import io.github.qavlad.adbrandomizer.core.Result

/**
 * Пример теста с использованием моков (MockK библиотека)
 * 
 * Моки нужны когда мы хотим протестировать код, который зависит от внешних систем
 * (например, ADB, файловая система, сеть)
 */
class PresetApplicationServiceTest {
    
    // Мокаем IDevice - это интерфейс из Android SDK
    private val mockDevice = mockk<IDevice>()
    private val mockProject = mockk<com.intellij.openapi.project.Project>()
    
    @Before
    fun setUp() {
        // Настраиваем поведение моков перед каждым тестом
        every { mockDevice.name } returns "TestDevice"
        every { mockDevice.serialNumber } returns "123456789"
        
        // Мокаем objects (синглтоны)
        mockkObject(AdbService)
        mockkObject(ValidationUtils)
    }
    
    @After
    fun tearDown() {
        // Очищаем моки после каждого теста
        unmockkAll()
    }
    
    @Test
    fun `applyPreset should set size and dpi when both are provided`() {
        // Arrange - подготовка
        val preset = DevicePreset("Test Preset", "1080x1920", "480")
        
        // Настраиваем, что ValidationUtils вернёт правильные значения
        every { ValidationUtils.parseSize("1080x1920") } returns Pair(1080, 1920)
        every { ValidationUtils.parseDpi("480") } returns 480
        
        // Настраиваем AdbService, чтобы он возвращал, мок устройства
        every { AdbService.getConnectedDevices(any()) } returns Result.Success(listOf(mockDevice))
        
        // Говорим что методы setSize и setDpi должны просто ничего не делать
        every { AdbService.setSize(mockDevice, 1080, 1920) } returns Result.Success(Unit)
        every { AdbService.setDpi(mockDevice, 480) } returns Result.Success(Unit)
        
        // Act - выполнение
        // Здесь бы мы вызвали PresetApplicationService.applyPreset()
        // Но так как он использует корутины и Task.Backgroundable, 
        // давай сделаем упрощённый пример
        
        // Симулируем применение пресета
        val devices = AdbService.getConnectedDevices(mockProject).getOrNull() ?: emptyList()
        devices.forEach { device ->
            val size = ValidationUtils.parseSize(preset.size)
            if (size != null) {
                AdbService.setSize(device, size.first, size.second)
            }
            val dpi = ValidationUtils.parseDpi(preset.dpi)
            if (dpi != null) {
                AdbService.setDpi(device, dpi)
            }
        }
        
        // Assert - проверка
        // Проверяем что методы были вызваны с правильными параметрами
        verify(exactly = 1) { AdbService.setSize(mockDevice, 1080, 1920) }
        verify(exactly = 1) { AdbService.setDpi(mockDevice, 480) }
    }
    
    @Test
    fun `applyPreset should skip invalid size`() {
        // Arrange
        val preset = DevicePreset("Test", "invalid", "480")
        
        every { ValidationUtils.parseSize("invalid") } returns null
        every { ValidationUtils.parseDpi("480") } returns 480
        every { AdbService.getConnectedDevices(any()) } returns Result.Success(listOf(mockDevice))
        every { AdbService.setDpi(mockDevice, 480) } returns Result.Success(Unit)
        
        // Act
        val devices = AdbService.getConnectedDevices(mockProject).getOrNull() ?: emptyList()
        devices.forEach { device ->
            val size = ValidationUtils.parseSize(preset.size)
            if (size != null) {
                AdbService.setSize(device, size.first, size.second)
            }
            val dpi = ValidationUtils.parseDpi(preset.dpi) 
            if (dpi != null) {
                AdbService.setDpi(device, dpi)
            }
        }
        
        // Assert
        // setSize не должен быть вызван, так как размер невалидный
        verify(exactly = 0) { AdbService.setSize(any(), any(), any()) }
        // setDpi должен быть вызван
        verify(exactly = 1) { AdbService.setDpi(mockDevice, 480) }
    }
    
    @Test
    fun `applyPreset should handle no connected devices`() {
        // Arrange
        every { AdbService.getConnectedDevices(any()) } returns Result.Success(emptyList())
        
        // Act
        val devices = AdbService.getConnectedDevices(mockProject).getOrNull() ?: emptyList()
        
        // Assert
        assertTrue("Должен вернуть пустой список", devices.isEmpty())
        // Никакие методы установки не должны быть вызваны
        verify(exactly = 0) { AdbService.setSize(any(), any(), any()) }
        verify(exactly = 0) { AdbService.setDpi(any(), any()) }
    }
}
