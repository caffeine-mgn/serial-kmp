package pw.binom.serial

/**
 * Дескриптор найденного порта.
 *
 * @property name Системное имя: "COM3", "/dev/ttyUSB0", "/dev/cu.usbserial-1420".
 * @property description Человекочитаемое описание, может быть null (Linux часто не даёт).
 * @property path Путь устройства для передачи в open(); на Windows == name.
 */
data class SerialPortInfo(
    val name: String,
    val description: String? = null,
    val path: String = name,
)
