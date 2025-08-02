package io.github.qavlad.adbrandomizer.services.integration.androidstudio

import io.github.qavlad.adbrandomizer.services.integration.androidstudio.constants.AndroidStudioConstants
import io.github.qavlad.adbrandomizer.utils.ConsoleLogger
import io.github.qavlad.adbrandomizer.utils.PluginLogger
import io.github.qavlad.adbrandomizer.utils.logging.LogCategory

/**
 * Service responsible for loading Android Studio internal classes via reflection
 */
class ClassLoaderService {
    
    private var mirroringServiceClass: Class<*>? = null
    private var deviceManagerClass: Class<*>? = null
    private var mirroringStateClass: Class<*>? = null
    
    /**
     * Loads Android Studio classes using reflection
     * @return true if at least some classes were loaded successfully
     */
    fun loadAndroidStudioClasses(): Boolean {
        return try {
            var loadedCount = 0
            
            // Try to load mirroring service classes
            mirroringServiceClass = tryLoadClass(
                AndroidStudioConstants.MIRRORING_SERVICE_CLASSES,
                "mirroring service"
            )
            if (mirroringServiceClass != null) loadedCount++
            
            // Try to load device manager classes
            deviceManagerClass = tryLoadClass(
                AndroidStudioConstants.DEVICE_MANAGER_CLASSES,
                "device manager"
            )
            if (deviceManagerClass != null) loadedCount++
            
            // Try to load state classes
            mirroringStateClass = tryLoadClass(
                AndroidStudioConstants.MIRRORING_STATE_CLASSES,
                "mirroring state"
            )
            if (mirroringStateClass != null) loadedCount++
            
            PluginLogger.info(
                LogCategory.GENERAL,
                "Android Studio classes scan completed. Loaded %d of 3 class types",
                loadedCount
            )
            
            loadedCount > 0
        } catch (e: Exception) {
            ConsoleLogger.logError("Error loading Android Studio classes", e)
            false
        }
    }
    
    /**
     * Tries to load a class from a list of possible class names
     */
    private fun tryLoadClass(classNames: List<String>, description: String): Class<*>? {
        for (className in classNames) {
            try {
                val clazz = Class.forName(className)
                PluginLogger.debug(
                    LogCategory.GENERAL,
                    "Successfully loaded %s class: %s",
                    description,
                    className
                )
                return clazz
            } catch (_: ClassNotFoundException) {
                // Continue trying other classes
            }
        }
        
        PluginLogger.debug(
            LogCategory.GENERAL,
            "Could not load any %s class",
            description
        )
        return null
    }
    
}