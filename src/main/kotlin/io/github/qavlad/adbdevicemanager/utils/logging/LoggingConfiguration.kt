package io.github.qavlad.adbdevicemanager.utils.logging

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.concurrent.ConcurrentHashMap

@State(
    name = "AdbDeviceManagerLoggingConfiguration",
    storages = [Storage("adb-device-manager-logging.xml")]
)
@Service
class LoggingConfiguration : PersistentStateComponent<LoggingConfiguration.State> {
    
    data class State(
        var globalLogLevel: String = LogLevel.INFO.name,
        var categoryLevels: MutableMap<String, String> = mutableMapOf(),
        var isDebugModeEnabled: Boolean = false,
        var rateLimitWindow: Long = 1000L, // milliseconds
        var rateLimitCount: Int = 5
    )
    
    private var state = State()
    private val lastLogTimes = ConcurrentHashMap<String, MutableList<Long>>()
    
    init {
        // Проверяем аргументы командной строки
        val logLevel = System.getProperty("adb.device.manager.log.level")
        val logCategory = System.getProperty("adb.device.manager.log.category")
        
        if (logLevel != null) {
            try {
                val level = LogLevel.valueOf(logLevel.uppercase())
                if (logCategory != null) {
                    // Устанавливаем уровень для конкретной категории
                    val category = LogCategory.fromName(logCategory.uppercase())
                    if (category != null) {
                        setCategoryLogLevel(category, level)
                        println("ADB_Device_Manager: Set log level for $category to $level")
                    }
                } else {
                    // Устанавливаем глобальный уровень
                    setGlobalLogLevel(level)
                    println("ADB_Device_Manager: Set global log level to $level")
                }
            } catch (_: IllegalArgumentException) {
                println("ADB_Device_Manager: Invalid log level: $logLevel")
            }
        }
    }
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    fun getGlobalLogLevel(): LogLevel = LogLevel.valueOf(state.globalLogLevel)
    
    fun setGlobalLogLevel(level: LogLevel) {
        state.globalLogLevel = level.name
    }
    
    fun getCategoryLogLevel(category: LogCategory): LogLevel {
        val levelName = state.categoryLevels[category.name]
        return if (levelName != null) {
            try {
                LogLevel.valueOf(levelName)
            } catch (_: IllegalArgumentException) {
                category.defaultLevel
            }
        } else {
            category.defaultLevel
        }
    }
    
    fun setCategoryLogLevel(category: LogCategory, level: LogLevel) {
        state.categoryLevels[category.name] = level.name
    }
    
    fun isDebugModeEnabled(): Boolean = state.isDebugModeEnabled
    
    fun setDebugModeEnabled(enabled: Boolean) {
        state.isDebugModeEnabled = enabled
    }
    
    fun shouldLog(level: LogLevel, category: LogCategory): Boolean {
        val categoryLevel = getCategoryLogLevel(category)
        val globalLevel = getGlobalLogLevel()
        val effectiveLevel = if (categoryLevel.value > globalLevel.value) categoryLevel else globalLevel
        
        return level.isEnabled(effectiveLevel)
    }
    
    fun shouldLogWithRateLimit(key: String, level: LogLevel, category: LogCategory): Boolean {
        if (!shouldLog(level, category)) return false
        
        val now = System.currentTimeMillis()
        val times = lastLogTimes.computeIfAbsent(key) { mutableListOf() }
        
        synchronized(times) {
            // Remove old entries outside the rate limit window
            times.removeAll { it < now - state.rateLimitWindow }
            
            if (times.size >= state.rateLimitCount) {
                return false
            }
            
            times.add(now)
            return true
        }
    }
    
    fun resetRateLimits() {
        lastLogTimes.clear()
    }
    
    /**
     * Сбрасывает все настройки логирования на значения по умолчанию
     */
    fun resetToDefaults() {
        state = State()
        lastLogTimes.clear()
    }
    
    companion object {
        fun getInstance(): LoggingConfiguration = service()
    }
}