package io.github.qavlad.adbdevicemanager.services

import io.github.qavlad.adbdevicemanager.utils.PluginLogger
import io.github.qavlad.adbdevicemanager.utils.logging.LogCategory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Сервис для проверки сетевой доступности устройств
 */
object NetworkConnectivityService {
    private val executor = Executors.newCachedThreadPool()
    
    data class ConnectivityCheckResult(
        val ipAddress: String,
        val port: Int,
        val isReachable: Boolean,
        val responseTime: Long? = null,
        val errorMessage: String? = null
    )

    /**
     * Проверяет доступность устройства по всем известным IP-адресам
     */
    fun checkDeviceConnectivity(
        deviceSerial: String,
        timeout: Int = 3000
    ): CompletableFuture<List<ConnectivityCheckResult>> {
        return CompletableFuture.supplyAsync({
            val results = mutableListOf<ConnectivityCheckResult>()
            
            try {
                // Извлекаем IP и порт из serial (формат: IP:PORT)
                val parts = deviceSerial.split(":")
                if (parts.size != 2) {
                    PluginLogger.warn(LogCategory.NETWORK, 
                        "Invalid device serial format for connectivity check: %s", deviceSerial)
                    return@supplyAsync results
                }
                
                val primaryIp = parts[0]
                val port = parts[1].toIntOrNull() ?: 5555
                
                
                // Собираем все известные IP адреса для устройства
                val ipsToCheck = mutableSetOf(primaryIp)
                
                // Добавляем все известные IP из истории
                val historicalIps = WifiDeviceHistoryService.getAllKnownIpAddresses(deviceSerial)
                ipsToCheck.addAll(historicalIps)
                
                PluginLogger.info(LogCategory.NETWORK, 
                    "Checking connectivity for device %s across %d IP addresses", 
                    deviceSerial, ipsToCheck.size)
                
                // Проверяем каждый IP
                for (ip in ipsToCheck) {
                    val result = checkSingleIp(ip, port, timeout)
                    results.add(result)
                    
                    if (result.isReachable) {
                        PluginLogger.info(LogCategory.NETWORK, 
                            "Device %s is reachable at %s:%d (response time: %d ms)", 
                            deviceSerial, ip, port, result.responseTime)
                    }
                }
                
            } catch (e: Exception) {
                PluginLogger.warn(LogCategory.NETWORK, 
                    "Error checking device connectivity: %s", e.message)
            }
            
            results
        }, executor)
    }
    
    /**
     * Проверяет доступность одного IP адреса
     */
    private fun checkSingleIp(ip: String, port: Int, timeout: Int): ConnectivityCheckResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Сначала пробуем ping
            val inetAddress = InetAddress.getByName(ip)
            val isPingable = inetAddress.isReachable(timeout)
            
            if (isPingable) {
                // Если ping прошел, проверяем доступность порта ADB
                val socket = Socket()
                socket.soTimeout = timeout
                
                try {
                    socket.connect(InetSocketAddress(ip, port), timeout)
                    socket.close()
                    
                    val responseTime = System.currentTimeMillis() - startTime
                    ConnectivityCheckResult(ip, port, true, responseTime)
                } catch (_: Exception) {
                    // Ping проходит, но порт недоступен
                    ConnectivityCheckResult(
                        ip, port, false, 
                        errorMessage = "Device is pingable but ADB port $port is not accessible"
                    )
                }
            } else {
                // Даже ping не проходит
                ConnectivityCheckResult(
                    ip, port, false,
                    errorMessage = "Device is not reachable (ping failed)"
                )
            }
        } catch (e: Exception) {
            ConnectivityCheckResult(
                ip, port, false,
                errorMessage = "Network error: ${e.message}"
            )
        }
    }
    
    /**
     * Проверяет доступность устройства через TCP соединение на ADB порт
     */
    fun checkAdbPortAvailability(ip: String, port: Int = 5555, timeout: Int = 2000): Boolean {
        return try {
            val socket = Socket()
            socket.soTimeout = timeout
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }


}