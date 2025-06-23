// Файл: src/main/kotlin/io/github/qavlad/adbrandomizer/services/AdbService.kt

package io.github.qavlad.adbrandomizer.services
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.util.concurrent.TimeUnit

object AdbService {

    fun getConnectedDevices(project: Project): List<IDevice> {
        var bridge: AndroidDebugBridge? = null

        // --- НАЧАЛО ИСПРАВЛЕНИЯ ---

        // Проблема: AndroidSdkUtils.getDebugBridge() должен вызываться из главного потока (EDT).
        // Решение: Мы используем invokeAndWait, чтобы безопасно выполнить этот вызов из любого потока.
        // Он заставит текущий (фоновый) поток дождаться, пока код внутри лямбды выполнится в EDT.
        ApplicationManager.getApplication().invokeAndWait {
            bridge = AndroidSdkUtils.getDebugBridge(project)
        }

        // --- КОНЕЦ ИСПРАВЛЕНИЯ ---

        if (bridge == null) {
            println("ADB_Randomizer: AndroidDebugBridge is not available. ADB might not be started.")
            return emptyList()
        }

        // 2. Если мост найден, но еще не подключился к устройствам, дадим ему немного времени.
        // Эта часть кода выполняется в том же потоке, из которого была вызвана getConnectedDevices.
        var attempts = 10
        while (!bridge!!.hasInitialDeviceList() && attempts > 0) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                // Игнорируем
            }
            attempts--
        }

        // 3. Возвращаем список устройств, которые онлайн.
        return bridge!!.devices.filter { it.isOnline }
    }

    // Сброс только размера экрана
    fun resetSize(device: IDevice) {
        device.executeShellCommand("wm size reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    // Сброс только DPI
    fun resetDpi(device: IDevice) {
        device.executeShellCommand("wm density reset", NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    // Установка размера экрана
    fun setSize(device: IDevice, width: Int, height: Int) {
        val command = "wm size ${width}x${height}"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }

    // Установка DPI
    fun setDpi(device: IDevice, dpi: Int) {
        val command = "wm density $dpi"
        device.executeShellCommand(command, NullOutputReceiver(), 15, TimeUnit.SECONDS)
    }
}
