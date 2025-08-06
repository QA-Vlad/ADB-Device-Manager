package io.github.qavlad.adbrandomizer.exceptions

/**
 * Исключение, выбрасываемое когда устройство требует ручного переключения Wi-Fi сети
 * из-за отсутствия root доступа
 */
class ManualWifiSwitchRequiredException(message: String) : Exception(message)