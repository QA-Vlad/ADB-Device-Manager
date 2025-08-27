package io.github.qavlad.adbrandomizer.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "io.github.qavlad.adbrandomizer.settings.PluginSettings",
    storages = [Storage("AdbRandomizerSettings.xml")]
)
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings> {
    
    // Настройка для автоматического перезапуска scrcpy при изменении разрешения
    var restartScrcpyOnResolutionChange: Boolean = true
    
    // Настройка для автоматического перезапуска Running Devices при изменении разрешения (только Android Studio)
    var restartRunningDevicesOnResolutionChange: Boolean = true
    
    // Настройка для автоматического перезапуска активного приложения при изменении разрешения
    var restartActiveAppOnResolutionChange: Boolean = false
    
    // Настройка для автоматического переключения устройства на Wi-Fi сеть ПК при подключении
    var autoSwitchToHostWifi: Boolean = true
    
    // Настройка для автоматического переключения ПК на Wi-Fi сеть устройства при подключении  
    var autoSwitchPCWifi: Boolean = false
    
    // Настройка для включения режима отладки
    var debugMode: Boolean = false
    
    // Настройка для включения визуальной отладки хитбоксов
    var debugHitboxes: Boolean = false
    
    // Настройки для эмуляции отсутствия ADB/Scrcpy (для тестирования)
    var debugSimulateAdbNotFound: Boolean = false
    var debugSimulateScrcpyNotFound: Boolean = false
    
    // Пользовательские флаги для scrcpy
    var scrcpyCustomFlags: String = "--show-touches --stay-awake --always-on-top"
    
    // Путь к scrcpy (может быть путь к exe файлу или к папке)
    var scrcpyPath: String = ""
    
    // Путь к ADB (может быть путь к exe файлу или к папке)
    var adbPath: String = ""
    
    // Порт для TCP/IP подключения к устройствам (по умолчанию 5555)
    var adbPort: Int = 5555
    
    override fun getState(): PluginSettings = this
    
    override fun loadState(state: PluginSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        val instance: PluginSettings
            get() = service()
    }
}