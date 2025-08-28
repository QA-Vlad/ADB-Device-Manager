package io.github.qavlad.adbdevicemanager.ui.models

import io.github.qavlad.adbdevicemanager.services.DeviceInfo

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
    val defaultDpi: Int? = null,
    var isSelectedForAdb: Boolean = true // По умолчанию выбрано для ADB команд
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
     * Проверяет, загружаются ли текущие параметры
     * (есть дефолтные, но нет текущих)
     */
    val isLoadingCurrentParams: Boolean
        get() = (defaultResolution != null || defaultDpi != null) && 
                currentResolution == null && currentDpi == null
}