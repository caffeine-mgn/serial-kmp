@file:OptIn(ExperimentalForeignApi::class)

package pw.binom.serial

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import pw.binom.serial.core.serial_close
import pw.binom.serial.core.serial_config_t
import pw.binom.serial.core.serial_drain
import pw.binom.serial.core.serial_error_text
import pw.binom.serial.core.serial_flush
import pw.binom.serial.core.serial_list
import pw.binom.serial.core.serial_open
import pw.binom.serial.core.serial_read
import pw.binom.serial.core.serial_write

/**
 * Нативная реализация порта поверх общего C-ядра (`serial_core.c`).
 *
 * Один и тот же C-код собирается для Linux, Windows и macOS, поэтому здесь
 * нет ни одной платформенной ветки — только вызовы из cinterop-пакета
 * `pw.binom.serial.core`.
 */
internal class NativeSerialPort(
    private val handle: Long,
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

        var result = 0
        dst.usePinned { pinned ->
            result = serial_read(
                actualHandle,
                pinned.addressOf(offset),
                size,
                currentConfig.readTimeoutMs,
            )
        }
        if (result < 0) throw mapError(result, "read")
        return result
    }

    override fun write(src: ByteArray, offset: Int, size: Int) {
        checkBounds(src, offset, size)
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        if (size == 0) return

        var result = 0
        src.usePinned { pinned ->
            result = serial_write(
                actualHandle,
                pinned.addressOf(offset),
                size,
                currentConfig.writeTimeoutMs,
            )
        }
        if (result < 0) {
            if (result == ERR_TIMEOUT) {
                throw SerialException.SerialWriteTimeoutException(serialInfo.name, 0, size)
            }
            throw mapError(result, "write")
        }
        if (result != size) {
            throw SerialException.SerialWriteTimeoutException(serialInfo.name, result, size)
        }
    }

    override fun flush() {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        val result = serial_flush(actualHandle)
        if (result < 0) throw mapError(result, "flush")
    }

    override fun drain() {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        val result = serial_drain(actualHandle)
        if (result < 0) throw mapError(result, "drain")
    }

    override fun reconfigure(config: SerialConfig) {
        if (closed) throw SerialException.ClosedException(serialInfo.name)
        val newHandle = openNative(serialInfo.path, config)
        if (newHandle < 0) throw mapError(newHandle.toInt(), "reconfigure")
        serial_close(actualHandle)
        actualHandle = newHandle
        currentConfig = config
    }

    override fun close() {
        if (closed) return
        closed = true
        serial_close(actualHandle)
    }

    private fun mapError(code: Int, operation: String): SerialException {
        val text = serial_error_text(code)?.toKString() ?: "unknown error"
        return when (code) {
            ERR_OPEN -> SerialException.OpenException(serialInfo.name, "$operation: $text")
            ERR_BAUD, ERR_ARG -> SerialException.UnsupportedConfigException("$operation: $text")
            ERR_CLOSED -> SerialException.ClosedException(serialInfo.name)
            else -> SerialException.IoException(serialInfo.name, "$operation: $text (code=$code)")
        }
    }

    private fun checkBounds(array: ByteArray, offset: Int, size: Int) {
        if (offset < 0 || size < 0 || offset + size > array.size) {
            throw IndexOutOfBoundsException("offset=$offset size=$size length=${array.size}")
        }
    }
}

internal const val ERR_OPEN: Int = -1
internal const val ERR_IOCTL: Int = -2
internal const val ERR_BAUD: Int = -3
internal const val ERR_IO: Int = -4
internal const val ERR_TIMEOUT: Int = -5
internal const val ERR_CLOSED: Int = -6
internal const val ERR_ARG: Int = -7

/** Открыть порт через C-ядро. Возвращает handle или отрицательный код ошибки. */
internal fun openNative(path: String, config: SerialConfig): Long = memScoped {
    val cfg = alloc<serial_config_t>()
    cfg.baud_rate = config.baudRate
    cfg.data_bits = config.dataBits
    cfg.parity = config.parity.code()
    cfg.stop_bits = config.stopBits.code()
    cfg.flow_control = config.flowControl.code()
    cfg.read_timeout_ms = config.readTimeoutMs
    cfg.write_timeout_ms = config.writeTimeoutMs
    serial_open(path.cstr.ptr, cfg.ptr)
}

actual fun openSerialPort(name: String, config: SerialConfig): SerialPort {
    val handle = openNative(name, config)
    if (handle < 0) {
        val text = serial_error_text(handle.toInt())?.toKString() ?: "unknown error"
        val code = handle.toInt()
        if (code == ERR_BAUD || code == ERR_ARG) {
            throw SerialException.UnsupportedConfigException("open '$name': $text")
        }
        throw SerialException.OpenException(name, "open '$name' failed: $text")
    }
    return NativeSerialPort(
        handle = handle,
        serialInfo = SerialPortInfo(name = name.substringAfterLast('/'), description = null, path = name),
        config = config,
    )
}

actual fun listSerialPorts(): List<SerialPortInfo> = memScoped {
    val capacity = 64 * 256
    val buffer = allocArray<ByteVar>(capacity)
    val total = serial_list(buffer, capacity)
    if (total <= 0) return emptyList()

    // C отдаёт последовательность строк, завершённую дополнительным \0.
    // Читаем весь блок разом и режем по нулям — так не нужно считать смещения вручную.
    val raw = buffer.readBytes(total)
    raw
        .decodeToString()
        .split('\u0000')
        .filter { it.isNotEmpty() }
        .map { path ->
            SerialPortInfo(
                name = path.substringAfterLast('/'),
                description = null,
                path = path,
            )
        }
}
