package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.IDevice
import io.github.qavlad.adbrandomizer.utils.DeviceConnectionUtils
import java.util.Locale

data class DeviceInfo(
    val device: IDevice?,
    val displayName: String,
    val displaySerialNumber: String?,
    val logicalSerialNumber: String,
    val androidVersion: String,
    val apiLevel: String,
    val ipAddress: String?
) {

    constructor(device: IDevice, ipAddress: String?) : this(
        device = device,
        displayName = createDisplayName(device),
        displaySerialNumber = getDisplaySerialNumber(device),
        logicalSerialNumber = device.serialNumber,
        androidVersion = device.getProperty(IDevice.PROP_BUILD_VERSION) ?: "Unknown",
        apiLevel = device.getProperty(IDevice.PROP_BUILD_API_LEVEL) ?: "Unknown",
        ipAddress = ipAddress
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceInfo
        return logicalSerialNumber == other.logicalSerialNumber
    }

    override fun hashCode(): Int = logicalSerialNumber.hashCode()



    companion object {
        private fun createDisplayName(device: IDevice): String {
            // Для Wi-Fi устройств пытаемся получить имя из истории
            if (DeviceConnectionUtils.isWifiConnection(device.serialNumber)) {
                val ipAddress = device.serialNumber.substringBefore(":")
                val history = WifiDeviceHistoryService.getHistory()
                val historyEntry = history.find { 
                    it.ipAddress == ipAddress || it.logicalSerialNumber == device.serialNumber
                }
                
                // Для отладки логируем создание имени для Wi-Fi устройств
                println("ADB_Randomizer: [DeviceInfo] Creating display name for Wi-Fi device:")
                println("ADB_Randomizer: [DeviceInfo]   - device.name: ${device.name}")
                println("ADB_Randomizer: [DeviceInfo]   - device.serialNumber: ${device.serialNumber}")
                println("ADB_Randomizer: [DeviceInfo]   - historyEntry found: ${historyEntry != null}")
                
                if (historyEntry != null && historyEntry.displayName.isNotBlank()) {
                    println("ADB_Randomizer: [DeviceInfo]   - historyEntry.displayName: ${historyEntry.displayName}")
                    println("ADB_Randomizer: [DeviceInfo]   - historyEntry.realSerialNumber: ${historyEntry.realSerialNumber}")
                    
                    // Проверяем, не содержит ли displayName серийный номер
                    var cleanDisplayName = historyEntry.displayName
                    
                    // Убираем серийный номер если он есть в конце имени
                    if (historyEntry.realSerialNumber != null && cleanDisplayName.contains(historyEntry.realSerialNumber)) {
                        cleanDisplayName = cleanDisplayName.replace(historyEntry.realSerialNumber, "").trim()
                    }
                    
                    // Убираем IP:PORT если есть
                    val beforeIpClean = cleanDisplayName
                    cleanDisplayName = cleanDisplayName
                        .replace(Regex("\\s*\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+"), "")
                        .replace(Regex("\\s*\\d+\\.\\d+\\.\\d+\\.\\d+"), "")
                        .trim()
                    
                    if (beforeIpClean != cleanDisplayName) {
                        println("ADB_Randomizer: [DeviceInfo]   - After removing IP: $cleanDisplayName")
                    }
                    
                    if (cleanDisplayName.isNotBlank()) {
                        println("ADB_Randomizer: [DeviceInfo]   - Final display name from history: $cleanDisplayName")
                        return cleanDisplayName
                    }
                }
                
                // Если в истории нет, пытаемся извлечь имя из device.name
                // Для Wi-Fi устройств device.name может быть типа "finepower_c3-192.168.1.132:5555" или "finepower_c3-TMMPH9252N201505"
                val deviceName = device.name
                println("ADB_Randomizer: [DeviceInfo]   - Extracting from device.name: $deviceName")
                
                // Получаем реальный серийный номер устройства
                val realSerial = getDeviceRealSerial(device) ?: device.serialNumber.substringBefore(":")
                
                // Убираем IP:PORT паттерн из имени
                var cleanedName = deviceName
                    .replace(Regex("-\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+"), "") // Убираем -IP:PORT
                    .replace(Regex("\\s+\\d+\\.\\d+\\.\\d+\\.\\d+$"), "") // Убираем IP в конце
                
                // Убираем реальный серийный номер из имени, если он там есть
                if (realSerial != device.serialNumber && cleanedName.contains(realSerial)) {
                    cleanedName = cleanedName.replace(realSerial, "").replace("--", "-").trim('-')
                    println("ADB_Randomizer: [DeviceInfo]   - Removed serial $realSerial from name")
                }
                
                // Убираем любые длинные серийные номера в конце (10+ символов)
                cleanedName = cleanedName.replace(Regex("\\s+[A-Z0-9]{10,}$"), "")
                
                println("ADB_Randomizer: [DeviceInfo]   - After cleaning: $cleanedName")
                
                // Если после очистки имя пустое, используем базовую обработку
                if (cleanedName.isBlank()) {
                    println("ADB_Randomizer: [DeviceInfo]   - Cleaned name is blank, returning serial")
                    return device.serialNumber
                }
                
                // Форматируем очищенное имя
                val nameParts = cleanedName.replace("_", " ").split('-')
                val manufacturer = nameParts.getOrNull(0)?.replaceFirstChar { 
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
                } ?: ""
                val model = nameParts.getOrNull(1)?.uppercase(Locale.getDefault()) ?: ""
                val result = "$manufacturer $model".trim()
                println("ADB_Randomizer: [DeviceInfo]   - Final formatted name: $result")
                return if (result.isNotBlank()) result else device.serialNumber
            }
            
            // Для USB устройств используем стандартную логику
            // Сначала получаем реальный серийный номер устройства
            val realSerial = getDeviceRealSerial(device) ?: device.serialNumber
            
            // Убираем серийный номер из имени устройства если он там есть
            var cleanedName = device.name
            if (cleanedName.contains(realSerial)) {
                cleanedName = cleanedName.replace(realSerial, "").replace("--", "-").trim('-')
            }
            
            val nameParts = cleanedName.replace("_", " ").split('-')
            val manufacturer = nameParts.getOrNull(0)?.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
            } ?: ""
            val model = nameParts.getOrNull(1)?.uppercase(Locale.getDefault()) ?: ""
            return "$manufacturer $model".trim()
        }

        private fun getDisplaySerialNumber(device: IDevice): String? {
            val logicalSerial = device.serialNumber
            return if (DeviceConnectionUtils.isWifiConnection(logicalSerial)) {
                getDeviceRealSerial(device) ?: logicalSerial
            } else {
                logicalSerial
            }
        }

        private fun getDeviceRealSerial(device: IDevice): String? {
            val properties = listOf("ro.serialno", "ro.boot.serialno", "gsm.sn1", "ril.serialnumber")

            for (property in properties) {
                try {
                    val serial = device.getProperty(property)
                    if (!serial.isNullOrBlank() && serial != "unknown" && serial.length > 3) {
                        return serial
                    }
                } catch (_: Exception) {
                    // Игнорируем ошибки и пробуем следующее свойство
                }
            }
            return null
        }
    }
}