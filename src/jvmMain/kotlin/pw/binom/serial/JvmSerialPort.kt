package pw.binom.serial

import pw.binom.serial.jni.SerialNative

/**
 * JVM-реализация порта поверх собственной нативной библиотеки (`serial_core.c`
 * через JNI). Никаких сторонних зависимостей — тот же C-код, что и на нативе.
 */
internal class JvmSerialPort(
    handle: Long,
    private val serialInfo: SerialPortInfo,
    config: SerialConfig,
) : SerialPort {
    private var currentConfig: SerialConfig = config
    private var closed = false
    private var actualHandle: Long = handle

    override val info: SerialPortInfo get() = serialInfo

    override val isOpen: Boolean get() = !closed

    override fun read(dst: ByteArray, offset: Int, size: Int): Int {
        checkBounds(dst, offset, size)
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        if (size == 0) return 0

        val result = SerialNative.read(actualHandle, dst, offset, size, currentConfig.readTimeoutMs)
        if (result < 0) {
            throw mapError(result, "read")
        }
        return result
    }

    override fun write(src: ByteArray, offset: Int, size: Int) {
        checkBounds(src, offset, size)
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        if (size == 0) return

        val result = SerialNative.write(actualHandle, src, offset, size, currentConfig.writeTimeoutMs)
        if (result < 0) {
            if (result == SerialNative.ERR_TIMEOUT) {
                throw SerialException.SerialWriteTimeoutException(serialInfo.name, 0, size)
            }
            throw mapError(result, "write")
        }
        if (result != size) {
            // Частичная запись означает потерю байт в потоке: для канального
            // протокола это фатально, поэтому не «дописываем», а падаем явно.
            throw SerialException.SerialWriteTimeoutException(serialInfo.name, result, size)
        }
    }

    override fun flush() {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        val result = SerialNative.flush(actualHandle)
        if (result < 0) {
            throw mapError(result, "flush")
        }
    }

    override fun drain() {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        val result = SerialNative.drain(actualHandle)
        if (result < 0) {
            throw mapError(result, "drain")
        }
    }

    override fun reconfigure(config: SerialConfig) {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        // Переконфигурация = переоткрытие: менять termios/DCB на живой линии
        // рискованно, а стоимость открытия на этих скоростях пренебрежима.
        val newHandle = SerialNative.open(
            path = serialInfo.path,
            baudRate = config.baudRate,
            dataBits = config.dataBits,
            parity = config.parity.code(),
            stopBits = config.stopBits.code(),
            flowControl = config.flowControl.code(),
            readTimeoutMs = config.readTimeoutMs,
            writeTimeoutMs = config.writeTimeoutMs,
        )
        if (newHandle < 0) {
            throw mapError(newHandle.toInt(), "reconfigure")
        }
        SerialNative.close(actualHandle)
        actualHandle = newHandle
        currentConfig = config
    }

    override fun close() {
        if (closed) return
        closed = true
        SerialNative.close(actualHandle)
    }

    private fun mapError(code: Int, operation: String): SerialException {
        val text = SerialNative.errorText(code)
        return when (code) {
            SerialNative.ERR_OPEN -> SerialException.OpenException(serialInfo.name, "$operation: $text")
            SerialNative.ERR_BAUD,
            SerialNative.ERR_ARG,
            -> SerialException.UnsupportedConfigException("$operation: $text")
            SerialNative.ERR_CLOSED -> SerialException.ClosedException(serialInfo.name)
            else -> SerialException.IoException(serialInfo.name, "$operation: $text (code=$code)")
        }
    }

    private fun checkBounds(array: ByteArray, offset: Int, size: Int) {
        if (offset < 0 || size < 0 || offset + size > array.size) {
            throw IndexOutOfBoundsException("offset=$offset size=$size length=${array.size}")
        }
    }
}
