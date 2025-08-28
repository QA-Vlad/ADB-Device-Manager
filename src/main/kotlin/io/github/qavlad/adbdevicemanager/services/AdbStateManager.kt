package io.github.qavlad.adbdevicemanager.services

import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Глобальный менеджер состояния ADB сервера
 * Позволяет всем компонентам знать о текущем состоянии ADB
 */
object AdbStateManager {
    
    private val isRestarting = AtomicBoolean(false)
    private val pendingScrcpyRequests = CopyOnWriteArrayList<PendingScrcpyRequest>()
    private val stateListeners = CopyOnWriteArrayList<AdbStateListener>()
    
    /**
     * Данные о запросе на запуск scrcpy, который был заблокирован из-за рестарта ADB
     */
    data class PendingScrcpyRequest(
        val serialNumber: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Интерфейс для слушателей состояния ADB
     */
    interface AdbStateListener {
        fun onAdbRestartStarted()
        fun onAdbRestartCompleted()
    }
    
    /**
     * Проверяет, идёт ли сейчас рестарт ADB
     */
    fun isAdbRestarting(): Boolean = isRestarting.get()
    
    /**
     * Устанавливает состояние рестарта ADB
     */
    fun setAdbRestarting(restarting: Boolean) {
        val wasRestarting = isRestarting.getAndSet(restarting)
        
        if (restarting && !wasRestarting) {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "ADB restart started - blocking all operations")
            notifyRestartStarted()
        } else if (!restarting && wasRestarting) {
            PluginLogger.info(LogCategory.ADB_CONNECTION, "ADB restart completed - resuming operations")
            notifyRestartCompleted()
            processPendingScrcpyRequests()
        }
    }
    
    /**
     * Добавляет запрос на запуск scrcpy в очередь для выполнения после рестарта
     */
    fun addPendingScrcpyRequest(serialNumber: String) {
        if (isRestarting.get()) {
            pendingScrcpyRequests.add(PendingScrcpyRequest(serialNumber))
            PluginLogger.info(LogCategory.SCRCPY, 
                "Scrcpy request for device %s added to pending queue due to ADB restart", 
                serialNumber)
        }
    }

    /**
     * Добавляет слушателя состояния ADB
     */
    fun addStateListener(listener: AdbStateListener) {
        stateListeners.add(listener)
    }

    private fun notifyRestartStarted() {
        stateListeners.forEach { 
            try {
                it.onAdbRestartStarted()
            } catch (e: Exception) {
                PluginLogger.warn(LogCategory.ADB_CONNECTION, 
                    "Error notifying listener about ADB restart start: %s", e.message)
            }
        }
    }
    
    private fun notifyRestartCompleted() {
        stateListeners.forEach { 
            try {
                it.onAdbRestartCompleted()
            } catch (e: Exception) {
                PluginLogger.warn(LogCategory.ADB_CONNECTION, 
                    "Error notifying listener about ADB restart completion: %s", e.message)
            }
        }
    }
    
    private fun processPendingScrcpyRequests() {
        if (pendingScrcpyRequests.isNotEmpty()) {
            val requests = pendingScrcpyRequests.toList()
            pendingScrcpyRequests.clear()
            
            PluginLogger.info(LogCategory.SCRCPY, 
                "Processing %d pending scrcpy requests after ADB restart", 
                requests.size)
            
            // Запросы будут обработаны слушателями
            // ScrcpyService должен подписаться на события и обработать их
        }
    }

}