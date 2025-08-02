package io.github.qavlad.adbrandomizer.services.integration.androidstudio.constants

/**
 * Constants for Android Studio integration
 */
object AndroidStudioConstants {
    
    // Tool window names
    const val RUNNING_DEVICES_TOOL_WINDOW = "Running Devices"
    
    // Delays in milliseconds
    const val SHORT_DELAY = 200L
    const val MEDIUM_DELAY = 500L
    const val LONG_DELAY = 1000L
    const val TAB_CLOSE_DELAY = 300L
    
    // Component class names
    const val ACTION_BUTTON_CLASS_NAME = "ActionButton"
    const val TAB_PANEL_CLASS_NAME = "TabPanel"
    
    // Mirroring service class names to try
    val MIRRORING_SERVICE_CLASSES = listOf(
        "com.android.tools.idea.streaming.DeviceMirroringService",
        "com.android.tools.idea.streaming.device.DeviceMirroringService",
        "com.android.tools.idea.streaming.mirroring.DeviceMirroringService",
        "com.android.tools.idea.devicestreaming.DeviceMirroringService",
        "com.android.tools.idea.devicestreaming.DeviceStreamingService",
        "com.android.tools.idea.runningdevices.RunningDevicesService",
        "com.android.tools.idea.runningdevices.RunningDevicesManager",
        "com.android.tools.idea.streaming.RunningDevicesController",
        "com.android.tools.idea.devicestreaming.physical.PhysicalDeviceStreamingService",
        "com.android.tools.idea.runningdevices.physical.PhysicalDeviceController"
    )
    
    // Device manager class names to try
    val DEVICE_MANAGER_CLASSES = listOf(
        "com.android.tools.idea.devicemanager.DeviceManagerService",
        "com.android.tools.idea.devicemanager.physical.PhysicalDeviceManager",
        "com.android.tools.idea.devicemanager.DeviceManagerController",
        "com.android.tools.idea.devicemanager.RunningDevicesManager"
    )
    
    // Mirroring state class names to try
    val MIRRORING_STATE_CLASSES = listOf(
        "com.android.tools.idea.streaming.DeviceMirroringState",
        "com.android.tools.idea.streaming.device.DeviceMirroringState",
        "com.android.tools.idea.devicestreaming.StreamingState"
    )
    
    // NewTab action IDs
    val NEW_TAB_ACTION_IDS = listOf(
        "com.android.tools.idea.streaming.core.StreamingToolWindowManager\$NewTabAction",
        "StreamingToolWindowManager.NewTabAction",
        "RunningDevices.NewTab",
        "RunningDevices.AddDevice",
        "StreamingToolWindow.NewTab",
        "StreamingToolWindow.AddDevice",
        "Android.RunningDevices.NewTab",
        "Android.Streaming.NewTab",
        "DeviceMirroring.NewTab",
        "NewTabAction",
        "StreamingToolWindowManager\$NewTabAction",
        "NewTab"
    )
    
    // Close tab action IDs
    val CLOSE_TAB_ACTION_IDS = listOf(
        "CloseContent",
        "CloseTab",
        "CloseActiveTab",
        "\$CloseContent",
        "CloseEditor"
    )
    
    
    // Android device properties
    const val DEVICE_PROP_MANUFACTURER = "ro.product.manufacturer"
    const val DEVICE_PROP_MODEL = "ro.product.model"
    const val DEVICE_PROP_API_LEVEL = "ro.build.version.sdk"
    
    // Reflection field names
    const val ACTION_FIELD_NAME = "myAction"

    // Maximum component hierarchy depth for logging
    const val MAX_HIERARCHY_DEPTH = 5
    const val DETAILED_HIERARCHY_DEPTH = 10
}