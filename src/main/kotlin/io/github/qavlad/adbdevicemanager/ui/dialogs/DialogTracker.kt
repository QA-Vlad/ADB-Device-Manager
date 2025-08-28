package io.github.qavlad.adbdevicemanager.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.lang.ref.WeakReference
import javax.swing.SwingUtilities

/**
 * Отслеживает открытые диалоги плагина
 */
object DialogTracker {
    private val activeDialogs = mutableSetOf<WeakReference<DialogWrapper>>()
    private var presetsDialogRef: WeakReference<PresetsDialog>? = null
    
    /**
     * Регистрирует открытый диалог пресетов
     */
    fun registerPresetsDialog(dialog: PresetsDialog) {
        presetsDialogRef = WeakReference(dialog)
        activeDialogs.add(WeakReference(dialog))
        PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Registered PresetsDialog")
        PluginLogger.debug(LogCategory.UI_EVENTS, "DialogTracker: Registered PresetsDialog at %s", dialog.hashCode())
    }
    
    /**
     * Удаляет закрытый диалог пресетов из трекера
     */
    fun unregisterPresetsDialog() {
        val wasRegistered = presetsDialogRef?.get() != null
        presetsDialogRef = null
        // Очищаем мертвые ссылки
        activeDialogs.removeIf { it.get() == null }
        PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Unregistered PresetsDialog (was registered: %s)", wasRegistered)
    }
    
    /**
     * Проверяет, открыт ли диалог пресетов
     */
    fun isPresetsDialogOpen(): Boolean {
        val dialog = presetsDialogRef?.get()
        val isOpen = dialog != null && dialog.isVisible
        PluginLogger.debug(LogCategory.UI_EVENTS, 
            "DialogTracker: Checking if PresetsDialog is open: %s (ref exists: %s, visible: %s)", 
            isOpen, dialog != null, dialog?.isVisible
        )
        return isOpen
    }
    
    /**
     * Закрывает диалог пресетов, если он открыт
     * @return true если диалог был закрыт, false если не был открыт
     */
    fun closePresetsDialogIfOpen(): Boolean {
        PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: closePresetsDialogIfOpen() called")
        val dialog = presetsDialogRef?.get()
        PluginLogger.info(LogCategory.UI_EVENTS, 
            "DialogTracker: Attempting to close PresetsDialog (ref exists: %s, visible: %s)", 
            dialog != null, dialog?.isVisible
        )
        
        if (dialog != null && dialog.isVisible) {
            PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Closing PresetsDialog with CANCEL_EXIT_CODE")
            
            // Проверяем, в каком потоке мы находимся
            if (SwingUtilities.isEventDispatchThread()) {
                // Мы уже в EDT, можем закрыть напрямую
                try {
                    // ВАЖНО: Сначала восстанавливаем состояние, потом закрываем
                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Restoring original state before closing")
                    dialog.restoreStateAndClose()
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Successfully closed PresetsDialog from EDT with state restoration")
                    return true
                } catch (e: Exception) {
                    PluginLogger.error("DialogTracker: Failed to close PresetsDialog from EDT", e)
                    return false
                }
            } else {
                // Мы в фоновом потоке, нужно переключиться в EDT
                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Not in EDT, need to switch")
                
                // Попробуем использовать invokeAndWait вместо invokeLater
                var result = false
                try {
                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Using invokeAndWait to close dialog")
                    SwingUtilities.invokeAndWait {
                        PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Now in EDT via invokeAndWait")
                        try {
                            if (dialog.isVisible) {
                                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Dialog is visible, attempting to close with state restoration")
                                // ВАЖНО: Сначала восстанавливаем состояние, потом закрываем
                                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Restoring original state before closing")
                                dialog.restoreStateAndClose()
                                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                                result = true
                                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Successfully closed PresetsDialog with state restoration")
                            } else {
                                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Dialog was already closed")
                            }
                        } catch (e: Exception) {
                            PluginLogger.error("DialogTracker: Failed to close PresetsDialog", e)
                        }
                    }
                } catch (e: Exception) {
                    PluginLogger.error("DialogTracker: Failed to execute invokeAndWait", e)
                    
                    // Если invokeAndWait не работает (например, из-за модального диалога),
                    // попробуем закрыть через отдельный поток
                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Trying to close via separate thread")
                    Thread {
                        SwingUtilities.invokeLater {
                            try {
                                if (dialog.isVisible) {
                                    // ВАЖНО: Сначала восстанавливаем состояние, потом закрываем
                                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Restoring original state before closing (separate thread)")
                                    dialog.restoreStateAndClose()
                                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                                    PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Closed via separate thread with state restoration")
                                }
                            } catch (e2: Exception) {
                                PluginLogger.error("DialogTracker: Failed to close via separate thread", e2)
                            }
                        }
                    }.start()
                    
                    // Даем время на закрытие
                    Thread.sleep(500)
                }
                
                PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: Returning result=%s", result)
                return result
            }
        }
        
        PluginLogger.info(LogCategory.UI_EVENTS, "DialogTracker: PresetsDialog was not open, nothing to close")
        return false
    }

}