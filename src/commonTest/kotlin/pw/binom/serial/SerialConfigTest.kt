package pw.binom.serial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerialConfigTest {

    @Test
    fun `default config is valid`() {
        val config = SerialConfig()
        assertEquals(115200, config.baudRate)
        assertEquals(8, config.dataBits)
        assertEquals(1000, config.readTimeoutMs)
        assertEquals(1000, config.writeTimeoutMs)
    }

    @Test
    fun `baud rate must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            SerialConfig(baudRate = 0)
        }
    }

    @Test
    fun `data bits must be 5 to 8`() {
        assertFailsWith<IllegalArgumentException> {
            SerialConfig(dataBits = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            SerialConfig(dataBits = 9)
        }
    }

    @Test
    fun `read timeout must be non-negative`() {
        assertFailsWith<IllegalArgumentException> {
            SerialConfig(readTimeoutMs = -1)
        }
    }

    @Test
    fun `write timeout must be non-negative`() {
        assertFailsWith<IllegalArgumentException> {
            SerialConfig(writeTimeoutMs = -1)
        }
    }
}
