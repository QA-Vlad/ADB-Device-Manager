package io.github.qavlad.adbrandomizer.utils.logging

enum class LogLevel(val value: Int) {
    TRACE(0),
    DEBUG(1), 
    INFO(2),
    WARN(3),
    ERROR(4);
    
    fun isEnabled(minLevel: LogLevel): Boolean = this.value >= minLevel.value
}