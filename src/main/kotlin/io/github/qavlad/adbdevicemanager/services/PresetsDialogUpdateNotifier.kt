package io.github.qavlad.adbdevicemanager.services

object PresetsDialogUpdateNotifier {
    
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