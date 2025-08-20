package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.settings.PluginSettings
import io.github.qavlad.adbrandomizer.services.integration.scrcpy.ScrcpyService
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory

/**
 * Сервис для управления перезапуском приложений, scrcpy и Running Devices
 * при изменении разрешения экрана устройства
 */
object ResolutionChangeRestartService {
    
    data class ResolutionChangeContext(
        val device: IDevice,
        val sizeBefore: Pair<Int, Int>?,
        val sizeAfter: Pair<Int, Int>?,
        val defaultSize: Pair<Int, Int>?,
        val wasCustomSize: Boolean,
        val hasResolutionChanged: Boolean
    )
    
    data class RestartResult(
        val appsRestarted: Int = 0,
        val scrcpyRestarted: Int = 0,
        val runningDevicesRestarted: Int = 0
    )
    
    /**
     * Основной метод для обработки перезапусков при изменении разрешения
     * @param activeAppsBeforeChange - мапа устройств и их активных приложений ДО изменения разрешения (опционально)
     */
    fun handleResolutionChangeRestarts(
        project: Project,
        devices: List<IDevice>,
        resolutionContexts: Map<IDevice, ResolutionChangeContext>,
        activeAppsBeforeChange: Map<IDevice, Pair<String, String>>? = null
    ): RestartResult {
        val settings = PluginSettings.instance
        
        println("ADB_Randomizer: === RESOLUTION CHANGE RESTART SERVICE START ===")
        println("ADB_Randomizer: Devices count: ${devices.size}")
        println("ADB_Randomizer: Active apps before change: ${activeAppsBeforeChange?.map { "${it.key.serialNumber}: ${it.value.first}" }?.joinToString(", ") ?: "none"}")
        println("ADB_Randomizer: Settings - restartApp: ${settings.restartActiveAppOnResolutionChange}, restartScrcpy: ${settings.restartScrcpyOnResolutionChange}, restartRunningDevices: ${settings.restartRunningDevicesOnResolutionChange}")
        
        // Фильтруем только устройства с изменением разрешения
        val devicesWithChanges = devices.filter { device ->
            val context = resolutionContexts[device] ?: return@filter false
            val hasChange = context.hasResolutionChanged || context.wasCustomSize
            println("ADB_Randomizer: Device ${device.serialNumber}: hasResolutionChanged=${context.hasResolutionChanged}, wasCustomSize=${context.wasCustomSize}, will process=$hasChange")
            hasChange
        }
        
        if (devicesWithChanges.isEmpty()) {
            println("ADB_Randomizer: No devices with resolution changes, skipping all restarts")
            return RestartResult()
        }
        
        // Собираем информацию о том, что нужно перезапустить
        val devicesWithActiveApps = mutableMapOf<IDevice, Pair<String, String>>()
        val devicesNeedingScrcpyRestart = mutableSetOf<String>() // Используем Set для уникальных значений
        val devicesNeedingRunningDevicesRestart = mutableListOf<IDevice>()
        
        // 1. Проверяем активные приложения
        if (settings.restartActiveAppOnResolutionChange) {
            if (activeAppsBeforeChange != null) {
                // Используем переданную информацию о приложениях до изменения
                devicesWithChanges.forEach { device ->
                    val activeApp = activeAppsBeforeChange[device]
                    if (activeApp != null) {
                        devicesWithActiveApps[device] = activeApp
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Will restart app %s on device %s (from pre-change state)", 
                            activeApp.first, device.serialNumber)
                    }
                }
            } else {
                // Если информация не передана, получаем текущие приложения
                // (это может быть неточно, если разрешение уже изменено)
                devicesWithChanges.forEach { device ->
                    val activeApp = getActiveNonSystemApp(device)
                    if (activeApp != null) {
                        devicesWithActiveApps[device] = activeApp
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Will restart app %s on device %s (current state)", 
                            activeApp.first, device.serialNumber)
                    }
                }
            }
        }
        
        // 2. Проверяем scrcpy - собираем ВСЕ активные процессы для связанных устройств
        if (settings.restartScrcpyOnResolutionChange) {
            devicesWithChanges.forEach { device ->
                if (ScrcpyService.hasAnyScrcpyProcessForDevice(device.serialNumber)) {
                    // Получаем все связанные serial numbers и проверяем какие из них имеют активные процессы
                    val activeScrcpySerials = ScrcpyService.getActiveScrcpySerials(device.serialNumber)
                    activeScrcpySerials.forEach { serial ->
                        devicesNeedingScrcpyRestart.add(serial)
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Will restart scrcpy for serial %s (related to device %s)", 
                            serial, device.serialNumber)
                    }
                }
            }
        }
        
        // 3. Проверяем Running Devices (только для Android Studio)
        if (settings.restartRunningDevicesOnResolutionChange) {
            AndroidStudioIntegrationService.instance?.let { androidService ->
                devicesWithChanges.forEach { device ->
                    if (androidService.hasActiveDeviceTab(device)) {
                        devicesNeedingRunningDevicesRestart.add(device)
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Will restart Running Devices for device %s", device.serialNumber)
                    }
                }
            }
        }
        
        // Выполняем перезапуски в правильном порядке
        return executeRestarts(
            project,
            devicesWithActiveApps,
            devicesNeedingScrcpyRestart,
            devicesNeedingRunningDevicesRestart
        )
    }
    
    /**
     * Проверяет, было ли изменено разрешение устройства
     */
    fun checkResolutionChange(
        device: IDevice,
        sizeBefore: Pair<Int, Int>?,
        sizeAfter: Pair<Int, Int>?
    ): ResolutionChangeContext {
        val defaultSize = AdbService.getDefaultSize(device).getOrNull()
        
        val wasCustomSize = if (sizeBefore != null && defaultSize != null) {
            sizeBefore != defaultSize
        } else false
        
        val hasChanged = if (sizeBefore != null && sizeAfter != null) {
            sizeBefore != sizeAfter
        } else false
        
        return ResolutionChangeContext(
            device = device,
            sizeBefore = sizeBefore,
            sizeAfter = sizeAfter,
            defaultSize = defaultSize,
            wasCustomSize = wasCustomSize,
            hasResolutionChanged = hasChanged
        )
    }
    
    /**
     * Получает активное несистемное приложение на устройстве
     */
    private fun getActiveNonSystemApp(device: IDevice): Pair<String, String>? {
        val focusedAppResult = AdbService.getCurrentFocusedApp(device)
        val focusedApp = focusedAppResult.getOrNull() ?: return null
        
        PluginLogger.info(LogCategory.PRESET_SERVICE,
            "Found focused app on device %s: %s/%s",
            device.serialNumber, focusedApp.first, focusedApp.second)
        
        // Проверяем, не является ли это системным приложением
        val isSystemResult = AdbService.isSystemApp(device, focusedApp.first)
        val isSystem = isSystemResult.getOrNull() ?: false
        
        return if (!isSystem) {
            focusedApp
        } else {
            PluginLogger.info(LogCategory.PRESET_SERVICE,
                "Skipping system app %s on device %s",
                focusedApp.first, device.serialNumber)
            null
        }
    }
    
    /**
     * Выполняет перезапуски в правильном порядке:
     * 1. Перезапуск приложений
     * 2. Закрытие scrcpy
     * 3. Закрытие и открытие Running Devices
     * 4. Запуск scrcpy
     */
    private fun executeRestarts(
        project: Project,
        devicesWithActiveApps: Map<IDevice, Pair<String, String>>,
        devicesNeedingScrcpyRestart: Set<String>,
        devicesNeedingRunningDevicesRestart: List<IDevice>
    ): RestartResult {
        var appsRestarted = 0
        var scrcpyRestarted = 0
        var runningDevicesRestarted = 0
        
        println("ADB_Randomizer: === EXECUTE RESTARTS START ===")
        println("ADB_Randomizer: Apps to restart: ${devicesWithActiveApps.size} (${devicesWithActiveApps.map { "${it.key.serialNumber}: ${it.value.first}" }.joinToString(", ")})")
        println("ADB_Randomizer: Scrcpy to restart: ${devicesNeedingScrcpyRestart.size}")
        println("ADB_Randomizer: Running Devices to restart: ${devicesNeedingRunningDevicesRestart.size}")
        
        if (devicesWithActiveApps.isEmpty() && 
            devicesNeedingScrcpyRestart.isEmpty() && 
            devicesNeedingRunningDevicesRestart.isEmpty()) {
            PluginLogger.info(LogCategory.PRESET_SERVICE, "Nothing to restart, returning")
            return RestartResult()
        }
        
        Thread {
            // Step 1: Перезапускаем активные приложения
            if (devicesWithActiveApps.isNotEmpty()) {
                PluginLogger.info(LogCategory.PRESET_SERVICE,
                    "Step 1: Restarting active apps for %d devices",
                    devicesWithActiveApps.size)
                
                Thread.sleep(1000) // Даём время на стабилизацию после изменения разрешения
                
                devicesWithActiveApps.forEach { (device, appInfo) ->
                    val (packageName, activityName) = appInfo
                    PluginLogger.info(LogCategory.PRESET_SERVICE,
                        "Attempting to restart app %s/%s on device %s",
                        packageName, activityName, device.serialNumber
                    )
                    
                    if (restartApp(device, packageName, activityName)) {
                        appsRestarted++
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Successfully restarted app %s on device %s",
                            packageName, device.serialNumber
                        )
                    } else {
                        PluginLogger.error(LogCategory.PRESET_SERVICE,
                            "Failed to restart app %s on device %s", null,
                            packageName, device.serialNumber
                        )
                    }
                }
                
                Thread.sleep(1000) // Даём время приложениям запуститься
            } else {
                PluginLogger.info(LogCategory.PRESET_SERVICE, "Step 1: No apps to restart")
            }
            
            // Step 2: Закрываем все процессы scrcpy
            if (devicesNeedingScrcpyRestart.isNotEmpty()) {
                PluginLogger.info(LogCategory.PRESET_SERVICE,
                    "Step 2: Closing scrcpy for %d serial numbers: %s",
                    devicesNeedingScrcpyRestart.size,
                    devicesNeedingScrcpyRestart.joinToString(", "))
                
                // Останавливаем процессы только для уникальных устройств (stopScrcpyForDevice уже обрабатывает связанные)
                val uniqueDevices = mutableSetOf<String>()
                devicesNeedingScrcpyRestart.forEach { serial ->
                    // Добавляем только если это не связанный serial уже обработанного устройства
                    var isRelated = false
                    for (existing in uniqueDevices) {
                        if (ScrcpyService.areSerialNumbersRelated(existing, serial)) {
                            isRelated = true
                            break
                        }
                    }
                    if (!isRelated) {
                        uniqueDevices.add(serial)
                    }
                }
                
                PluginLogger.info(LogCategory.PRESET_SERVICE,
                    "Stopping scrcpy for %d unique devices: %s",
                    uniqueDevices.size,
                    uniqueDevices.joinToString(", "))
                
                val closeThreads = uniqueDevices.map { serialNumber ->
                    Thread {
                        ScrcpyService.stopScrcpyForDevice(serialNumber)
                        PluginLogger.debug(LogCategory.PRESET_SERVICE,
                            "Closed scrcpy for device %s and its related connections", serialNumber)
                    }
                }
                closeThreads.forEach { it.start() }
                closeThreads.forEach { it.join() }
                
                Thread.sleep(500)
            }
            
            // Step 3: Перезапускаем Running Devices
            if (devicesNeedingRunningDevicesRestart.isNotEmpty()) {
                AndroidStudioIntegrationService.instance?.let { androidService ->
                    PluginLogger.info(LogCategory.PRESET_SERVICE,
                        "Step 3: Restarting Running Devices for %d devices",
                        devicesNeedingRunningDevicesRestart.size)
                    
                    androidService.restartRunningDevicesForMultiple(devicesNeedingRunningDevicesRestart)
                    runningDevicesRestarted = devicesNeedingRunningDevicesRestart.size
                    
                    Thread.sleep(2000)
                }
            }
            
            // Step 4: Запускаем scrcpy процессы для ВСЕХ serial numbers, которые были активны
            if (devicesNeedingScrcpyRestart.isNotEmpty()) {
                PluginLogger.info(LogCategory.PRESET_SERVICE,
                    "Step 4: Starting scrcpy for %d serial numbers: %s",
                    devicesNeedingScrcpyRestart.size,
                    devicesNeedingScrcpyRestart.joinToString(", "))
                
                val scrcpyPath = ScrcpyService.findScrcpyExecutable()
                if (scrcpyPath == null) {
                    PluginLogger.error(LogCategory.PRESET_SERVICE,
                        "Cannot restart scrcpy - executable not found", null, "")
                } else {
                    val serialsList = devicesNeedingScrcpyRestart.toList()
                    serialsList.forEachIndexed { index, serialNumber ->
                        PluginLogger.info(LogCategory.PRESET_SERVICE,
                            "Launching scrcpy for serial: %s", serialNumber)
                        if (ScrcpyService.launchScrcpy(scrcpyPath, serialNumber, project)) {
                            scrcpyRestarted++
                            PluginLogger.info(LogCategory.PRESET_SERVICE,
                                "Successfully launched scrcpy for serial: %s", serialNumber)
                        } else {
                            PluginLogger.error(LogCategory.PRESET_SERVICE,
                                "Failed to launch scrcpy for serial: %s", null, serialNumber)
                        }
                        
                        // Задержка между запусками для избежания конфликтов портов
                        if (index < serialsList.size - 1) {
                            Thread.sleep(1000)
                        }
                    }
                }
            }
        }.start()
        
        return RestartResult(appsRestarted, scrcpyRestarted, runningDevicesRestarted)
    }
    
    /**
     * Перезапускает приложение на устройстве
     */
    private fun restartApp(device: IDevice, packageName: String, activityName: String): Boolean {
        PluginLogger.info(LogCategory.PRESET_SERVICE,
            "Attempting to restart app %s on device %s",
            packageName, device.serialNumber)
        
        // Останавливаем приложение
        val stopResult = AdbService.stopApp(device, packageName)
        if (!stopResult.isSuccess()) {
            PluginLogger.error(LogCategory.PRESET_SERVICE,
                "Failed to stop app %s on device %s", null,
                packageName, device.serialNumber)
            return false
        }
        
        PluginLogger.info(LogCategory.PRESET_SERVICE,
            "Successfully stopped app %s, waiting before restart",
            packageName)
        
        Thread.sleep(500) // Небольшая задержка между остановкой и запуском
        
        // Запускаем приложение заново
        val startResult = AdbService.startApp(device, packageName, activityName)
        if (!startResult.isSuccess()) {
            PluginLogger.error(LogCategory.PRESET_SERVICE,
                "Failed to start app %s on device %s", null,
                packageName, device.serialNumber)
            return false
        }
        
        PluginLogger.info(LogCategory.PRESET_SERVICE,
            "Successfully restarted app %s on device %s",
            packageName, device.serialNumber)
        
        return true
    }
    
    /**
     * Метод для простого случая - проверка и перезапуск для одного устройства
     * при известном изменении разрешения
     */
    fun handleSingleDeviceResolutionChange(
        project: Project,
        device: IDevice,
        sizeBefore: Pair<Int, Int>?,
        sizeAfter: Pair<Int, Int>?,
        activeAppBeforeChange: Pair<String, String>? = null
    ): RestartResult {
        val context = checkResolutionChange(device, sizeBefore, sizeAfter)
        val activeAppsMap = if (activeAppBeforeChange != null) {
            mapOf(device to activeAppBeforeChange)
        } else {
            null
        }
        return handleResolutionChangeRestarts(
            project,
            listOf(device),
            mapOf(device to context),
            activeAppsMap
        )
    }
}