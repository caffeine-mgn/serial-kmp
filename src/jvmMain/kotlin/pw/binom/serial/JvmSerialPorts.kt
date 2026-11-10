package pw.binom.serial

import pw.binom.serial.jni.SerialNative

/** Перечислить доступные порты. Не бросает: при сбое энумерации — пустой список. */
actual fun listSerialPorts(): List<SerialPortInfo> =
    runCatching {
        SerialNative.ensureLoaded()
        SerialNative.list().map { path ->
            SerialPortInfo(
                name = path.substringAfterLast('/'),
                description = null,
                path = path,
            )
        }
    }.getOrElse {
        emptyList()
    }

/** Открыть порт по имени/пути из [listSerialPorts] или вручную. */
actual fun openSerialPort(name: String, config: SerialConfig): SerialPort {
    SerialNative.ensureLoaded()
    val handle = SerialNative.open(
        path = name,
        baudRate = config.baudRate,
        dataBits = config.dataBits,
        parity = config.parity.code(),
        stopBits = config.stopBits.code(),
        flowControl = config.flowControl.code(),
        readTimeoutMs = config.readTimeoutMs,
        writeTimeoutMs = config.writeTimeoutMs,
    )
    if (handle < 0) {
        val text = SerialNative.errorText(handle.toInt())
        if (handle.toInt() == SerialNative.ERR_BAUD || handle.toInt() == SerialNative.ERR_ARG) {
            throw SerialException.UnsupportedConfigException("open '$name': $text")
        }
        throw SerialException.OpenException(name, "open '$name' failed: $text")
    }
    return JvmSerialPort(
        handle = handle,
        serialInfo = SerialPortInfo(name = name.substringAfterLast('/'), description = null, path = name),
        config = config,
    )
}
