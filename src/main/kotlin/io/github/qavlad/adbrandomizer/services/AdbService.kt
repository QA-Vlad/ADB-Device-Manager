// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/AdbService.kt

package io.github.qavlad.adbrandomizer.services

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.util.concurrent.TimeUnit

object AdbService {

    fun getConnectedDevices(project: Project): List<IDevice> {
        // --- НАЧАЛО ПРАВИЛЬНОГО РЕШЕНИЯ ---

        // 1. Получаем ADB-мост, который уже используется в IDE.
        // AndroidSdkUtils.getDebugBridge(project) — это правильный и безопасный способ.
        // Он вернет существующий мост или null, если ADB не запущен.
        val bridge = AndroidSdkUtils.getDebugBridge(project)

        if (bridge == null) {
            println("ADB_Randomizer: AndroidDebugBridge is not available. ADB might not be started.")
            return emptyList()
        }

        // 2. Если мост найден, но еще не подключился к устройствам, дадим ему немного времени.
        var attempts = 10
        while (!bridge.hasInitialDeviceList() && attempts > 0) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                // Игнорируем
            }
            attempts--
        }

        // 3. Возвращаем список устройств, которые онлайн.
        return bridge.devices.filter { it.isOnline }

        // --- КОНЕЦ ПРАВИЛЬНОГО РЕШЕНИЯ ---
    }

    // Эта функция остается без изменений
    fun resetScreen(device: IDevice) {
        device.executeShellCommand("wm size reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
        device.executeShellCommand("wm density reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    fun setSize(device: IDevice, width: Int, height: Int) {
        val command = "wm size ${width}x${height}"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    // Новый метод для установки DPI
    fun setDpi(device: IDevice, dpi: Int) {
        val command = "wm density $dpi"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }
}

