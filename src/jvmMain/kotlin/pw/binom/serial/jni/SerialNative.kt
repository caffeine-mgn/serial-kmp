package pw.binom.serial.jni

/**
 * JNI-мост к `serial_core.c`. Единственный класс, знающий про `native`.
 *
 * Соглашение: методы возвращают `Int`/`Long`; отрицательное значение — код
 * ошибки `SERIAL_ERR_*` из `serial_core.h`, ноль и положительные — результат.
 * Handle непрозрачен: на POSIX это `fd`, на Windows — `HANDLE` в `long`.
 *
 * Класс внутренний: потребитель работает через `SerialPort`.
 */
internal object SerialNative {

    /** Код ошибки: порт не открылся. */
    const val ERR_OPEN = -1
    const val ERR_IOCTL = -2
    const val ERR_BAUD = -3
    const val ERR_IO = -4
    const val ERR_TIMEOUT = -5
    const val ERR_CLOSED = -6
    const val ERR_ARG = -7

    @Volatile
    private var loaded = false

    /**
     * Загружает нативную библиотеку. Идемпотентно.
     *
     * Библиотека лежит в jar под `native/<target>/libserialjvm.<ext>`
     * и распаковывается во временный каталог при первом обращении.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            NativeLoader.load()
            loaded = true
        }
    }

    external fun open(
        path: String,
        baudRate: Int,
        dataBits: Int,
        parity: Int,
        stopBits: Int,
        flowControl: Int,
        readTimeoutMs: Int,
        writeTimeoutMs: Int,
    ): Long

    external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    external fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    external fun flush(handle: Long): Int

    external fun drain(handle: Long): Int

    external fun close(handle: Long): Int

    external fun list(): Array<String>

    external fun errorText(code: Int): String
}
