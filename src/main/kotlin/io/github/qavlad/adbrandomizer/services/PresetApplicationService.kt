package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils
import io.github.qavlad.adbrandomizer.ui.services.TableStateTracker
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import javax.swing.SwingUtilities

object PresetApplicationService {
    
    fun applyPreset(project: Project, preset: DevicePreset, setSize: Boolean, setDpi: Boolean, currentTablePosition: Int? = null, selectedDevices: List<IDevice>? = null) {
        println("ADB_Randomizer: applyPreset called - preset: ${preset.label}, setSize: $setSize, setDpi: $setDpi")
        object : Task.Backgroundable(project, "Applying preset") {
            override fun run(indicator: ProgressIndicator) {
                val settings = PluginSettings.instance
                println("ADB_Randomizer: Settings - restartApp: ${settings.restartActiveAppOnResolutionChange}, restartScrcpy: ${settings.restartScrcpyOnResolutionChange}")
                val presetData = validateAndParsePresetData(preset, setSize, setDpi) ?: return
                
                // Используем переданный список устройств или получаем все подключенные
                val devices = if (selectedDevices != null) {
                    selectedDevices
                } else {
                    // Если список устройств не передан, получаем все подключенные устройства
                    // В этом случае вызов должен происходить из контекстного меню, где устройства уже отфильтрованы
                    val devicesResult = AdbService.getConnectedDevices(project)
                    devicesResult.getOrNull() ?: emptyList()
                }
                
                if (devices.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showInfo(project, "No devices selected or connected")
                    }
                    return
                }
                
                applyPresetToAllDevices(devices, preset, presetData, indicator, project)
                
                // Обновляем состояние устройств после применения пресета
                updateDeviceStatesAfterPresetApplication(devices, presetData)
                
                // Увеличиваем счетчики использования
                if (setSize && preset.size.isNotBlank()) {
                    UsageCounterService.incrementSizeCounter(preset.size)
                }
                if (setDpi && preset.dpi.isNotBlank()) {
                    UsageCounterService.incrementDpiCounter(preset.dpi)
                }
                
                // Отслеживаем какой пресет был применен (сохраняем полную копию текущего состояния пресета)
                val appliedSizePreset = if (setSize) preset.copy(id = preset.id) else null
                val appliedDpiPreset = if (setDpi) preset.copy(id = preset.id) else null
                
                DeviceStateService.setLastAppliedPresets(appliedSizePreset, appliedDpiPreset)
                
                // Немедленно уведомляем об обновлении до UI обновлений
                PresetsDialogUpdateNotifier.notifyUpdate()
                
                ApplicationManager.getApplication().invokeLater {
                    // Добавляем дополнительную задержку для обеспечения обновления таблицы
                    SwingUtilities.invokeLater {
                        // Всегда вычисляем позицию после обновления счетчиков
                        val displayNumber = run {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Computing position after counter update, currentTablePosition: %s", currentTablePosition)
                            
                            // Если есть currentTablePosition, значит применение из контекстного меню таблицы
                            // В этом случае используем TableStateTracker для получения актуальной позиции
                            if (currentTablePosition != null) {
                                val position = TableStateTracker.getPresetPosition(preset)
                                PluginLogger.debug(LogCategory.PRESET_SERVICE, "Using TableStateTracker position: %s", position)
                                position ?: currentTablePosition
                            } else {
                            // Иначе получаем позицию из отсортированного списка
                            val sortedPresets = PresetListService.getSortedPresets()
                            
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Sorted presets count: %s", sortedPresets.size)
                            
                            // Логируем первые 10 пресетов для отладки
                            sortedPresets.take(10).forEachIndexed { index, p ->
                                val sizeUses = UsageCounterService.getSizeCounter(p.size)
                                val dpiUses = UsageCounterService.getDpiCounter(p.dpi)
                                PluginLogger.debug(LogCategory.PRESET_SERVICE, "  Position %s: %s (%s, %s) - Size Uses: %s, DPI Uses: %s", 
                                    index + 1, p.label, p.size, p.dpi, sizeUses, dpiUses)
                            }
                            
                            val presetIndex = sortedPresets.indexOfFirst { 
                                it.label == preset.label && it.size == preset.size && it.dpi == preset.dpi 
                            }
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Looking for preset: %s (%s, %s)", preset.label, preset.size, preset.dpi)
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Found preset at index: %s", presetIndex)
                            
                            if (presetIndex >= 0) presetIndex + 1 else 1
                        }
                    }
                    
                        PluginLogger.debug(LogCategory.PRESET_SERVICE, "Showing result with displayNumber: %s", displayNumber)
                        showPresetApplicationResult(project, preset, displayNumber, presetData.appliedSettings)
                    }
                    // Уведомляем все открытые диалоги настроек об обновлении
                    PresetsDialogUpdateNotifier.notifyUpdate()
                }
            }
        }.queue()
    }
    
    private data class PresetData(
        val width: Int?,
        val height: Int?,
        val dpi: Int?,
        val appliedSettings: List<String>
    )
    
    private fun validateAndParsePresetData(preset: DevicePreset, setSize: Boolean, setDpi: Boolean): PresetData? {
        val appliedSettings = mutableListOf<String>()
        var width: Int? = null
        var height: Int? = null
        var dpi: Int? = null
        
        if (setSize && preset.size.isNotBlank()) {
            val sizeData = ValidationUtils.parseSize(preset.size) ?: return null
            width = sizeData.first
            height = sizeData.second
            appliedSettings.add("Size: ${preset.size}")
        }
        
        if (setDpi && preset.dpi.isNotBlank()) {
            val dpiValue = ValidationUtils.parseDpi(preset.dpi) ?: return null
            dpi = dpiValue
            appliedSettings.add("DPI: ${preset.dpi}")
        }
        
        if (appliedSettings.isEmpty()) {
            return null
        }
        
        return PresetData(width, height, dpi, appliedSettings)
    }
    
    private fun applyPresetToAllDevices(devices: List<IDevice>, preset: DevicePreset, presetData: PresetData, indicator: ProgressIndicator, project: Project) {
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "===== APPLY PRESET TO ALL DEVICES START =====\nPreset: %s\nDevices count: %d\nDevices: %s\nPresetData: width=%s, height=%s, dpi=%s", 
            preset.label,
            devices.size,
            devices.joinToString(", ") { it.serialNumber },
            presetData.width?.toString() ?: "null",
            presetData.height?.toString() ?: "null",
            presetData.dpi?.toString() ?: "null"
        )
        
        // Собираем информацию о разрешениях ДО применения пресета
        val resolutionContexts = mutableMapOf<IDevice, ResolutionChangeRestartService.ResolutionChangeContext>()
        val activeAppsBeforeChange = mutableMapOf<IDevice, Pair<String, String>>()
        val settings = PluginSettings.instance
        
        // Сначала собираем контексты изменения разрешения и активные приложения для всех устройств
        if (presetData.width != null && presetData.height != null) {
            devices.forEach { device ->
                // Получаем текущее разрешение для сравнения
                val currentSizeResult = AdbService.getCurrentSize(device)
                val currentSize = currentSizeResult.getOrNull()
                val defaultSize = AdbService.getDefaultSize(device).getOrNull()
                
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Checking resolution change for device %s: current=%s, target=%dx%d", 
                    device.serialNumber, 
                    currentSize?.let { "${it.first}x${it.second}" } ?: "null",
                    presetData.width, presetData.height
                )
                
                // Проверяем, изменится ли разрешение
                val resolutionWillChange = currentSize != null && 
                    (currentSize.first != presetData.width || currentSize.second != presetData.height)
                
                // Также считаем что разрешение "изменилось" если пресет был применен недавно
                // Это нужно для случаев когда применяется тот же пресет повторно
                val wasRecentlyApplied = DeviceStateService.wasPresetRecentlyApplied(device.serialNumber)
                
                val targetSize = Pair(presetData.width, presetData.height)
                
                // Создаем контекст изменения разрешения
                val context = ResolutionChangeRestartService.ResolutionChangeContext(
                    device = device,
                    sizeBefore = currentSize,
                    sizeAfter = targetSize, // После применения будет целевое разрешение
                    defaultSize = defaultSize,
                    wasCustomSize = defaultSize != null && currentSize != null && currentSize != defaultSize,
                    hasResolutionChanged = resolutionWillChange || wasRecentlyApplied
                )
                
                resolutionContexts[device] = context
                
                // Сохраняем активное приложение ПЕРЕД изменением, если разрешение изменится
                if ((resolutionWillChange || wasRecentlyApplied) && settings.restartActiveAppOnResolutionChange) {
                    println("ADB_Randomizer: Checking active app before change on device: ${device.serialNumber}")
                    val focusedAppResult = AdbService.getCurrentFocusedApp(device)
                    val focusedApp = focusedAppResult.getOrNull()
                    
                    if (focusedApp != null) {
                        println("ADB_Randomizer: Found focused app on device ${device.serialNumber}: ${focusedApp.first}/${focusedApp.second}")
                        
                        // Пропускаем только критически важные системные приложения
                        val isEssentialSystem = focusedApp.first == "com.android.systemui" ||
                                              focusedApp.first == "com.android.launcher" ||
                                              focusedApp.first == "com.sec.android.app.launcher" ||
                                              focusedApp.first == "com.miui.home" || // MIUI launcher
                                              focusedApp.first == "com.android.settings" ||
                                              focusedApp.first == "com.android.phone" ||
                                              focusedApp.first == "com.android.dialer"
                        
                        if (!isEssentialSystem) {
                            activeAppsBeforeChange[device] = focusedApp
                            println("ADB_Randomizer: Will restart app ${focusedApp.first} on device ${device.serialNumber} after resolution change")
                        } else {
                            println("ADB_Randomizer: Skipping essential system app ${focusedApp.first} on device ${device.serialNumber}")
                        }
                    } else {
                        println("ADB_Randomizer: No focused app found on device ${device.serialNumber}")
                    }
                }
                
                if (resolutionWillChange) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Resolution WILL change for device %s: %dx%d -> %dx%d", 
                        device.serialNumber, currentSize?.first ?: 0, currentSize?.second ?: 0, 
                        presetData.width, presetData.height
                    )
                } else if (wasRecentlyApplied) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Device %s marked for restart due to recent preset application (resolution already %dx%d)", 
                        device.serialNumber, presetData.width, presetData.height
                    )
                } else {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Resolution will NOT change for device %s (already %dx%d, not recently applied)", 
                        device.serialNumber, presetData.width, presetData.height
                    )
                }
            }
        } else {
            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                "No resolution data in preset (width=%s, height=%s)", 
                presetData.width?.toString() ?: "null",
                presetData.height?.toString() ?: "null"
            )
        }
        
        // Сохраняем состояния автоповорота для всех устройств перед применением пресетов
        val devicesWithAutoRotation = mutableListOf<IDevice>()
        devices.forEach { device ->
            if (presetData.width != null && presetData.height != null) {
                val wasAutoRotationEnabled = AutoRotationStateManager.saveAutoRotationState(device)
                if (wasAutoRotationEnabled) {
                    devicesWithAutoRotation.add(device)
                }
            }
        }
        
        devices.forEach { device ->
            indicator.text = "Applying '${preset.label}' to ${device.name}..."
            
            if (presetData.width != null && presetData.height != null) {
                // Определяем естественную ориентацию устройства
                val naturalOrientation = AdbService.getNaturalOrientation(device).getOrNull() ?: "portrait"
                val isNaturallyPortrait = naturalOrientation == "portrait"
                
                // Определяем целевую ориентацию по разрешению
                val targetIsLandscape = presetData.width > presetData.height
                
                // Получаем текущую ориентацию устройства
                val currentRotation = AdbService.getCurrentOrientation(device).getOrNull() ?: 0
                val isCurrentlyLandscape = currentRotation == 1 || currentRotation == 3
                
                // Логируем для отладки
                PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                    "Device %s: natural orientation=%s, applying %dx%d, target orientation: %s, current: %s (rotation=%d)", 
                    device.name,
                    naturalOrientation,
                    presetData.width, 
                    presetData.height,
                    if (targetIsLandscape) "landscape" else "portrait",
                    if (isCurrentlyLandscape) "landscape" else "portrait",
                    currentRotation
                )
                
                // Определяем, какие значения передавать в wm size
                // Для устройств с естественной портретной ориентацией:
                // - В портретном режиме: меньшее значение × большее значение
                // - В горизонтальном режиме: большее значение × меньшее значение
                var finalWidth = presetData.width
                var finalHeight = presetData.height
                
                if (isNaturallyPortrait) {
                    // Телефон - естественная ориентация портретная
                    if (targetIsLandscape) {
                        // Хотим горизонтальную ориентацию - меняем ориентацию устройства
                        if (!isCurrentlyLandscape) {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Switching device to landscape")
                            val result = AdbService.setOrientationToLandscape(device)
                            if (result.isSuccess()) {
                                Thread.sleep(1500)
                            }
                        }
                        // Для телефона в горизонтальной ориентации wm size всё равно ожидает 
                        // размеры относительно естественной (портретной) ориентации
                        // Поэтому меняем местами ширину и высоту
                        finalWidth = presetData.height
                        finalHeight = presetData.width
                    } else {
                        // Хотим портретную ориентацию
                        if (isCurrentlyLandscape) {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Switching device to portrait")
                            val result = AdbService.setOrientationToPortrait(device)
                            if (result.isSuccess()) {
                                Thread.sleep(1500)
                            }
                        }
                        // Оставляем как есть - уже в правильном формате для портретной ориентации
                    }
                } else {
                    // Планшет - естественная ориентация горизонтальная
                    if (!targetIsLandscape) {
                        // Хотим портретную ориентацию на планшете
                        if (isCurrentlyLandscape) {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Switching tablet to portrait")
                            val result = AdbService.setOrientationToPortrait(device)
                            if (result.isSuccess()) {
                                Thread.sleep(1500)
                            }
                        }
                    } else {
                        // Хотим горизонтальную ориентацию на планшете
                        if (!isCurrentlyLandscape) {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, "Switching tablet to landscape")
                            val result = AdbService.setOrientationToLandscape(device)
                            if (result.isSuccess()) {
                                Thread.sleep(1500)
                            }
                        }
                    }
                    // Для планшетов размеры передаём как есть
                }
                
                PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                    "Applying size: %dx%d (original: %dx%d)", 
                    finalWidth, finalHeight, presetData.width, presetData.height
                )
                
                AdbService.setSize(device, finalWidth, finalHeight)
                
                // Помечаем что пресет был применен для этого устройства
                DeviceStateService.markPresetApplied(device.serialNumber)
                
                // Дополнительная задержка после установки размера
                Thread.sleep(500)
            }
            
            if (presetData.dpi != null) {
                AdbService.setDpi(device, presetData.dpi)
            }
        }
        
        // Используем новый сервис для обработки всех перезапусков
        val restartResult = ResolutionChangeRestartService.handleResolutionChangeRestarts(
            project,
            devices,
            resolutionContexts,
            activeAppsBeforeChange // передаем сохраненные приложения
        )
        
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Restart result: apps=%d, scrcpy=%d, runningDevices=%d", 
            restartResult.appsRestarted,
            restartResult.scrcpyRestarted,
            restartResult.runningDevicesRestarted
        )
        
        // Восстанавливаем автоповорот для устройств, у которых он был включен
        if (devicesWithAutoRotation.isNotEmpty()) {
            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                "Restoring auto-rotation for %d devices", 
                devicesWithAutoRotation.size
            )
            
            Thread {
                Thread.sleep(2000) // Даём время на завершение всех операций
                devicesWithAutoRotation.forEach { device ->
                    AutoRotationStateManager.restoreAutoRotationState(device)
                }
            }.start()
        }
    }
    
    private fun updateDeviceStatesAfterPresetApplication(devices: List<IDevice>, presetData: PresetData) {
        devices.forEach { device ->
            // Если мы применили новые значения, обновляем их в состоянии
            // Если какое-то значение не применялось (null), оно не изменится
            DeviceStateService.updateDeviceState(
                device.serialNumber,
                presetData.width,
                presetData.height,
                presetData.dpi
            )
        }
    }
    
    private fun showPresetApplicationResult(project: Project, preset: DevicePreset, presetNumber: Int, appliedSettings: List<String>) {
        val message = "<html>Preset №${presetNumber}: ${preset.label};<br>${appliedSettings.joinToString(", ")}</html>"
        NotificationUtils.showSuccess(project, message)
    }
}