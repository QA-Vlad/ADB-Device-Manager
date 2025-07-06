package io.github.qavlad.adbrandomizer.config

object PluginConfig {
    // ADB Configuration
    object Adb {
        const val CONNECTION_TIMEOUT_MS = 10_000L
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val BRIDGE_CONNECTION_ATTEMPTS = 100
        const val BRIDGE_CONNECTION_DELAY_MS = 200L
        const val SERVER_START_TIMEOUT_SECONDS = 5L
        const val DEVICE_LIST_WAIT_ATTEMPTS = 20
        const val DEVICE_LIST_WAIT_DELAY_MS = 100L
    }
    
    // UI Configuration
    object UI {
        const val DEVICE_POLLING_INTERVAL_MS = 5000
        const val TOOLTIP_INITIAL_DELAY_MS = 100
        const val TOOLTIP_DISMISS_DELAY_MS = 5000
        const val TOOLTIP_RESHOW_DELAY_MS = 50
        const val TABLE_ROW_HEIGHT = 35
        const val SETTINGS_DIALOG_WIDTH = 650
        const val SETTINGS_DIALOG_HEIGHT = 400
        const val NOTIFICATION_GROUP_ID = "ADB Randomizer Notifications"
    }
    
    // Scrcpy Configuration
    object Scrcpy {
        const val STARTUP_WAIT_MS = 2000L
        const val PROCESS_CHECK_DELAY_MS = 3000L
        const val VERSION_CHECK_TIMEOUT_SECONDS = 3L
        const val MAX_LOG_LINES = 20
        val SCRCPY_NAMES = mapOf(
            "windows" to "scrcpy.exe",
            "default" to "scrcpy"
        )
    }
    
    // Network Configuration
    object Network {
        const val DEFAULT_ADB_PORT = 5555
        const val MIN_ADB_PORT = 1024
        const val MAX_PORT = 65535
        val WIFI_INTERFACES = listOf("wlan0", "wlan1", "wlan2", "eth0", "rmnet_data0")
        const val TCPIP_ENABLE_DELAY_MS = 1000L
        const val WIFI_CONNECTION_VERIFY_ATTEMPTS = 10
        const val WIFI_CONNECTION_VERIFY_DELAY_MS = 1000L
        const val CONNECTION_VERIFY_DELAY_MS = 1000L
        const val DISCONNECT_WAIT_MS = 500L
    }
    
    // Device Properties
    object DeviceProperties {
        val SERIAL_NUMBER_PROPERTIES = listOf(
            "ro.serialno", 
            "ro.boot.serialno", 
            "gsm.sn1", 
            "ril.serialnumber"
        )
    }
    
    // Default Presets
    object DefaultPresets {
        val PRESETS = listOf(
            Triple("Pixel 5", "1080x2340", "432"),
            Triple("Pixel 3a", "1080x2220", "441"),
            Triple("Generic Tablet", "1200x1920", "240")
        )
        
        val COMMON_DEVICES = listOf(
            Triple("Pixel 6 Pro", "1440x3120", "512"),
            Triple("Pixel 5", "1080x2340", "432"),
            Triple("Pixel 4a", "1080x2340", "413"),
            Triple("Samsung Galaxy S21", "1080x2400", "421"),
            Triple("OnePlus 9 Pro", "1440x3216", "525")
        )
    }
    
    // Regex Patterns
    object Patterns {
        val WIFI_SERIAL_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")
        val SIZE_FORMAT_REGEX = Regex("""^\d+\s*[xхXХ]\s*\d+$""")
        val IP_PATTERN = """inet (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})"""
        val NETCFG_PATTERN = """wlan\d+\s+UP\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})"""
        val IFCONFIG_PATTERN = """inet addr:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})"""
        val SIZE_OUTPUT_PATTERN = """(?:Physical|Override) size: (\d+)x(\d+)"""
        val DPI_OUTPUT_PATTERN = """(?:Physical|Override) density: (\d+)"""
    }
    
    // Settings Keys
    object SettingsKeys {
        const val PRESETS_KEY = "ADB_RANDOMIZER_PRESETS_JSON"
        const val SCRCPY_PATH_KEY = "ADB_RANDOMIZER_SCRCPY_PATH"
    }
    
    // UI Constants
    object UIConstants {
        const val RETRY_EXIT_CODE = 101
        const val BUTTON_TYPE_MIRROR = "MIRROR"
        const val BUTTON_TYPE_WIFI = "WIFI"
    }
    
    // Feature Flags (для будущего)
    object Features {
        const val ENABLE_DEVICE_LOGS = true
        const val ENABLE_AUTO_RECONNECT = false
        const val ENABLE_CRASH_REPORTING = false
    }
} 