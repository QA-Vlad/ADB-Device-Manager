package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.qavlad.adbrandomizer.utils.NotificationUtils
import io.github.qavlad.adbrandomizer.utils.ValidationUtils

object PresetApplicationService {
    
    fun applyPreset(project: Project, preset: DevicePreset, setSize: Boolean, setDpi: Boolean) {
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
                
                applyPresetToAllDevices(devices, preset, presetData, indicator)
                
                // Обновляем состояние устройств после применения пресета
                updateDeviceStatesAfterPresetApplication(devices, presetData)
                
                // Отслеживаем какой пресет был применен (сохраняем полную копию текущего состояния пресета)
                val appliedSizePreset = if (setSize) preset.copy() else null
                val appliedDpiPreset = if (setDpi) preset.copy() else null
                
                DeviceStateService.setLastAppliedPresets(appliedSizePreset, appliedDpiPreset)
                
                // Немедленно уведомляем об обновлении до UI обновлений
                SettingsDialogUpdateNotifier.notifyUpdate()
                
                ApplicationManager.getApplication().invokeLater {
                    // Ищем пресет по label, а не по точному совпадению
                    val savedPresets = SettingsService.getPresets()
                    val presetIndex = savedPresets.indexOfFirst { it.label == preset.label }
                    val presetNumber = if (presetIndex >= 0) presetIndex + 1 else 1
                    
                    showPresetApplicationResult(project, preset, presetNumber, presetData.appliedSettings)
                    
                    // Уведомляем все открытые диалоги настроек об обновлении
                    SettingsDialogUpdateNotifier.notifyUpdate()
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
    
    private fun applyPresetToAllDevices(devices: List<IDevice>, preset: DevicePreset, presetData: PresetData, indicator: ProgressIndicator) {
        devices.forEach { device ->
            indicator.text = "Applying '${preset.label}' to ${device.name}..."
            
            if (presetData.width != null && presetData.height != null) {
                AdbService.setSize(device, presetData.width, presetData.height)
            }
            
            if (presetData.dpi != null) {
                AdbService.setDpi(device, presetData.dpi)
            }
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