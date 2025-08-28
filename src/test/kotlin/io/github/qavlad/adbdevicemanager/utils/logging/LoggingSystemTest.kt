package io.github.qavlad.adbdevicemanager.utils.logging

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LoggingSystemTest {
    
    @Before
    fun setUp() {
        LoggingConfiguration.getInstance().resetRateLimits()
    }
    
    @Test
    fun `test log level filtering`() {
        val config = LoggingConfiguration.getInstance()
        
        // По умолчанию SYNC_OPERATIONS имеет уровень WARN
        assertTrue(config.shouldLog(LogLevel.ERROR, LogCategory.SYNC_OPERATIONS))
        assertTrue(config.shouldLog(LogLevel.WARN, LogCategory.SYNC_OPERATIONS))
        assertFalse(config.shouldLog(LogLevel.INFO, LogCategory.SYNC_OPERATIONS))
        assertFalse(config.shouldLog(LogLevel.DEBUG, LogCategory.SYNC_OPERATIONS))
        
        // Изменяем уровень на DEBUG
        config.setCategoryLogLevel(LogCategory.SYNC_OPERATIONS, LogLevel.DEBUG)
        assertTrue(config.shouldLog(LogLevel.ERROR, LogCategory.SYNC_OPERATIONS))
        assertTrue(config.shouldLog(LogLevel.WARN, LogCategory.SYNC_OPERATIONS))
        assertTrue(config.shouldLog(LogLevel.INFO, LogCategory.SYNC_OPERATIONS))
        assertTrue(config.shouldLog(LogLevel.DEBUG, LogCategory.SYNC_OPERATIONS))
    }
    
    @Test
    fun `test rate limiting`() {
        val config = LoggingConfiguration.getInstance()
        config.setCategoryLogLevel(LogCategory.TABLE_OPERATIONS, LogLevel.DEBUG)
        
        // Первые 5 сообщений должны пройти
        for (i in 1..5) {
            assertTrue(
                "Message $i should be logged",
                config.shouldLogWithRateLimit("test_key", LogLevel.DEBUG, LogCategory.TABLE_OPERATIONS)
            )
        }
        
        // 6-е сообщение должно быть заблокировано
        assertFalse(
            "6th message should be rate limited",
            config.shouldLogWithRateLimit("test_key", LogLevel.DEBUG, LogCategory.TABLE_OPERATIONS)
        )
        
        // Другой ключ должен работать
        assertTrue(
            "Different key should work",
            config.shouldLogWithRateLimit("another_key", LogLevel.DEBUG, LogCategory.TABLE_OPERATIONS)
        )
    }
    
    @Test
    fun `test global log level`() {
        val config = LoggingConfiguration.getInstance()
        
        // Установим глобальный уровень ERROR
        config.setGlobalLogLevel(LogLevel.ERROR)
        
        // Даже если категория имеет уровень INFO, глобальный уровень должен иметь приоритет
        assertFalse(config.shouldLog(LogLevel.WARN, LogCategory.GENERAL))
        assertTrue(config.shouldLog(LogLevel.ERROR, LogCategory.GENERAL))
    }
    
    @Test
    fun `test category default levels`() {
        // Проверяем дефолтные уровни
        assertEquals(LogLevel.INFO, LogCategory.GENERAL.defaultLevel)
        assertEquals(LogLevel.WARN, LogCategory.TABLE_OPERATIONS.defaultLevel)
        assertEquals(LogLevel.WARN, LogCategory.SYNC_OPERATIONS.defaultLevel)
        assertEquals(LogLevel.ERROR, LogCategory.DEBUG_TRACE.defaultLevel)
        assertEquals(LogLevel.INFO, LogCategory.SCRCPY.defaultLevel)
    }
}