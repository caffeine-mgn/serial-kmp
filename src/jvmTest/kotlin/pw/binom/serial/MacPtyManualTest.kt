package pw.binom.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Живой прогон JVM-порта через уже поднятый pty (macOS/ручная проверка).
 *
 * Тест ожидает, что в `/tmp/pty.txt` лежит путь псевдотерминала, а на другом
 * конце петли работает эхо (python-скрипт с `pty.openpty()`).
 */
class MacPtyManualTest {

    @Test
    fun roundTripThroughExternalPty() {
        val pathFile = java.io.File("/tmp/pty.txt")
        if (!pathFile.exists()) return
        val path = pathFile.readText().trim()
        if (path.isEmpty()) return

        val config = SerialConfig(readTimeoutMs = 3_000, writeTimeoutMs = 3_000)
        openSerialPort(path, config).use { port ->
            assertTrue(port.isOpen, "port must be open")
            val payload = "MAC-C-PING".encodeToByteArray()
            port.write(payload)

            val buffer = ByteArray(payload.size)
            val read = port.read(buffer, 0, payload.size)
            assertEquals(payload.size, read)
            assertEquals("MAC-C-PING", buffer.decodeToString())
        }
    }
}
