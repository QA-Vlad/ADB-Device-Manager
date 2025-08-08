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
        
        // Collect devices that need scrcpy restart
        val devicesNeedingScrcpyRestart = mutableListOf<String>()
        
        // Сохраняем информацию о том, изменилось ли разрешение для каждого устройства
        val devicesWithResolutionChange = mutableSetOf<IDevice>()
        
        // Сохраняем активные приложения для каждого устройства перед изменением разрешения
        val devicesWithActiveApps = mutableMapOf<IDevice, Pair<String, String>>()
        val settings = PluginSettings.instance
        
        // Сначала проверяем, какие устройства будут иметь изменение разрешения
        if (presetData.width != null && presetData.height != null) {
            devices.forEach { device ->
                // Получаем текущее разрешение для сравнения
                val currentSizeResult = AdbService.getCurrentSize(device)
                val currentSize = currentSizeResult.getOrNull()
                
                // Проверяем, изменится ли разрешение
                if (currentSize != null && (currentSize.first != presetData.width || currentSize.second != presetData.height)) {
                    devicesWithResolutionChange.add(device)
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                        "Resolution will change for device %s: %dx%d -> %dx%d", 
                        device.serialNumber, currentSize.first, currentSize.second, 
                        presetData.width, presetData.height
                    )
                } else {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                        "Resolution will NOT change for device %s (already %dx%d)", 
                        device.serialNumber, presetData.width, presetData.height
                    )
                }
            }
        }
        
        // Сохраняем активные приложения только для устройств с изменением разрешения
        PluginLogger.info(LogCategory.PRESET_SERVICE, 
            "Checking active apps - restartActiveAppOnResolutionChange: %s, devicesWithResolutionChange: %d", 
            settings.restartActiveAppOnResolutionChange, devicesWithResolutionChange.size
        )
        
        if (settings.restartActiveAppOnResolutionChange && devicesWithResolutionChange.isNotEmpty()) {
            devicesWithResolutionChange.forEach { device ->
                // Получаем активное приложение
                val focusedAppResult = AdbService.getCurrentFocusedApp(device)
                
                focusedAppResult.onError { exception, message ->
                    PluginLogger.error("Failed to get focused app for device ${device.serialNumber}", 
                        exception, message ?: "")
                }
                
                val focusedApp = focusedAppResult.getOrNull()
                
                if (focusedApp != null) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Found focused app on device %s: %s/%s", 
                        device.serialNumber, focusedApp.first, focusedApp.second
                    )
                    
                    // Проверяем, не является ли это системным приложением
                    val isSystemResult = AdbService.isSystemApp(device, focusedApp.first)
                    val isSystem = isSystemResult.getOrNull() ?: false
                    
                    if (!isSystem) {
                        devicesWithActiveApps[device] = focusedApp
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Will restart app %s on device %s after resolution change", 
                            focusedApp.first, device.serialNumber
                        )
                    } else {
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Skipping system app %s on device %s", 
                            focusedApp.first, device.serialNumber
                        )
                    }
                } else {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "No focused app found on device %s", 
                        device.serialNumber
                    )
                }
            }
        } else {
            if (!settings.restartActiveAppOnResolutionChange) {
                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                    "Active app restart is disabled in settings"
                )
            }
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
                
                // Дополнительная задержка после установки размера
                Thread.sleep(500)
                
                // Собираем информацию о необходимости перезапуска scrcpy
                if (settings.restartScrcpyOnResolutionChange && devicesWithResolutionChange.contains(device)) {
                    val serialNumber = device.serialNumber
                    // Проверяем любые процессы scrcpy (наши или внешние)
                    if (ScrcpyService.hasAnyScrcpyProcessForDevice(serialNumber)) {
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Found scrcpy process for device %s, will restart after resolution change", 
                            serialNumber
                        )
                        devicesNeedingScrcpyRestart.add(serialNumber)
                    } else {
                        PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                            "No scrcpy processes found for device %s", 
                            serialNumber
                        )
                    }
                } else if (!devicesWithResolutionChange.contains(device)) {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                        "Skipping scrcpy restart for device %s - resolution did not change", 
                        device.serialNumber
                    )
                } else {
                    PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                        "Scrcpy restart is disabled in settings"
                    )
                }
                
                // Check if Running Devices restart is needed (Android Studio only)
                // Only restart if resolution actually changed AND there's an active tab for this device
                AndroidStudioIntegrationService.instance?.let { androidService ->
                    when {
                        !devicesWithResolutionChange.contains(device) -> {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                "Skipping Running Devices restart for device %s - resolution did not change", 
                                device.serialNumber
                            )
                        }
                        settings.restartRunningDevicesOnResolutionChange && 
                        androidService.hasActiveDeviceTab(device) -> {
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                "Running Devices has active tab for device %s with resolution change, will restart after all devices are processed", 
                                device.serialNumber
                            )
                            devicesNeedingRunningDevicesRestart.add(device)
                        }
                        else -> {
                            // Running Devices restart is disabled or no active tab
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                "Running Devices restart not needed for device %s (disabled or no active tab)", 
                                device.serialNumber
                            )
                        }
                    }
                }
            }
            
            if (presetData.dpi != null) {
                AdbService.setDpi(device, presetData.dpi)
            }
        }
        
        // Restart apps, Running Devices and scrcpy in the correct order
        // Order: Restart apps → Close scrcpy → Close Running Devices → Start Running Devices → Start scrcpy
        if (devicesNeedingRunningDevicesRestart.isNotEmpty() || devicesNeedingScrcpyRestart.isNotEmpty() || devicesWithActiveApps.isNotEmpty()) {
            Thread {
                // Step 1: Перезапускаем активные приложения ПЕРВЫМИ
                if (devicesWithActiveApps.isNotEmpty()) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Step 1: Restarting active apps for %d devices", 
                        devicesWithActiveApps.size
                    )
                    
                    Thread.sleep(1000) // Даём время на стабилизацию после изменения разрешения
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Beginning app restart process for %d devices", 
                        devicesWithActiveApps.size
                    )
                    
                    devicesWithActiveApps.forEach { (device, appInfo) ->
                        val (packageName, activityName) = appInfo
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Attempting to restart app %s on device %s", 
                            packageName, device.serialNumber
                        )
                        
                        // Останавливаем приложение
                        val stopResult = AdbService.stopApp(device, packageName)
                        stopResult.onError { exception, message ->
                            PluginLogger.error("Failed to stop app $packageName on device ${device.serialNumber}", 
                                exception, message ?: "")
                        }
                        
                        if (stopResult.isSuccess()) {
                            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                                "Successfully stopped app %s, waiting before restart", 
                                packageName
                            )
                            Thread.sleep(500) // Небольшая задержка между остановкой и запуском
                            
                            // Запускаем приложение заново
                            val startResult = AdbService.startApp(device, packageName, activityName)
                            startResult.onError { exception, message ->
                                PluginLogger.error("Failed to start app $packageName on device ${device.serialNumber}", 
                                    exception, message ?: "")
                            }
                            
                            if (startResult.isSuccess()) {
                                PluginLogger.info(LogCategory.PRESET_SERVICE, 
                                    "Successfully restarted app %s on device %s", 
                                    packageName, device.serialNumber
                                )
                            } else {
                                PluginLogger.error(LogCategory.PRESET_SERVICE, 
                                    "Failed to start app %s on device %s", null,
                                    packageName, device.serialNumber
                                )
                            }
                        } else {
                            PluginLogger.error(LogCategory.PRESET_SERVICE, 
                                "Failed to stop app %s on device %s", null,
                                packageName, device.serialNumber
                            )
                        }
                    }
                    
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "All apps restart attempts completed"
                    )
                    
                    // Даём время приложениям полностью запуститься перед перезапуском UI
                    Thread.sleep(1000)
                }
                
                // Step 2: Close all scrcpy processes (if any need restart)
                if (devicesNeedingScrcpyRestart.isNotEmpty()) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Step 2: Closing scrcpy for %d devices", 
                        devicesNeedingScrcpyRestart.size
                    )
                    
                    // Close all scrcpy processes in parallel
                    val closeThreads = devicesNeedingScrcpyRestart.map { serialNumber ->
                        Thread {
                            ScrcpyService.stopScrcpyForDevice(serialNumber)
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                "Closed scrcpy for device %s", serialNumber
                            )
                        }
                    }
                    closeThreads.forEach { it.start() }
                    closeThreads.forEach { it.join() }
                    
                    // Small delay after closing
                    Thread.sleep(500)
                    PluginLogger.info(LogCategory.PRESET_SERVICE, "All scrcpy processes closed")
                }
                
                // Step 3: Restart Running Devices (this will close and reopen tabs)
                if (devicesNeedingRunningDevicesRestart.isNotEmpty()) {
                    AndroidStudioIntegrationService.instance?.let { androidService ->
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Step 3: Restarting Running Devices for %d devices", 
                            devicesNeedingRunningDevicesRestart.size
                        )
                        
                        androidService.restartRunningDevicesForMultiple(devicesNeedingRunningDevicesRestart)
                        
                        // Wait for Running Devices to fully restart
                        Thread.sleep(2000)
                        PluginLogger.info(LogCategory.PRESET_SERVICE, 
                            "Running Devices restart completed"
                        )
                    }
                }
                
                // Step 4: Start scrcpy processes after everything is ready
                if (devicesNeedingScrcpyRestart.isNotEmpty()) {
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "Step 4: Starting scrcpy for %d devices sequentially with delays", 
                        devicesNeedingScrcpyRestart.size
                    )
                    
                    val scrcpyPath = ScrcpyService.findScrcpyExecutable()
                    if (scrcpyPath == null) {
                        PluginLogger.error(LogCategory.PRESET_SERVICE, 
                            "Cannot restart scrcpy - executable not found", null, ""
                        )
                    } else {
                        // Start scrcpy processes sequentially with small delays to avoid ADB port conflicts
                        devicesNeedingScrcpyRestart.forEachIndexed { index, serialNumber ->
                            PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                "Starting scrcpy for device %s (%d of %d)", 
                                serialNumber, index + 1, devicesNeedingScrcpyRestart.size
                            )
                            
                            // Launch scrcpy directly since we already stopped it earlier
                            val startResult = ScrcpyService.launchScrcpy(scrcpyPath, serialNumber, project)
                            
                            PluginLogger.info(LogCategory.PRESET_SERVICE, 
                                "Scrcpy start result for device %s: %s", 
                                serialNumber, if (startResult) "SUCCESS" else "FAILED"
                            )
                            
                            // Add delay between starts to avoid port conflicts (except for the last one)
                            if (index < devicesNeedingScrcpyRestart.size - 1) {
                                Thread.sleep(1000) // 1 second delay between launches
                                PluginLogger.debug(LogCategory.PRESET_SERVICE, 
                                    "Waiting before starting next scrcpy process"
                                )
                            }
                        }
                    }
                    
                    PluginLogger.info(LogCategory.PRESET_SERVICE, 
                        "All scrcpy processes start attempts completed"
                    )
                }
            }.start()
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