package pw.binom.serial

sealed class SerialException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Порта нет / занят / нет прав. */
    class OpenException(val portName: String, message: String, cause: Throwable? = null) :
        SerialException(message, cause)

    /** Попытка операции на закрытом порту. */
    class ClosedException(val portName: String) :
        SerialException("Serial port '$portName' is closed")

    /** Ошибка ввода-вывода. */
    class IoException(val portName: String, message: String, cause: Throwable? = null) :
        SerialException(message, cause)

    /** Конфигурация не поддерживается платформой (например STOP_BITS_1_5 на POSIX). */
    class UnsupportedConfigException(message: String) : SerialException(message)

    /** Не удалось записать всё за writeTimeoutMs. */
    class SerialWriteTimeoutException(val portName: String, val written: Int, val expected: Int) :
        SerialException("Write timeout on '$portName': $written of $expected bytes")
}
