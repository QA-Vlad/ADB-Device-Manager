package io.github.qavlad.adbdevicemanager.ui.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.PathManager
import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.awt.Rectangle
import java.io.File
import java.io.InputStreamReader

/**
 * Управление конфигурацией позиций хитбоксов
 */
object HitboxConfigManager {
    private const val CONFIG_FILE_NAME = "hitbox-config.json"
    private const val RESOURCE_PATH = "/hitbox-config.json"
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var config: HitboxConfig? = null
    private val configFile: File by lazy {
        File(PathManager.getConfigPath(), "AdbRandomizer/$CONFIG_FILE_NAME")
    }
    
    /**
     * Загружает конфигурацию из файла или из ресурсов
     */
    fun loadConfig(): HitboxConfig {
        if (config != null) return config!!
        
        config = try {
            // Пытаемся загрузить пользовательскую конфигурацию
            if (configFile.exists()) {
                PluginLogger.debug(LogCategory.UI_EVENTS, "Loading hitbox config from: %s", configFile.absolutePath)
                gson.fromJson(configFile.readText(), HitboxConfig::class.java)
            } else {
                // Загружаем дефолтную конфигурацию из ресурсов
                loadDefaultConfig()
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load hitbox config: %s", e, e.message)
            loadDefaultConfig()
        }
        
        return config!!
    }
    
    /**
     * Загружает дефолтную конфигурацию из ресурсов
     */
    private fun loadDefaultConfig(): HitboxConfig {
        return try {
            val inputStream = HitboxConfigManager::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: throw IllegalStateException("Default hitbox config not found in resources")
            
            InputStreamReader(inputStream).use { reader ->
                gson.fromJson(reader, HitboxConfig::class.java)
            }
        } catch (e: Exception) {
            PluginLogger.error(LogCategory.UI_EVENTS, "Failed to load default hitbox config: %s", e, e.message)
            // Возвращаем хардкоженные значения как последний резерв
            createFallbackConfig()
        }
    }

    /**
     * Получает Rectangle для хитбокса
     */
    fun getHitboxRect(deviceType: DeviceType, hitboxType: HitboxType, cellBounds: Rectangle): Rectangle? {
        val cfg = config ?: loadConfig()
        
        return when (deviceType) {
            DeviceType.CONNECTED -> {
                val hitbox = when (hitboxType) {
                    HitboxType.CHECKBOX -> cfg.connectedDevices.checkbox
                    HitboxType.RESET_SIZE -> cfg.connectedDevices.resetSize
                    HitboxType.RESET_DPI -> cfg.connectedDevices.resetDpi
                    HitboxType.EDIT_SIZE -> cfg.connectedDevices.editSize
                    HitboxType.EDIT_DPI -> cfg.connectedDevices.editDpi
                    HitboxType.USB_MIRROR -> cfg.connectedDevices.usbMirror
                    HitboxType.WIFI_MIRROR -> cfg.connectedDevices.wifiMirror
                    HitboxType.WIFI_CONNECT -> cfg.connectedDevices.wifiConnect
                    HitboxType.WIFI_DISCONNECT -> cfg.connectedDevices.wifiDisconnect
                    HitboxType.DEFAULT_SIZE_TOOLTIP -> cfg.connectedDevices.defaultSizeTooltip ?: return null
                    HitboxType.DEFAULT_DPI_TOOLTIP -> cfg.connectedDevices.defaultDpiTooltip ?: return null
                    HitboxType.USB_ICON_TOOLTIP -> cfg.connectedDevices.usbIconTooltip ?: return null
                    HitboxType.WIFI_ICON_TOOLTIP -> cfg.connectedDevices.wifiIconTooltip ?: return null
                    else -> return null
                }
                
                hitbox.toRectangle(cellBounds)
            }
            DeviceType.PREVIOUSLY_CONNECTED -> {
                val hitbox = when (hitboxType) {
                    HitboxType.CONNECT -> cfg.previouslyConnected.connect
                    HitboxType.DELETE -> cfg.previouslyConnected.delete
                    HitboxType.GROUP_INDICATOR -> cfg.previouslyConnected.groupIndicator ?: return null
                    else -> return null
                }
                
                hitbox.toRectangle(cellBounds)
            }
        }
    }

    /**
     * Создает резервную конфигурацию с хардкоженными значениями
     */
    private fun createFallbackConfig(): HitboxConfig {
        return HitboxConfig(
            connectedDevices = ConnectedDevicesHitboxes(
                checkbox = HitboxRect(x = 8, y = 10, width = 20, height = 20),
                resetSize = HitboxRect(xOffset = 180, yOffset = 52, width = 16, height = 16),
                resetDpi = HitboxRect(xOffset = 292, yOffset = 52, width = 16, height = 16),
                editSize = HitboxRect(xOffset = 94, yOffset = 75, width = 101, height = 20),
                editDpi = HitboxRect(xOffset = 241, yOffset = 75, width = 71, height = 20),
                usbMirror = HitboxRect(xOffset = 75, yFromBottom = 32, width = 22, height = 22),
                wifiMirror = HitboxRect(xOffset = 235, yFromBottom = 32, width = 22, height = 22),
                wifiConnect = HitboxRect(xOffset = 190, yFromBottom = 32, width = 65, height = 22),
                wifiDisconnect = HitboxRect(xOffset = 262, yFromBottom = 32, width = 70, height = 22),
                defaultSizeTooltip = HitboxRect(xOffset = 100, yOffset = 43, width = 80, height = 20),
                defaultDpiTooltip = HitboxRect(xOffset = 245, yOffset = 43, width = 55, height = 20),
                usbIconTooltip = HitboxRect(xOffset = 8, yFromBottom = 35, width = 70, height = 30),
                wifiIconTooltip = HitboxRect(xOffset = 135, yFromBottom = 35, width = 60, height = 30)
            ),
            previouslyConnected = PreviouslyConnectedHitboxes(
                connect = HitboxRect(xFromRight = 48, yCenter = true, width = 65, height = 22), // 5 (padding) + 35 (delete) + 8 (gap) = 48
                delete = HitboxRect(xFromRight = 5, yCenter = true, width = 35, height = 25), // 5 (padding) = 5
                groupIndicator = HitboxRect(xOffset = 280, yOffset = 20, width = 35, height = 18)
            )
        )
    }
}

/**
 * Типы устройств
 */
enum class DeviceType {
    CONNECTED,
    PREVIOUSLY_CONNECTED
}

/**
 * Типы хитбоксов
 */
enum class HitboxType {
    CHECKBOX,
    RESET_SIZE,
    RESET_DPI,
    EDIT_SIZE,
    EDIT_DPI,
    USB_MIRROR,
    WIFI_MIRROR,
    WIFI_CONNECT,
    WIFI_DISCONNECT,
    CONNECT,
    DELETE,
    GROUP_INDICATOR,
    DEFAULT_SIZE_TOOLTIP,
    DEFAULT_DPI_TOOLTIP,
    USB_ICON_TOOLTIP,
    WIFI_ICON_TOOLTIP
}

/**
 * Основная конфигурация хитбоксов
 */
data class HitboxConfig(
    @SerializedName("connectedDevices")
    val connectedDevices: ConnectedDevicesHitboxes,
    
    @SerializedName("previouslyConnected")
    val previouslyConnected: PreviouslyConnectedHitboxes
)

/**
 * Хитбоксы для подключенных устройств
 */
data class ConnectedDevicesHitboxes(
    val checkbox: HitboxRect,
    val resetSize: HitboxRect,
    val resetDpi: HitboxRect,
    val editSize: HitboxRect,
    val editDpi: HitboxRect,
    val usbMirror: HitboxRect,
    val wifiMirror: HitboxRect,
    val wifiConnect: HitboxRect,
    val wifiDisconnect: HitboxRect,
    val defaultSizeTooltip: HitboxRect? = null,
    val defaultDpiTooltip: HitboxRect? = null,
    val usbIconTooltip: HitboxRect? = null,
    val wifiIconTooltip: HitboxRect? = null
)

/**
 * Хитбоксы для ранее подключенных устройств
 */
data class PreviouslyConnectedHitboxes(
    val connect: HitboxRect,
    val delete: HitboxRect,
    val groupIndicator: HitboxRect? = null
)

/**
 * Прямоугольник хитбокса с гибкими координатами
 */
data class HitboxRect(
    var x: Int? = null,           // Абсолютная X координата
    var y: Int? = null,           // Абсолютная Y координата
    var xOffset: Int? = null,     // Смещение от левого края ячейки
    var xFromRight: Int? = null,  // Смещение от правого края ячейки
    var yOffset: Int? = null,     // Смещение от верхнего края ячейки
    var yFromBottom: Int? = null, // Смещение от нижнего края ячейки
    var yCenter: Boolean? = null, // Центрировать по вертикали
    val width: Int,
    val height: Int
) {
    /**
     * Преобразует в Rectangle с учетом границ ячейки
     */
    fun toRectangle(cellBounds: Rectangle): Rectangle {
        val rectX = when {
            x != null -> x!!
            xOffset != null -> cellBounds.x + xOffset!!
            xFromRight != null -> cellBounds.x + cellBounds.width - xFromRight!!
            else -> cellBounds.x
        }
        
        val rectY = when {
            y != null -> y!!
            yOffset != null -> cellBounds.y + yOffset!!
            yFromBottom != null -> cellBounds.y + cellBounds.height - yFromBottom!!
            yCenter == true -> cellBounds.y + (cellBounds.height - height) / 2
            else -> cellBounds.y
        }
        
        return Rectangle(rectX, rectY, width, height)
    }
}