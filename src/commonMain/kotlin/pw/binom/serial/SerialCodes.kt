package pw.binom.serial

/** Код паритета для передачи в C-ядро. Должен совпадать с `SERIAL_PARITY_*`. */
internal fun Parity.code(): Int = when (this) {
    Parity.NONE -> 0
    Parity.ODD -> 1
    Parity.EVEN -> 2
    Parity.MARK -> 3
    Parity.SPACE -> 4
}

/** Код стоп-битов. Должен совпадать с `SERIAL_STOP_*`. */
internal fun StopBits.code(): Int = when (this) {
    StopBits.ONE -> 0
    StopBits.ONE_POINT_FIVE -> 1
    StopBits.TWO -> 2
}

/** Код управления потоком. Должен совпадать с `SERIAL_FLOW_*`. */
internal fun FlowControl.code(): Int = when (this) {
    FlowControl.NONE -> 0
    FlowControl.HARDWARE -> 1
    FlowControl.SOFTWARE -> 2
}
