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
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ScrcpyService
import javax.swing.SwingUtilities

object PresetApplicationService {
    
    fun applyPreset(project: Project, preset: DevicePreset, setSize: Boolean, setDpi: Boolean, currentTablePosition: Int? = null) {
        PluginLogger.debug(LogCategory.PRESET_SERVICE, "applyPreset called - preset: %s, currentTablePosition: %s", preset.label, currentTablePosition)
        object : Task.Backgroundable(project, "Applying preset") {
            override fun run(indicator: ProgressIndicator) {
                val presetData = validateAndParsePresetData(preset, setSize, setDpi) ?: return
                val devicesResult = AdbService.getConnectedDevices(project)
                val devices = devicesResult.getOrNull() ?: emptyList()
                if (devices.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showInfo(project, "No devices connected")
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
            val sizeData = ValidationUtils.parseSize(preset.size)
            if (sizeData == null) {
                return null
            }
            width = sizeData.first
            height = sizeData.second
            appliedSettings.add("Size: ${preset.size}")
        }
        
        if (setDpi && preset.dpi.isNotBlank()) {
            val dpiValue = ValidationUtils.parseDpi(preset.dpi)
            if (dpiValue == null) {
                return null
            }
            dpi = dpiValue
            appliedSettings.add("DPI: ${preset.dpi}")
        }
        
        if (appliedSettings.isEmpty()) {
            return null
        }
        
        return PresetData(width, height, dpi, appliedSettings)
    }
    
    private fun applyPresetToAllDevices(devices: List<IDevice>, preset: DevicePreset, presetData: PresetData, indicator: ProgressIndicator, project: Project) {
        // Collect devices that need Running Devices restart
        val devicesNeedingRunningDevicesRestart = mutableListOf<IDevice>()
        
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
                
                // Дополнительная задержка после установки размера
                Thread.sleep(500)
                
                // Проверяем и перезапускаем scrcpy если он активен для этого устройства
                val settings = PluginSettings.instance
                if (settings.restartScrcpyOnResolutionChange) {
                    val serialNumber = device.serialNumber
                    // Проверяем любые процессы scrcpy (наши или внешние)
                    if (ScrcpyService.hasAnyScrcpyProcessForDevice(serialNumber)) {
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Found scrcpy process for device %s, restarting after resolution change", 
                            serialNumber
                        )
                        
                        // Перезапускаем scrcpy в отдельном потоке, чтобы не блокировать применение пресета
                        Thread {
                            Thread.sleep(500) // Даём время на стабилизацию после изменения разрешения
                            val restartResult = ScrcpyService.restartScrcpyForDevice(serialNumber, project)
                            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                                "Scrcpy restart result for device %s: %s", 
                                serialNumber, restartResult.toString()
                            )
                        }.start()
                    } else {
                        PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                            "No scrcpy processes found for device %s", 
                            serialNumber
                        )
                    }
                } else {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                        "Scrcpy restart is disabled in settings"
                    )
                }
                
                // Check if Running Devices restart is needed (Android Studio only)
                AndroidStudioIntegrationService.instance?.let { androidService ->
                    if (settings.restartRunningDevicesOnResolutionChange && 
                        androidService.isRunningDevicesActive(device)) {
                        PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                            "Running Devices is active for device %s, will restart after all devices are processed", 
                            device.serialNumber
                        )
                        devicesNeedingRunningDevicesRestart.add(device)
                    }
                }
            }
            
            if (presetData.dpi != null) {
                AdbService.setDpi(device, presetData.dpi)
            }
        }
        
        // Restart Running Devices for all affected devices
        if (devicesNeedingRunningDevicesRestart.isNotEmpty()) {
            AndroidStudioIntegrationService.instance?.let { androidService ->
                PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                    "Restarting Running Devices for %d devices", 
                    devicesNeedingRunningDevicesRestart.size
                )
                
                // Restart in a separate thread to avoid blocking
                Thread {
                    Thread.sleep(500) // Give time for resolution changes to stabilize
                    androidService.restartRunningDevicesForMultiple(devicesNeedingRunningDevicesRestart)
                }.start()
            }
        }
        
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