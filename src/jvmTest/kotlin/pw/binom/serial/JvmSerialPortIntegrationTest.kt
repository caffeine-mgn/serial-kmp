package pw.binom.serial

import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@EnabledIfSystemProperty(named = "serial.integration", matches = "true")
class JvmSerialPortIntegrationTest {

    private val linkA = "/tmp/ttyA"
    private val linkB = "/tmp/ttyB"
    private var socatProcess: Process? = null

    @AfterTest
    fun cleanup() {
        socatProcess?.destroy()
        File(linkA).delete()
        File(linkB).delete()
    }

    @Test
    fun `pty roundtrip`() {
        val builder =
            ProcessBuilder(
                "socat",
                "pty,raw,echo=0,link=$linkA",
                "pty,raw,echo=0,link=$linkB",
            )
        builder.redirectErrorStream(true)
        socatProcess = builder.start()

        // Wait for socat to create the pty links.
        Thread.sleep(1_000)

        val portA = openSerialPort(linkA, SerialConfig(readTimeoutMs = 5_000))
        val portB = openSerialPort(linkB, SerialConfig(readTimeoutMs = 5_000))

        try {
            val payload = "PING".encodeToByteArray()
            portA.write(payload)

            val buf = ByteArray(payload.size)
            val read = portB.read(buf, 0, payload.size)
            assertEquals(payload.size, read, "expected ${payload.size} bytes on $linkB")
            assertEquals("PING", buf.decodeToString())
        } finally {
            portA.close()
            portB.close()
        }
    }

    @Test
    fun `large payload survives write and drain`() {
        startSocat()
        val config = SerialConfig(readTimeoutMs = 5_000, writeTimeoutMs = 5_000)
        val portA = openSerialPort(linkA, config)
        val portB = openSerialPort(linkB, config)
        try {
            val payload = ByteArray(256 * 1024) { (it * 31).toByte() }
            val received = ByteArray(payload.size)
            var receivedSize = 0

            val reader = Thread {
                var offset = 0
                while (offset < received.size) {
                    val read = portB.read(received, offset, received.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                receivedSize = offset
            }
            reader.start()

            // Пишем кусками, как это делает канальный протокол.
            var offset = 0
            while (offset < payload.size) {
                val chunk = minOf(8 * 1024, payload.size - offset)
                portA.write(payload, offset, chunk)
                offset += chunk
            }
            portA.drain()

            reader.join(30_000)
            assertEquals(payload.size, receivedSize, "expected ${payload.size} bytes on $linkB")
            assertContentEquals(payload, received)
        } finally {
            portA.close()
            portB.close()
        }
    }

    private fun startSocat() {
        val builder =
            ProcessBuilder(
                "socat",
                "pty,raw,echo=0,link=$linkA",
                "pty,raw,echo=0,link=$linkB",
            )
        builder.redirectErrorStream(true)
        socatProcess = builder.start()

        // Wait for socat to create the pty links.
        Thread.sleep(1_000)
    }
}
