package pw.binom.serial

/**
 * Открытый порт. НЕ потокобезопасен: один порт — один читатель/писатель.
 */
interface SerialPort : AutoCloseable {
    val info: SerialPortInfo
    val isOpen: Boolean

    /**
     * Прочитать до [size] байт, ожидая не дольше readTimeoutMs.
     * Возвращает 0 при таймауте, -1 при закрытии/EOF.
     * Прочитанные байты пишутся в [dst] начиная с индекса [offset].
     */
    fun read(dst: ByteArray, offset: Int = 0, size: Int = dst.size - offset): Int

    /**
     * Записать ВСЕ [size] байт. При частичной записи или таймауте бросает
     * [SerialException.SerialWriteTimeoutException]: молча терять байты в
     * потоковом протоколе нельзя.
     */
    fun write(src: ByteArray, offset: Int = 0, size: Int = src.size - offset)

    /**
     * Сбросить (ВЫБРОСИТЬ) содержимое входного и выходного буферов.
     *
     * ⚠️ Это не «дослать данные»! Непереданные байты будут потеряны.
     * Чтобы дождаться отправки, используйте [drain].
     */
    fun flush()

    /**
     * Дождаться, пока все записанные байты уйдут в линию (tcdrain/FlushFileBuffers).
     * Данные не выбрасываются.
     */
    fun drain()

    /** Изменить параметры на лету (baud и т.п.). */
    fun reconfigure(config: SerialConfig)

    /** Оборвать соединение и освободить дескриптор. Идемпотентно. */
    override fun close()
}
