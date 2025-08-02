package io.github.qavlad.adbrandomizer.utils

import com.intellij.openapi.application.ApplicationInfo

/**
 * Utility to detect if the plugin is running in Android Studio environment
 */
object AndroidStudioDetector {
    
    private var cachedResult: Boolean? = null
    
    /**
     * Checks if the plugin is running in Android Studio
     * Uses multiple detection methods for reliability
     */
    fun isAndroidStudio(): Boolean {
        // Return cached result if available
        cachedResult?.let { return it }
        
        val result = try {
            // Method 1: Check application name
            val appInfo = ApplicationInfo.getInstance()
            val isAndroidStudioByName = appInfo.fullApplicationName.contains("Android Studio", ignoreCase = true)
            
            // Method 2: Check for Android-specific classes
            val hasAndroidClasses = checkAndroidClasses()
            
            // Method 3: Check build number prefix (Android Studio usually starts with "AI-")
            val isAndroidStudioByBuild = appInfo.build.asString().startsWith("AI-")
            
            // Return true if any method confirms Android Studio
            isAndroidStudioByName || hasAndroidClasses || isAndroidStudioByBuild
        } catch (e: Exception) {
            PluginLogger.debug("Error detecting Android Studio environment: ${e.message}")
            false
        }
        
        // Cache the result
        cachedResult = result
        PluginLogger.info("Android Studio environment detected: $result")
        return result
    }
    
    /**
     * Checks for Android-specific classes using reflection
     */
    private fun checkAndroidClasses(): Boolean {
        val androidClasses = listOf(
            "com.android.tools.idea.devicemanager.DeviceManagerService",
            "com.android.tools.idea.ddms.DevicePanel",
            "com.android.tools.idea.run.AndroidDevice",
            "com.android.tools.idea.streaming.DeviceMirroringService"
        )
        
        return androidClasses.any { className ->
            try {
                Class.forName(className)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }

}