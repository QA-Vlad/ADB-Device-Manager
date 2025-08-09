package io.github.qavlad.adbrandomizer.ui.models

import io.github.qavlad.adbrandomizer.services.DeviceInfo

/**
 * Представляет объединённую информацию об устройстве, 
 * которое может быть подключено одновременно по USB и Wi-Fi
 */
data class CombinedDeviceInfo(
    val baseSerialNumber: String, // Основной серийный номер устройства (без IP для Wi-Fi)
    val displayName: String,
    val androidVersion: String,
    val apiLevel: String,
    val usbDevice: DeviceInfo? = null,
    val wifiDevice: DeviceInfo? = null,
    val ipAddress: String? = null, // IP адрес устройства (может быть доступен и для USB)
    val currentResolution: Pair<Int, Int>? = null, // width x height
    val currentDpi: Int? = null,
    val defaultResolution: Pair<Int, Int>? = null,
    val defaultDpi: Int? = null
) {

    /**
     * Проверяет, есть ли USB подключение
     */
    val hasUsbConnection: Boolean
        get() = usbDevice != null
    
    /**
     * Проверяет, есть ли Wi-Fi подключение
     */
    val hasWifiConnection: Boolean
        get() = wifiDevice != null

    /**
     * Проверяет, изменены ли параметры экрана от дефолтных
     */
    val hasModifiedResolution: Boolean
        get() = currentResolution != null && defaultResolution != null && 
                currentResolution != defaultResolution
    
    /**
     * Проверяет, изменён ли DPI от дефолтного
     */
    val hasModifiedDpi: Boolean
        get() = currentDpi != null && defaultDpi != null && 
                currentDpi != defaultDpi
    
    /**
     * Форматирует текущее разрешение для отображения
     */
    fun getFormattedResolution(): String? {
        return currentResolution?.let { "${it.first}x${it.second}" }
    }
    
    /**
     * Форматирует текущие параметры экрана для отображения
     */
    fun getFormattedScreenParams(): String? {
        val resolution = getFormattedResolution()
        val dpi = currentDpi?.toString()
        
        return when {
            resolution != null && dpi != null -> "$resolution • ${dpi}dpi"
            resolution != null -> resolution
            dpi != null -> "${dpi}dpi"
            else -> null
        }
    }

}