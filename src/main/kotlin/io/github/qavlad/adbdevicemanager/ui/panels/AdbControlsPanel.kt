package io.github.qavlad.adbdevicemanager.ui.panels

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.qavlad.adbdevicemanager.config.PluginConfig
import io.github.qavlad.adbdevicemanager.services.*
import io.github.qavlad.adbdevicemanager.services.integration.scrcpy.ScrcpyService
import io.github.qavlad.adbdevicemanager.services.integration.scrcpy.ui.ScrcpyCompatibilityDialog
import io.github.qavlad.adbdevicemanager.ui.components.*
import io.github.qavlad.adbdevicemanager.ui.dialogs.PresetsDialog
import io.github.qavlad.adbdevicemanager.utils.NotificationUtils
import io.github.qavlad.adbdevicemanager.utils.ValidationUtils
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import io.github.qavlad.adbdevicemanager.settings.PluginSettings
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.util.Locale
import javax.swing.*
import javax.swing.Timer

class AdbControlsPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        @Volatile
        private var sentryInitialized = false
    }

    private var lastUsedPreset: DevicePreset? = null
    private var currentPresetIndex: Int = -1
    // Отслеживаем, какой пресет дал текущий Size и DPI
    private var sizeSourcePreset: DevicePreset? = null
    private var dpiSourcePreset: DevicePreset? = null
    private var currentHoverState = HoverState.noHover()
    
    // Debounce timer для навигации по пресетам стрелками
    private var presetNavigationTimer: Timer? = null
    
    private val devicePollingService = DevicePollingService(project)
    private lateinit var deviceListPanel: DeviceListPanel
    private lateinit var buttonPanel: CardControlPanel
    private lateinit var compactActionPanel: CompactActionPanel

    init {
        // Инициализируем Sentry при создании панели
        initializeSentry()
        
        setupUI()
        PluginLogger.info("AdbControlsPanel initialized, starting device polling...")
        startDevicePolling()
    }
    
    private fun initializeSentry() {
        if (!sentryInitialized) {
            sentryInitialized = true
            val settings = PluginSettings.instance
            io.github.qavlad.adbdevicemanager.telemetry.SentryInitializer.initialize(settings.enableTelemetry)
        }
    }

    private fun setupUI() {
        buttonPanel = CardControlPanel(
            onRandomAction = { setSize, setDpi -> executeRandomAction(setSize, setDpi) },
            onNextPreset = { navigateToNextPreset() },
            onPreviousPreset = { navigateToPreviousPreset() },
            onResetAction = { resetSize, resetDpi -> executeResetAction(resetSize, resetDpi) },
            onOpenPresetSettings = { openPresetsDialog() }
        )
        
        compactActionPanel = CompactActionPanel(
            onConnectDevice = { promptForManualConnection() },
            onKillAdbServer = { executeKillAdbServer() },
            onOpenSettings = { openPluginSettings() }
        )
        
        deviceListPanel = DeviceListPanel(
            getHoverState = { currentHoverState },
            setHoverState = { newState -> currentHoverState = newState },
            getAllDevices = { getAllDevicesFromModel() },
            onMirrorClick = { deviceInfo -> startScreenMirroring(deviceInfo) },
            onWifiClick = { device -> connectDeviceViaWifi(device) },
            onWifiDisconnect = { ipAddress -> disconnectWifiDevice(ipAddress) },
            compactActionPanel = compactActionPanel,
            onForceUpdate = { devicePollingService.forceCombinedUpdate() },
            onResetSize = { device -> resetDeviceSize(device) },
            onResetDpi = { device -> resetDeviceDpi(device) },
            onApplyChanges = { device, newSize, newDpi -> applyDeviceChanges(device, newSize, newDpi) },
            onAdbCheckboxChanged = { device, isSelected -> 
                device.isSelectedForAdb = isSelected
                // Сохраняем состояние чекбокса чтобы оно не сбрасывалось при обновлении
                devicePollingService.updateDeviceSelection(device.baseSerialNumber, isSelected)
            },
            onWifiConnectByIp = { ipAddress, port -> connectDeviceViaWifiByIp(ipAddress, port) },
            onParallelWifiConnect = { ipAddresses -> connectDeviceViaWifiParallel(ipAddresses) }
        )
        
        // Используем BorderLayout вместо SplitPane для максимальной компактности
        add(buttonPanel, BorderLayout.NORTH)
        add(deviceListPanel, BorderLayout.CENTER)
    }

    private fun startDevicePolling() {
        PluginLogger.info("Starting device polling from AdbControlsPanel")
        devicePollingService.startCombinedDevicePolling { combinedDevices ->
            PluginLogger.info("Device polling callback received %d combined devices", combinedDevices.size)
            deviceListPanel.updateCombinedDeviceList(combinedDevices)
        }
    }

    private fun getAllDevicesFromModel(): List<DeviceInfo> {
        return deviceListPanel.getDeviceListModel().let { model ->
            (0 until model.size())
                .mapNotNull { model.getElementAt(it) as? DeviceListItem.Device }
                .map { it.info }
        }
    }
    
    private fun getSelectedDevicesForAdb(): List<IDevice> {
        val model = deviceListPanel.getDeviceListModel()
        val selectedDevices = mutableListOf<IDevice>()
        
        for (i in 0 until model.size()) {
            when (val item = model.getElementAt(i)) {
                is DeviceListItem.CombinedDevice -> {
                    if (item.info.isSelectedForAdb) {
                        // Добавляем активное устройство (USB или Wi-Fi)
                        val device = item.info.usbDevice?.device ?: item.info.wifiDevice?.device
                        device?.let { selectedDevices.add(it) }
                    }
                }
                is DeviceListItem.Device -> {
                    // Для старого формата - считаем все подключенные устройства выбранными
                    item.info.device?.let { selectedDevices.add(it) }
                }
                else -> {} // Игнорируем другие типы элементов
            }
        }
        
        return selectedDevices
    }

    // ==================== VALIDATION AND HELPERS ====================

    private fun validateDevicesAvailable(): Boolean {
        val devicesResult = AdbService.getConnectedDevices()
        val devices = devicesResult.getOrNull() ?: run {
            devicesResult.onError { exception, _ ->
                PluginLogger.warn(LogCategory.ADB_CONNECTION, "Failed to get connected devices: %s", exception.message)
            }
            emptyList()
        }
        if (devices.isEmpty()) {
            NotificationUtils.showInfo(project, "No connected devices found.")
            return false
        }
        return true
    }

    private fun validatePresetsAvailable(): Boolean {
        val presets = getVisiblePresets()
        if (presets.isEmpty()) {
            NotificationUtils.showInfo(project, "No presets found.")
            return false
        }
        return true
    }

    // ==================== PRESET ACTIONS ====================

    private fun executeRandomAction(setSize: Boolean, setDpi: Boolean) {
        println("ADB_DEBUG: executeRandomAction called - setSize: $setSize, setDpi: $setDpi")
        
        val selectedDevices = getSelectedDevicesForAdb()
        if (selectedDevices.isEmpty()) {
            NotificationUtils.showWarning(project, "No devices selected for ADB commands. Please check the ADB checkboxes.")
            return
        }

        val randomPreset = selectRandomPreset(setSize, setDpi) ?: return
        
        // Комбинируем с существующим пресетом если применяем только часть параметров
        println("ADB_DEBUG [Preset Combination]: Previous preset: ${lastUsedPreset?.label ?: "none"} (Size='${lastUsedPreset?.size ?: ""}', DPI='${lastUsedPreset?.dpi ?: ""}')")
        println("ADB_DEBUG [Preset Combination]: New random preset: ${randomPreset.label} (Size='${randomPreset.size}', DPI='${randomPreset.dpi}')")
        
        // Обновляем источники Size и DPI
        if (setSize) {
            sizeSourcePreset = randomPreset
        }
        if (setDpi) {
            dpiSourcePreset = randomPreset  
        }
        
        val combinedPreset = when {
            setSize && setDpi -> {
                // Применяем оба параметра - используем новый пресет полностью
                randomPreset
            }
            setSize && !setDpi -> {
                // Применяем только size - сохраняем DPI от предыдущего пресета если есть
                if (lastUsedPreset?.dpi?.isNotBlank() == true) {
                    // Комбинируем: берём dpi от старого, size от нового
                    // Сохраняем оригинальное название пресета для size
                    randomPreset.copy(
                        dpi = lastUsedPreset!!.dpi
                    )
                } else {
                    randomPreset.copy(dpi = "")
                }
            }
            !setSize && setDpi -> {
                // Применяем только DPI - сохраняем size от предыдущего пресета если есть
                if (lastUsedPreset?.size?.isNotBlank() == true) {
                    // Комбинируем: берём size от старого, dpi от нового
                    // Показываем новый пресет как активный (последний применённый)
                    randomPreset.copy(
                        size = lastUsedPreset!!.size
                    )
                } else {
                    randomPreset.copy(size = "")
                }
            }
            else -> randomPreset
        }
        
        println("ADB_DEBUG [Preset Combination]: Result preset: ${combinedPreset.label} (Size='${combinedPreset.size}', DPI='${combinedPreset.dpi}')")
        
        // Обновляем индикатор текущего пресета
        // Если режим Show All включен, показываем "All presets"
        val listName = if (PresetStorageService.getShowAllPresetsMode()) {
            "All presets"
        } else {
            try {
                PresetListService.getActivePresetList()?.name
            } catch (_: Exception) {
                null
            }
        }
        buttonPanel.updateLastUsedPreset(combinedPreset, listName)
        
        // Позиция будет вычислена динамически после обновления счетчиков
        // Передаём оригинальный randomPreset для ADB команд, но combinedPreset уже сохранён в lastUsedPreset
        lastUsedPreset = combinedPreset
        PresetApplicationService.applyPreset(project, randomPreset, setSize, setDpi, selectedDevices = selectedDevices)
    }

    private fun selectRandomPreset(setSize: Boolean, setDpi: Boolean): DevicePreset? {
        var availablePresets = filterSuitablePresets(setSize, setDpi)

        if (availablePresets.size > 1 && lastUsedPreset != null) {
            availablePresets = availablePresets.filter { it != lastUsedPreset }
        }

        if (availablePresets.isEmpty()) {
            val allSuitablePresets = filterSuitablePresets(setSize, setDpi)
            if (allSuitablePresets.isNotEmpty()) {
                availablePresets = allSuitablePresets
            } else {
                NotificationUtils.showInfo(project, "No suitable presets found.")
                return null
            }
        }

        val randomPreset = availablePresets.random()
        updateCurrentPresetIndex(randomPreset)
        return randomPreset
    }

    private fun filterSuitablePresets(setSize: Boolean, setDpi: Boolean): List<DevicePreset> {
        return getVisiblePresets().filter { preset ->
            (!setSize || preset.size.isNotBlank()) && (!setDpi || preset.dpi.isNotBlank())
        }
    }

    private fun updateCurrentPresetIndex(preset: DevicePreset) {
        val allPresets = getVisiblePresets()
        currentPresetIndex = allPresets.indexOf(preset)
    }
    
    private fun getVisiblePresets(): List<DevicePreset> {
        // Получаем пресеты с учетом текущего режима отображения и сортировки
        val presets = PresetListService.getSortedPresets()
        println("ADB_DEBUG: AdbControlsPanel.getVisiblePresets() - returned ${presets.size} presets")
        if (presets.isEmpty()) {
            println("ADB_DEBUG: AdbControlsPanel.getVisiblePresets() - EMPTY LIST! Show All Mode: ${PresetStorageService.getShowAllPresetsMode()}")
        }
        return presets
    }

    private fun navigateToNextPreset() {
        if (!validatePresetsAvailable()) return

        val presets = getVisiblePresets()
        currentPresetIndex = (currentPresetIndex + 1) % presets.size
        
        // Обновляем UI сразу
        updatePresetIndicator(currentPresetIndex)
        
        // Отменяем предыдущий таймер, если он есть
        presetNavigationTimer?.stop()
        
        // Создаем новый таймер с задержкой для применения ADB команд
        presetNavigationTimer = Timer(PluginConfig.UI.PRESET_NAVIGATION_DEBOUNCE_MS) { _ ->
            applyPresetByIndex(currentPresetIndex)
        }
        presetNavigationTimer?.isRepeats = false
        presetNavigationTimer?.start()
    }

    private fun navigateToPreviousPreset() {
        if (!validatePresetsAvailable()) return

        val presets = getVisiblePresets()
        currentPresetIndex = if (currentPresetIndex <= 0) presets.size - 1 else currentPresetIndex - 1
        
        // Обновляем UI сразу
        updatePresetIndicator(currentPresetIndex)
        
        // Отменяем предыдущий таймер, если он есть
        presetNavigationTimer?.stop()
        
        // Создаем новый таймер с задержкой для применения ADB команд
        presetNavigationTimer = Timer(PluginConfig.UI.PRESET_NAVIGATION_DEBOUNCE_MS) { _ ->
            applyPresetByIndex(currentPresetIndex)
        }
        presetNavigationTimer?.isRepeats = false
        presetNavigationTimer?.start()
    }

    private fun applyPresetByIndex(index: Int) {
        if (!validateDevicesAvailable()) return

        val presets = getVisiblePresets()
        if (index < 0 || index >= presets.size) {
            NotificationUtils.showInfo(project, "Invalid preset index.")
            return
        }

        val preset = presets[index]
        // Позиция будет вычислена динамически после обновления счетчиков
        applyPresetToDevices(preset)
    }
    
    private fun updatePresetIndicator(index: Int) {
        val presets = getVisiblePresets()
        if (index < 0 || index >= presets.size) return
        
        val preset = presets[index]
        // Обновляем только UI индикатор, без применения ADB команд
        val listName = if (PresetStorageService.getShowAllPresetsMode()) {
            "All presets"
        } else {
            try {
                PresetListService.getActivePresetList()?.name
            } catch (_: Exception) {
                null
            }
        }
        buttonPanel.updateLastUsedPreset(preset, listName)
    }
    
    private fun applyPresetToDevices(preset: DevicePreset) {
        lastUsedPreset = preset
        // Обновляем источники для Size и DPI
        if (preset.size.isNotBlank()) {
            sizeSourcePreset = preset
        }
        if (preset.dpi.isNotBlank()) {
            dpiSourcePreset = preset
        }
        
        // Обновляем индикатор с учетом режима Show All
        val listName = if (PresetStorageService.getShowAllPresetsMode()) {
            "All presets"
        } else {
            try {
                PresetListService.getActivePresetList()?.name
            } catch (_: Exception) {
                null
            }
        }
        buttonPanel.updateLastUsedPreset(preset, listName)
        
        // Получаем список выбранных устройств для применения пресета
        val selectedDevices = getSelectedDevicesForAdb()
        if (selectedDevices.isEmpty()) {
            NotificationUtils.showWarning(project, "No devices selected for ADB commands. Please check the ADB checkboxes.")
            return
        }
        PresetApplicationService.applyPreset(project, preset, setSize = true, setDpi = true, selectedDevices = selectedDevices)
    }

    // ==================== RESET ACTIONS ====================

    private fun executeResetAction(resetSize: Boolean, resetDpi: Boolean) {
        val selectedDevices = getSelectedDevicesForAdb()
        if (selectedDevices.isEmpty()) {
            NotificationUtils.showWarning(project, "No devices selected for ADB commands. Please check the ADB checkboxes.")
            return
        }

        val actionDescription = getResetActionDescription(resetSize, resetDpi)

        object : Task.Backgroundable(project, actionDescription) {
            override fun run(indicator: ProgressIndicator) {
                resetAllDevices(selectedDevices, resetSize, resetDpi, indicator)
                
                DeviceStateService.handleReset(resetSize, resetDpi)
                
                // Немедленно уведомляем об обновлении до UI обновлений
                PresetsDialogUpdateNotifier.notifyUpdate()
                
                ApplicationManager.getApplication().invokeLater {
                    showResetResult(selectedDevices.size, resetSize, resetDpi)
                    
                    // Обновляем индикатор активного пресета после сброса
                    updateActivePresetAfterReset(resetSize, resetDpi)
                }
            }
        }.queue()
    }

    private fun getResetActionDescription(resetSize: Boolean, resetDpi: Boolean): String {
        return when {
            resetSize && resetDpi -> "Resetting screen and DPI"
            resetSize -> "Resetting screen Size"
            resetDpi -> "Resetting DPI"
            else -> "No action"
        }
    }

    private fun resetAllDevices(devices: List<IDevice>, resetSize: Boolean, resetDpi: Boolean, indicator: ProgressIndicator) {
        val settings = PluginSettings.instance
        
        devices.forEach { device ->
            indicator.text = "Resetting ${device.name}..."
            
            // Получаем текущий размер перед сбросом (только если нужно сбрасывать размер)
            val currentSizeBefore = if (resetSize) {
                val size = AdbService.getCurrentSize(device).getOrNull()
                PluginLogger.debug(LogCategory.UI_EVENTS, "Current size before reset for device %s: %s", 
                    device.name, size?.let { "${it.first}x${it.second}" } ?: "null")
                size
            } else null

            // Сохраняем активное приложение ПЕРЕД сбросом, если будет изменение размера
            var activeAppBeforeChange: Pair<String, String>? = null
            if (settings.restartActiveAppOnResolutionChange && resetSize && currentSizeBefore != null) {
                PluginLogger.info(LogCategory.UI_EVENTS, "Checking active app before reset on device: %s", device.serialNumber)
                val focusedAppResult = AdbService.getCurrentFocusedApp(device)
                val focusedApp = focusedAppResult.getOrNull()
                
                if (focusedApp != null) {
                    PluginLogger.info(LogCategory.UI_EVENTS, 
                        "Found focused app on device %s: %s/%s", 
                        device.serialNumber, focusedApp.first, focusedApp.second
                    )
                    
                    // Пропускаем только критически важные системные приложения
                    val isEssentialSystem = focusedApp.first == "com.android.systemui" ||
                                          focusedApp.first == "com.android.launcher" ||
                                          focusedApp.first == "com.sec.android.app.launcher" ||
                                          focusedApp.first == "com.miui.home" || // MIUI launcher
                                          focusedApp.first == "com.android.settings" ||
                                          focusedApp.first == "com.android.phone" ||
                                          focusedApp.first == "com.android.dialer"
                    
                    if (!isEssentialSystem) {
                        activeAppBeforeChange = focusedApp
                        PluginLogger.info(LogCategory.UI_EVENTS, 
                            "Will restart app %s on device %s after reset", 
                            focusedApp.first, device.serialNumber
                        )
                    } else {
                        PluginLogger.info(LogCategory.UI_EVENTS,
                            "Skipping essential system app %s on device %s",
                            focusedApp.first, device.serialNumber
                        )
                    }
                }
            }
            
            // Выполняем сброс
            if (resetSize) {
                PluginLogger.debug(LogCategory.UI_EVENTS, "Executing size reset for device %s", device.name)
                AdbService.resetSize(device)
            }
            if (resetDpi) {
                PluginLogger.debug(LogCategory.UI_EVENTS, "Executing DPI reset for device %s", device.name)
                AdbService.resetDpi(device)
            }
            
            // Проверяем, изменился ли размер и нужно ли перезапустить scrcpy
            if (resetSize && currentSizeBefore != null) {
                // Даём время на применение сброса
                Thread.sleep(1000) // Увеличим задержку для надёжности
                
                // Получаем новый размер после сброса
                val currentSizeAfter = AdbService.getCurrentSize(device).getOrNull()
                PluginLogger.debug(LogCategory.UI_EVENTS, "Current size after reset for device %s: %s", 
                    device.name, currentSizeAfter?.let { "${it.first}x${it.second}" } ?: "null")
                
                // Используем новый сервис для обработки всех перезапусков
                val restartResult = ResolutionChangeRestartService.handleSingleDeviceResolutionChange(
                    project,
                    device,
                    currentSizeBefore,
                    currentSizeAfter,
                    activeAppBeforeChange // передаем сохраненное приложение
                )
                
                PluginLogger.debug(LogCategory.UI_EVENTS, 
                    "Restart result for device %s: apps=%d, scrcpy=%d, runningDevices=%d", 
                    device.serialNumber,
                    restartResult.appsRestarted,
                    restartResult.scrcpyRestarted,
                    restartResult.runningDevicesRestarted
                )
            }
        }
    }

    private fun updateActivePresetAfterReset(resetSize: Boolean, resetDpi: Boolean) {
        println("ADB_DEBUG [Reset]: updateActivePresetAfterReset called - resetSize: $resetSize, resetDpi: $resetDpi")
        println("ADB_DEBUG [Reset]: Current preset before reset: ${lastUsedPreset?.label ?: "none"} (Size='${lastUsedPreset?.size ?: ""}', DPI='${lastUsedPreset?.dpi ?: ""}')")
        println("ADB_DEBUG [Reset]: Size source: ${sizeSourcePreset?.label ?: "none"}, DPI source: ${dpiSourcePreset?.label ?: "none"}")
        
        // Обновляем источники после сброса
        if (resetSize) {
            sizeSourcePreset = null
        }
        if (resetDpi) {
            dpiSourcePreset = null
        }
        
        // Определяем, какой пресет показывать как активный
        val activePreset = when {
            sizeSourcePreset != null && dpiSourcePreset != null -> {
                // Есть и Size и DPI - показываем последний применённый
                // Обычно DPI применяется после Size, так что показываем источник DPI
                dpiSourcePreset
            }
            sizeSourcePreset != null -> {
                // Только Size активен
                sizeSourcePreset
            }
            dpiSourcePreset != null -> {
                // Только DPI активен
                dpiSourcePreset
            }
            else -> null
        }
        
        if (activePreset != null) {
            // Создаем пресет с актуальными значениями
            val remainingPreset = activePreset.copy(
                size = if (sizeSourcePreset != null) sizeSourcePreset!!.size else "",
                dpi = if (dpiSourcePreset != null) dpiSourcePreset!!.dpi else ""
            )
            
            val listName = try {
                PresetListService.getActivePresetList()?.name
            } catch (_: Exception) {
                null
            }
            
            buttonPanel.updateLastUsedPreset(remainingPreset, listName)
            lastUsedPreset = remainingPreset
        } else {
            // Если ничего не осталось, очищаем
            buttonPanel.updateLastUsedPreset(null, null)
            lastUsedPreset = null
        }
        
        println("ADB_DEBUG [Reset]: Preset after reset: ${lastUsedPreset?.label ?: "none"} (Size='${lastUsedPreset?.size ?: ""}', DPI='${lastUsedPreset?.dpi ?: ""}')")
    }
    
    private fun showResetResult(deviceCount: Int, resetSize: Boolean, resetDpi: Boolean) {
        val resetItems = mutableListOf<String>()
        if (resetSize) resetItems.add("screen Size")
        if (resetDpi) resetItems.add("DPI")

        val resetDescription = resetItems.joinToString(" and ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        NotificationUtils.showSuccess(project, "$resetDescription reset for $deviceCount device(s).")
    }

    // ==================== DEVICE PARAMETER ACTIONS ====================
    
    private fun resetDeviceSize(device: io.github.qavlad.adbdevicemanager.ui.models.CombinedDeviceInfo) {
        // Проверяем, изменено ли разрешение
        if (!device.hasModifiedResolution) {
            NotificationUtils.showInfo(project, "Screen Size is already at default")
            return
        }
        
        val activeDevice = device.usbDevice?.device ?: device.wifiDevice?.device
        if (activeDevice != null) {
            object : Task.Backgroundable(project, "Resetting screen Size") {
                override fun run(indicator: ProgressIndicator) {
                    val settings = PluginSettings.instance
                    
                    // Получаем текущий размер перед сбросом
                    val currentSizeBefore = AdbService.getCurrentSize(activeDevice).getOrNull()
                    
                    // Сохраняем активное приложение ПЕРЕД сбросом
                    var activeAppBeforeChange: Pair<String, String>? = null
                    if (settings.restartActiveAppOnResolutionChange && currentSizeBefore != null) {
                        println("ADB_Device_Manager: Getting focused app before reset on device ${activeDevice.serialNumber}")
                        
                        // Временно вызываем отладочный метод для диагностики проблемы с scrcpy
                        AdbService.debugGetCurrentApp(activeDevice)
                        
                        val focusedAppResult = AdbService.getCurrentFocusedApp(activeDevice)
                        val focusedApp = focusedAppResult.getOrNull()
                        
                        if (focusedApp != null) {
                            println("ADB_Device_Manager: Found focused app: ${focusedApp.first}/${focusedApp.second}")
                            
                            // Пропускаем только критически важные системные приложения
                            // Google Assistant, Chrome и другие приложения должны перезапускаться!
                            val isEssentialSystem = focusedApp.first == "com.android.systemui" ||
                                                   focusedApp.first == "com.android.launcher" ||
                                                   focusedApp.first == "com.sec.android.app.launcher" ||
                                                   focusedApp.first == "com.miui.home" || // MIUI launcher
                                                   focusedApp.first == "com.android.settings" ||
                                                   focusedApp.first == "com.android.phone" ||
                                                   focusedApp.first == "com.android.dialer"
                            
                            if (!isEssentialSystem) {
                                activeAppBeforeChange = focusedApp
                                println("ADB_Device_Manager: Saved app ${focusedApp.first} for restart after size reset on device ${activeDevice.serialNumber}")
                            } else {
                                println("ADB_Device_Manager: App ${focusedApp.first} is essential system app, skipping")
                            }
                        } else {
                            println("ADB_Device_Manager: No focused app found")
                        }
                    } else {
                        println("ADB_Device_Manager: Not checking for active app - restartActiveAppOnResolutionChange=${settings.restartActiveAppOnResolutionChange}, currentSizeBefore=$currentSizeBefore")
                    }
                    
                    // Выполняем сброс
                    AdbService.resetSize(activeDevice)
                    DeviceStateService.handleReset(resetSize = true, resetDpi = false)
                    PresetsDialogUpdateNotifier.notifyUpdate()
                    
                    // Получаем новый размер после сброса
                    Thread.sleep(1000) // Даем время на применение
                    val currentSizeAfter = AdbService.getCurrentSize(activeDevice).getOrNull()
                    
                    // Обрабатываем перезапуски
                    val restartResult = ResolutionChangeRestartService.handleSingleDeviceResolutionChange(
                        project,
                        activeDevice,
                        currentSizeBefore,
                        currentSizeAfter,
                        activeAppBeforeChange
                    )
                    
                    PluginLogger.info(LogCategory.UI_EVENTS, 
                        "Device size reset restart result: apps=%d, scrcpy=%d, runningDevices=%d", 
                        restartResult.appsRestarted,
                        restartResult.scrcpyRestarted,
                        restartResult.runningDevicesRestarted
                    )
                    
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showSuccess(project, "Screen Size reset to default")
                        devicePollingService.forceCombinedUpdate()
                    }
                }
            }.queue()
        }
    }
    
    private fun resetDeviceDpi(device: io.github.qavlad.adbdevicemanager.ui.models.CombinedDeviceInfo) {
        // Проверяем, изменено ли DPI
        if (!device.hasModifiedDpi) {
            NotificationUtils.showInfo(project, "DPI is already at default")
            return
        }
        
        val activeDevice = device.usbDevice?.device ?: device.wifiDevice?.device
        if (activeDevice != null) {
            object : Task.Backgroundable(project, "Resetting DPI") {
                override fun run(indicator: ProgressIndicator) {
                    AdbService.resetDpi(activeDevice)
                    DeviceStateService.handleReset(resetSize = false, resetDpi = true)
                    PresetsDialogUpdateNotifier.notifyUpdate()
                    
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtils.showSuccess(project, "DPI reset to default")
                        devicePollingService.forceCombinedUpdate()
                    }
                }
            }.queue()
        }
    }
    
    private fun applyDeviceChanges(device: io.github.qavlad.adbdevicemanager.ui.models.CombinedDeviceInfo, newSize: String?, newDpi: String?) {
        val activeDevice = device.usbDevice?.device ?: device.wifiDevice?.device
        if (activeDevice != null) {
            object : Task.Backgroundable(project, "Applying device changes") {
                override fun run(indicator: ProgressIndicator) {
                    val settings = PluginSettings.instance
                    var success = true
                    val messages = mutableListOf<String>()
                    
                    // Получаем текущий размер ПЕРЕД изменением
                    val currentSizeBefore = if (newSize != null) {
                        AdbService.getCurrentSize(activeDevice).getOrNull()
                    } else null
                    
                    // Сохраняем активное приложение ПЕРЕД изменением размера
                    var activeAppBeforeChange: Pair<String, String>? = null
                    if (settings.restartActiveAppOnResolutionChange && newSize != null) {
                        println("ADB_Device_Manager: Getting focused app before size change on device ${activeDevice.serialNumber}")
                        val focusedAppResult = AdbService.getCurrentFocusedApp(activeDevice)
                        val focusedApp = focusedAppResult.getOrNull()
                        
                        if (focusedApp != null) {
                            println("ADB_Device_Manager: Found focused app: ${focusedApp.first}/${focusedApp.second}")
                            // Пропускаем только критически важные системные приложения
                            val isEssentialSystem = focusedApp.first == "com.android.systemui" ||
                                                  focusedApp.first == "com.android.launcher" ||
                                                  focusedApp.first == "com.sec.android.app.launcher" ||
                                                  focusedApp.first == "com.miui.home" || // MIUI launcher
                                                  focusedApp.first == "com.android.settings" ||
                                                  focusedApp.first == "com.android.phone" ||
                                                  focusedApp.first == "com.android.dialer"
                            
                            if (!isEssentialSystem) {
                                activeAppBeforeChange = focusedApp
                                println("ADB_Device_Manager: Saved app ${focusedApp.first} for restart after size change on device ${activeDevice.serialNumber}")
                                PluginLogger.info(LogCategory.UI_EVENTS, 
                                    "Saved app %s for restart after size change on device %s", 
                                    focusedApp.first, activeDevice.serialNumber
                                )
                            } else {
                                println("ADB_Device_Manager: App ${focusedApp.first} is essential system app, skipping")
                                PluginLogger.info(LogCategory.UI_EVENTS, 
                                    "Skipping essential system app %s on device %s", 
                                    focusedApp.first, activeDevice.serialNumber
                                )
                            }
                        } else {
                            println("ADB_Device_Manager: No focused app found")
                        }
                    } else {
                        println("ADB_Device_Manager: Not checking for active app - restartActiveAppOnResolutionChange=${settings.restartActiveAppOnResolutionChange}, newSize=$newSize")
                    }
                    
                    // Применяем изменения размера
                    var sizeChanged = false
                    newSize?.let { sizeStr ->
                        if (ValidationUtils.isValidScreenSize(sizeStr)) {
                            val parts = sizeStr.split("x")
                            val width = parts[0].toInt()
                            val height = parts[1].toInt()
                            val result = AdbService.setSize(activeDevice, width, height)
                            if (result.isSuccess()) {
                                messages.add("Size set to $sizeStr")
                                sizeChanged = true
                                
                                // Помечаем что пресет был применен для этого устройства
                                DeviceStateService.markPresetApplied(activeDevice.serialNumber)
                            } else {
                                success = false
                                messages.add("Failed to set size")
                            }
                        } else {
                            success = false
                            messages.add("Invalid size format")
                        }
                    }
                    
                    // Применяем изменения DPI
                    newDpi?.let { dpiStr ->
                        val dpiValue = dpiStr.replace("dpi", "").trim().toIntOrNull()
                        if (dpiValue != null && ValidationUtils.isValidDpi(dpiValue)) {
                            val result = AdbService.setDpi(activeDevice, dpiValue)
                            if (result.isSuccess()) {
                                messages.add("DPI set to $dpiValue")
                            } else {
                                success = false
                                messages.add("Failed to set DPI")
                            }
                        } else {
                            success = false
                            messages.add("Invalid DPI value")
                        }
                    }
                    
                    PresetsDialogUpdateNotifier.notifyUpdate()
                    
                    // Обрабатываем перезапуски если размер был изменен
                    if (sizeChanged && currentSizeBefore != null) {
                        Thread.sleep(1000) // Даем время на применение
                        val currentSizeAfter = AdbService.getCurrentSize(activeDevice).getOrNull()
                        
                        val restartResult = ResolutionChangeRestartService.handleSingleDeviceResolutionChange(
                            project,
                            activeDevice,
                            currentSizeBefore,
                            currentSizeAfter,
                            activeAppBeforeChange
                        )
                        
                        PluginLogger.info(LogCategory.UI_EVENTS, 
                            "Device changes restart result: apps=%d, scrcpy=%d, runningDevices=%d", 
                            restartResult.appsRestarted,
                            restartResult.scrcpyRestarted,
                            restartResult.runningDevicesRestarted
                        )
                    }
                    
                    ApplicationManager.getApplication().invokeLater {
                        if (success) {
                            NotificationUtils.showSuccess(project, messages.joinToString(", "))
                        } else {
                            NotificationUtils.showError(project, messages.joinToString(", "))
                        }
                        devicePollingService.forceCombinedUpdate()
                    }
                }
            }.queue()
        }
    }
    
    // ==================== CONNECTION ACTIONS ====================
    
    private fun disconnectWifiDevice(ipAddress: String) {
        object : Task.Backgroundable(project, "Disconnecting Wi-Fi device") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Disconnecting from $ipAddress..."
                
                println("ADB_Device_Manager: Disconnecting Wi-Fi device: $ipAddress")
                PluginLogger.warn(LogCategory.ADB_CONNECTION, "Starting Wi-Fi device disconnect: %s", ipAddress)
                
                // Сначала останавливаем scrcpy если он запущен для этого устройства
                // ipAddress может содержать порт (например "192.168.1.100:5555"), извлекаем серийный номер
                val serialNumber = ipAddress // В случае Wi-Fi устройств, серийный номер это IP:port
                
                // Проверяем все возможные варианты серийных номеров
                val possibleSerials = listOf(
                    serialNumber,
                    serialNumber.substringBefore(":"),  // Без порта
                    "$serialNumber:5555"  // С явным портом если его нет
                )
                
                println("ADB_Device_Manager: Checking scrcpy for serial numbers: ${possibleSerials.joinToString(", ")}")
                
                var scrcpyStopped = false
                for (serial in possibleSerials) {
                    if (ScrcpyService.isScrcpyActiveForDevice(serial)) {
                        println("ADB_Device_Manager: Found active scrcpy for device: $serial, stopping it...")
                        PluginLogger.warn(LogCategory.SCRCPY, "Stopping scrcpy before disconnecting Wi-Fi device: %s", serial)
                        // Используем новый метод для остановки только Wi-Fi процесса
                        ScrcpyService.stopScrcpyForSingleSerial(serial)
                        scrcpyStopped = true
                        break
                    }
                }
                
                // Помечаем только Wi-Fi серийные номера как намеренно остановленные
                // чтобы предотвратить попытки запуска scrcpy после отключения
                for (serial in possibleSerials) {
                    // Помечаем только если это Wi-Fi serial (содержит ":")
                    if (serial.contains(":")) {
                        ScrcpyService.markSingleSerialAsIntentionallyStopped(serial)
                        println("ADB_Device_Manager: Marked $serial as intentionally stopped")
                    }
                }
                
                if (scrcpyStopped) {
                    println("ADB_Device_Manager: Scrcpy stopped, waiting for process to terminate...")
                    // Даем время процессу завершиться корректно
                    Thread.sleep(1000)
                } else {
                    println("ADB_Device_Manager: No active scrcpy found for device: $ipAddress")
                }
                
                val result = AdbService.disconnectWifi(ipAddress)
                val success = result.getOrNull() ?: false
                
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        NotificationUtils.showSuccess(project, "Disconnected from $ipAddress")
                        // Форсируем обновление списка устройств
                        devicePollingService.forceCombinedUpdate()
                    } else {
                        NotificationUtils.showError(project, "Failed to disconnect from $ipAddress")
                    }
                }
            }
        }.queue()
    }

    private fun executeKillAdbServer() {
        object : Task.Backgroundable(project, "Killing ADB Server") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Stopping ADB server..."
                
                // Блокируем обновления на время рестарта
                devicePollingService.setAdbRestarting(true)
                
                val success = AdbServerService.killAdbServer()
                
                // Даём время ADB серверу корректно завершиться
                if (success) {
                    Thread.sleep(1000) // Ждём 1 секунду после убийства сервера
                }
                
                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        NotificationUtils.showSuccess(project, "ADB server killed successfully")
                        // Форсируем обновление списка устройств через увеличенную задержку
                        // Проверяем состояние ADB асинхронно, но не блокируем слишком долго
                        Thread {
                            PluginLogger.info("ADB restart recovery thread started")
                            
                            // Ждём базовое время для восстановления ADB (3 секунды)
                            Thread.sleep(3000)
                            PluginLogger.info("Initial 3-second wait completed, checking ADB availability")
                            
                            var adbReady = false
                            var attempts = 0
                            val maxAttempts = 7 // Максимум 7 попыток (ещё 7 секунд после начальной задержки)
                            
                            while (!adbReady && attempts < maxAttempts) {
                                try {
                                    PluginLogger.info("Checking ADB availability, attempt %d/%d", attempts + 1, maxAttempts)
                                    // Пытаемся получить список устройств - это покажет что ADB работает
                                    val devicesResult = AdbService.getConnectedDevices()
                                    if (devicesResult.isSuccess()) {
                                        adbReady = true
                                        PluginLogger.info("ADB server is ready after %d attempts, found %d devices", 
                                            attempts + 1, devicesResult.getOrNull()?.size ?: 0)
                                    } else {
                                        PluginLogger.warn("ADB not ready yet, result: %s", devicesResult)
                                        Thread.sleep(1000) // Ждём секунду перед следующей попыткой
                                        attempts++
                                    }
                                } catch (e: Exception) {
                                    PluginLogger.warn("Error checking ADB availability: %s", e.message)
                                    Thread.sleep(1000)
                                    attempts++
                                }
                            }
                            
                            ApplicationManager.getApplication().invokeLater {
                                PluginLogger.info("Clearing ADB restarting flag, ADB ready: %s", adbReady)
                                // Всегда снимаем блокировку и пытаемся обновить устройства
                                // Это важно для переподключения Wi-Fi устройств
                                devicePollingService.setAdbRestarting(false)
                                
                                // Небольшая задержка для синхронизации флага
                                Timer(200) {
                                    PluginLogger.info("Starting device list update after ADB restart")
                                    // Всегда пытаемся обновить список устройств
                                    devicePollingService.forceCombinedUpdate()
                                    
                                    if (!adbReady) {
                                        // Если ADB не готов, показываем предупреждение
                                        PluginLogger.warn("ADB server not fully ready after %d attempts", maxAttempts)
                                        NotificationUtils.showWarning(project, 
                                            "ADB server may not be fully restored. Device list will update automatically when ready.")
                                    } else {
                                        PluginLogger.info("Device list update initiated successfully")
                                    }
                                }.apply {
                                    isRepeats = false
                                    start()
                                }
                            }
                        }.start()
                    } else {
                        // В случае ошибки тоже снимаем блокировку
                        devicePollingService.setAdbRestarting(false)
                        NotificationUtils.showError(project, "Failed to kill ADB server")
                    }
                }
            }
        }.queue()
    }

    private fun promptForManualConnection() {
        val settings = PluginSettings.instance
        val defaultPort = settings.adbPort.toString()
        
        val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
            private val ipField = JTextField(20)
            private val portField = JTextField(6).apply {
                text = defaultPort
            }
            
            init {
                title = "Connect Device via Wi-Fi"
                init()
                
                // Add document listener to auto-parse IP:PORT format in IP field
                ipField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    
                    private fun checkForIpPortFormat() {
                        val text = ipField.text
                        if (text.contains(":")) {
                            val parts = text.split(":")
                            if (parts.size == 2 && ValidationUtils.isValidIpAddress(parts[0].trim())) {
                                SwingUtilities.invokeLater {
                                    ipField.text = parts[0].trim()
                                    portField.text = parts[1].trim()
                                }
                            }
                        }
                    }
                })
                
                // Add document listener to auto-parse IP:PORT format in port field
                portField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent) = checkForIpPortFormat()
                    
                    private fun checkForIpPortFormat() {
                        val text = portField.text
                        if (text.contains(":")) {
                            val parts = text.split(":")
                            if (parts.size == 2 && ValidationUtils.isValidIpAddress(parts[0].trim())) {
                                SwingUtilities.invokeLater {
                                    ipField.text = parts[0].trim()
                                    portField.text = parts[1].trim()
                                }
                            }
                        }
                    }
                })
            }
            
            override fun createCenterPanel(): JComponent {
                val panel = JPanel(GridBagLayout())
                val gbc = GridBagConstraints()
                
                // IP Address label and field
                gbc.gridx = 0
                gbc.gridy = 0
                gbc.anchor = GridBagConstraints.WEST
                gbc.insets = JBUI.insets(5)
                panel.add(JLabel("IP Address:"), gbc)
                
                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                panel.add(ipField, gbc)
                
                // Port label and field
                gbc.gridx = 0
                gbc.gridy = 1
                gbc.fill = GridBagConstraints.NONE
                gbc.weightx = 0.0
                panel.add(JLabel("Port:"), gbc)
                
                gbc.gridx = 1
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.weightx = 1.0
                panel.add(portField, gbc)
                
                // Info label
                gbc.gridx = 0
                gbc.gridy = 2
                gbc.gridwidth = 2
                gbc.insets = JBUI.insetsTop(10)
                val infoLabel = JLabel("<html><i>Tip: You can paste IP:PORT format (e.g., 192.168.1.100:5555) in either field and it will auto-split</i></html>")
                infoLabel.foreground = JBColor.GRAY
                panel.add(infoLabel, gbc)
                
                return panel
            }
            
            override fun getPreferredFocusedComponent(): JComponent = ipField
            
            override fun doOKAction() {
                val ip = ipField.text.trim()
                val portText = portField.text.trim()
                
                if (ip.isEmpty()) {
                    Messages.showErrorDialog(this.contentPanel, "Please enter IP address", "Invalid Input")
                    return
                }
                
                if (!ValidationUtils.isValidIpAddress(ip)) {
                    Messages.showErrorDialog(this.contentPanel, "Please enter a valid IP address", "Invalid IP")
                    return
                }
                
                val port = portText.toIntOrNull()
                if (port == null) {
                    Messages.showErrorDialog(this.contentPanel, "Please enter a valid port number", "Invalid Port")
                    return
                }
                
                if (!ValidationUtils.isValidAdbPort(port)) {
                    Messages.showErrorDialog(this.contentPanel, "Port must be between 1024 and 65535", "Invalid Port")
                    return
                }
                
                super.doOKAction()
            }
            
            fun getIpAddress(): String = ipField.text.trim()
            fun getPort(): Int = portField.text.trim().toIntOrNull() ?: settings.adbPort
        }
        
        if (dialog.showAndGet()) {
            executeManualWifiConnection(dialog.getIpAddress(), dialog.getPort())
        }
    }

    private fun executeManualWifiConnection(ipAddress: String, port: Int) {
        object : Task.Backgroundable(project, "Connecting to $ipAddress:$port") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to $ipAddress:$port..."

                try {
                    val connectResult = AdbService.connectWifi(project, ipAddress, port)
                    val success = connectResult.getOrNull() ?: run {
                        connectResult.onError { exception, _ ->
                            PluginLogger.wifiConnectionFailed(ipAddress, port, exception)
                            
                            // Check if the error was due to different Wi-Fi networks
                            // If so, don't show additional error in handleConnectionResult
                            if (exception is io.github.qavlad.adbdevicemanager.exceptions.DifferentWifiNetworksException ||
                                exception is io.github.qavlad.adbdevicemanager.exceptions.ManualWifiSwitchRequiredException) {
                                // These exceptions already show their own warning, just return
                                return
                            }
                        }
                        false
                    }
                    handleConnectionResult(success, ipAddress, port)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorMessage = when {
                            e.message?.contains("not connected to Wi-Fi", ignoreCase = true) == true ||
                            e.message?.contains("Empty ip route", ignoreCase = true) == true -> {
                                """Device is not connected to Wi-Fi network.
                                |
                                |Please:
                                |1. Connect the device to the same Wi-Fi network as your computer
                                |2. Make sure Wi-Fi is turned on in device settings
                                |3. Try again after connecting to Wi-Fi""".trimMargin()
                            }
                            else -> "Error connecting to device: ${e.message}"
                        }
                        NotificationUtils.showError(project, errorMessage)
                    }
                }
            }
        }.queue()
    }

    private fun handleConnectionResult(success: Boolean, ipAddress: String, port: Int) {
        if (success) {
            Thread.sleep(PluginConfig.Network.CONNECTION_VERIFY_DELAY_MS)
            val devicesResult = AdbService.getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: run {
                devicesResult.onError { exception, _ ->
                    PluginLogger.warn(LogCategory.ADB_CONNECTION, "Failed to verify connection: %s", exception.message)
                }
                emptyList()
            }
            val connectedDevice = devices.find { it.serialNumber.startsWith(ipAddress) }

            if (connectedDevice != null) {
                // Сохраняем в историю Wi-Fi устройств
                val deviceInfo = DeviceInfo(connectedDevice, ipAddress)
                WifiDeviceHistoryService.addOrUpdateDevice(
                    WifiDeviceHistoryService.WifiDeviceHistoryEntry(
                        ipAddress = ipAddress,
                        port = port,
                        displayName = deviceInfo.displayName,
                        androidVersion = deviceInfo.androidVersion,
                        apiLevel = deviceInfo.apiLevel,
                        logicalSerialNumber = deviceInfo.logicalSerialNumber,
                        realSerialNumber = deviceInfo.displaySerialNumber  // Сохраняем настоящий серийник
                    )
                )
            }

            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showSuccess(project, "Successfully connected to device at $ipAddress:$port")
                // Форсируем обновление списка устройств
                devicePollingService.forceCombinedUpdate()
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                NotificationUtils.showError(project, "Failed to connect to $ipAddress:$port. Make sure device is in TCP/IP mode and both devices are on the same Wi-Fi network.")
            }
        }
    }

    /**
     * Подключает устройство по Wi-Fi используя IP адрес и порт
     * Используется для подключения из секции Previously connected devices
     */
    fun connectDeviceViaWifiByIp(ipAddress: String, port: Int = 5555) {
        object : Task.Backgroundable(project, "Connecting to $ipAddress:$port") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to $ipAddress:$port..."

                try {
                    val connectResult = AdbService.connectWifi(project, ipAddress, port)
                    val success = connectResult.getOrNull() ?: run {
                        connectResult.onError { exception, _ ->
                            PluginLogger.wifiConnectionFailed(ipAddress, port, exception)
                        }
                        false
                    }
                    handleConnectionResult(success, ipAddress, port)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorMessage = when {
                            e.message?.contains("not connected to Wi-Fi", ignoreCase = true) == true ||
                            e.message?.contains("Empty ip route", ignoreCase = true) == true -> {
                                """Device is not connected to Wi-Fi network.
                                |
                                |Please:
                                |1. Connect the device to the same Wi-Fi network as your computer
                                |2. Make sure Wi-Fi is turned on in device settings
                                |3. Try again after connecting to Wi-Fi""".trimMargin()
                            }
                            else -> "Error connecting to device: ${e.message}"
                        }
                        NotificationUtils.showError(project, errorMessage)
                    }
                }
            }
        }.queue()
    }

    private fun connectDeviceViaWifi(device: IDevice) {
        object : Task.Backgroundable(project, "Connecting to Device via Wi-Fi") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                
                // Проверяем настройку для переключения WiFi компьютера
                if (PluginSettings.instance.autoSwitchPCWifi) {
                    indicator.text = "Checking device WiFi network..."
                    PluginLogger.info(LogCategory.NETWORK, "Auto PC WiFi switch enabled, checking device network")
                    
                    try {
                        // Блокируем до получения сети устройства
                        val deviceWifiInfo = kotlinx.coroutines.runBlocking {
                            PCWifiSwitchService.getDeviceWifiNetwork(device.serialNumber)
                        }
                        
                        if (deviceWifiInfo != null) {
                            indicator.text = "Switching PC to network: ${deviceWifiInfo.ssid}..."
                            PluginLogger.info(LogCategory.NETWORK, "Attempting to switch PC to device network: %s", deviceWifiInfo.ssid)
                            
                            // Пытаемся переключить WiFi компьютера
                            val switchSuccess = kotlinx.coroutines.runBlocking {
                                PCWifiSwitchService.switchPCWifiNetwork(project, deviceWifiInfo)
                            }
                            
                            if (switchSuccess) {
                                // Даем время на переключение сети
                                Thread.sleep(3000)
                                PluginLogger.info(LogCategory.NETWORK, "PC WiFi switched successfully, continuing with connection")
                            } else {
                                PluginLogger.warn(LogCategory.NETWORK, "Failed to switch PC WiFi, continuing with current network")
                            }
                        } else {
                            PluginLogger.info(LogCategory.NETWORK, "Could not determine device WiFi network, continuing with current PC network")
                        }
                    } catch (e: Exception) {
                        PluginLogger.error(LogCategory.NETWORK, "Error during PC WiFi switch attempt", e)
                        // Продолжаем попытку подключения даже если не удалось переключить WiFi
                    }
                }
                
                indicator.text = "Getting IP address for ${device.name}..."
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Getting IP address for device %s", device.name)

                val ipResult = AdbService.getDeviceIpAddress(device)
                val ipAddress = ipResult.getOrNull() ?: run {
                    ipResult.onError { exception, message ->
                        // Используем info вместо error для ожидаемых ситуаций
                        if (message?.contains("not connected to Wi-Fi", ignoreCase = true) == true) {
                            PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Device %s is not connected to Wi-Fi", device.name)
                        } else {
                            PluginLogger.warn(LogCategory.ADB_CONNECTION, "[UI] Failed to get IP address for device %s: %s", device.name, message ?: exception.message)
                        }
                    }
                    null
                }
                
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Device %s IP address: %s", device.name, ipAddress ?: "null")
                
                if (ipAddress.isNullOrBlank()) {
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] IP address is null or blank for device %s", device.name)
                    val errorMessage = """Device ${device.name} is not connected to Wi-Fi network.
                        |
                        |Please:
                        |1. Connect the device to the same Wi-Fi network as your computer
                        |2. Make sure Wi-Fi is turned on in device settings
                        |3. Try again after connecting to Wi-Fi""".trimMargin()
                    NotificationUtils.showError(project, errorMessage)
                    return
                }

                indicator.text = "Enabling TCP/IP mode on ${device.name}..."
                PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Starting TCP/IP enable process")
                
                try {
                    // Получаем IP до включения TCP/IP, чтобы убедиться что устройство подключено к Wi-Fi
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Verifying device IP before TCP/IP enable...")
                    val ipBeforeResult = AdbService.getDeviceIpAddress(device)
                    val ipBeforeEnable = ipBeforeResult.getOrNull() ?: run {
                        ipBeforeResult.onError { exception, _ ->
                            PluginLogger.warn(LogCategory.ADB_CONNECTION, "[UI] Failed to verify device IP before TCP/IP enable", exception)
                        }
                        null
                    }
                    
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] IP before TCP/IP enable: %s", ipBeforeEnable ?: "null")
                    
                    if (ipBeforeEnable.isNullOrBlank()) {
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Cannot get device IP before TCP/IP enable - device not connected to Wi-Fi")
                        ApplicationManager.getApplication().invokeLater {
                            val errorMessage = """Device is not connected to Wi-Fi network.
                                |
                                |Please:
                                |1. Connect the device to the same Wi-Fi network as your computer
                                |2. Make sure Wi-Fi is turned on in device settings
                                |3. Try again after connecting to Wi-Fi""".trimMargin()
                            NotificationUtils.showError(project, errorMessage)
                        }
                        return
                    }
                    
                    // Сохраняем serial number устройства на случай если оно отключится после включения TCP/IP
                    val deviceSerial = device.serialNumber
                    
                    // Сначала включаем TCP/IP режим
                    PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Calling AdbService.enableTcpIp for device %s...", deviceSerial)
                    val tcpipResult = AdbService.enableTcpIp(device)
                    
                    if (!tcpipResult.isSuccess()) {
                        tcpipResult.onError { exception, message ->
                            PluginLogger.warn(LogCategory.ADB_CONNECTION, "[UI] Failed to enable TCP/IP mode: %s", message ?: exception.message)
                            // Если не удалось включить TCP/IP, пробуем восстановить USB режим
                            PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] Attempting to restore USB mode...")
                            AdbService.disableTcpIp(deviceSerial)
                        }
                        return
                    } else {
                        PluginLogger.info(LogCategory.ADB_CONNECTION, "[UI] TCP/IP mode enabled or already was enabled on port 5555")
                    }
                    
                    // Даем устройству время на включение TCP/IP
                    Thread.sleep(PluginConfig.Network.TCPIP_ENABLE_DELAY_MS)
                    
                    indicator.text = "Connecting to $ipAddress:5555..."
                    
                    // Первая попытка подключения
                    PluginLogger.info("Starting first Wi-Fi connection attempt to %s", ipAddress)
                    val firstConnectResult = AdbService.connectWifi(project, ipAddress)
                    var success = firstConnectResult.getOrNull() ?: run {
                        firstConnectResult.onError { exception, _ ->
                            // Проверяем специальные исключения, которые требуют прерывания
                            if (exception is io.github.qavlad.adbdevicemanager.exceptions.ManualWifiSwitchRequiredException ||
                                exception is io.github.qavlad.adbdevicemanager.exceptions.DifferentWifiNetworksException) {
                                // Пробрасываем исключение дальше, не пытаемся повторно
                                throw exception
                            }
                            PluginLogger.wifiConnectionFailed(ipAddress, 5555, exception)
                        }
                        false
                    }
                    
                    if (!success) {
                        // Если не удалось, пробуем еще раз с большей задержкой
                        PluginLogger.info("First connection attempt failed, retrying...")
                        Thread.sleep(PluginConfig.Network.WIFI_CONNECTION_VERIFY_DELAY_MS)
                        val retryConnectResult = AdbService.connectWifi(project, ipAddress)
                        success = retryConnectResult.getOrNull() ?: run {
                            retryConnectResult.onError { exception, _ ->
                                // Проверяем специальные исключения, которые требуют прерывания
                                if (exception is io.github.qavlad.adbdevicemanager.exceptions.ManualWifiSwitchRequiredException ||
                                    exception is io.github.qavlad.adbdevicemanager.exceptions.DifferentWifiNetworksException) {
                                    // Пробрасываем исключение дальше
                                    throw exception
                                }
                                PluginLogger.wifiConnectionFailed(ipAddress, 5555, exception)
                            }
                            false
                        }
                    }
                    
                    PluginLogger.info("Connection result: success=%s for device %s at %s", success, device.name, ipAddress)
                    
                    // НЕ возвращаем в USB режим для Android 6 - пусть пользователь отключит кабель
                    if (!success) {
                        PluginLogger.warn(LogCategory.ADB_CONNECTION, "[UI] Wi-Fi connection failed for device %s. Try disconnecting USB cable.", deviceSerial)
                        // НЕ отключаем TCP/IP автоматически
                        // AdbService.disableTcpIp(deviceSerial)
                    }
                    
                    showWifiConnectionResult(success, device.name, ipAddress)
                } catch (_: io.github.qavlad.adbdevicemanager.exceptions.ManualWifiSwitchRequiredException) {
                    // Ручное переключение требуется - не показываем ошибку подключения
                    PluginLogger.info("Manual WiFi switch required for device ${device.name}")
                    // Уведомление уже показано в tryAutoSwitchWifi, ничего дополнительно не делаем
                } catch (_: io.github.qavlad.adbdevicemanager.exceptions.DifferentWifiNetworksException) {
                    // Разные Wi-Fi сети - не показываем дополнительную ошибку
                    PluginLogger.info("Different Wi-Fi networks detected for device ${device.name}")
                    // Уведомление уже показано в checkWifiNetworks, ничего дополнительно не делаем
                } catch (e: Exception) {
                    PluginLogger.error("Error connecting to device", e)
                    NotificationUtils.showError(project, "Error connecting to device: ${e.message}")
                }
            }
        }.queue()
    }

    private fun showWifiConnectionResult(success: Boolean, deviceName: String, ipAddress: String) {
        if (success) {
            // Попытка найти устройство среди подключённых и добавить в историю
            val devicesResult = AdbService.getConnectedDevices()
            val devices = devicesResult.getOrNull() ?: emptyList()
            val connectedDevice = devices.find { it.serialNumber.startsWith(ipAddress) }
            if (connectedDevice != null) {
                val deviceInfo = DeviceInfo(connectedDevice, ipAddress)
                WifiDeviceHistoryService.addOrUpdateDevice(
                    WifiDeviceHistoryService.WifiDeviceHistoryEntry(
                        ipAddress = ipAddress,
                        port = 5555,
                        displayName = deviceInfo.displayName,
                        androidVersion = deviceInfo.androidVersion,
                        apiLevel = deviceInfo.apiLevel,
                        logicalSerialNumber = deviceInfo.logicalSerialNumber,
                        realSerialNumber = deviceInfo.displaySerialNumber  // Сохраняем настоящий серийник
                    )
                )
            }
            NotificationUtils.showSuccess(project, "Successfully connected to $deviceName at $ipAddress.")
            // Форсируем обновление списка устройств с небольшой задержкой
            SwingUtilities.invokeLater {
                // Небольшая задержка, чтобы дать время ADB обновить список устройств
                Timer(500) {
                    devicePollingService.forceCombinedUpdate()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        } else {
            val message = """Failed to connect to $deviceName via Wi-Fi.
                |
                |For Android 6 devices:
                |TCP/IP mode is already enabled. Please disconnect the USB cable 
                |and try connecting again via Wi-Fi.
                |
                |Other solutions:
                |1. Make sure device is connected to the same Wi-Fi network
                |2. Check that 'USB debugging' is enabled in Developer Options
                |3. Some devices require 'Wireless debugging' to be enabled separately""".trimMargin()
            NotificationUtils.showError(project, message)
        }
    }

    // ==================== SCREEN MIRRORING ====================

    private fun startScreenMirroring(deviceInfo: DeviceInfo) {
        // Проверяем, не идёт ли сейчас рестарт ADB
        if (AdbStateManager.isAdbRestarting()) {
            NotificationUtils.showWarning(project, 
                "ADB server is restarting. Please wait for it to complete before launching screen mirroring.")
            
            // Добавляем запрос в очередь для выполнения после рестарта
            AdbStateManager.addPendingScrcpyRequest(deviceInfo.logicalSerialNumber)
            
            return
        }
        
        object : Task.Backgroundable(project, "Starting screen mirroring") {
            private var scrcpyPath: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Searching for scrcpy..."

                scrcpyPath = ScrcpyService.findScrcpyExecutable()

                if (scrcpyPath == null) {
                    handleScrcpyNotFound(deviceInfo)
                    return
                }

                launchScrcpyProcess(deviceInfo, scrcpyPath!!, indicator)
            }
        }.queue()
    }

    private fun handleScrcpyNotFound(deviceInfo: DeviceInfo) {
        ApplicationManager.getApplication().invokeLater {
            NotificationUtils.showInfo(project, "scrcpy executable not found in PATH. Please select the file.")
            showScrcpyCompatibilityDialog(deviceInfo)
        }
    }

    private fun showScrcpyCompatibilityDialog(deviceInfo: DeviceInfo) {
        val dialog = ScrcpyCompatibilityDialog(
            project,
            "Not found",
            deviceInfo.displayName,
            ScrcpyCompatibilityDialog.ProblemType.NOT_FOUND
        )
        dialog.show()

        if (dialog.exitCode == ScrcpyCompatibilityDialog.RETRY_EXIT_CODE) {
            retryScreenMirroring(deviceInfo)
        } else {
            NotificationUtils.showError(project, "scrcpy not found. Could not start mirroring.")
        }
    }

    private fun retryScreenMirroring(deviceInfo: DeviceInfo) {
        val scrcpyPath = ScrcpyService.findScrcpyExecutable()
        if (scrcpyPath != null) {
            object : Task.Backgroundable(project, "Starting screen mirroring") {
                override fun run(indicator: ProgressIndicator) {
                    launchScrcpyProcess(deviceInfo, scrcpyPath, indicator)
                }
            }.queue()
        } else {
            NotificationUtils.showError(project, "scrcpy path not provided. Could not start mirroring.")
        }
    }

    private fun launchScrcpyProcess(deviceInfo: DeviceInfo, scrcpyPath: String, indicator: ProgressIndicator) {
        val serialToUse = deviceInfo.logicalSerialNumber
        indicator.text = "Running: scrcpy -s $serialToUse"

        val success = ScrcpyService.launchScrcpy(scrcpyPath, serialToUse, project)

        ApplicationManager.getApplication().invokeLater {
            if (success) {
                NotificationUtils.showSuccess(project, "Screen mirroring started for ${deviceInfo.displayName}")
            } else {
                NotificationUtils.showError(project, "Failed to start screen mirroring for ${deviceInfo.displayName}")
            }
        }
    }

    /**
     * Умное параллельное подключение к нескольким IP адресам
     * Прерывается при успешном подключении или обнаружении верного IP с выключенным TCP/IP
     */
    fun connectDeviceViaWifiParallel(ipAddresses: List<Pair<String, Int>>) {
        if (ipAddresses.isEmpty()) return
        
        object : Task.Backgroundable(project, "Connecting to device via Wi-Fi") {
            @Volatile
            private var shouldStop = false
            private val connectionAttempts = mutableMapOf<String, String>() // IP -> status
            
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "Attempting connection to ${ipAddresses.size} IP addresses..."
                
                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val latch = java.util.concurrent.CountDownLatch(ipAddresses.size)
                
                ipAddresses.forEach { (ipAddress, port) ->
                    Thread {
                        try {
                            if (!shouldStop) {
                                connectionAttempts[ipAddress] = "connecting"
                                PluginLogger.debug("Starting connection attempt to $ipAddress:$port")
                                
                                val connectResult = AdbService.connectWifi(project, ipAddress, port)
                                
                                if (connectResult.isSuccess()) {
                                    val success = connectResult.getOrNull() ?: false
                                    if (success) {
                                        connectionAttempts[ipAddress] = "success"
                                        PluginLogger.info("Successfully connected to $ipAddress:$port")
                                        shouldStop = true // Останавливаем остальные попытки
                                        
                                        ApplicationManager.getApplication().invokeLater {
                                            handleConnectionResult(true, ipAddress, port)
                                        }
                                    } else {
                                        connectionAttempts[ipAddress] = "failed"
                                        PluginLogger.debug("Connection failed for $ipAddress:$port")
                                    }
                                } else {
                                    var errorMessage = ""
                                    connectResult.onError { exception, _ ->
                                        errorMessage = exception.message ?: ""
                                    }
                                    
                                    // Проверяем типы ошибок
                                    when {
                                        errorMessage.contains("отверг запрос на подключение") ||
                                        errorMessage.contains("Connection refused") ||
                                        errorMessage.contains("10061") -> {
                                            // IP верный, но TCP/IP не включен
                                            connectionAttempts[ipAddress] = "tcp_disabled"
                                            PluginLogger.info("Found correct IP $ipAddress but TCP/IP is disabled")
                                            shouldStop = true // Останавливаем остальные попытки
                                            
                                            ApplicationManager.getApplication().invokeLater {
                                                NotificationUtils.showWarning(
                                                    project,
                                                    "Found device at $ipAddress but Wi-Fi debugging is disabled. Connect via USB and click Connect to enable it."
                                                )
                                            }
                                        }
                                        errorMessage.contains("timed out") ||
                                        errorMessage.contains("timeout") -> {
                                            // Таймаут - вероятно неверный IP или устройство недоступно
                                            connectionAttempts[ipAddress] = "timeout"
                                            PluginLogger.debug("Connection timeout for $ipAddress:$port")
                                        }
                                        else -> {
                                            connectionAttempts[ipAddress] = "error"
                                            PluginLogger.debug("Connection error for $ipAddress:$port: $errorMessage")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            connectionAttempts[ipAddress] = "exception"
                            PluginLogger.debug("Exception during connection to $ipAddress:$port: ${e.message}")
                        } finally {
                            val completed = completedCount.incrementAndGet()
                            indicator.fraction = completed.toDouble() / ipAddresses.size
                            indicator.text = "Attempted $completed of ${ipAddresses.size} connections..."
                            latch.countDown()
                        }
                    }.start()
                }
                
                // Ждём завершения всех попыток или остановки
                try {
                    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                
                // Анализируем результаты
                val successfulIp = connectionAttempts.entries.find { it.value == "success" }?.key
                val tcpDisabledIp = connectionAttempts.entries.find { it.value == "tcp_disabled" }?.key
                
                if (successfulIp == null && tcpDisabledIp == null) {
                    // Ни одно подключение не удалось
                    ApplicationManager.getApplication().invokeLater {
                        val timeouts = connectionAttempts.count { it.value == "timeout" }
                        if (timeouts == ipAddresses.size) {
                            NotificationUtils.showWarning(project, "Device not found. Connect via USB first to enable Wi-Fi debugging.")
                        } else {
                            NotificationUtils.showWarning(project, "Connection failed. Connect device via USB and try again.")
                        }
                    }
                }
                
                PluginLogger.debug("Connection attempts summary: $connectionAttempts")
            }
        }.queue()
    }

    // ==================== SETTINGS ====================

    private fun openPresetsDialog() {
        PresetsDialog(
            project = project,
            getSelectedDevices = ::getSelectedDevicesForAdb,
            onPresetApplied = { preset, listName, setSize, setDpi ->
                // Комбинируем с существующим пресетом если применяем только часть параметров
                val combinedPreset = when {
                    setSize && setDpi -> {
                        // Применяем оба параметра - используем новый пресет полностью
                        sizeSourcePreset = preset
                        dpiSourcePreset = preset
                        preset
                    }
                    setSize && !setDpi -> {
                        // Применяем только size - сохраняем DPI от предыдущего пресета если есть
                        sizeSourcePreset = preset
                        if (lastUsedPreset?.dpi?.isNotBlank() == true) {
                            preset.copy(dpi = lastUsedPreset!!.dpi)
                        } else {
                            preset.copy(dpi = "")
                        }
                    }
                    !setSize && setDpi -> {
                        // Применяем только DPI - сохраняем size от предыдущего пресета если есть
                        dpiSourcePreset = preset
                        if (lastUsedPreset?.size?.isNotBlank() == true) {
                            preset.copy(size = lastUsedPreset!!.size)
                        } else {
                            preset.copy(size = "")
                        }
                    }
                    else -> preset
                }
                
                // Обновляем индикатор активного пресета при применении из диалога
                buttonPanel.updateLastUsedPreset(combinedPreset, listName)
                lastUsedPreset = combinedPreset
            }
        ).show()
    }
    
    private fun openPluginSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "ADB Device Manager")
    }
    
    // ==================== LIFECYCLE ====================
    
    /**
     * Освобождает ресурсы при закрытии панели
     */
    fun dispose() {
        presetNavigationTimer?.stop()
        presetNavigationTimer = null
        devicePollingService.dispose()
    }
}