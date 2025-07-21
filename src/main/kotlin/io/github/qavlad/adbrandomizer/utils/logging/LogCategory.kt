package io.github.qavlad.adbrandomizer.utils.logging

enum class LogCategory(val displayName: String, val defaultLevel: LogLevel) {
    GENERAL("General", LogLevel.INFO),
    TABLE_OPERATIONS("Table Operations", LogLevel.WARN),
    PRESET_SERVICE("Preset Service", LogLevel.WARN),
    SYNC_OPERATIONS("Sync Operations", LogLevel.WARN),
    UI_EVENTS("UI Events", LogLevel.WARN),
    SCRCPY("Scrcpy", LogLevel.INFO),
    ADB_CONNECTION("ADB Connection", LogLevel.INFO),
    DEBUG_TRACE("Debug Trace", LogLevel.ERROR),
    DRAG_DROP("Drag & Drop", LogLevel.WARN),
    KEYBOARD("Keyboard", LogLevel.WARN),
    SORTING("Sorting", LogLevel.WARN),
    COMMAND_HISTORY("Command History", LogLevel.WARN);
    
    companion object {
        fun fromName(name: String): LogCategory? = values().find { it.name == name }
    }
}