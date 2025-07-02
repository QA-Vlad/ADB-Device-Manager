package io.github.qavlad.adbrandomizer.utils

object ValidationUtils {

    /**
     * Валидирует IP адрес
     * @param ip строка для проверки
     * @return true если IP адрес валидный, false иначе
     */
    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false

        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (_: NumberFormatException) {
                false
            }
        }
    }

    /**
     * Валидирует порт
     * @param port номер порта для проверки
     * @return true если порт валидный, false иначе
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * Валидирует порт из строки
     * @param portString строка с портом
     * @return true если порт валидный, false иначе
     */
    fun isValidPortString(portString: String): Boolean {
        return try {
            val port = portString.trim().toInt()
            isValidPort(port)
        } catch (_: NumberFormatException) {
            false
        }
    }

    /**
     * Валидирует формат размера экрана (например, "1080x1920")
     * @param size строка с размером
     * @return true если формат валидный, false иначе
     */
    fun isValidSizeFormat(size: String): Boolean {
        if (size.isBlank()) return false

        val sizeRegex = Regex("""^\d+\s*[xхXХ]\s*\d+$""")
        return sizeRegex.matches(size.trim())
    }

    /**
     * Валидирует DPI (должно быть положительным числом)
     * @param dpi строка с DPI
     * @return true если DPI валидный, false иначе
     */
    fun isValidDpi(dpi: String): Boolean {
        if (dpi.isBlank()) return false

        return try {
            val dpiValue = dpi.trim().toInt()
            dpiValue > 0
        } catch (_: NumberFormatException) {
            false
        }
    }

    /**
     * Парсит размер из строки в пару (width, height)
     * @param size строка с размером (например, "1080x1920")
     * @return Pair<Int, Int>? или null если не удалось распарсить
     */
    fun parseSize(size: String): Pair<Int, Int>? {
        if (!isValidSizeFormat(size)) return null

        return try {
            val parts = size.split('x', 'X', 'х', 'Х').map { it.trim() }
            val width = parts[0].toInt()
            val height = parts[1].toInt()
            Pair(width, height)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Парсит DPI из строки
     * @param dpi строка с DPI
     * @return Int? или null если не удалось распарсить
     */
    fun parseDpi(dpi: String): Int? {
        return if (isValidDpi(dpi)) {
            try {
                dpi.trim().toInt()
            } catch (_: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Валидирует TCP порт для ADB (должен быть >= 1024)
     * @param port номер порта
     * @return true если порт подходит для ADB, false иначе
     */
    fun isValidAdbPort(port: Int): Boolean {
        return port in 1024..65535
    }

    /**
     * Валидирует строку подключения вида "IP:PORT"
     * @param connectionString строка подключения
     * @return true если формат валидный, false иначе
     */
    fun isValidConnectionString(connectionString: String): Boolean {
        if (connectionString.isBlank()) return false

        val parts = connectionString.trim().split(":")
        if (parts.size != 2) return false

        val ip = parts[0].trim()
        val portString = parts[1].trim()

        return isValidIpAddress(ip) && isValidPortString(portString)
    }

    /**
     * Парсит строку подключения в пару (IP, Port)
     * @param connectionString строка вида "192.168.1.100:5555"
     * @return Pair<String, Int>? или null если не удалось распарсить
     */
    fun parseConnectionString(connectionString: String): Pair<String, Int>? {
        if (!isValidConnectionString(connectionString)) return null

        return try {
            val parts = connectionString.trim().split(":")
            val ip = parts[0].trim()
            val port = parts[1].trim().toInt()
            Pair(ip, port)
        } catch (_: Exception) {
            null
        }
    }
}