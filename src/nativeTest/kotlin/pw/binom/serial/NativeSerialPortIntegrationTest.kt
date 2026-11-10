package pw.binom.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Живой прогон нативного порта через pty.
 *
 * Запуск: `./gradlew linuxX64Test -Pserial.integration` при поднятом socat:
 * ```
 * socat pty,raw,echo=0,link=/tmp/ttyA pty,raw,echo=0,link=/tmp/ttyB
 * ```
 */
class NativeSerialPortIntegrationTest {

    @Test
    fun ptyRoundTrip() {
        val config = SerialConfig(readTimeoutMs = 3_000, writeTimeoutMs = 3_000)
        val ports = try {
            openSerialPort("/tmp/ttyA", config) to openSerialPort("/tmp/ttyB", config)
        } catch (_: SerialException.OpenException) {
            // pty-пара поднимается снаружи (socat). Без неё тест нечего
            // проверять — CI поднимает socat перед прогоном, локально можно
            // запустить вручную (см. KDoc класса).
            println("SKIP: /tmp/ttyA + /tmp/ttyB not available (start socat first)")
            return
        }
        ports.first.use { portA ->
            ports.second.use { portB ->
                assertTrue(portA.isOpen, "port A must be open")

                val payload = "NATIVE-C-PING".encodeToByteArray()
                portA.write(payload)

                val buffer = ByteArray(payload.size)
                val read = portB.read(buffer, 0, payload.size)
                assertEquals(payload.size, read, "expected ${payload.size} bytes through the pty loop")
                assertEquals("NATIVE-C-PING", buffer.decodeToString())
            }
        }
    }

    @Test
    fun listReturnsAtLeastEmptyList() {
        // На CI без последовательных устройств список пуст — это норма,
        // проверяем что вызов не падает и не возвращает мусор.
        val ports = listSerialPorts()
        ports.forEach { port ->
            assertTrue(port.path.startsWith("/dev/"), "unexpected path: ${port.path}")
        }
    }
}
