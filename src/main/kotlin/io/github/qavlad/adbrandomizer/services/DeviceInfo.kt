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
        apiLevel = device.version.apiLevel.toString(),
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
            val nameParts = device.name.replace("_", " ").split('-')
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