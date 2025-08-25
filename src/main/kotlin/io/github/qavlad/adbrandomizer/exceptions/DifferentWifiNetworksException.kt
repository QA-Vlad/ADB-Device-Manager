package io.github.qavlad.adbrandomizer.exceptions

/**
 * Исключение, выбрасываемое когда устройство и компьютер находятся в разных Wi-Fi сетях
 */
class DifferentWifiNetworksException(message: String) : Exception(message)