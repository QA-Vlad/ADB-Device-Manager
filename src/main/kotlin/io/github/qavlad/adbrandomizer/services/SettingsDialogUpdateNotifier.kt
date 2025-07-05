package io.github.qavlad.adbrandomizer.services

object SettingsDialogUpdateNotifier {
    
    private val listeners = mutableSetOf<() -> Unit>()
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    fun notifyUpdate() {
        listeners.forEach { it.invoke() }
    }
}