package pw.binom.serial

/** Перечислить доступные порты. Не бросает: при сбое энумерации — пустой список. */
expect fun listSerialPorts(): List<SerialPortInfo>

/** Открыть порт по имени/пути из [listSerialPorts] или вручную. */
expect fun openSerialPort(
    name: String,
    config: SerialConfig = SerialConfig(),
): SerialPort
