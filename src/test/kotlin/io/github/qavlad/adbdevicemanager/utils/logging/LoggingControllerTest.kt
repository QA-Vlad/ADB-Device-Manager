package io.github.qavlad.adbdevicemanager.utils.logging

import org.junit.Test

/**
 * Тест для проверки системы логирования
 * 
 * Важно: Этот тест демонстрирует использование системы логирования,
 * но не может быть запущен в обычном unit test окружении, так как
 * требует IntelliJ Platform runtime.
 * 
 * Для реального тестирования нужно использовать:
 * 1. IntelliJ Platform Test Framework
 * 2. Или запускать тесты внутри IDE с плагином
 */
class LoggingControllerTest {
    
    @Test
    fun testLoggingFormatting() {
        println("\n===== Testing Logging Format =====\n")
        
        // Тестируем форматирование без реального вызова логгера
        val testCases = listOf(
            Triple("Test message %s", arrayOf<Any?>("param1"), "Test message param1"),
            Triple("Multiple params: %s, %d", arrayOf<Any?>("text", 42), "Multiple params: text, 42"),
            Triple("No params", emptyArray<Any?>(), "No params")
        )
        
        for ((message, args, expected) in testCases) {
            val formatted = try {
                if (args.isNotEmpty()) {
                    message.format(*args)
                } else {
                    message
                }
            } catch (e: Exception) {
                "$message (format error: ${e.message})"
            }
            
            println("Input: \"$message\" with ${args.contentToString()}")
            println("Output: \"$formatted\"")
            println("Expected: \"$expected\"")
            println("Match: ${formatted == expected}\n")
        }
        
        println("===== Format Testing Complete =====\n")
    }
    
    @Test
    fun testLogLevelComparison() {
        println("\n===== Testing Log Level Comparison =====\n")
        
        // Тестируем сравнение уровней логирования
        val levels = listOf(
            LogLevel.TRACE,
            LogLevel.DEBUG,
            LogLevel.INFO,
            LogLevel.WARN,
            LogLevel.ERROR
        )
        
        println("Log Level Values:")
        for (level in levels) {
            println("$level: value = ${level.value}")
        }
        
        println("\nLog Level Comparison Matrix:")
        println("Level\t\tTRACE\tDEBUG\tINFO\tWARN\tERROR")
        for (currentLevel in levels) {
            print("$currentLevel\t")
            if (currentLevel.name.length < 5) print("\t")
            for (checkLevel in levels) {
                val enabled = checkLevel.isEnabled(currentLevel)
                print(if (enabled) "✓" else "✗")
                print("\t")
            }
            println()
        }
        
        println("\n===== Log Level Testing Complete =====\n")
    }
    
    @Test
    fun testCategoryDefaults() {
        println("\n===== Testing Category Default Levels =====\n")
        
        val categories = LogCategory.values()
        
        println("Category Default Log Levels:")
        for (category in categories) {
            println("${category.name}: ${category.displayName} -> ${category.defaultLevel}")
        }
        
        println("\n===== Category Testing Complete =====\n")
    }

}