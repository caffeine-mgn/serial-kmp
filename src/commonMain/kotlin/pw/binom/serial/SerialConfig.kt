package pw.binom.serial

enum class Parity {
    NONE,
    ODD,
    EVEN,
    MARK,
    SPACE,
}

enum class StopBits {
    ONE,
    ONE_POINT_FIVE,
    TWO,
}

enum class FlowControl {
    NONE,
    HARDWARE,
    SOFTWARE,
}

data class SerialConfig(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
    val flowControl: FlowControl = FlowControl.NONE,
    /** Таймаут ожидания данных на чтении, мс. 0 — не ждать. */
    val readTimeoutMs: Int = 1000,
    /** Таймаут записи, мс. */
    val writeTimeoutMs: Int = 1000,
) {
    init {
        require(baudRate > 0) { "baudRate must be positive" }
        require(dataBits in 5..8) { "dataBits must be 5..8" }
        require(readTimeoutMs >= 0) { "readTimeoutMs must be >= 0" }
        require(writeTimeoutMs >= 0) { "writeTimeoutMs must be >= 0" }
    }
}
